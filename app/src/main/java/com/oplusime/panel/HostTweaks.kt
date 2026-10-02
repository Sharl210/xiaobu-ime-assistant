package com.oplusime.panel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

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
    /**
     * `enter_btn_title_enter`（宿主主键盘回车键上的「换行」二字）在当前 APK 的字符串 id；
     * 0 表示未解析到，则不改写。用户要求把「换行」换成回车**箭头符号**。
     */
    private val enterTitleId: Int = 0,
    /** 宿主自己的“收起面板、回键盘”链；null 表示未解析到，则不做返回。 */
    private val closePanel: (() -> Boolean)?,
) {
    private companion object {
        /** 计数显示里的无限符号。 */
        const val INFINITY = "∞"

        /**
         * 回车键上要显示的符号。
         *
         * 用户要的是「带箭头的回车」。这里用**方向回车箭头** `⏎`（U+23CE），
         * 它是单码位字形、在任何字体下都会按文本渲染，且不会像 emoji 那样被着色彩渲染
         * （回车键上要的是线条符号，不是彩色 emoji）。
         */
    private val ENTER_GLYPH = "\u23CE"
    }

    /** 是否已经拦到过剪贴板计数格式化（用于日志只打一次）。 */
    @Volatile
    private var counterSeen = false

    /** 是否已经处理过一次“剪切/粘贴返回”。 */
    @Volatile
    private var closeReported = false

    fun install() {
        hookCounterFormatting()
        hookEnterTitle()
        // 宿主自己的字符串助手（按结构定位）。没有 bridge 时跳过，不静默假装成功。
        val bridge = bridgeRef
        val loader = hostClassLoaderRef
        if (bridge != null && loader != null) {
            hookHostStringResolver(bridge, loader)
        } else {
            log("enter glyph: host resolver skipped (bridge/classloader unavailable)")
        }
    }

    /** 安装时由入口注入：做宿主字符串助手定位用（缺省不做，不影响其它改写）。 */
    @Volatile
    private var bridgeRef: DexKitBridge? = null

    @Volatile
    private var hostClassLoaderRef: ClassLoader? = null

    fun bindHost(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        bridgeRef = bridge
        hostClassLoaderRef = hostClassLoader
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

    // ------------------------------------------------- 回车键「换行」→ 回车符号

    /** 是否已经拦到过回车键文案（日志只打一次）。 */
    @Volatile
    private var enterSeen = false

    /**
     * 把主键盘回车键上的「换行」二字替换成回车**箭头符号**。
     *
     * 与上面的计数改写同一套做法：只认**这一个字符串资源 id**，
     * 命中就把结果换掉，其它任何资源、任何调用原样放行 —— 因此不会影响输入法其它文案
     * （宿主自己还有好几处「换行」提示语，例如设置页说明，那些不该动）。
     */
    private fun hookEnterTitle() {
        if (enterTitleId == 0) {
            log("enter glyph skipped: enter_btn_title_enter resource not found in this build")
            return
        }
        runCatching {
            // **三条入口都要挂**，而且必须一致替换。
            //
            // 1) `Resources.getString/getText`：`TextView.setText(resId)` 与 `context.getString` 的底。
            // 2) 宿主自己的字符串解析器：宿主大量用 `ext/g;->g(Object, int, Object[]) -> String`
            //    这种"带上下文 + 资源 id + 变参"的静态助手来取串（`body/V;->d(String)` 里
            //    对 `0x7f13024f` 的比较就是这么来的）。只挂框架那条，键面走宿主助手时就不生效。
            //
            // 关键一致性：这两条都会被替换成同一个字形，而宿主 `body/V;->d(String)`
            // 判断"当前键是不是回车键"也是按同一个资源 id 取串比较的 —— 两边同时变，
            // 判定依然成立，因此**不会**把回车键的行为改坏。
            listOf("getString", "getText").forEach { name ->
                XposedBridge.hookAllMethods(
                    Resources::class.java,
                    name,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val id = param.args.getOrNull(0) as? Int ?: return
                            if (id != enterTitleId) return
                            // 只拦"单个 id"的调用；带 formatArgs 的结果可能是格式化串，不碰。
                            if (param.args.size > 1) return
                            param.result = ENTER_GLYPH
                            if (!enterSeen) {
                                enterSeen = true
                                log("enter glyph applied (#$name): enter_btn_title_enter -> '$ENTER_GLYPH'")
                            }
                        }
                    },
                )
            }
            log("enter glyph hooked: enter_btn_title_enter id=0x${Integer.toHexString(enterTitleId)}")
        }.onFailure { log("enter glyph hook failed: ${it.message}") }
    }

    /**
     * 宿主自己的字符串解析助手：**按结构定位，不写死类名**。
     *
     * 形状是 `static (Object 上下文, int 资源 id, Object[] 变参) -> String`。
     * 宿主用它取各种界面串；回车键的显示文字很可能就是从这里出来的。
     */
    private fun hookHostStringResolver(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates: List<MethodData> = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes("java.lang.Object", "int", "java.lang.Object[]")
                    returnType("java.lang.String")
                }
            }.toList()
        }.onFailure { log("enter glyph: resolver query failed: ${it.message}") }
            .getOrDefault(emptyList())

        var hooks = 0
        candidates.forEach { data ->
            val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val id = param.args.getOrNull(1) as? Int ?: return
                        if (id != enterTitleId) return
                        param.result = ENTER_GLYPH
                        if (!enterSeen) {
                            enterSeen = true
                            log(
                                "enter glyph applied (#host-resolver " +
                                    "${method.declaringClass.name}): -> '$ENTER_GLYPH'"
                            )
                        }
                    }
                })
                hooks++
            }.onFailure { log("enter glyph: resolver hook failed: ${it.message}") }
        }
        log("enter glyph: host resolver candidates=${candidates.size} hooks=$hooks")
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
