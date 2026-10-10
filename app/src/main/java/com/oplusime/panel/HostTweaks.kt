package com.oplusime.panel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 宿主容量与计数相关的定点修正。
 *
 * 本轮已取证的两个上限（com.oplus.keyboard 1.7.38.17-os）：
 *
 * 1) **剪贴板计数显示**：格式化串 `clip_length` = `%1$d/%2$d`（资源名，跨版本按名解析整数 id）。
 *    宿主把当前条数与上限填进这个格式串。要把上限显示成 `∞`，只需要在**这一个资源 id**
 *    上拦截格式化结果，不触碰其它任何文案。
 *
 * 2) **剪切 / 粘贴的条件返回键盘**：
 *    - 剪切（`btn_clip`）执行完即回主键盘；
 *    - 粘贴（`btn_paste`）**只有在真的粘到内容时才回**：判据用框架事实——编辑连接存在
 *      且剪贴板里有非空文本。宿主自己在 `InputConnection == null` 时会提前 return，
 *      因此这两条合起来正好等价于“真的粘到才返回”。
 *
 * 关闭动作不自己造：复用宿主自己的关闭链（左上返回箭头走的那条），
 * 由 [HookEntry] 按“静态无参访问器 + 静态单参关闭方法”的形状解析后传入。
 */
internal class HostTweaks(
    /** `clip_length` 在当前 APK 的整数 id；0 表示未解析到，则不做显示改写。 */
    private val clipLengthId: Int,
    /** 宿主自己的“收起面板、回键盘”链；null 表示未解析到，则不做返回。 */
    private val closePanel: (() -> Boolean)?,
) {
    private companion object {
        /** 计数显示里的无限符号。 */
        const val INFINITY = "∞"
    }

    /** 计数显示格式化是否已观察到（用于日志只打一次）。 */
    @Volatile
    private var counterSeen = false

    /** 是否已经处理过一次“剪切/粘贴返回”。 */
    @Volatile
    private var closeReported = false

    fun install() {
        hookCounterFormatting()
    }

    // ------------------------------------------------------- 计数显示改 ∞

    /**
     * 只拦截 `clip_length` 这一个资源 id 的 `getString(int, Object[])` 结果：
     * 宿主拿它拼“当前条数/上限”，我们把上限那一段换成 `∞`。
     *
     * 其余任何资源、任何调用都原样放行，因此不会影响输入法其它文案。
     */
    private fun hookCounterFormatting() {
        if (clipLengthId == 0) {
            log("counter ∞ skipped: clip_length resource not found in this build")
            return
        }
        runCatching {
            XposedBridge.hookAllMethods(
                Resources::class.java,
                "getString",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val id = param.args.getOrNull(0) as? Int ?: return
                        if (id != clipLengthId) return
                        val args = param.args.getOrNull(1) as? Array<*> ?: return
                        val count = (args.getOrNull(0) as? Number)?.toInt() ?: return
                        param.result = "$count/$INFINITY"
                        if (!counterSeen) {
                            counterSeen = true
                            log("counter ∞ applied: clip_length override active (sample=$count/$INFINITY)")
                        }
                    }
                },
            )
            log("counter ∞ hooked: clip_length id=0x${Integer.toHexString(clipLengthId)}")
        }.onFailure { log("counter ∞ hook failed: ${it.message}") }
    }

    // ------------------------------------------------- 剪切/粘贴条件返回键盘

    /** 由入口在宿主面板 onClick 之后调用（宿主动作此时已完成）。 */
    fun onHostButtonClick(clickedId: Int, panelIds: PanelIds, panel: View?) {
        val closer = closePanel ?: return
        val isClip = clickedId == panelIds.clip
        val isPaste = clickedId == panelIds.paste
        if (!isClip && !isPaste) return
        val target = panel ?: return

        if (isPaste && !pasteTargetReady(target.context)) {
            if (!closeReported) {
                closeReported = true
                log("paste tapped but nothing to paste -> panel stays open")
            }
            return
        }

        target.post {
            runCatching {
                val ok = closer()
                log(
                    if (ok) {
                        (if (isClip) "cut tapped -> back to keyboard" else "paste applied -> back to keyboard")
                    } else {
                        "close skipped (host already closed or type mismatch)"
                    }
                )
            }.onFailure { log("close path invoke failed: ${it.message}") }
        }
    }

    /**
     * “真的能粘到东西”的框架判据：剪贴板里有非空文本。
     * 编辑连接是否存在的判断由宿主自己做（它在 IC 为 null 时提前 return）。
     */
    private fun pasteTargetReady(context: Context): Boolean {
        return runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching false
            val clip: ClipData = manager.primaryClip ?: return@runCatching false
            if (clip.itemCount <= 0) return@runCatching false
            val text = clip.getItemAt(0).coerceToText(context)
            !text.isNullOrEmpty()
        }.getOrDefault(false)
    }
}
