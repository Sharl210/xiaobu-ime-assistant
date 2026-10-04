package com.oplusime.panel

import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Hook 匹配诊断"
        setContentView(render())
    }

    override fun onResume() {
        super.onResume()
        setContentView(render())
        // 安装期匹配在宿主进程后台线程执行；进入诊断页时可能还没写完。
        // 延迟复读一次，避免页面把“尚未回传”永久显示成静态结果。
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) setContentView(render())
        }, 1200L)
    }

    private fun render(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#F5F5F7"))
        }
        root.addView(TextView(this).apply {
            text = "Hook 匹配诊断"
            textSize = 22f
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "这里显示模块启动时 DexKit 的结构匹配结果。绿色表示安装期已匹配，红色表示安装期匹配失败，灰色表示没有收到安装期回传。"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(14))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        HookDiagnostics.snapshot(this).forEach { point ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.WHITE)
                isClickable = true
            }
            val status = when {
                point.updatedAt == 0L -> "⏳ 未收到安装期结果"
                point.matched -> "✅ 已匹配"
                else -> "❌ 安装期匹配失败"
            }
            val statusColor = when {
                point.updatedAt == 0L -> Color.DKGRAY
                point.matched -> Color.rgb(0, 128, 0)
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
        return "记录时间：$time\n匹配结果：${if (point.matched) "成功" else "失败/未回传"}\n${point.detail}"
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
