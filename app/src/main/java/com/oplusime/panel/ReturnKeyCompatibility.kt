package com.oplusime.panel

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 小布输入法回车兼容。
 *
 * 真实宿主链路不是 commitText("\\n")：新旧宿主都会在 input.event 中先把回车键
 * 转成 q0/t0(KEYCODE_ENTER, 0)，再由 InputConnection.sendKeyEvent() 发送按下/抬起。
 * 旧版模块只观察了一个过窄的分发器，实际回车入口没有命中，所以用户点击键盘时
 * 模块没有任何回车日志，也没有真正发出目标应用能识别的 Enter。
 */
internal object ReturnKeyCompatibility {
    private const val HOST_EVENT_PREFIX = "com.oplus.keyboard.input.event."

    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedHostMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedConnectionProviders = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedServiceClasses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedKeyboardReleaseMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedKeyboardTouchMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var providerInstalled = false

    @Volatile
    private var hostEnterHooksInstalled = false

    @Volatile
    private var currentConnection: InputConnection? = null

    @Volatile
    private var currentService: InputMethodService? = null

    /**
     * sendDownUpKeyEvents() 自身也会回到当前 InputConnection.sendKeyEvent()。
     * 这个标记只用于区分“模块转发产生的事件”和“宿主原始事件”，避免递归转发。
     */
    private val forwardingEnter = ThreadLocal.withInitial { false }

    @Volatile
    private var lastNativeEnterAt = 0L

    @Volatile
    private var convertedCount = 0

    /**
     * 当前编辑器是否仍处于候选/组合文本阶段。
     *
     * 主键盘回车的宿主原生链负责“选择候选”；模块自己的兜底只允许在没有候选时
     * 补发真正的 Enter。否则第一次点击会变成“候选上屏 + 额外回车”两步。
     */
    @Volatile
    private var composingActive = false

    /** 最近一次组合文本变化的时间，用于覆盖候选提交和回车抬起之间的短暂时序差。 */
    @Volatile
    private var lastComposingTextAt = 0L

    /** 最近一次非换行文本实际提交的时间；用于识别“回车抬起后才到达的候选上屏”。 */
    @Volatile
    private var lastCommittedTextAt = 0L

    private const val COMPOSITION_RELEASE_GRACE_MS = 180L

