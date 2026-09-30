package com.oplusime.panel

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 引号「成对补全」抑制。
 *
 * ## 为什么放在这一层
 *
 * 取证结论（com.oplus.keyboard 1.7.38.17-os）：
 *
 * - Java 侧**没有**任何"输入左引号 → 补右引号"的判定。全包内含 `“`/`”` 的类只有三处，
 *   全部是"符号清单/候选表"（`input/utils/l` 的符号集合、`input/view/O` 的符号键候选、
 *   `base/data/s` 的符号数据）；字符码 `0x201c` 在 `com/oplus/keyboard` 全包内只出现在
 *   markdown 的 HTML 解析器里；`SmartPunctuation` 相关类属于**语音输入**加标点
 *   （`VoiceInputPanelHelper`），与符号面板无关。
 * - 因此引号配对由**输入引擎（native：libjni_ime / libIQQILib / libokim_shared 等）**完成，
 *   Java 层没有可以直接关闭它的开关或函数。
 *
 * ## 因此采取的策略：在上屏结果上做修正
 *
 * 无论配对是谁做的、也无论它是"一次提交两个字符"还是"提交左引号后再补右引号并移动光标"，
 * 最终编辑框里必然出现同一个可检测的状态：
 *
 * ```text
 * 光标前面是左引号  且  光标后面是配对的右引号        →  “ | ”
 * ```
 *
 * 检测到这个状态就删掉光标后面的那个右引号，于是剩下：
 *
 * ```text
 * 光标前面是左引号，光标在它后面                      →  “ |
 * ```
 *
 * 这正是用户要的"把引号当普通符号输入，光标留在引号后面"。
 *
 * ## 挂点
 *
 * 输入法进程内承载"输入法 → 编辑框"全部调用的，是 framework 的 InputConnection 代理实现
 * （`com.android.internal.view.IInputConnectionWrapper` 及其同族）。native 引擎与 Java 代码
 * 最终都经过它，所以在它的 `commitText` / `setSelection` 之后各检查一次，即可覆盖两种形态：
 *
 * - 若配对是"一次提交两个字符" → `commitText` 之后就能看到 `“ | ”`；
 * - 若配对是"提交左引号后再补右引号并 setSelection 到中间" → `setSelection` 之后才成立。
 *
 * 两个检查都是幂等的：删掉之后状态不再成立，不会重复删除。
 *
 * ## 误伤控制
 *
 * 只在**本次动作确实与引号有关**时才检查（提交文本含引号，或涉及的选区间隔极小），
 * 因此用户平时"手动把光标放进一对引号中间"的正常编辑不会被干扰。
 */
internal object QuotePairSuppressor {

    /** 左引号 → 与它配对的右引号。中英文各一对（英文左右同形）。 */
    private val PAIRS: Map<Char, Char> = mapOf(
        '\u201C' to '\u201D', // 中文双引号 “ ”
        '\u2018' to '\u2019', // 中文单引号 ‘ ’
        '"' to '"',           // 英文双引号
        '\'' to '\'',         // 英文单引号
    )

    /** 光标两侧都是引号时，两次删除之间的最小间隔，避免同一状态被连续处理。 */
    private const val MIN_INTERVAL_MS = 60L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastFixAt: Long = 0L

    @Volatile
    private var installed = false

    @Volatile
    private var fixCount: Int = 0

    /**
     * 在宿主进程内安装。输入法进程里承载编辑框调用的代理类可能不止一个名字，
     * 逐个尝试，命中即装；全部不可用时如实记日志（功能退化为原生行为，不会崩）。
     */
    fun install(hostClassLoader: ClassLoader) {
        if (installed) return
        val candidates = listOf(
            "com.android.internal.view.IInputConnectionWrapper",
            "com.android.internal.view.InputConnectionWrapper",
            "android.view.inputmethod.InputConnectionWrapper",
        )
        var hooked = 0
        candidates.forEach { name ->
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
            if (cls == null) {
                log("quote-pair: $name not present")
                return@forEach
            }
            hooked += hookAll(cls)
        }
        if (hooked == 0) {
            log("quote-pair: no InputConnection proxy hooked; quotes keep host behaviour")
        } else {
            installed = true
            log("quote-pair: installed, hook points=$hooked")
        }
    }

    private fun hookAll(cls: Class<*>): Int {
        var count = 0
        count += runCatching {
            XposedBridge.hookAllMethods(cls, "commitText", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    if (!mentionsQuote(text)) return
                    inspect(param.thisObject, "commitText")
                }
            }).size
        }.onFailure { log("quote-pair: commitText hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)

        count += runCatching {
            XposedBridge.hookAllMethods(cls, "setSelection", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // setSelection 是高频调用，只有"左右两侧都是引号"这种极窄状态才继续
                    check(param.thisObject as? InputConnection)
                }
            }).size
        }.onFailure { log("quote-pair: setSelection hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)
        return count
    }

    /** 本次提交的文本是否涉及引号（含"一次提交成对"与"提交单个左引号"两种形态）。 */
    private fun mentionsQuote(text: CharSequence): Boolean {
        if (text.isEmpty()) return false
        if (text.length > 4) return false
        for (i in 0 until text.length) {
            val c = text[i]
            if (PAIRS.containsKey(c) || PAIRS.containsValue(c)) return true
        }
        return false
    }

    private fun inspect(target: Any?, source: String) {
        val ic = target as? InputConnection ?: return
        // 延后一帧再读：若宿主在 commitText 之后才 setSelection 到引号中间，这里能等到结果。
        main.post {
            runCatching { check(ic, source) }
                .onFailure { log("quote-pair: inspect failed: ${it.message}") }
        }
    }

    /**
     * 检测"光标夹在一对引号中间"并拆掉右引号。
     *
     * 成功条件必须同时满足，缺一不可：
     *  1. 光标前恰好一个字符，且它是某个左引号；
     *  2. 光标后恰好一个字符，且它是该左引号的配对右引号；
     *  3. 距离上次修正超过 [MIN_INTERVAL_MS]（防止同一状态被重复处理）。
     *
     * 任一条不满足即不做任何事——因此不会触碰普通文本。
     */
    private fun check(ic: InputConnection?, source: String = "setSelection") {
        if (ic == null) return
        val before = runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull() ?: return
        if (before.length != 1) return
        val expected = PAIRS[before[0]] ?: return
        val after = runCatching { ic.getTextAfterCursor(1, 0) }.getOrNull() ?: return
        if (after.length != 1 || after[0] != expected) return
        if (System.currentTimeMillis() - lastFixAt < MIN_INTERVAL_MS) return

        val removed = runCatching { ic.deleteSurroundingText(0, 1) }.getOrDefault(false)
        if (removed) {
            lastFixAt = System.currentTimeMillis()
            fixCount++
            log(
                "quote-pair: removed auto-inserted closing quote '" + describe(expected) +
                    "' after '" + describe(before[0]) + "' (source=" + source +
                    ", total=" + fixCount + ")"
            )
        } else {
            log("quote-pair: editor rejected deleteSurroundingText (source=$source)")
        }
    }

    /** 只输出可读字符；引号本身用码位标注，避免日志里出现成对引号引起歧义。 */
    private fun describe(c: Char): String = String(charArrayOf(c)) + " (U+" + String.format("%04X", c.code) + ")"
}
