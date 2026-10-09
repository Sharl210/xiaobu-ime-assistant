package com.oplusime.panel

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference

/**
 * 面板当前是否真的显示着，以及"收起面板"走的哪条链。
 *
 * 只存**弱引用**：面板随时可能被宿主回收，用强引用会把已经销毁的视图钉在内存里，
 * 而且会让"面板是否显示"这个判断失真。
 */
internal object PanelState {

    private val panels = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())
    private var watchRunning = false
    @Volatile private var lastRememberedAt: Long = 0L
    @Volatile var closePanel: (() -> Boolean)? = null

    fun remember(view: View) {
        lastRememberedAt = android.os.SystemClock.uptimeMillis()
        if (panels.put(view, true) == null) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.post { PanelBackRouter.syncPanelBackRegistration() }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    v.post { PanelBackRouter.syncPanelBackRegistration() }
                }
            })
        }
        view.post { PanelBackRouter.syncPanelBackRegistration() }
        if (!watchRunning) {
            watchRunning = true
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post(object : Runnable {
                override fun run() {
                    if (panels.isEmpty()) { watchRunning = false; return }
                    PanelBackRouter.syncPanelBackRegistration()
                    handler.postDelayed(this, 100L)
                }
            })
        }
    }

    fun anyShown(): Boolean = synchronized(panels) {
        panels.keys.any { it.isAttachedToWindow && it.isShown }
    }

    fun wasShownRecently(windowMs: Long = 1800L): Boolean {
        val at = lastRememberedAt
        return at > 0L && android.os.SystemClock.uptimeMillis() - at <= windowMs
    }
}

/**
 * 「返回 = 回键盘主页面」。
 *
 * 用户实测：在文本编辑面板、剪贴板面板、常用语面板里按返回，整个输入法会被关掉，
 * 而不是回到用来打字的主键盘页。这里在**面板正显示着**的时候把系统返回键吃掉，
 * 改成走宿主自己的两条链：先收起面板，再把键盘恢复成主键盘页。
 *
 * 只在"面板确实显示着"时生效，因此不会影响正常使用输入法时的返回行为。
 */
internal object PanelBackRouter {

    @Volatile
    private var hookedCount = 0
    private val onKeyDownMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val systemGestureMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var handledCount = 0

    @Volatile
    private var backViewId: Int = 0

    @Volatile
    private var handlingSystemBack = false

