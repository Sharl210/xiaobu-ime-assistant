package com.oplusime.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/**
 * 输入法窗口内的提示层。
 *
 * 这里只保留切换功能的文字提示，不绘制英文候选图标，也不读取或维护任何图标颜色状态。
 */
internal object SwipeIconLayer {
    private const val TOAST_MS = 1500L

    private class Layer(context: Context) : View(context) {
        var text: String? = null
        var shownAt: Long = 0L
        private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 34f
        }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setWillNotDraw(false)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val parentView = parent as? View
            val width = parentView?.width ?: 0
            val height = parentView?.height ?: 0
            if (width > 0 && height > 0) setMeasuredDimension(width, height)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun layout(l: Int, t: Int, r: Int, b: Int) {
            val parentView = parent as? View
            val width = parentView?.width ?: 0
            val height = parentView?.height ?: 0
            if (width > 0 && height > 0) super.layout(0, 0, width, height)
            else super.layout(l, t, r, b)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

        override fun onDraw(canvas: Canvas) {
            val value = text ?: return
            val age = SystemClock.uptimeMillis() - shownAt
            if (age > TOAST_MS) {
                text = null
                return
            }
            val fade = if (age > TOAST_MS - 350L) {
                ((TOAST_MS - age).toFloat() / 350f).coerceIn(0f, 1f)
            } else 1f
            val padding = 30f
            val width = label.measureText(value) + padding * 2f
            val height = 82f
            val box = RectF(
                (this.width - width) / 2f,
                this.height * 0.05f,
                (this.width + width) / 2f,
                this.height * 0.05f + height,
            )
            bubble.color = Color.BLACK
            bubble.alpha = (170f * fade).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(box, height / 2f, height / 2f, bubble)
            label.color = Color.WHITE
            label.alpha = (255f * fade).toInt().coerceIn(0, 255)
            val metrics = label.fontMetrics
            val baseline = box.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(value, box.centerX(), baseline, label)
        }
    }

    private fun findContainer(view: View?): ViewGroup? {
        val root = view?.rootView as? ViewGroup ?: return null
        return (view.parent as? ViewGroup) ?: root
    }

    fun toast(view: View?, message: String): Boolean {
        val container = findContainer(view) ?: return false
        container.post {
            runCatching {
                val layer = (0 until container.childCount)
                    .map { container.getChildAt(it) }
                    .filterIsInstance<Layer>()
                    .firstOrNull()
                    ?: Layer(container.context).also {
                        container.addView(it, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ))
                    }
                layer.text = message
                layer.shownAt = SystemClock.uptimeMillis()
                layer.invalidate()
                layer.postDelayed({ layer.invalidate() }, TOAST_MS + 80L)
            }.onFailure { log("swipe-icon: toast failed ${it.message}") }
        }
        return true
    }
}