    fun install(hostClassLoader: ClassLoader) {
        if (providerInstalled) return
        synchronized(this) {
            if (providerInstalled) return
            runCatching {
                val frameworkService = Class.forName(
                    InputMethodService::class.java.name,
                    false,
                    hostClassLoader,
                )
                hookServiceLifecycle(frameworkService)

                XposedBridge.hookAllMethods(
                    frameworkService,
                    "getCurrentInputConnection",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            captureService(param.thisObject)
                            val connection = param.result as? InputConnection ?: return
                            updateCurrentConnection(connection)
                            hookConnection(connection)
                            logCritical(
                                "return-key: framework connection observed class=${connection.javaClass.name}",
                            )
                        }
                    },
                )
                hookFrameworkInputConnectionMethods()
                providerInstalled = true
                logCritical("return-key: base provider hooks installed")
            }.onFailure {
                providerInstalled = false
                logCritical("return-key: base provider hook failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun hookServiceLifecycle(serviceClass: Class<*>) {
        if (!InputMethodService::class.java.isAssignableFrom(serviceClass)) return
        if (!hookedServiceClasses.add(serviceClass.name)) return
        listOf("onCreate", "onStartInput", "onStartInputView", "onWindowShown").forEach { name ->
            runCatching {
                XposedBridge.hookAllMethods(
                    serviceClass,
                    name,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            captureService(param.thisObject)
                            if (name == "onStartInput" || name == "onStartInputView") {
                                resetCompositionState()
                            }
                        }
                    },
                )
            }.onFailure {
                logCritical("return-key: service lifecycle hook failed ${serviceClass.name}#$name: ${it.message}")
            }
        }
        logCritical("return-key: service lifecycle hooked ${serviceClass.name}")
    }

    private fun captureService(value: Any?) {
        val service = value as? InputMethodService ?: return
        currentService = service
        logCritical("return-key: service captured class=${service.javaClass.name}")
    }

    /**
     * 宿主 q0/t0 直接从 input.manager.h/s 取连接。这里挂提供者，确保模块记录到的
     * 连接就是宿主回车分发器实际使用的那一个，而不是只依赖框架公开 getter。
     */
    fun attachHostConnectionProviders(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates = runCatching {
            bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("android.view.inputmethod.InputConnection")
                }
            }.toList()
        }.onFailure { logCritical("return-key: connection provider query failed: ${it.message}") }
            .getOrDefault(emptyList())

        val matched = candidates.filter { data ->
            data.declaredClassName.orEmpty().startsWith("com.oplus.keyboard.input.manager.")
        }
        val signatures = mutableListOf<String>()
        var hooks = 0
        matched.forEach { data ->
            val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (!Modifier.isStatic(method.modifiers) || method.parameterTypes.isNotEmpty()) return@forEach
            signatures += HookDiagnostics.methodSignature(method)
            if (!hookedConnectionProviders.add(method.toGenericString())) return@forEach
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val connection = param.result as? InputConnection ?: return
                        updateCurrentConnection(connection)
                        hookConnection(connection)
                        logCritical(
                            "return-key: host provider hit ${method.declaringClass.name}#${method.name}" +
                                " connection=${connection.javaClass.name}",
                        )
                    }
                })
                hooks++
            }.onFailure {
                logCritical("return-key: provider hook failed ${method.toGenericString()}: ${it.message}")
            }
        }
        logCritical(
            "return-key: host providers candidates=${candidates.size} matched=${matched.size} hooks=$hooks",
        )
        if (signatures.isNotEmpty()) {
            HookDiagnostics.recordMatch(
                "输入提交拦截",
                signatures,
                "宿主 InputConnection 提供者已匹配；运行时回车分发使用同一连接",
            )
        }
    }

    fun attachKeyboardTouchLogging(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates = runCatching {
            val fourArg = bridge.findMethod {
                matcher {
                    paramCount(4)
                    returnType("void")
                }
            }.toList()
            val fiveArg = bridge.findMethod {
                matcher {
                    paramCount(5)
                    returnType("void")
                }
            }.toList()
            (fourArg + fiveArg).distinctBy {
                "${it.declaredClassName}#${it.name}#${it.paramTypeNames.joinToString(",")}#${it.returnTypeName}"
            }
        }.onFailure {
            logCritical("return-key: keyboard touch logging query failed: ${it.stackTraceToString()}")
        }.getOrDefault(emptyList())

        val methods = candidates.mapNotNull { data ->
            runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
        }.filter { method ->
            val params = method.parameterTypes
            val shape = when (params.size) {
                4 -> params[0] == Int::class.javaPrimitiveType &&
                    params[1] == Float::class.javaPrimitiveType &&
                    params[2] == Float::class.javaPrimitiveType
                5 -> params[0] == Int::class.javaPrimitiveType &&
                    params[1] == Float::class.javaPrimitiveType &&
                    params[2] == Float::class.javaPrimitiveType &&
                    params[3] == Long::class.javaPrimitiveType
                else -> false
            }
            shape && method.declaringClass.name.startsWith("com.oplus.keyboard.input.view.body.")
        }.distinctBy { it.toGenericString() }

        var hooks = 0
        methods.forEach { method ->
            if (!hookedKeyboardTouchMethods.add(method.toGenericString())) return@forEach
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = resolveReleaseSoftKey(param)
                        val keyCode = key?.let { softKeyCode(it) }
                        logCritical(
                            "return-key: keyboard touch edge method=${method.declaringClass.name}#${method.name}" +
                                " phase=before args=${param.args.orEmpty().contentToString()}" +
                                " keyCode=$keyCode candidatePending=${candidatePendingAtEnter()}" +
                                " composing=$composingActive",
                        )
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logCritical(
                            "return-key: keyboard touch edge method=${method.declaringClass.name}#${method.name}" +
                                " phase=after result=${param.result}",
                        )
                    }
                })
                hooks++
            }.onFailure {
                logCritical("return-key: keyboard touch logging hook failed ${method.toGenericString()}: ${it.stackTraceToString()}")
            }
        }
        logCritical("return-key: keyboard touch logging hooks=$hooks candidates=${methods.size}")
    }

    /**
     * 主键盘按键抬起链：只记录真实点击，不修改参数、不改变返回值。
     */
    fun attachKeyboardRelease(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates = runCatching {
            val legacyRelease = bridge.findMethod {
                matcher {
                    paramCount(4)
                    returnType("void")
                }
            }.toList()
            val currentRelease = bridge.findMethod {
                matcher {
                    paramCount(5)
                    returnType("void")
                }
            }.toList()
            (legacyRelease + currentRelease).distinctBy {
                "${it.declaredClassName}#${it.name}#${it.paramTypeNames.joinToString(",")}#${it.returnTypeName}"
            }
        }.onFailure {
            logCritical("return-key: keyboard release query failed: ${it.stackTraceToString()}")
        }.getOrDefault(emptyList())

        val methods = candidates.mapNotNull { data ->
            runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
        }.filter { method ->
            val params = method.parameterTypes
            val keyParameter = params.lastOrNull() ?: return@filter false
            val releaseShape = when (params.size) {
                4 -> params[0] == Int::class.javaPrimitiveType &&
                    params[1] == Float::class.javaPrimitiveType &&
                    params[2] == Float::class.javaPrimitiveType
                5 -> params[0] == Int::class.javaPrimitiveType &&
                    params[1] == Float::class.javaPrimitiveType &&
                    params[2] == Float::class.javaPrimitiveType &&
                    params[3] == Long::class.javaPrimitiveType
                else -> false
            }
            releaseShape &&
                method.declaringClass.name.startsWith("com.oplus.keyboard.input.view.body.") &&
                method.declaringClass.superclass != null &&
                keyParameter.declaredFields.any { field ->
                    field.type == Int::class.javaPrimitiveType && !Modifier.isStatic(field.modifiers)
                } &&
                keyParameter.declaredFields.count { field ->
                    field.type == String::class.java && !Modifier.isStatic(field.modifiers)
                } >= 2
        }.distinctBy { it.toGenericString() }

        val signatures = methods.map { HookDiagnostics.methodSignature(it) }.distinct()
        logCritical(
            "return-key: keyboard release candidates=${methods.size}" +
                " signatures=${signatures.joinToString(" | ")}",
        )

        var hooks = 0
        methods.forEach { method ->
            if (!hookedKeyboardReleaseMethods.add(method.toGenericString())) return@forEach
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = resolveReleaseSoftKey(param) ?: return
                        val keyCode = softKeyCode(key) ?: return
                        if (keyCode != KeyEvent.KEYCODE_ENTER) return
                        val observedBefore = lastNativeEnterAt
                        val releaseObservedAt = SystemClock.uptimeMillis()
                        val observedCommitAt = lastCommittedTextAt
                        val candidatePending = candidatePendingAtEnter()
                        logCritical(
                            "return-key: keyboard release ENTER keyCode=$keyCode key=${key.javaClass.name}" +
                                " method=${method.declaringClass.name}#${method.name}" +
                                " candidatePending=$candidatePending composing=$composingActive" +
                                " commitAt=$observedCommitAt",
                        )
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (lastNativeEnterAt > observedBefore) {
                                logCritical(
                                    "return-key: keyboard release native ENTER observed" +
                                        " method=${method.declaringClass.name}#${method.name}",
                                )
                                return@postDelayed
                            }
                            if (candidatePending ||
                                (lastCommittedTextAt > observedCommitAt && lastCommittedTextAt >= releaseObservedAt)
                            ) {
                                logCritical(
                                    "return-key: candidate commit only; suppress automatic ENTER fallback" +
                                        " method=${method.declaringClass.name}#${method.name}" +
                                        " commitAt=$lastCommittedTextAt",
                                )
                                return@postDelayed
                            }
                            logCritical(
                                "return-key: keyboard release native ENTER missing; service fallback" +
                                    " method=${method.declaringClass.name}#${method.name}",
                            )
                            emitNativeEnter("keyboard-release:${method.declaringClass.name}#${method.name}")
                        }, 80L)
                    }
                })
                hooks++
            }.onFailure {
                logCritical("return-key: keyboard release hook failed ${method.toGenericString()}: ${it.message}")
            }
        }

        if (signatures.isNotEmpty()) {
            HookDiagnostics.recordMatch(
                "输入提交拦截",
                signatures,
                "主键盘 SoftKey 抬起收尾结构已匹配；仅对 ENTER=66 做原生链缺失兜底",
            )
        }
        logCritical("return-key: keyboard release hooks=$hooks")
    }

    private fun resolveReleaseSoftKey(param: XC_MethodHook.MethodHookParam): Any? {
        val args = param.args ?: return null
        val direct = args.lastOrNull()
        if (direct != null && softKeyCode(direct) != null) return direct

        val pointerId = (args.getOrNull(0) as? Number)?.toInt() ?: return direct
        val receiver = param.thisObject ?: return direct
        var cls: Class<*>? = receiver.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (!Map::class.java.isAssignableFrom(field.type)) continue
                val map = runCatching {
                    field.isAccessible = true
                    field.get(receiver) as? Map<*, *>
                }.getOrNull() ?: continue
                for (mapEntry in map.entries) {
                    val key = mapEntry.key as? Number
                    val value = mapEntry.value
                    if (key?.toInt() == pointerId && value != null) {
                        val softKey = findSoftKeyInReleaseEntry(value)
                        if (softKey != null) return softKey
                    }
                }
            }
            cls = cls.superclass
        }
        return direct
    }

    private fun findSoftKeyInReleaseEntry(entry: Any): Any? {
        var cls: Class<*>? = entry.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(entry)
                }.getOrNull() ?: continue
                if (softKeyCode(value) != null) return value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun isEnterSoftKey(key: Any): Boolean =
        softKeyCode(key) == KeyEvent.KEYCODE_ENTER

    private fun softKeyCode(key: Any): Int? {
        var cls: Class<*>? = key.javaClass
        while (cls != null && cls != Any::class.java) {
            val field = cls.declaredFields.firstOrNull { candidate ->
                candidate.name == "a" && candidate.type == Int::class.javaPrimitiveType
            }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.getInt(key)
                }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * 不能再用宿主日志字符串反查类：旧版日志字符串可能被移除、改写或只在另一条
     * 调试分支中出现，结果就是连接提供者已命中，但主键盘 q0/t0 根本没有被挂上。
     * 这里直接按稳定包范围 + 参数/返回结构召回，再由 Xposed 挂载；宿主方法名仍不参与
     * 匹配，因此兼容新版 q0/r0 与旧版 t0/u0 等混淆变化。
     */
    fun attachHostDispatchers(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        if (hostEnterHooksInstalled) return
        synchronized(this) {
            if (hostEnterHooksInstalled) return

            val all = runCatching {
                val twoArg = bridge.findMethod {
                    matcher {
                        paramTypes("int", "int")
                        returnType("boolean")
                    }
                }.toList()
                val oneArgVoid = bridge.findMethod {
                    matcher {
                        paramTypes("int")
                        returnType("void")
                    }
                }.toList()
                val oneArgBoolean = bridge.findMethod {
                    matcher {
                        paramTypes("int")
                        returnType("boolean")
                    }
                }.toList()
                (twoArg + oneArgVoid + oneArgBoolean)
                    .filter { it.declaredClassName.orEmpty().startsWith(HOST_EVENT_PREFIX) }
                    .distinctBy {
                        "${it.declaredClassName}#${it.name}#${it.paramTypeNames.joinToString(",")}#${it.returnTypeName}"
                    }
            }.onFailure {
                logCritical("return-key: dispatcher structural query failed: ${it.stackTraceToString()}")
            }.getOrDefault(emptyList())

            val signatures = all.mapNotNull { data ->
                runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                    ?.let { HookDiagnostics.methodSignature(it) }
            }.distinct()

            logCritical(
                "return-key: dispatcher structural candidates=${all.size}" +
                    " owners=${all.mapNotNull { it.declaredClassName }.distinct()}",
            )

            var hooks = 0
            all.forEach { data ->
                val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                    ?: return@forEach
                if (!hookedHostMethods.add(method.toGenericString())) return@forEach
                runCatching {
                    method.isAccessible = true
                    logCritical("return-key: dispatcher candidate hooked ${method.toGenericString()}")
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val values = param.args.orEmpty().mapNotNull { (it as? Number)?.toInt() }
                            logCritical(
                                "return-key: dispatcher invoked ${method.declaringClass.name}#${method.name}" +
                                    " args=${values.joinToString(",")}",
                            )
                            val enterIndex = values.indexOfFirst { it == KeyEvent.KEYCODE_ENTER }
                            if (enterIndex < 0) return
                            val phase = if (values.size == 2) {
                                values.getOrNull(if (enterIndex == 0) 1 else 0)
                            } else null
                            lastNativeEnterAt = SystemClock.uptimeMillis()
                            logCritical(
                                "return-key: ENTER dispatcher observed ${method.declaringClass.name}#${method.name}" +
                                    " enterIndex=$enterIndex phase=$phase; native path preserved",
                            )
                            // 只观察，不设置 param.result、不改参数、不重复发按键。
                        }
                    })
                    hooks++
                }.onFailure {
                    logCritical("return-key: enter hook failed ${method.toGenericString()}: ${it.message}")
                }
            }

            hostEnterHooksInstalled = true
            logCritical(
                "return-key: enter candidates methods=${all.size} hooks=$hooks",
            )
            if (signatures.isNotEmpty()) {
                HookDiagnostics.recordMatch(
                    "输入提交拦截",
                    signatures,
                    "宿主 input.event 按键分发器按包范围与签名结构匹配；只观察参数并保留宿主原生 KeyEvent 分发",
                )
            } else {
                HookDiagnostics.record(
                    null,
                    "输入提交拦截",
                    false,
                    "DexKit/结构匹配：未找到 input.event 按键分发器",
                )
            }
        }
    }


    private fun emitNativeEnter(source: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastNativeEnterAt < 120L) {
            logCritical("return-key: native Enter already observed; no duplicate source=$source")
            return true
        }

        val service = currentService
        if (service != null) {
            return runCatching {
                forwardingEnter.set(true)
                try {
                    service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                } finally {
                    forwardingEnter.set(false)
                }
                lastNativeEnterAt = now
                convertedCount++
                logCritical(
                    "return-key: SERVICE sendDownUpKeyEvents ENTER source=$source count=$convertedCount",
                )
                true
            }.onFailure {
                forwardingEnter.set(false)
                logCritical("return-key: service Enter failed source=$source error=${it.stackTraceToString()}")
            }.getOrDefault(false)
        }

        val connection = currentConnection
        if (connection != null) {
            val downTime = SystemClock.uptimeMillis()
            return runCatching {
                forwardingEnter.set(true)
                try {
                    val down = KeyEvent(
                        downTime,
                        downTime,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_ENTER,
                        0,
                        0,
                        -1,
                        0,
                        6,
                    )
                    val up = KeyEvent(
                        downTime,
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_ENTER,
                        0,
                        0,
                        -1,
                        0,
                        6,
                    )
                    val downOk = connection.sendKeyEvent(down)
                    val upOk = connection.sendKeyEvent(up)
                    val accepted = downOk && upOk
                    logCritical(
                        "return-key: DIRECT connection Enter source=$source down=$downOk up=$upOk",
                    )
                    if (accepted) {
                        lastNativeEnterAt = now
                        convertedCount++
                    }
                    accepted
                } finally {
                    forwardingEnter.set(false)
                }
            }.onFailure {
                forwardingEnter.set(false)
                logCritical("return-key: direct Enter failed source=$source error=${it.stackTraceToString()}")
            }.getOrDefault(false)
        }

        logCritical("return-key: Enter has no service and no current connection source=$source")
        return false
    }

    private fun updateCurrentConnection(connection: InputConnection) {
        currentConnection = connection
    }

    private fun resetCompositionState() {
        composingActive = false
        lastComposingTextAt = 0L
    }

    /**
     * 只在回车抬起的这一刻判断是否仍有候选/组合文本，不把候选状态永久带到下一次点击。
     * 这样第一次回车可以只完成候选上屏，而用户稍后再次点击回车仍能正常换行。
     */
    private fun candidatePendingAtEnter(): Boolean {
        val now = SystemClock.uptimeMillis()
        return composingActive ||
            (lastComposingTextAt > 0L && now - lastComposingTextAt <= COMPOSITION_RELEASE_GRACE_MS)
    }

    private fun noteComposingText(text: CharSequence?) {
        val now = SystemClock.uptimeMillis()
        val active = !text.isNullOrEmpty()
        if (active || composingActive) lastComposingTextAt = now
        composingActive = active
        logCritical(
            "return-key: composing state active=$composingActive" +
                " length=${text?.length ?: 0}",
        )
    }

    private fun noteCompositionFinished() {
        val wasActive = composingActive
        if (wasActive) lastComposingTextAt = SystemClock.uptimeMillis()
        composingActive = false
        if (wasActive) {
            logCritical("return-key: composing finished; candidate-only window armed")
        }
    }

    private fun noteCommittedText(text: CharSequence?) {
        if (text.isNullOrEmpty() || isStandaloneNewline(text)) return
        lastCommittedTextAt = SystemClock.uptimeMillis()
        if (!composingActive) {
            logCritical(
                "return-key: non-composing text committed; candidate fallback guard armed" +
                    " length=${text.length}",
            )
            return
        }
        lastComposingTextAt = SystemClock.uptimeMillis()
        composingActive = false
        logCritical(
            "return-key: composing committed; candidate-only window armed" +
                " length=${text.length}",
        )
    }

    private fun hookConnection(connection: InputConnection) {
        var cls: Class<*>? = connection.javaClass
        val chain = mutableListOf<Class<*>>()
        while (cls != null) {
            chain += cls
            cls = cls.superclass
        }
        var hooks = 0
        chain.forEach { owner ->
            owner.declaredMethods
                .filter { method ->
                    when (method.name) {
                        "commitText" -> method.parameterTypes.contentEquals(
                            arrayOf(CharSequence::class.java, Int::class.javaPrimitiveType),
                        )
                        "setComposingText" -> method.parameterTypes.contentEquals(
                            arrayOf(CharSequence::class.java, Int::class.javaPrimitiveType),
                        )
                        "finishComposingText" -> method.parameterTypes.isEmpty()
                        "performEditorAction" -> method.parameterTypes.contentEquals(
                            arrayOf(Int::class.javaPrimitiveType),
                        )
                        "sendKeyEvent" -> method.parameterTypes.contentEquals(
                            arrayOf(KeyEvent::class.java),
                        )
                        else -> false
                    }
                }
                .forEach methodLoop@ { method ->
                    if (!hookedMethods.add(method.toGenericString())) return@methodLoop
                    runCatching {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, inputConnectionHook("instance:${method.name}"))
                        hooks++
                    }.onFailure {
                        logCritical("return-key: instance hook failed ${method.toGenericString()}: ${it.message}")
                    }
                }
        }
        logCritical("return-key: concrete connection=${connection.javaClass.name} methods=$hooks")
    }

    private fun hookFrameworkInputConnectionMethods() {
        listOf(
            InputConnection::class.java,
            android.view.inputmethod.BaseInputConnection::class.java,
            android.view.inputmethod.InputConnectionWrapper::class.java,
        ).forEach { cls ->
            listOf("commitText", "setComposingText", "finishComposingText", "performEditorAction", "sendKeyEvent").forEach { name ->
                runCatching {
                    XposedBridge.hookAllMethods(cls, name, inputConnectionHook("framework:$name"))
                }.onFailure {
                    logCritical("return-key: framework hook failed ${cls.name}#$name: ${it.message}")
                }
            }
        }
    }

    private fun inputConnectionHook(source: String): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val connection = param.thisObject as? InputConnection ?: return
            val event = param.args?.firstOrNull() as? KeyEvent
            if (event != null) {
                if (forwardingEnter.get() == true) return
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    logCritical(
                        "return-key: connection ENTER source=$source action=${event.action}" +
                            " class=${connection.javaClass.name}",
                    )
                }
                return
            }

            if (source.endsWith("commitText")) {
                val text = param.args?.getOrNull(0) as? CharSequence
                logCritical(
                    "return-key: commitText source=$source text=${text?.toString()?.replace("\\n", "\\\\n")}" +
                        " length=${text?.length ?: -1} candidatePending=${candidatePendingAtEnter()}" +
                        " composing=$composingActive",
                )
            }

            when {
                source.endsWith("setComposingText") -> {
                    noteComposingText(param.args?.getOrNull(0) as? CharSequence)
                }
                source.endsWith("finishComposingText") -> {
                    noteCompositionFinished()
                }
                source.endsWith("commitText") -> {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    if (isStandaloneNewline(text)) {
                        // 候选提交链有时会先把候选上屏，再向同一个 InputConnection
                        // 送一个单独的换行文本。这个换行不是用户第二次点击产生的，
                        // 不能再转换成 Enter，否则就会出现“候选上屏后自动多换一行”。
                        if (candidatePendingAtEnter()) {
                            logCritical(
                                "return-key: suppress standalone newline after candidate commit" +
                                    " source=$source",
                            )
                            resetCompositionState()
                            param.result = true
                            return
                        }
                        if (sendEnter(connection, source)) param.result = true
                    } else {
                        noteCommittedText(text)
                    }
                }
            }
        }
    }

    /** 安装输入连接上单独的换行文本兜底；主键盘按键的修复在宿主 input.event 分发器内独立观察。 */
    private fun isStandaloneNewline(text: CharSequence): Boolean =
        text.toString() == "\n" || text.toString() == "\r" || text.toString() == "\r\n"

    private fun sendEnter(connection: InputConnection, source: String): Boolean {
        val service = currentService
        if (service != null) {
            return runCatching {
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                convertedCount++
                logCritical("return-key: text fallback via service source=$source count=$convertedCount")
                true
            }.getOrDefault(false)
        }
        val downTime = SystemClock.uptimeMillis()
        return runCatching {
            val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, -1, 0, 6)
            val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0, -1, 0, 6)
            val downOk = connection.sendKeyEvent(down)
            val upOk = connection.sendKeyEvent(up)
            logCritical("return-key: text fallback direct source=$source down=$downOk up=$upOk")
            downOk && upOk
        }.getOrDefault(false)
    }
}
