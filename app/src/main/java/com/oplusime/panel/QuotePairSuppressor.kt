package com.oplusime.panel

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.content.DialogInterface
import android.app.Dialog
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.lang.reflect.Modifier

/**
 * 成对符号自动补全抑制。
 *
 * 宿主可能把自动补全拆成两次 `RemoteInputConnection.commitText`，也可能在宿主配对表源头
 * 生成右符号。模块优先在配对表源头只执行左符号回调；输入连接层只保留一次性完整成对提交
 * 的裁剪和观察，不按时间窗删除字符，因此用户手动输入 `()` 等内容不会被误删。
 *
 * 所有宿主定位仍使用 DexKit 语义/结构匹配；框架输入连接和运行时远程输入连接只作为
 * Android 输入法进程的稳定系统接口，不写死宿主混淆类名。
 */
internal object QuotePairSuppressor {

    /**
     * 配对符号数据表只用于识别一次提交即为完整成对文本和记录符号相关输入。
     * 不根据相邻字符推断自动补全，也不以此删除任何用户输入。
     * 范围覆盖引号、圆括号、方括号、花括号、尖括号、书名号及中文/全角变体。
     */
    private val PAIRS: Map<Char, Char> = mapOf(
        // 引号
        '\u201C' to '\u201D', // “ ”
        '\u2018' to '\u2019', // ‘ ’
        '\u201E' to '\u201C', // „ “（德语式低引号）
        '\u00AB' to '\u00BB', // « »
        '\u2039' to '\u203A', // ‹ ›
        '\u201A' to '\u2018', // ‚ ‘
        '\u300C' to '\u300D', // 「 」
        '\u300E' to '\u300F', // 『 』
        '\u301D' to '\u301E', // 〝 〞
        '"' to '"',           // 英文双引号
        '\'' to '\'',         // 英文单引号
        // 圆括号
        '(' to ')',
        '\uFF08' to '\uFF09', // （ ）
        // 方括号
        '[' to ']',
        '\u3010' to '\u3011', // 【 】
        '\uFF3B' to '\uFF3D', // ［ ］
        // 花括号
        '{' to '}',
        '\uFF5B' to '\uFF5D', // ｛ ｝
        // 尖括号 / 书名号
        '<' to '>',
        '\u3008' to '\u3009', // 〈 〉
        '\u300A' to '\u300B', // 《 》
        '\uFF1C' to '\uFF1E', // ＜ ＞
    )

    /** 输入连接钩子安装后只保留计数诊断，不再用时间窗做破坏性删除。 */
    /** 成对提交仅用于记录与一次性参数裁剪，不驱动后续删除。 */
    @Volatile
    private var installed = false

    @Volatile
    private var lastObserveAt: Long = 0L

    @Volatile
    private var lastObservedText: String = ""

    /** 宿主自动补全与用户手动输入都最终会走 RemoteInputConnection.commitText。 */
    private data class PendingLeft(
        val value: Char,
        val at: Long,
        val userInputGeneration: Long,
    )

