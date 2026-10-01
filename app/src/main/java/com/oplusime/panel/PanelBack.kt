package com.oplusime.panel

import android.inputmethodservice.InputMethodService
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

    @Volatile
    private var panelRef: WeakReference<View>? = null

    /** 宿主自己的「收起面板回键盘」链（与剪切/粘贴返回用的是同一条）。 */
    @Volatile
    var closePanel: (() -> Boolean)? = null

    fun remember(view: View) {
        panelRef = WeakReference(view)
    }

    /** 用视图的**实时**显示状态判断，而不是记一个可能失真的布尔量。 */
    fun anyShown(): Boolean = panelRef?.get()?.isShown == true
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

    @Volatile
    private var handledCount = 0

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        // 宿主自己的 IME 服务：按"覆写了 onKeyDown(int, KeyEvent)"且确实是 InputMethodService 子类定位，
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
            runCatching { PanelState.closePanel?.invoke() }
                .onFailure { log("panel-back: close panel failed: ${it.message}") }
            SymbolPageRedirect.backToMainKeyboard()
            log("panel-back: back consumed while panel shown (total=$handledCount) -> main keyboard")
        }
    }
}
