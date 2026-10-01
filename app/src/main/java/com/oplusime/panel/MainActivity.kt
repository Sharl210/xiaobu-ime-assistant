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

        addGroup(
            root, "文本编辑面板",
            listOf(
                "按钮重排为：左列 全选／复制／粘贴，右列 删除／回车／剪贴板",
                "左列第一格双态：没选中是「全选」，选中后变「剪切」并真的剪切",
                "六个按钮加宽 1.2 倍，左侧白色面板自动让位",
                "「剪切」点完回键盘；「粘贴」只有真粘到内容才回",
            ),
        )
        addGroup(
            root, "剪贴板面板",
            listOf(
                "计数行右侧新增「搜索」按钮，点击弹出搜索框，输入后点「搜索」开始过滤",
                "搜索生效时按钮变浅蓝气泡＋蓝字，再点一次取消搜索",
                "条目数量上限解除：计数显示 ∞，存满时不再丢掉最旧的条目",
            ),
        )
        addGroup(
            root, "常用语",
            listOf(
                "单条正文 500 字上限解除，字数显示 ∞，超长也能保存",
                "条目数量上限解除",
            ),
        )
        addGroup(
            root, "符号",
            listOf("中文前引号与英文引号不再自动配成一对，只上屏你按的那一个"),
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

    private fun header(): View = TextView(this).apply {
        text = getString(R.string.module_title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(FG)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun caption(): View = TextView(this).apply {
        text = "作用域：小布输入法（com.oplus.keyboard）"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(FG_MUTED)
        setPadding(0, dp(6), 0, dp(4))
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
    }
}
