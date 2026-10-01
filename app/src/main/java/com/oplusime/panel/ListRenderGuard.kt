package com.oplusime.panel

import android.text.TextUtils
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.MethodData

/**
 * 列表行「超大文本」渲染护栏。
 *
 * ## 要解决的问题
 *
 * 剪贴板 / 常用语列表里只要有一条**超长正文**（几万字的一段复制内容），滑动就会卡：
 * 行的正文控件在每次绑定时都会被整段塞进去，而文本控件即使设置了最大行数，
 * 仍然要为**整段文本**建立一次排版（行数、断行、宽度测量），代价与字符数近似成正比。
 * 屏幕上一屏最多也就十几行、每行看得见几百个字符，把整段都排出来属于纯浪费。
 *
 * ## 改法
 *
 * 在正文控件接收文本的入口处，只对**过长**的文本做显示层截断（保留前面一段 + 省略号）。
 *
 * 为什么不影响复制：
 *
 *  - 复制/剪切走的是**列表项数据对象**里的原始正文（行的点击回调拿到的是数据对象本身），
 *    与控件显示的文字无关，因此截断只影响"看见多少"，不影响"复制到多少"；
 *  - 常用语正文的编辑发生在宿主的编辑弹窗里（独立输入框），不经过这条显示路径。
 *
 * ## 定位方式
 *
 * 不写死宿主任何类名：按**结构特征**召回「名为 setText、参数为 (CharSequence, BufferType)」
 * 的方法，再在运行时确认两点——① 声明类的继承链上出现文本控件；② 该类另有一个
 * CharSequence 字段（即宿主"可展开文本"的形态：把原文也存了一份用于展开/收起）。
 * 两条同时满足才挂，避免误伤普通文本控件。
 */
internal object ListRenderGuard {

    /**
     * 显示层保留的最大字符数。
     *
     * 取值理由：一屏能看到的正文远小于此，正常长度的条目（短信、地址、代码片段）完全不受影响；
     * 只有真正的"超长粘贴"才会被截断，而它恰恰是卡顿的来源。若将来觉得需要看得更多，
     * 只改这一个数即可。
     */
    private const val MAX_RENDER_CHARS: Int = 1500

    /** 已挂过的类，避免重复挂（宿主可能多次触发安装）。 */
    private val hooked = java.util.Collections.synchronizedSet(HashSet<String>())

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates: List<MethodData> = runCatching {
            bridge.findMethod {
                matcher {
                    name("setText")
                    paramTypes(
                        "java.lang.CharSequence",
                        "android.widget.TextView\$BufferType",
                    )
                }
            }.toList()
        }.onFailure { log("render-guard query failed: ${it.message}") }
            .getOrDefault(emptyList())
        log("render-guard candidates=${candidates.size}")

        var installed = 0
        candidates.forEach { candidate ->
            val ownerName = candidate.declaredClassName ?: return@forEach
            if (hooked.contains(ownerName)) return@forEach
            val owner = runCatching { Class.forName(ownerName, false, hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (!isTextCarrier(owner)) return@forEach
            val method = runCatching { candidate.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        guard(param)
                    }
                })
                hooked.add(ownerName)
                installed++
                log("render-guard hooked $ownerName#setText")
            }.onFailure { log("render-guard hook failed $ownerName: ${it.message}") }
        }
        log("render-guard installed=$installed maxChars=$MAX_RENDER_CHARS")
    }

    /**
     * 截断超长文本。
     *
     * 只在**明显过长**时动手，并且只动第一个参数（文本本身），其余参数原样传递。
     * 文本已经是短文本时直接返回，零额外开销。
     */
    private fun guard(param: XC_MethodHook.MethodHookParam) {
        val text = param.args?.getOrNull(0) as? CharSequence ?: return
        if (text.length <= MAX_RENDER_CHARS) return
        val kept = TextUtils.substring(text, 0, MAX_RENDER_CHARS) + "…"
        param.args[0] = kept
        logThrottled("render-guard", 2_000L) {
            "render-guard truncated row text ${text.length} -> ${kept.length} chars"
        }
    }

    /**
     * 判定"这个类是不是列表行的正文载体"。
     *
     * 两个条件同时成立才算：
     *  1. 继承链上出现文本控件（`TextView` 及其子类）——它确实在渲染文字；
     *  2. 类自己声明了一个 `CharSequence` 字段——宿主"可展开文本"的形态：
     *     把原文另存一份用于展开/收起，而不是只依赖控件内部状态。
     *
     * 只满足第一条就挂，会波及大量普通文本控件；加上第二条后命中面很窄。
     */
    private fun isTextCarrier(cls: Class<*>): Boolean {
        var hasCharSequenceField = false
        runCatching {
            cls.declaredFields.forEach { field ->
                if (field.type == CharSequence::class.java || field.type == String::class.java) {
                    hasCharSequenceField = true
                }
            }
        }
        if (!hasCharSequenceField) return false
        var current: Class<*>? = cls
        var depth = 0
        while (current != null && current != Any::class.java && depth < 12) {
            if (TextView::class.java.isAssignableFrom(current)) return true
            current = current.superclass
            depth++
        }
        return false
    }
}
