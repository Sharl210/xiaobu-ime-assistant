package com.oplusime.panel

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 模块主页面：把本模块做过的每一件事按「面板 / 剪贴板 / 常用语 / 符号」分组列出来。
 *
 * 纯代码搭界面，不引入任何依赖：这样模块包体不变大，也不必随主题适配。
 * 内容全部来自 `strings.xml`，与 README 保持同一套说法。
 */
class MainActivity : Activity() {

    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.module_title)
        // 放开开关文件的读权限，宿主进程（输入法）才读得到日志开关。
        // 放在建界面之前，保证"打开本页"这一动作本身就已经让开关对输入法可用。
        runCatching { SwitchStore.makeReadable(this) }
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(BG)
        }

        root.addView(header())
        root.addView(caption())
        root.addView(switchCard())
        root.addView(rememberedCard())

        addGroup(
            root, "文本编辑面板",
            listOf(
                "排版参考百度输入法：六个操作按钮分两列排列，保留宿主原有点击行为",
                "左上操作格随选择状态在「全选」与「剪切」之间切换",
                "剪切完成回到打字键盘；只有实际粘贴成功才自动返回",
                "系统返回手势从文本编辑面板返回打字主键盘，不主动收起输入法",
            ),
        )
        addGroup(
            root, "剪贴板面板",
            listOf(
                "条目数量上限解除，计数显示 ∞；不再达到上限时挤掉最旧记录",
                "超长条目采用受控预览渲染，复制和剪切仍保留完整原文",
                "剪贴板页可搜索并过滤条目；常用语页不显示此搜索入口",
                "剪贴板条目可通过宿主原生编辑界面修改并写回",
            ),
        )
        addGroup(
            root, "常用语",
            listOf(
                "单条正文 500 字输入上限与条目数量上限解除",
                "输入超长内容时宿主计数和截断限制一并处理",
            ),
        )
        addGroup(
            root, "键盘输入与符号",
            listOf(
                "中英文 26 键上滑字符采用各自键位表；中文逗号上滑输入感叹号",
                "英文书册键切换宿主的英文候选设置，并按设置结果显示状态",
                "候选拼音行支持点选编辑位置、占位光标及中间插入/退格",
                "抑制引号、括号等字符的自动成对补全",
                "中文、英文、数字符号键跳转到宿主完整符号页；实际兼容状态可查诊断页",
                "主键盘回车键显示回车箭头，按键行为仍走宿主逻辑",
            ),
        )

        addGroup(
            root, "诊断与设备适配",
            listOf(
                "「Hook诊断」页面展示已匹配、匹配失败或尚未回传的点位；点开可查看匹配详情",
                "面向小布输入法宿主包；设备需实际安装兼容版本并由 LSPosed 成功注入",
                "宿主升级或匹配失败时，可在诊断页查看具体点位；状态不等同于功能验收",
                "日志由本页开关控制；Debug 测试包默认开启，正式 Release 默认关闭",
            ),
        )

        addGroup(
            root, "使用方式",
            listOf(
                "1. 在 LSPosed 中启用本模块",
                "2. 作用域只勾选「小布输入法」",
                "3. 重启输入法进程（或重启手机）",
            ),
        )
        root.addView(note(
            "卸载或停用模块即完全恢复原状：本模块不改动输入法的任何文件，只在运行时介入。" +
                "模块不写死任何类名，全部靠运行时的结构特征定位，因此输入法小版本升级后通常无需改动。",
        ))
        return ScrollView(this).apply { addView(root) }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val titleView = TextView(this@MainActivity).apply {
            text = getString(R.string.module_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(FG)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(titleView)
        addView(TextView(this@MainActivity).apply {
            text = "Hook诊断"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ACCENT)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, HookDiagnosticsActivity::class.java))
            }
        })
    }

    private fun caption(): View = TextView(this).apply {
        text = "作用域：小布输入法（com.oplus.keyboard）"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(FG_MUTED)
        setPadding(0, dp(6), 0, dp(4))
    }

    /**
     * 日志开关卡片。
     *
     * 说明为什么放在界面上而不是写死在代码里：这个模块的日志是排查宿主问题的唯一手段，
     * 但日志本身有代价（跨进程写文件、在 LSPosed 日志里占行）。调试期要开着，
     * 正式使用时要能一键关掉。做成界面开关后，用户排障时打开、日常关掉，都不必重新装包。
     */
    private fun switchCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 14f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "日志开关"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(FG)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "开启后本模块会在 LSPosed 日志里记录诊断信息，用于排查问题；" +
                "关闭后完全不产生日志，减少性能开销，也不会干扰其它模块的日志分析。" +
                "改动立即生效（输入法进程最多 5 秒后跟随）。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(FG_MUTED)
            setPadding(0, dp(6), 0, dp(12))
        })

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stateText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(FG)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pill = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
        }

        fun render() {
            val on = SwitchStore.logEnabled(this)
            stateText.text = if (on) "当前：已开启" else "当前：已关闭"
            pill.text = if (on) "关闭日志" else "开启日志"
            pill.setTextColor(if (on) FG else Color.WHITE)
            pill.background = rounded(if (on) TOGGLE_OFF else ACCENT, 18f)
        }

        pill.setOnClickListener {
            val next = !SwitchStore.logEnabled(this)
            SwitchStore.setLogEnabled(this, next)
            render()
        }
        render()

        toggleRow.addView(stateText)
        toggleRow.addView(pill)
        card.addView(toggleRow)
        return card
    }

    /** 提示卡片：告诉用户"没手动设置过时用的是哪个默认值"。 */
    private fun rememberedCard(): View = TextView(this).apply {
        val customized = SwitchStore.logEnabledCustomized(this@MainActivity)
        text = if (customized) {
            "当前取值由你在本页设置决定，会一直保持，直到你再次修改。"
        } else {
            "你还没有手动设置过：当前使用的是本版本的默认值（" +
                if (ModuleSwitches.DEFAULT_LOG_ENABLED) "默认开启）" else "默认关闭）"
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(FG_MUTED)
        setPadding(dp(4), dp(10), dp(4), 0)
    }

    private fun addGroup(root: LinearLayout, title: String, items: List<String>) {
        root.addView(sectionTitle(title))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, 14f)
        }
        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (index > 0) setPadding(0, dp(10), 0, 0)
            }
            row.addView(dot())
            row.addView(TextView(this).apply {
                text = item
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(FG)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(8) }
            })
            card.addView(row)
        }
        root.addView(card)
    }

    private fun sectionTitle(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(ACCENT)
        setPadding(dp(4), dp(18), 0, dp(8))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dot(): View = View(this).apply {
        background = rounded(ACCENT, 4f)
        layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).also { it.topMargin = dp(6) }
    }

    private fun note(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(FG_MUTED)
        setPadding(dp(4), dp(20), dp(4), 0)
        gravity = Gravity.START
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * density
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        val BG: Int = Color.parseColor("#F5F5F7")
        val CARD: Int = Color.WHITE
        val FG: Int = Color.parseColor("#E5000000")
        val FG_MUTED: Int = Color.parseColor("#99000000")
        val ACCENT: Int = Color.parseColor("#0A59F7")
        val TOGGLE_OFF: Int = Color.parseColor("#E8E8ED")
    }
}
