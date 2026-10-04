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
    private val hiddenInputCode = mutableListOf<Pair<View, Int>>()

    /** 先撤销活动状态再还原控件，允许宿主关闭链重入而不重复执行。 */
    private fun releaseSession(reason: String) {
        val finish = cleanup ?: return
        cleanup = null
        activeHeader = null
        runCatching { finish() }
            .onFailure { log("native-editor: cleanup failed: ${it.message}") }
        log("native-editor: session released reason=$reason")
    }

    fun install(bridge: DexKitBridge, loader: ClassLoader) {
        runCatching {
            val entry = bridge.findMethod {
                matcher {
                    usingStrings(listOf("directory"), StringMatchType.Equals, false)
                    paramTypes("java.lang.String")
                    returnType("void")
                }
            }.filter { data ->
                data.invokes.any { it.name == "setDirectory" && it.paramTypeNames == listOf("java.lang.String") }
            }.single()
            val setter = entry.invokes.single { it.name == "setDirectory" }
            val header = setter.getClassInstance(loader)
            val helper = entry.getClassInstance(loader)
            val field = helper.declaredFields.single { it.type == header && !Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }
            val getter = bridge.findMethod {
                matcher { paramCount(0); returnType(helper.name); modifiers(Modifier.STATIC) }
            }.distinctBy { it.descriptor }.single().getMethodInstance(loader)
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
            open = entry.getMethodInstance(loader).apply { isAccessible = true }
            accessor = getter.apply { isAccessible = true }
            headerField = field
            log("native-editor: host phrase entry and close chain resolved")
            HookDiagnostics.record(null, "宿主常用语编辑入口", true,
                "open=${open?.declaringClass?.name}#${open?.name}; close=${closes.joinToString { it.descriptor }}")
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
            HookDiagnostics.record(anchor.context, "宿主编辑界面输入码隐藏", true,
                hiddenInputCode.joinToString { it.first.javaClass.name })
        } else {
            HookDiagnostics.record(anchor.context, "宿主编辑界面输入码隐藏", false, "未找到 tv_input_code/input_code/ed_input_code")
        }
    }
    fun show(anchor: View, title: String, initial: String,
             register: ((EditText) -> Boolean)?,
             onConfirm: (String) -> Unit, onClose: () -> Unit): Boolean {
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
            HookDiagnostics.record(anchor.context, if (title == "搜索") "搜索界面原生模板" else "编辑界面原生模板", true,
                "title=$title; input=${input.javaClass.name}; finish=${finish.javaClass.name}")
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
                try { oldCancel.onClick(cancel) } finally { releaseSession("cancel-button") }
            }
            finish.setOnClickListener {
                runCatching { onConfirm(input.text.toString()) }
                    .onSuccess {
                        try { oldCancel.onClick(cancel) } finally { releaseSession("confirm-button") }
                    }
                    .onFailure { log("native-editor: confirm failed: ${it.message}") }
            }
            input.requestFocus()
            input.setSelection(input.text.length)
            check(register?.invoke(input) == true) { "native input registration failed" }
            log("native-editor: actual host phrase view shown title=$title")
            true
        }.onFailure {
            releaseSession("open-failure")
            log("native-editor: open failed: ${it.message}")
        }.getOrDefault(false)
    }
}
