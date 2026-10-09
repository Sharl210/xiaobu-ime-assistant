package com.oplusime.panel

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 复用宿主常用语编辑覆盖层；不创建 Dialog、布局或输入框。 */
internal object HostPhraseEditor {
    private var open: Method? = null
    private var accessor: Method? = null
    private var headerField: java.lang.reflect.Field? = null
    private var cleanup: (() -> Unit)? = null
    private var activeHeader: java.lang.ref.WeakReference<View>? = null
    private var activeFinish: java.lang.ref.WeakReference<View>? = null
    private var activeCancel: java.lang.ref.WeakReference<View>? = null
    private var activeConfirm: (((Throwable?) -> Unit) -> Unit)? = null
    @Volatile private var confirmRunning = false
    @Volatile private var confirmHooksInstalled = false
    private val finishHookedClasses = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>(),
    )
    private val hiddenInputCode = mutableListOf<Pair<View, Int>>()
    /** Host phrase-template methods resolved during installation; reused by search and clipboard edit. */
    private val templateMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** 先撤销活动状态再还原控件，允许宿主关闭链重入而不重复执行。 */
    private fun releaseSession(reason: String) {
        val finish = cleanup ?: return
        cleanup = null
        activeHeader = null
        activeFinish = null
        activeCancel = null
        activeConfirm = null
        confirmRunning = false
        runCatching { finish() }
            .onFailure { logCritical("native-editor: cleanup failed reason=$reason error=${it.stackTraceToString()}") }
        logCritical("native-editor: session released reason=$reason")
    }

    /**
     * 宿主会在某些版本的生命周期回调里重新给 tv_finish 设置原生监听器。
     * 只替换 setOnClickListener 不可靠，因此同时在 View 的最终点击分发入口拦截当前编辑会话的
     * finish 视图。这样无论点击来自触摸、accessibility 还是宿主重新绑定监听器，都走同一条确认链。
     */
    private fun installConfirmDispatchHooks(finish: View) {
        val clickHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view !== activeFinish?.get()) return
                logCritical("native-editor: finish dispatch intercepted class=${view.javaClass.name} method=${param.method.name}")
                dispatchConfirm(param.method.name)
                // 不论写回成功还是失败都消费宿主原生 finish；否则宿主会沿“新增/关闭”分支继续。
                param.result = true
            }
        }
        val touchHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view !== activeFinish?.get()) return
                val event = param.args?.firstOrNull() as? android.view.MotionEvent ?: return
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                    event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                    event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                    logCritical("native-editor: finish touch dispatch class=${view.javaClass.name} action=${event.actionMasked}")
                }
            }
        }
        runCatching {
            var current: Class<*>? = finish.javaClass
            while (current != null && View::class.java.isAssignableFrom(current)) {
                if (finishHookedClasses.add(current)) {
                    XposedBridge.hookAllMethods(current, "performClick", clickHook)
                    XposedBridge.hookAllMethods(current, "callOnClick", clickHook)
                    XposedBridge.hookAllMethods(current, "dispatchTouchEvent", touchHook)
                }
                if (current == View::class.java) break
                current = current.superclass
            }
            confirmHooksInstalled = true
            logCritical("native-editor: finish dispatch hooks installed target=${finish.javaClass.name}")
        }.onFailure {
            logCritical("native-editor: finish dispatch hooks failed ${it.stackTraceToString()}")
        }
    }

    private fun dispatchConfirm(source: String): Boolean {
        val callback = activeConfirm ?: return false
        if (confirmRunning) {
            logCritical("native-editor: duplicate confirm ignored source=$source")
            return true
        }
        confirmRunning = true
        return try {
            logCritical("native-editor: confirm tapped source=$source")
            callback.invoke { error ->
                if (error == null) {
                    logCritical("native-editor: confirm writeback succeeded source=$source")
                } else {
                    val cause = generateSequence(error) { it.cause }.lastOrNull() ?: error
                    logCritical("native-editor: confirm failed source=$source type=${error.javaClass.name} cause=${cause.javaClass.name} message=${cause.message}\n${error.stackTraceToString()}")
                }
                confirmRunning = false
            }
            true
        } catch (error: Throwable) {
            confirmRunning = false
            val cause = generateSequence(error) { it.cause }.lastOrNull() ?: error
            logCritical("native-editor: confirm dispatch failed source=$source type=${error.javaClass.name} cause=${cause.javaClass.name} message=${cause.message}\n${error.stackTraceToString()}")
            true
        }
    }

    fun install(bridge: DexKitBridge, loader: ClassLoader) {
        runCatching {
            val entryCandidates = runCatching {
                bridge.findMethod {
                    matcher {
                        usingStrings(listOf("directory"), StringMatchType.Equals, false)
                        paramTypes("java.lang.String")
                        returnType("void")
                    }
                }.toList()
            }.getOrDefault(emptyList())
            val structuralEntryCandidates = if (entryCandidates.isNotEmpty()) entryCandidates else runCatching {
                bridge.findMethod {
                    matcher {
                        paramTypes("java.lang.String")
                        returnType("void")
                    }
                }.filter { data -> data.invokes.any { it.name == "setDirectory" && it.paramCount == 1 } }
            }.getOrDefault(emptyList())
            val entryData = structuralEntryCandidates.firstOrNull { data ->
                data.invokes.any { it.name == "setDirectory" && it.paramCount == 1 }
            } ?: error("native phrase entry unresolved candidates=${structuralEntryCandidates.size}")
            val setter = entryData.invokes.first { it.name == "setDirectory" && it.paramCount == 1 }
            val header = setter.getClassInstance(loader)
            val helper = entryData.getClassInstance(loader)
            val field = helper.declaredFields.firstOrNull { it.type == header && !Modifier.isStatic(it.modifiers) }
                ?.apply { isAccessible = true }
                ?: error("native phrase header field unresolved")
            val getterData = bridge.findMethod {
                matcher { paramCount(0); returnType(helper.name); modifiers(Modifier.STATIC) }
            }.distinctBy { it.descriptor }.firstOrNull()
                ?: error("native phrase helper accessor unresolved")
            val getter = getterData.getMethodInstance(loader).apply { isAccessible = true }
            val closes = bridge.findMethod {
                matcher { declaredClass(helper.name); paramTypes("boolean") }
            }.filter { data ->
                data.returnTypeName in listOf("void", "boolean") &&
                    data.invokes.any { it.declaredClassName == header.name && it.paramCount == 0 }
            }
            check(closes.isNotEmpty()) { "native phrase close chain unavailable" }
            closes.forEach { close ->
                XposedBridge.hookMethod(close.getMethodInstance(loader), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        releaseSession("host-close[${close.name}]")
                    }
                })
            }
            open = entryData.getMethodInstance(loader).apply { isAccessible = true }
            accessor = getter.apply { isAccessible = true }
            headerField = field
            templateMatchSignatures.clear()
            buildList {
                runCatching { setter.getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()?.let(::add)
                open?.let(::add)
                accessor?.let(::add)
                closes.forEach { close ->
                    runCatching { close.getMethodInstance(loader).apply { isAccessible = true } }
                        .getOrNull()?.let(::add)
                }
            }.distinctBy { it.toGenericString() }
                .mapTo(templateMatchSignatures) { it.toGenericString() }
            log("native-editor: host phrase entry and close chain resolved")
            HookDiagnostics.recordMatch(
                "宿主常用语编辑入口",
                templateMatchSignatures,
                "宿主编辑模板打开、标题/输入框绑定及关闭链已按结构解析",
            )
            HookDiagnostics.recordMatch(
                "宿主编辑界面输入码隐藏",
                templateMatchSignatures,
                "输入码资源在模板实例显示时隐藏；这里记录模板结构匹配，不等待界面实际打开",
            )
            HookDiagnostics.recordMatch(
                "搜索界面原生模板",
                templateMatchSignatures,
                "搜索框复用同一宿主编辑模板；这里记录模板结构匹配，不等待搜索框实际打开",
            )
            HookDiagnostics.recordMatch(
                "编辑界面原生模板",
                templateMatchSignatures,
                "剪贴板编辑复用同一宿主编辑模板；这里记录模板结构匹配，不等待编辑框实际打开",
            )
        }.onFailure { log("native-editor: resolution failed: ${it.message}") }
    }

    private fun hideInputCode(header: View, anchor: View) {
        hiddenInputCode.clear()
        val names = listOf("tv_input_code", "input_code", "ed_input_code")
        names.forEach { name ->
            val id = anchor.resources.getIdentifier(name, "id", "com.oplus.keyboard")
            if (id == 0) return@forEach
            val view = header.findViewById<View>(id) ?: return@forEach
            val parent = view.parent as? View
            val target = if (parent is ViewGroup && parent.childCount <= 3) parent else view
            hiddenInputCode += target to target.visibility
            target.visibility = View.GONE
        }
        if (hiddenInputCode.isNotEmpty()) {
            log("native-editor: input-code layout hidden count=${hiddenInputCode.size}")
            HookDiagnostics.recordMatch(
                "宿主编辑界面输入码隐藏",
                templateMatchSignatures,
                if (hiddenInputCode.isNotEmpty()) {
                    "模板结构已匹配；本次实例隐藏控件数=${hiddenInputCode.size}"
                } else {
                    "模板结构已匹配；本次实例未解析到可隐藏的输入码资源 ID"
                },
            )
        } else {
            log("native-editor: input-code resource not found on this template instance")
            HookDiagnostics.recordMatch(
                "宿主编辑界面输入码隐藏",
                templateMatchSignatures,
                "模板结构已匹配；本次实例未解析到可隐藏的输入码资源 ID",
            )
        }
    }
    fun show(anchor: View, title: String, initial: String,
             register: ((EditText) -> Boolean)?,
             onConfirm: (String, (Throwable?) -> Unit) -> Unit, onClose: () -> Unit): Boolean {
        if (cleanup != null && activeHeader?.get()?.isShown != true) {
            releaseSession("stale-hidden-view")
        }
        if (cleanup != null) {
            log("native-editor: existing visible session retained")
            return false
        }
        return runCatching {
            val helper = accessor?.invoke(null) ?: error("native helper unavailable")
            val entry = open ?: error("native entry unavailable")
            entry.invoke(helper, "")
            val header = headerField?.get(helper) as? View ?: error("native header unavailable")
            fun id(name: String) = anchor.resources.getIdentifier(name, "id", "com.oplus.keyboard")
            val input = header.findViewById<EditText>(id("ed_phrase")) ?: error("native content field missing")
            val cancel = header.findViewById<TextView>(id("tv_cancel")) ?: error("native cancel missing")
            val finish = header.findViewById<TextView>(id("tv_finish")) ?: error("native finish missing")
            val heading = header.findViewById<TextView>(id("tv_add_phrase_title")) ?: error("native title missing")
            val listenerInfo = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }
            fun listener(v: View): View.OnClickListener? {
                val info = listenerInfo.get(v) ?: return null
                return info.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
                    .get(info) as? View.OnClickListener
            }
            val oldCancel = listener(cancel) ?: error("native cancel listener missing")
            val oldFinish = listener(finish)
            val oldTitle = heading.text
            val oldFinishText = finish.text
            val oldHint = input.hint
            val oldFilters = input.filters
            hideInputCode(header, anchor)
            input.filters = emptyArray()
            input.setHint(if (title == "搜索") "输入要搜索的关键字" else "输入要编辑的剪贴板内容")
            input.setText(initial)
            heading.text = title
            finish.text = "完成"
            activeHeader = java.lang.ref.WeakReference(header)
            activeFinish = java.lang.ref.WeakReference(finish)
            activeCancel = java.lang.ref.WeakReference(cancel)
            installConfirmDispatchHooks(finish)
            // 确认回调只在真正写回完成后关闭宿主模板；异步写回期间保持按钮受控。
            activeConfirm = { completed ->
                val value = input.text.toString()
                logCritical("native-editor: confirm callback entered textLen=${value.length}")
                onConfirm(value) { error ->
                    if (error != null) {
                        completed(error)
                    } else {
                        try {
                            oldCancel.onClick(cancel)
                            releaseSession("confirm-button")
                            completed(null)
                        } catch (closeError: Throwable) {
                            releaseSession("confirm-close-failed")
                            completed(closeError)
                        }
                    }
                }
            }
            // 常用语/剪贴板编辑复用的是宿主自己的 header，而不是剪贴板面板类；
            // 也要登记到统一返回路由，否则 Android 系统手势先隐藏窗口时
            // PanelState 会误判“当前没有面板”，无法执行回主键盘兜底。
            PanelState.remember(header)
            HookDiagnostics.recordMatch(
                if (title == "搜索") "搜索界面原生模板" else "编辑界面原生模板",
                templateMatchSignatures,
                "运行时模板已解析；title=$title; input=${input.javaClass.name}; finish=${finish.javaClass.name}; finishId=0x${Integer.toHexString(finish.id)}",
            )
            logCritical("native-editor: finish target resolved class=${finish.javaClass.name} id=0x${Integer.toHexString(finish.id)} clickable=${finish.isClickable} enabled=${finish.isEnabled}")
            val attachment = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { releaseSession("header-detached") }
            }
            header.addOnAttachStateChangeListener(attachment)
            val visibility = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                if (activeHeader?.get() === header && !header.isShown) releaseSession("header-hidden")
            }
            header.viewTreeObserver.addOnGlobalLayoutListener(visibility)
            cleanup = {
                header.removeOnAttachStateChangeListener(attachment)
                if (header.viewTreeObserver.isAlive) header.viewTreeObserver.removeOnGlobalLayoutListener(visibility)
                cancel.setOnClickListener(oldCancel)
                finish.setOnClickListener(oldFinish)
                heading.text = oldTitle
                finish.text = oldFinishText
                input.hint = oldHint
                input.filters = oldFilters
                hiddenInputCode.forEach { (view, visibility) -> view.visibility = visibility }
                hiddenInputCode.clear()
                onClose()
            }
            // 不能依赖宿主 close 方法的 Hook 必定执行：内联/分支会绕开那个入口。
            // 按钮完成原生关闭后必须显式释放；重复的 host-close 回调按幂等处理。
            cancel.setOnClickListener {
                logCritical("native-editor: cancel tapped")
                try { oldCancel.onClick(cancel) } finally { releaseSession("cancel-button") }
            }
            finish.isClickable = true
            finish.isEnabled = true
            finish.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    logCritical("native-editor: finish touch-up received")
                }
                false
            }
            finish.setOnClickListener {
                dispatchConfirm("onClickListener")
            }
            input.requestFocus()
            input.setSelection(input.text.length)
            check(register?.invoke(input) == true) { "native input registration failed" }
            logCritical("native-editor: actual host phrase view shown title=$title finishId=0x${Integer.toHexString(finish.id)}")
            true
        }.onFailure {
            activeHeader = null
            activeFinish = null
            activeCancel = null
            activeConfirm = null
            confirmRunning = false
            releaseSession("open-failure")
            logCritical("native-editor: open failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }
}