    /** 当前输入法真正对外使用的 InputConnection 实例。按实例登记比按类名判断稳定，
     * 因为新旧宿主都可能返回不同的框架代理类。 */
    private val remoteConnections = Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Boolean>(),
    )

    /** 一个输入法窗口同时只有一个活动编辑目标，成对补全的两次提交也可能由两个代理实例完成。 */
    @Volatile
    private var pendingLeftGlobal: PendingLeft? = null

    @Volatile
    private var lastUserInputAt: Long = 0L
    private val userInputGeneration = AtomicLong(0L)
    private const val AUTO_CLOSE_MAX_DELAY_MS = 1200L
    @Volatile
    private var touchProbeInstalled = false

    /**
     * `InputConnection` 提交留证用的字段。
     *
     * 1.20.0 的真机日志里，三条成对符号分支（源头拦截 / 事后删除 / 引擎回调）**一条都没出现**，
     * 说明我们这个版本的钩子根本没走到用户那条路径上。因此本版对「短文本提交」一律留证
     * （节流 150ms、相同文本跳过），下一轮就能直接看到引号到底是走 commitText、
     * setComposingText 还是纯 native 通道。
     */
    @Volatile
    private var lastCommitTraceAt: Long = 0L

    @Volatile
    private var lastCommitTraceText: String = ""

    @Volatile
    private var frameworkInputHookPoints = 0
    @Volatile
    private var hostInputHookPoints = 0
    @Volatile
    private var hostDispatcherHookPoints = 0
    @Volatile
    private var engineHookPoints = 0
    @Volatile
    private var pairSourceHooked = false
    private val frameworkMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val hostInputMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val dispatcherMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val engineMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val pairSourceMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun diagnosticSignatures(): List<String> = buildList {
        addAll(frameworkMatchSignatures)
        addAll(hostInputMatchSignatures)
        addAll(dispatcherMatchSignatures)
        addAll(engineMatchSignatures)
        addAll(pairSourceMatchSignatures)
    }.distinct()

    fun publishDiagnostics() {
        HookDiagnostics.recordMatch("引号:输入连接", frameworkMatchSignatures,
            "框架/远程 InputConnection 结构入口已解析")
        HookDiagnostics.recordMatch("引号:框架输入连接", frameworkMatchSignatures,
            "InputConnection 提交与组合文本方法")
        HookDiagnostics.recordMatch("引号:宿主输入连接", hostInputMatchSignatures,
            "宿主 onCreateInputConnection 返回实现的提交方法")
        HookDiagnostics.recordMatch("引号:宿主提交分发", dispatcherMatchSignatures,
            "宿主提交分发方法")
        HookDiagnostics.recordMatch("引号:引擎提交汇聚点", engineMatchSignatures,
            "引擎回调与最终提交汇聚方法")
        HookDiagnostics.recordMatch("引号:成对符号源头", pairSourceMatchSignatures,
            "配对表、选区查询与回调结构方法")
    }

    /**
     * 在宿主进程内安装。输入法进程里承载编辑框调用的代理类可能不止一个名字，
     * 逐个尝试，命中即装；全部不可用时如实记日志（功能退化为原生行为，不会崩）。
     */
    fun install(hostClassLoader: ClassLoader, extraClasses: List<Class<*>> = emptyList()) {
        if (installed) return
        installUserInputProbe()
        val candidates = listOf(
            // 框架侧的代理实现：native 引擎与 Java 代码最终都经过它。
            "com.android.internal.view.IInputConnectionWrapper",
            "com.android.internal.view.InputConnectionWrapper",
            "android.view.inputmethod.InputConnectionWrapper",
            // 框架基类：宿主自定义的 InputConnection 一般继承它。
            "android.view.inputmethod.BaseInputConnection",
        )
        var hooked = 0
        candidates.forEach { name ->
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
            if (cls == null) {
                log("quote-pair: $name not present")
                return@forEach
            }
            frameworkMatchSignatures.addAll(
                cls.methods.filter { it.name == "commitText" || it.name == "setComposingText" }
                    .map { HookDiagnostics.methodSignature(it) }
            )
            hooked += hookAll(cls)
        }
        // 宿主自己实现的 InputConnection（由入口用 DexKit 查出后传进来），
        // 逐一挂上——这类实现不会经过框架代理，必须单独覆盖。
        extraClasses.forEach { cls ->
            val added = hookAll(cls)
            if (added > 0) {
                hooked += added
                log("quote-pair: host InputConnection hooked ${cls.name} points=$added")
            }
        }
        frameworkInputHookPoints = hooked
        if (hooked == 0) {
            log("quote-pair: no InputConnection proxy hooked; quotes keep host behaviour")
            HookDiagnostics.record(null, "引号:框架输入连接", false, "hook points=0")
        } else {
            installed = true
            log("quote-pair: installed, hook points=$hooked")
            HookDiagnostics.record(null, "引号:框架输入连接", true, "hook points=$hooked")
        }
        // 输入法进程里真正把文本送进目标应用的，是框架侧那个"远程输入连接"
        // （`InputMethodService.getCurrentInputConnection()` 返回的对象）。它既不是
        // `BaseInputConnection`、也不是任何 `InputConnectionWrapper`。
        //
        // 1.21.1 真机日志把这一点钉死了：整场运行只有一行提交留证，而且是内部编辑框的
        // `BaseInputConnection.commitText`；用户点了很多次符号/引号，`commitText`、
        // `setComposingText`、引擎回调、两个分发器**全都没有出现**。也就是说符号提交
        // 走的是这条远程连接，而它从来没被挂过。
        //
        // 这里改成：挂住框架的提供者，拿到实例后**按运行时真实类**动态挂载。
        // 不写死任何与 Android 版本相关的类名，换版本也能继续命中。
        attachImeInputConnection(hostClassLoader)
    }

    /** 已动态挂载过的输入连接实现类名，避免同一类被反复挂钩。 */
    private val hookedIcClasses: MutableSet<String> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * 挂住框架的"当前输入连接"提供者，再按其返回值的**运行时类**挂提交方法。
     *
     * 这条链覆盖的是"输入法 → 目标应用"的真正出口，符号键、候选词、引号都从这里出去。
     */
    fun attachImeInputConnection(hostClassLoader: ClassLoader) {
        val serviceClass = runCatching {
            Class.forName("android.inputmethodservice.InputMethodService", false, hostClassLoader)
        }.getOrNull()
        if (serviceClass == null) {
            log("quote-pair: InputMethodService not present; remote IC path uncovered")
            return
        }
        runCatching {
            XposedBridge.hookAllMethods(
                serviceClass,
                "getCurrentInputConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ic = param.result as? InputConnection ?: return
                        remoteConnections[ic] = true
                        log("quote-pair: remote IC observed class=${ic.javaClass.name} identity=${System.identityHashCode(ic)}")
                        hookIcClass(ic.javaClass)
                    }
                },
            )
            log("quote-pair: remote IC provider hooked (${serviceClass.name}#getCurrentInputConnection)")
            frameworkMatchSignatures.addAll(
                serviceClass.methods.filter { it.name == "getCurrentInputConnection" }
                    .map { HookDiagnostics.methodSignature(it) }
            )
            HookDiagnostics.recordMatch("引号:框架输入连接", frameworkMatchSignatures,
                "动态提供者方法已解析；返回实例后再挂载提交方法")
        }.onFailure { log("quote-pair: remote IC provider hook failed: ${it.message}")
            HookDiagnostics.record(null, "引号:框架输入连接", false, it.message.orEmpty())
        }
    }

    private fun hookIcClass(cls: Class<*>) {
        if (!hookedIcClasses.add(cls.name)) return
        val added = hookAll(cls)
        log("quote-pair: remote IC hooked ${cls.name} points=$added")
        HookDiagnostics.record(null, "引号:框架输入连接", added > 0,
            "runtimeClass=${cls.name}; hookPoints=$added")
    }

    /**
     * 把「宿主自己实现的 InputConnection」也挂上。
     *
     * 宿主用 `onCreateInputConnection` 返回它自己的实现类（不是框架的包装类），
     * 这类实现完全绕过框架代理，前面那批候选类一个都覆盖不到——这正是上一版"hook 装上了
     * 但引号照样成对"的最可能原因。这里按方法返回值把实现类找出来，逐一挂载：
     * 只要宿主的输入连接还是由 `onCreateInputConnection` 产出的，这条链就能被重新找到。
     */
    fun attachHostImplementations(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val methods = runCatching {
            bridge.findMethod {
                matcher {
                    name("onCreateInputConnection")
                    paramTypes("android.view.inputmethod.EditorInfo")
                }
            }.toList()
        }.onFailure { log("quote-pair: host IC query failed: ${it.message}") }
            .getOrDefault(emptyList())

        val names = methods.mapNotNull { it.returnTypeName }.distinct()
        hostInputMatchSignatures.clear()
        hostInputMatchSignatures.addAll(methods.mapNotNull { data ->
            runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?.let { HookDiagnostics.methodSignature(it) }
        })
        var hooked = 0
        names.forEach { name ->
            if (name.isEmpty() || name == "android.view.inputmethod.InputConnection") return@forEach
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (cls.isInterface) return@forEach
            val added = hookAll(cls)
            if (added > 0) {
                hooked += added
                log("quote-pair: host IC hooked ${cls.name} points=$added")
            }
        }
        hostInputHookPoints = hooked
        log("quote-pair: host IC classes=${names.size} hooks=$hooked")
        HookDiagnostics.recordMatch("引号:宿主输入连接", hostInputMatchSignatures,
            "候选实现类=${names.size}；运行时 hook 点数=$hooked")
    }

    /**
     * 宿主自己的 KeyEventHandler 在真正调用 InputConnection 之前还会经过
     * `commonCommitText` 分发器。仅 hook InputConnectionWrapper 会漏掉“成对文本先在宿主分发器生成”的路径，
     * 因此这里按宿主 dex 的结构特征再挂两层：含 `match_symbol` 的 CharSequence 分发器，
     * 以及 `(int, CharSequence)` 的直接提交入口。
     */
    fun attachHostCommitDispatchers(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        var installed = 0
        val matchSymbol = runCatching {
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    usingStrings(listOf("match_symbol"), org.luckypray.dexkit.query.enums.StringMatchType.Equals, false)
                    paramCount(9)
                }
            }.toList()
        }.getOrDefault(emptyList())
        matchSymbol.forEach { data ->
            runCatching {
                XposedBridge.hookMethod(data.getMethodInstance(hostClassLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ic = param.args?.firstOrNull { it is InputConnection } as? InputConnection
                        scheduleInspect(ic, "commonCommitText")
                    }
                })
                installed++
            }.onFailure { log("quote-pair: common dispatcher hook failed: ${it.message}") }
        }

        // p.y(InputConnection, CharSequence, String, int, ..., boolean, boolean)
        // 是宿主真正把 commonCommitText 结果送进编辑器的最后一层；B() 之后
        // 仍可能在这里重新组合文本，因此必须同时覆盖这个形状。
        val finalCommit = runCatching {
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    paramCount(7)
                    usingStrings(
                        listOf("commitText failed", "KeyEventHandler"),
                        org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                        false,
                    )
                }
            }.toList()
        }.getOrDefault(emptyList())
        finalCommit.forEach { data ->
            runCatching {
                val method = data.getMethodInstance(hostClassLoader)
                if (method.parameterTypes.firstOrNull() != InputConnection::class.java) return@runCatching
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ic = param.args?.firstOrNull { it is InputConnection } as? InputConnection
                        scheduleInspect(ic, "final-commit")
                    }
                })
                installed++
            }.onFailure { log("quote-pair: final commit hook failed: ${it.message}") }
        }

        val direct = runCatching {
            bridge.findMethod {
                matcher {
                    returnType("void")
                    paramCount(2)
                    paramTypes("int", "java.lang.CharSequence")
                    addInvoke("Landroid/view/inputmethod/InputConnection;->commitText(Ljava/lang/CharSequence;I)Z")
                }
            }.toList()
        }.getOrDefault(emptyList())
        direct.forEach { data ->
            runCatching {
                XposedBridge.hookMethod(data.getMethodInstance(hostClassLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }
                })
                installed++
            }.onFailure { log("quote-pair: direct dispatcher hook failed: ${it.message}") }
        }
        hostDispatcherHookPoints = installed
        log("quote-pair: host commit dispatchers candidates=${matchSymbol.size + direct.size} installed=$installed")
        dispatcherMatchSignatures.clear()
        dispatcherMatchSignatures.addAll(
            (matchSymbol + finalCommit + direct).mapNotNull { data ->
                runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                    ?.let { HookDiagnostics.methodSignature(it) }
            }
        )
        HookDiagnostics.recordMatch("引号:宿主提交分发", dispatcherMatchSignatures,
            "common=${matchSymbol.size}; final=${finalCommit.size}; direct=${direct.size}; hookPoints=$installed")
    }

    /**
     * native 引擎 → Java 的提交汇聚点。
     *
     * 取证（宿主 dex）：native 引擎回调 Java 的实现是 `input/event/a`（类内日志串
     * `commitText, cleared compositionText`，日志 tag 为 `CjZhuyinEngineCallbackImpl`）；
     * 它的 `o(String)` 就是"引擎说要提交这段文字"，紧接着调用
     * `input/event/p;->A(CharSequence, InputConnection, c, boolean, int)` 完成提交。
     *
     * 符号键（上滑符号、符号页）同样从这里出去，所以引号成对必然在这条链上。
     * 按结构 + 语义串把两处找出来，都不写死类名或混淆方法名：
     *
     * 1. 含上述日志串、且存在 `(String) -> boolean` 实例方法的类 → 挂它的引擎回调；
     * 2. 含 `commitText failed` 串、且存在静态
     *    `(CharSequence, InputConnection, *, boolean, int) -> boolean` 的类 → 挂真正提交的分发器。
     *
     * 两个挂点只做两件事：①"一次提交上来正好是一对"就裁成单个左符号；
     * ②把这次动作记成"刚发生成对符号动作"，为 [check] 打开修正时间窗。
     */
    fun attachEngineCommit(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        var installed = 0
        val matchedEngineMethods = mutableListOf<java.lang.reflect.Method>()
        val matchedEngineSignatures = HashSet<String>()
        val attemptedEngineHooks = HashSet<String>()
        fun rememberEngineMatch(method: java.lang.reflect.Method): Boolean {
            val signature = method.toGenericString()
            if (matchedEngineSignatures.add(signature)) matchedEngineMethods.add(method)
            // A duplicated semantic/structural query may return the same method; only install one callback.
            return attemptedEngineHooks.add(signature)
        }

        val callbackClasses = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(
                        listOf("commitText, cleared compositionText"),
                        org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                    )
                }
            }.toList()
        }.onFailure { log("quote-pair: engine callback query failed: ${it.message}") }
            .getOrDefault(emptyList())

        callbackClasses.forEach { data ->
            val cls = runCatching { data.getInstance(hostClassLoader) }.getOrNull() ?: return@forEach
            cls.declaredMethods
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == String::class.java
                }
                .forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val text = param.args?.getOrNull(0) as? String ?: return
                                observeEngineCommit(text, cls.simpleName + "#" + method.name)
                                val single = unwrapPair(text) ?: return
                                param.args[0] = single.toString()
                                                    log(
                                    "quote-pair: engine commit pair trimmed to '" +
                                        describe(single[0]) + "'"
                                )
                            }
                        })
                        installed++
                        matchedEngineMethods.add(method)
                        log("quote-pair: engine callback hooked ${cls.name}#${method.name}")
                    }.onFailure { log("quote-pair: engine callback hook failed: ${it.message}") }
                }
        }

        val dispatcherClasses = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(
                        listOf("commitText failed"),
                        org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                    )
                }
            }.toList()
        }.onFailure { log("quote-pair: dispatcher query failed: ${it.message}") }
            .getOrDefault(emptyList())

        dispatcherClasses.forEach { data ->
            val cls = runCatching { data.getInstance(hostClassLoader) }.getOrNull() ?: return@forEach
            cls.declaredMethods
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.size == 5 &&
                        method.parameterTypes[0] == CharSequence::class.java &&
                        method.parameterTypes[1] == InputConnection::class.java &&
                        method.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes[4] == Int::class.javaPrimitiveType
                }
                .forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val text = param.args?.getOrNull(0) as? CharSequence ?: return
                                observeEngineCommit(text.toString(), cls.simpleName + "#" + method.name)
                                val single = unwrapPair(text) ?: return
                                param.args[0] = single
                                                    log(
                                    "quote-pair: dispatcher pair trimmed to '" +
                                        describe(single[0]) + "'"
                                )
                            }
                        })
                        installed++
                        matchedEngineMethods.add(method)
                        log("quote-pair: commit dispatcher hooked ${cls.name}#${method.name}")
                    }.onFailure { log("quote-pair: commit dispatcher hook failed: ${it.message}") }
                }
        }

        // 新旧宿主都保留一条稳定的最终提交形状：静态 (int, CharSequence) -> void，
        // 方法体直接调用 InputConnection.commitText。新版不再保留旧的日志串，
        // 因此这里用调用关系补回同一汇聚点，而不是按混淆类名或版本分支。
        val structuralDirect = runCatching {
            bridge.findMethod {
                matcher {
                    paramCount(2)
                    paramTypes("int", "java.lang.CharSequence")
                    returnType("void")
                    addInvoke("Landroid/view/inputmethod/InputConnection;->commitText(Ljava/lang/CharSequence;I)Z")
                }
            }.toList()
        }.onFailure { log("quote-pair: structural direct dispatcher query failed: ${it.message}") }
            .getOrDefault(emptyList())
        structuralDirect.forEach { data ->
            runCatching {
                val method = data.getMethodInstance(hostClassLoader).apply { isAccessible = true }
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }
                })
                installed++
                matchedEngineMethods.add(method)
                log("quote-pair: structural direct dispatcher hooked ${data.declaredClassName}#${data.name}")
            }.onFailure { log("quote-pair: structural direct dispatcher hook failed: ${it.message}") }
        }

        // 部分旧宿主在引擎回调里保留了完整日志串，新宿主删掉/改写了日志文案。
        // 退回到稳定结构：宿主引擎回调接口的实现类中，含 commitText 语义串的
        // 实例方法 (String) -> boolean。这样混淆类名和方法名变化不会切断这一层。
        fun hasEngineCallback(type: Class<*>): Boolean {
            fun hasInterface(current: Class<*>): Boolean =
                current.interfaces.any { iface ->
                    iface.name.startsWith("com.oplus.keyboard.base.engine.") || hasInterface(iface)
                } || (current.superclass?.let(::hasInterface) == true)
            return hasInterface(type)
        }
        val callbackFallback = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes("java.lang.String")
                    returnType("boolean")
                    usingStrings(
                        listOf("commitText"),
                        org.luckypray.dexkit.query.enums.StringMatchType.Contains,
                        false,
                    )
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(hostClassLoader).apply { isAccessible = true } }
                    .getOrNull()
            }.filter { method ->
                method.declaringClass.name.startsWith("com.oplus.keyboard.") &&
                    hasEngineCallback(method.declaringClass) &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }.distinctBy { it.toGenericString() }
        }.onFailure { log("quote-pair: structural engine callback query failed: ${it.message}") }
            .getOrDefault(emptyList())
        callbackFallback.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = param.args?.firstOrNull() as? String ?: return
                        observeEngineCommit(text, method.declaringClass.simpleName + "#" + method.name)
                        unwrapPair(text)?.let { param.args[0] = it.toString() }
                    }
                })
                installed++
                matchedEngineMethods.add(method)
                log("quote-pair: structural callback fallback hooked ${method.declaringClass.name}#${method.name}")
            }.onFailure { log("quote-pair: structural callback fallback failed: ${it.message}") }
        }

        val dispatcherFallback = runCatching {
            listOf(5, 7, 9).flatMap { count ->
                bridge.findMethod {
                    matcher { paramCount(count); returnType("boolean") }
                }.mapNotNull { data -> runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull() }
            }.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.any { it == CharSequence::class.java } &&
                    method.parameterTypes.any { InputConnection::class.java.isAssignableFrom(it) }
            }
        }.getOrDefault(emptyList())
        dispatcherFallback.distinctBy { it.toGenericString() }.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }
                })
                installed++
                matchedEngineMethods.add(method)
                log("quote-pair: structural dispatcher fallback hooked ${method.declaringClass.name}#${method.name}")
            }.onFailure { log("quote-pair: structural dispatcher fallback failed: ${it.message}") }
        }

        attachPairCompletionSource(bridge, hostClassLoader)

        engineHookPoints = installed
        log("quote-pair: engine commit hooks installed=$installed")
        engineMatchSignatures.clear()
        engineMatchSignatures.addAll(
            matchedEngineMethods.distinctBy { it.toGenericString() }
                .map { HookDiagnostics.methodSignature(it) }
        )
        HookDiagnostics.recordMatch("引号:引擎提交汇聚点", engineMatchSignatures,
            "callbackClasses=${callbackClasses.size}; dispatcherClasses=${dispatcherClasses.size}; hookPoints=$installed")
    }

    /**
     * 在宿主配对表源头只执行左符号回调，阻止宿主随后根据配对表再次生成右符号。
     *
     * DexKit 先按二参数/布尔返回宽召回，再用参数角色、HashMap 配对表引用、
     * InputConnection 查询调用和 Function0.invoke() 形状联合收敛；不依赖 Kotlin/R8
     * 对 Function0 的具体短类名。这样宿主升级后即使 `Function0` 的运行时名称变化，
     * 仍然可以从结构重新定位。
     */
    private fun attachPairCompletionSource(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        runCatching {
            val recalled = bridge.findMethod {
                matcher {
                    paramCount(2)
                    returnType("boolean")
                }
            }.toList()

            val candidates = recalled.filter { data ->
                val params = data.paramTypeNames
                if (params.size != 2 || params[0] != "java.lang.String") return@filter false
                // 不把 Kotlin/R8 对 Function0 的具体类型名当作硬条件。
                // 新版宿主仍保留同一个结构：第二参数是回调，方法体引用配对表，
                // 并查询 InputConnection 的选区文本；回调接口/生成类的名字可以变化。
                val pairTable = data.usingFields.any { field ->
                    field.field.typeName == "java.util.HashMap" ||
                        field.field.typeName == "java.util.Map" ||
                        field.field.typeName.endsWith("HashMap")
                }
                val queriesInputConnection = data.invokes.any { invoke ->
                    invoke.paramTypeNames == listOf(
                        "android.view.inputmethod.InputConnection",
                        "int",
                        "int",
                    ) && invoke.returnTypeName == "java.lang.CharSequence"
                }
                pairTable && queriesInputConnection
            }

            val resolved = candidates.mapNotNull { data ->
                runCatching {
                    data.getMethodInstance(hostClassLoader).apply { isAccessible = true }
                }.onFailure {
                    log("quote-pair: pair source candidate resolve failed params=${data.paramTypeNames.joinToString()} error=${it.message}")
                }.getOrNull()
            }.filter { method ->
                val params = method.parameterTypes
                val callback = params.getOrNull(1)
                val hasZeroArgInvoke = callback?.let { type ->
                    var current: Class<*>? = type
                    var found = false
                    while (current != null && !found) {
                        found = current.declaredMethods.any { invoke ->
                            invoke.name == "invoke" && invoke.parameterTypes.isEmpty()
                        } || current.methods.any { invoke ->
                            invoke.name == "invoke" && invoke.parameterTypes.isEmpty()
                        }
                        current = current.superclass
                    }
                    found
                } == true
                params.size == 2 &&
                    params[0] == String::class.java &&
                    callback != null &&
                    !callback.isPrimitive &&
                    hasZeroArgInvoke
            }.distinctBy { it.toGenericString() }

            // 该源头在不同 Kotlin/R8 输出中可能是实例方法，也可能是静态桥接方法；
            // 方法是否 static 不属于语义判据，不能因此丢掉唯一候选。
            if (resolved.size > 1) {
                log("quote-pair: pair source candidates after callback-shape=${resolved.map { it.toGenericString() }.take(8)}")
            }

            val method = resolved.singleOrNull()
                ?: error(
                    "pair completion source unresolved candidates=${candidates.size}" +
                        " resolved=${resolved.size} recalled=${recalled.size}"
                )

            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? String ?: return
                    if (text.length != 1 || !PAIRS.containsKey(text[0])) return
                    val callback = param.args?.getOrNull(1) ?: return
                    val invoke = callback.javaClass.methods.firstOrNull {
                        it.name == "invoke" && it.parameterTypes.isEmpty()
                    } ?: return
                    val callbackResult = invoke.invoke(callback)
                    param.result = callbackResult as? Boolean ?: true
                    log(
                        "quote-pair: host pair source short-circuited left=" +
                            describe(text[0]) + " right=${describe(PAIRS[text[0]]!!)}" +
                            " callbackResult=${param.result} method=${method.declaringClass.name}#${method.name}"
                    )
                }
            })
            pairSourceHooked = true
            pairSourceMatchSignatures.clear()
            pairSourceMatchSignatures.add(method.toGenericString())
            HookDiagnostics.recordMatch(
                "引号:成对符号源头",
                pairSourceMatchSignatures,
                "recalled=${recalled.size}; structuralCandidates=${candidates.size}; resolved=${resolved.size}",
            )
            log("quote-pair: pair completion source hooked method=$method")
        }.onFailure {
            HookDiagnostics.record(null, "引号:成对符号源头", false, it.message.orEmpty())
            log("quote-pair: pair completion source unresolved: ${it.message}")
        }
    }

    private fun observeEngineCommit(text: String, source: String) {
        // 诊断口径：短文本（符号、单字）一律留证；长文本只在确实含成对符号时记录。
        // 这是为了在不刷屏的前提下，把"引号究竟从哪条链提交"这件事钉死。
        val interesting = text.length <= 4 || mentionsPair(text)
        if (interesting) {
            val now = System.currentTimeMillis()
            if (now - lastObserveAt >= 150L || text != lastObservedText) {
                lastObserveAt = now
                lastObservedText = text
                log(
                    "quote-pair: engine commit text='" +
                        text.map { describe(it) }.joinToString("") +
                        "' len=" + text.length + " source=" + source
                )
            }
        }
    }

    private fun trimPairArguments(args: Array<Any?>?) {
        if (args == null) return
        var inputConnection: InputConnection? = null
        args.indices.forEach { index ->
            val candidate = args[index]
            if (candidate is InputConnection) inputConnection = candidate
            val value = candidate as? CharSequence ?: return@forEach
            val single = unwrapPair(value) ?: return@forEach
            args[index] = single
            log("quote-pair: host dispatcher pair trimmed to single ${describe(single[0])}")
        }
        scheduleInspect(inputConnection, "dispatcher-trim")
    }
    /** 记录真实用户输入事件，避免把用户稍后手动按下的后符号误判成宿主自动补全。 */
    private fun installUserInputProbe() {
        if (touchProbeInstalled) return
        touchProbeInstalled = true
        runCatching {
            XposedBridge.hookAllMethods(View::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args?.firstOrNull() as? MotionEvent ?: return
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        lastUserInputAt = SystemClock.uptimeMillis()
                        userInputGeneration.incrementAndGet()
                    }
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "dispatchKeyEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    lastUserInputAt = SystemClock.uptimeMillis()
                    userInputGeneration.incrementAndGet()
                }
            })
            log("quote-pair: user input probe installed")
        }.onFailure {
            touchProbeInstalled = false
            log("quote-pair: user input probe failed: ${it.message}")
        }
    }

    private fun isRemoteInputConnection(target: Any?): Boolean {
        if (target == null) return false
        val observed = synchronized(remoteConnections) { remoteConnections.containsKey(target) }
        // 兼容尚未完成第一次 provider 回调的早期提交；后续仍以实例登记为主。
        return observed || target.javaClass.name.contains("RemoteInputConnection")
    }

    private fun leftForClosing(value: Char): Char? =
        PAIRS.entries.firstOrNull { it.value == value }?.key

    /**
     * 只在真正对外的输入连接上处理宿主拆成两次提交的自动补全。
     * 待匹配状态按当前活动编辑目标全局保存，避免宿主在两次提交之间更换代理对象。
     */
    /**
     * 远程输入连接上的拆分提交处理：
     * - 左符号提交后记录一个待闭合状态；
     * - 后续没有新的用户触摸/按键、且在自动补全时间窗内到达的右符号，视为宿主自动补全并吞掉；
     * - 用户产生了新的触摸/按键世代，则明确放行右符号；
     * - 超时状态丢弃，不影响后续正常输入。
     *
     * 这里只在提交前返回 true，不调用 deleteSurroundingText，也不删除已经上屏的用户文本。
     */
    private fun suppressDelayedAutoClosing(target: Any, text: CharSequence): Boolean {
        if (!isRemoteInputConnection(target) || text.length != 1) return false
        val now = SystemClock.uptimeMillis()
        val value = text[0]
        val pending = pendingLeftGlobal
        val left = leftForClosing(value)
        if (pending != null && left == pending.value) {
            val delay = now - pending.at
            val currentGeneration = userInputGeneration.get()
            val userChanged = currentGeneration != pending.userInputGeneration
            pendingLeftGlobal = null
            if (userChanged) {
                log(
                    "quote-pair: manual closing preserved left=${describe(left)}" +
                        " right=${describe(value)} delayMs=$delay" +
                        " userGeneration=${pending.userInputGeneration}->${currentGeneration}" +
                        " connection=${target.javaClass.name}"
                )
                return false
            }
            if (delay in 0L..AUTO_CLOSE_MAX_DELAY_MS) {
                log(
                    "quote-pair: automatic closing suppressed left=${describe(left)}" +
                        " right=${describe(value)} delayMs=$delay" +
                        " userGeneration=${currentGeneration} connection=${target.javaClass.name}"
                )
                return true
            }
            log(
                "quote-pair: closing outside auto window preserved left=${describe(left)}" +
                    " right=${describe(value)} delayMs=$delay connection=${target.javaClass.name}"
            )
            return false
        }
        if (PAIRS.containsKey(value)) {
            pendingLeftGlobal = PendingLeft(
                value = value,
                at = now,
                userInputGeneration = userInputGeneration.get(),
            )
            log(
                "quote-pair: pending left observed ${describe(value)}" +
                    " userGeneration=${userInputGeneration.get()} connection=${target.javaClass.name}"
            )
        }
        return false
    }

    private fun hookAll(cls: Class<*>): Int {
        var count = 0
        count += runCatching {
            XposedBridge.hookAllMethods(cls, "commitText", object : XC_MethodHook() {
                /**
                 * 形态一：宿主/native **一次性提交成对的两个字符**（`“”`）。
                 * 这不需要事后删除，直接在参数上把右半边去掉，交给宿主自己的流程提交单个引号——
                 * 比"提交完再删"少一次编辑往返，也不会让光标先跳到中间再跳回来。
                 */
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    // 留证里带上**运行时类名**：1.21.1 的日志只写死了挂钩的类（`BaseInputConnection`），
                    // 分不清文本到底是进了宿主内部编辑框还是我们自己的搜索框。改成运行时类后，
                    // 一眼就能看出是哪一方的输入连接在收字。
                    val owner = param.thisObject?.javaClass?.simpleName ?: cls.simpleName
                    traceCommit(text, "$owner.commitText")
                    if (suppressDelayedAutoClosing(param.thisObject ?: return, text)) {
                        param.result = true
                        return
                    }
                    // 一次性提交成对字符时保留前半部分；拆分提交时按用户输入时间戳区分延迟自动补全。
                    val single = unwrapPair(text) ?: return
                    param.args[0] = single
                    log("quote-pair: pair commit trimmed to single '${describe(single[0])}'")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    if (!mentionsPair(text)) return
                            inspect(param.thisObject, "commitText")
                }
            }).size
        }.onFailure { log("quote-pair: commitText hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)

        count += runCatching {
            XposedBridge.hookAllMethods(cls, "setComposingText", object : XC_MethodHook() {
                /**
                 * 组合文本同样会出现「一次送来一对」的形态（不少输入法把成对符号
                 * 先作为组合文本推上去，再决定是否提交）。这里做与 commitText 相同的处理。
                 */
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    val owner = param.thisObject?.javaClass?.simpleName ?: cls.simpleName
                    traceCommit(text, "$owner.setComposingText")
                    if (suppressDelayedAutoClosing(param.thisObject ?: return, text)) {
                        param.result = true
                        return
                    }
                    val single = unwrapPair(text) ?: return
                    param.args[0] = single
                    log("quote-pair: composing pair trimmed to single '${describe(single[0])}'")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    if (!mentionsPair(text)) return
                }
            }).size
        }.onFailure { log("quote-pair: setComposingText hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)

        count += runCatching {
            XposedBridge.hookAllMethods(cls, "setSelection", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // setSelection 是高频调用，只有"左右两侧都是成对符号"这种极窄状态才继续
                    check(param.thisObject as? InputConnection)
                }
            }).size
        }.onFailure { log("quote-pair: setSelection hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)
        return count
    }

    /**
     * 若整段提交内容正好是「一个可配对的左符号 + 它的右符号」（允许前后带空白），
     * 返回只含左符号的内容；否则返回 null。
     *
     * 这是宿主"成对补全"最常见的形态：一次 commitText 把两个字符一起送上屏，
     * 再 setSelection 到中间。在参数上直接砍掉右半边即可，不需要任何事后删除。
     */
    private fun unwrapPair(text: CharSequence): CharSequence? {
        if (text.length != 2) return null
        val right = PAIRS[text[0]] ?: return null
        if (text[1] != right) return null
        return text.subSequence(0, 1)
    }

    /**
     * 短文本提交留证。
     *
     * 只记录长度 ≤ 4 的提交（符号、单字），并做 150ms + 相同文本去重，避免刷屏。
     * 这一行是下一轮的判据：
     *  - 点一次前引号若出现两行（先 `“` 后 `”`），说明配对发生在提交之后；
     *  - 若出现一行 `len=2`，说明配对发生在键位表里；
     *  - 若**一行都没有**，说明这条链根本没参与，配对完全在 native 侧完成。
     */
    private fun traceCommit(text: CharSequence, source: String) {
        if (text.isEmpty() || text.length > 4) return
        val now = System.currentTimeMillis()
        val asString = text.toString()
        if (now - lastCommitTraceAt < 150L && asString == lastCommitTraceText) return
        lastCommitTraceAt = now
        lastCommitTraceText = asString
        log(
            "quote-pair: commit '" +
                text.map { describe(it) }.joinToString("") +
                "' len=" + text.length + " via=" + source
        )
    }

    /** 本次提交的文本是否涉及成对符号（含一次提交成对与提交单个前/后符号）。 */
    private fun mentionsPair(text: CharSequence): Boolean {
        if (text.isEmpty()) return false
        if (text.length > 4) return false
        for (i in 0 until text.length) {
            val c = text[i]
            if (PAIRS.containsKey(c) || PAIRS.containsValue(c)) return true
        }
        return false
    }

    /**
     * 只处理明确可识别的“一次提交即为完整成对符号”形态。
     *
     * 如果宿主把自动补全拆成“先提交前符号、再提交后符号”，Java/Xposed 侧无法可靠区分
     * 它和用户随后手动输入后符号的调用；两者的 InputConnection 参数和时序完全相同。
     * 因此这里不再根据相邻字符、时间窗或光标夹缝删除任何后符号，避免破坏用户输入的 `()`、`[]`
     * 等相邻成对内容。自动补全若以一次性成对文本提交，则由 [unwrapPair] 在参数入口裁掉后半部分。
     */
    private fun inspect(target: Any?, source: String) {
        // 保留调用点和参数，方便后续日志取证；不执行任何破坏性删除。
        if (target != null) logThrottled("quote-pair-observe", 1_000L) {
            "quote-pair: observe source=$source; sequential closing is preserved"
        }
    }

    /**
     * 兼容旧调用链的观察入口。当前只观察，不再排队删除光标后的符号。
     */
    private fun scheduleInspect(ic: InputConnection?, source: String) {
        inspect(ic, source)
    }

    /**
     * 保留为统一调用接口，但禁止通过“光标夹在一对符号中间”删除字符。
     * 用户手动输入 `()`、`[]`、`{}` 等相邻成对符号时必须完整保留。
     */
    private fun check(ic: InputConnection?, source: String = "setSelection") {
        if (ic != null) {
            logThrottled("quote-pair-check", 1_000L) {
                "quote-pair: non-destructive check source=$source; manual closing preserved"
            }
        }
    }

    /** 只输出可读字符；引号本身用码位标注，避免日志里出现成对引号引起歧义。 */
    private fun describe(c: Char): String = String(charArrayOf(c)) + " (U+" + String.format("%04X", c.code) + ")"
}
