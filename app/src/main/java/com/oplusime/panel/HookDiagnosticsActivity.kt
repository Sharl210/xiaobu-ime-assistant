package com.oplusime.panel

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HookDiagnosticsActivity : Activity() {
    private val density get() = resources.displayMetrics.density
    private val muted = Color.parseColor("#88000000")
    private var renderedPoints: List<HookDiagnostics.Point> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Hook 匹配诊断"
        setContentView(render())
    }

    override fun onResume() {
        super.onResume()
        setContentView(render())
    }

    private fun render(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#F5F5F7"))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Hook 匹配诊断"
            textSize = 22f
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "复制"
            textSize = 14f
            setTextColor(Color.rgb(10, 89, 247))
            setPadding(dp(12), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val text = exportSnapshot()
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Hook 诊断", text))
                android.widget.Toast.makeText(this@HookDiagnosticsActivity, "诊断状态已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "这里显示本轮宿主启动时的结构匹配结果。绿色表示已匹配，红色表示明确未匹配，灰色表示不适用或尚未产生结构结果；实际运行回调只作为详情补充。"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(14))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val points = HookDiagnostics.snapshot(this)
        renderedPoints = points
        points.forEach { point ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.WHITE)
                isClickable = true
            }
            val pending = point.updatedAt == 0L ||
                point.detail.startsWith("未产生安装期记录") ||
                point.detail.startsWith("安装轮次未执行") ||
                point.detail.startsWith("安装轮次已完成但未提交") ||
                point.detail.startsWith("未提供") ||
                point.detail.startsWith("不适用") ||
                point.detail.startsWith("待运行时验证")
            val status = when {
                point.matched -> "✅ 已匹配"
                point.detail.startsWith("不适用") -> "⚪ 不适用"
                pending -> "⚪ 未产生结构结果"
                else -> "❌ 未匹配"
            }
            val statusColor = when {
                point.matched -> Color.rgb(0, 128, 0)
                pending -> Color.rgb(110, 110, 110)
                else -> Color.rgb(190, 0, 0)
            }
            val title = TextView(this).apply {
                text = "$status  ${point.name}"
                textSize = 16f
                setTextColor(statusColor)
            }
            val detail = TextView(this).apply {
                text = buildDetail(point)
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(dp(26), dp(8), 0, 0)
                visibility = View.GONE
            }
            row.setOnClickListener {
                detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            row.addView(title)
            row.addView(detail)
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(8)
            })
        }
        root.addView(list)
        return ScrollView(this).apply { addView(root) }
    }

    private fun buildDetail(point: HookDiagnostics.Point): String {
        val time = if (point.updatedAt == 0L) "无" else
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(point.updatedAt))
        val status = when {
            point.matched -> "成功"
            point.detail.startsWith("不适用") -> "不适用"
            point.updatedAt == 0L ||
                point.detail.startsWith("未产生安装期记录") ||
                point.detail.startsWith("安装轮次未执行") ||
                point.detail.startsWith("安装轮次已完成但未提交") ||
                point.detail.startsWith("待运行时验证") -> "未产生结构结果"
            else -> "未匹配"
        }
        return "说明：${point.description}\n记录时间：$time\n匹配结果：$status\n详情：${point.detail}"
    }

    private fun exportSnapshot(): String = HookDiagnostics.export(renderedPoints)

    private fun dp(value: Int): Int = (value * density).toInt()
}