    private var activeService: WeakReference<InputMethodService>? = null
    private data class BackRegistration(val dispatcher: Any, val callback: android.window.OnBackInvokedCallback)
    private val gestureCallbacks = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<InputMethodService, BackRegistration>(),
    )

    fun syncPanelBackRegistration() {
        val service = activeService?.get() ?: return
        if (Build.VERSION.SDK_INT < 33) return
        if (PanelState.anyShown()) registerGestureCallback(service)
        else gestureCallbacks.remove(service)?.let { registration ->
            runCatching {
                registration.dispatcher.javaClass.getMethod("unregisterOnBackInvokedCallback", android.window.OnBackInvokedCallback::class.java)
                    .invoke(registration.dispatcher, registration.callback)
                log("panel-back: panel closed; custom callback unregistered")
            }.onFailure { log("panel-back: unregister failed ${it.message}") }
        }
    }

    fun publishDiagnostics() {
        HookDiagnostics.recordMatch("返回键:宿主onKeyDown", onKeyDownMatchSignatures,
            "InputMethodService 子类 onKeyDown 结构匹配；运行时 hook 数量=$hookedCount")
        HookDiagnostics.recordMatch("返回键:系统手势", systemGestureMatchSignatures,
            "系统返回生命周期/回调入口结构已解析")
    }

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader, backId: Int) {
        backViewId = backId
        installPerformClickHook()
        installImeGestureBackHook()
        installOnBackInvokedHook()
        // 宿主自己的 IME 服务：按“覆写了 onKeyDown(int, KeyEvent)”且确实是 InputMethodService 子类定位，
        // 不写死类名。
        val candidates = runCatching {
            bridge.findMethod {
                matcher {
                    name("onKeyDown")
                    paramTypes("int", "android.view.KeyEvent")
                    returnType("boolean")
                }
            }.toList()
        }.onFailure { log("panel-back: query failed: ${it.message}") }
            .getOrDefault(emptyList())

        onKeyDownMatchSignatures.clear()
        onKeyDownMatchSignatures.addAll(candidates.mapNotNull { data ->
            runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?.takeIf { InputMethodService::class.java.isAssignableFrom(it.declaringClass) }
                ?.let { HookDiagnostics.methodSignature(it) }
        })

        candidates.forEach { data ->
            val cls = runCatching { data.declaredClass?.getInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (!InputMethodService::class.java.isAssignableFrom(cls)) return@forEach
            val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, backHook())
                hookedCount++
            }.onFailure { log("panel-back: host hook failed on ${cls.name}: ${it.message}") }
        }

        // 宿主没有覆写时，直接挂框架基类（作用域是输入法进程，只影响它自己）。
        runCatching {
            XposedBridge.hookAllMethods(InputMethodService::class.java, "onKeyDown", backHook())
            hookedCount++
        }.onFailure { log("panel-back: base hook failed: ${it.message}") }

        log("panel-back: candidates=${candidates.size} hooks=$hookedCount")
        HookDiagnostics.recordMatch("返回键:宿主onKeyDown", onKeyDownMatchSignatures,
            "候选=${onKeyDownMatchSignatures.size}; 运行时 hook 数量=$hookedCount")
    }

    private fun returnToTypingPage(source: String): Boolean {
        val closed = runCatching { PanelState.closePanel?.invoke() == true }.getOrDefault(false)
        log("panel-back: direct main-page return source=$source closePanel=$closed keyboardTypeSwitch=false")
        return closed
    }

    private fun installPerformClickHook() {
        runCatching {
            XposedBridge.hookAllMethods(View::class.java, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (backViewId == 0 || view.id != backViewId || !PanelState.anyShown()) return
                    param.result = true
                    handledCount++
                    returnToTypingPage("performClick")
                    log("panel-back: back view performClick consumed id=0x${Integer.toHexString(backViewId)} total=$handledCount")
                }
            })
            log("panel-back: performClick fallback hooked backId=0x${Integer.toHexString(backViewId)}")
        }.onFailure { log("panel-back: performClick hook failed: ${it.message}") }
    }

    private fun installOnBackInvokedHook() {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            val lifecycle = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    activeService = WeakReference(service)
                    syncPanelBackRegistration()
                    service.window?.window?.decorView?.post { syncPanelBackRegistration() }
                }
            }
            listOf("onWindowShown", "onStartInputView", "onCreateInputView").forEach {
                XposedBridge.hookAllMethods(InputMethodService::class.java, it, lifecycle)
            }
            // 框架把 IME 回调转发给当前应用窗口；默认回调重新登记时重检我们的面板回调。
            XposedBridge.hookAllMethods(InputMethodService::class.java, "registerDefaultOnBackInvokedCallback", lifecycle)
            XposedBridge.hookAllMethods(InputMethodService::class.java, "onWindowHidden", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    val panelWasVisible = PanelState.wasShownRecently()
                    log("panel-back: window hidden; panelRecentlyVisible=$panelWasVisible")
                    if (!panelWasVisible) return
                    // Android 17 的边缘返回有时绕过 IME dispatcher，直接先隐藏窗口；
                    // 这时前面的 callback/onKeyDown 都没有机会消费返回。恢复链必须在
                    // onWindowHidden 之后补一次：先关闭宿主面板状态，再重新显示 IME，
                    // 否则用户看到的就是“编辑页返回后输入法消失”。
                    service.window?.window?.decorView?.post {
                        val closed = runCatching { PanelState.closePanel?.invoke() == true }.getOrDefault(false)
                        val shown = runCatching {
                            service.showWindow(false)
                            true
                        }.getOrDefault(false)
                        log("panel-back: hidden fallback restored closePanel=$closed showWindow=$shown")
                        service.window?.window?.decorView?.postDelayed({
                            syncPanelBackRegistration()
                        }, 120L)
                    }
                }
            })
            systemGestureMatchSignatures.clear()
            systemGestureMatchSignatures.addAll(
                listOf("onWindowShown", "onStartInputView", "onCreateInputView", "registerDefaultOnBackInvokedCallback", "onWindowHidden")
                    .flatMap { name ->
                        InputMethodService::class.java.methods.filter { it.name == name }
                    }
                    .map { HookDiagnostics.methodSignature(it) }
            )
            HookDiagnostics.recordMatch("返回键:系统手势", systemGestureMatchSignatures,
                "API ${Build.VERSION.SDK_INT} 生命周期入口已解析；回调注册发生在运行时")
        }.onFailure {
            HookDiagnostics.record(null, "返回键:系统手势", false, it.message.orEmpty())
            log("panel-back: direct back lifecycle failed ${it.message}")
        }
    }

    private fun registerGestureCallback(service: InputMethodService) {
        if (Build.VERSION.SDK_INT < 33 || !PanelState.anyShown()) return
        runCatching {
            // 优先注册 IME 转发器，避免只注册输入法自身 Window 而错过当前应用的系统手势。
            val field = InputMethodService::class.java.declaredFields.singleOrNull {
                it.type.name == "android.window.ImeOnBackInvokedDispatcher"
            }?.apply { isAccessible = true }
            val dispatcher = field?.get(service) ?: service.window?.window?.onBackInvokedDispatcher
                ?: error("IME back dispatcher unavailable")
            val old = gestureCallbacks[service]
            if (old?.dispatcher === dispatcher) return
            if (old != null) {
                old.dispatcher.javaClass.getMethod("unregisterOnBackInvokedCallback", android.window.OnBackInvokedCallback::class.java)
                    .invoke(old.dispatcher, old.callback)
                gestureCallbacks.remove(service)
            }
            val callback = android.window.OnBackInvokedCallback {
                if (!consumePanelBack("IME-forwarded-back")) {
                    syncPanelBackRegistration()
                    service.requestHideSelf(0)
                }
            }
            dispatcher.javaClass.getMethod("registerOnBackInvokedCallback", Int::class.javaPrimitiveType,
                android.window.OnBackInvokedCallback::class.java)
                .invoke(dispatcher, android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            gestureCallbacks[service] = BackRegistration(dispatcher, callback)
            log("panel-back: direct callback registered dispatcher=${dispatcher.javaClass.name} panel=true")
        }.onFailure { log("panel-back: direct callback failed ${it.message}") }
    }

    private fun consumePanelBack(source: String): Boolean {
        if (!PanelState.anyShown() || handlingSystemBack) return false
        handlingSystemBack = true
        try {
            handledCount++
            returnToTypingPage(source)
            log("panel-back: direct panel return source=$source hideRequested=false")
            syncPanelBackRegistration()
            return true
        } finally { handlingSystemBack = false }
    }

    private fun installImeGestureBackHook() {
        runCatching {
            // requestHideSelf 是尚未请求 system_server 隐藏之前的入口；只在面板显示时消费。
            // 不拦所有 hideWindow，也不在隐藏后重新弹出，避免误接管用户切应用/收键盘。
            XposedBridge.hookAllMethods(InputMethodService::class.java, "requestHideSelf", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (consumePanelBack("requestHideSelf-before-system")) param.result = null
                }
            })
        }.onFailure { log("panel-back: pre-hide back hook failed ${it.message}") }
    }

    private fun backHook() = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val keyCode = param.args?.getOrNull(0) as? Int ?: return
            if (keyCode != KeyEvent.KEYCODE_BACK) return
            // 搜索输入条显示中：返回 = 取消搜索，回到面板看全部条目。
            // （顺序必须在面板判断之前：输入条显示时面板是收起的，否则这一按会落到宿主手里。）
            if (ClipSearch.isSearchBarShown()) {
                param.result = true
                handledCount++
                runCatching { ClipSearch.cancelActiveSearch() }
                    .onFailure { log("panel-back: cancel search failed: ${it.message}") }
                log("panel-back: back consumed while search bar shown (total=$handledCount) -> cancel search")
                return
            }
            // 只在面板真的显示着的时候接管；否则原样放行，不影响正常收起键盘。
            if (!PanelState.anyShown()) return
            param.result = true
            handledCount++
            returnToTypingPage("system-back")
            log("panel-back: back consumed while panel shown (total=$handledCount) -> main keyboard")
        }
    }
}
