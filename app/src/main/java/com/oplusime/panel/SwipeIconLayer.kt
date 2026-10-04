package com.oplusime.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import java.lang.ref.WeakReference

/**
 * 输入法窗口内的「自有覆盖层」：负责英文候选图标的即时上色和模式切换提示。
 *
 * ## 为什么非要自己画一层
 *
 * 真机日志里，上滑切换之后宿主收到 `invalidate()` 之后并没有重画键面
 * （它的键面内容被它自己缓存/复用），于是图标颜色要等到"切一次面板、让宿主整体重建键盘"
 * 才更新。同一个原因也解释了系统 Toast 在输入法窗口层级里显示不出来。
 *
 * 因此这里在**键盘视图旁边**挂一层我们自己的 View，图标和提示都由它绘制，
 * 由我们自己 `invalidate()` —— 想什么时候变就什么时候变。
 *
 * ## 这一层挂在哪儿（两轮回归的教训，写在最前面）
 *
 * 1.33.29：挂在**窗口根**（DecorView）上。结果它永远盖在所有东西上面，切页也不消失 ——
 * 用户形容"像披在输入法上"。
 *
 * 1.33.31：改成"只在英文页画 + 心跳过期"。心跳带来闪烁（英文页不一定每 350 毫秒都重绘键面），
 * 而"只在英文页画"也救不了**语音面板**这种"不再绘制键盘、但也没人通知我们"的情形 ——
 * 用户实测"开启语音模式，图标还浮在屏幕上"。
 *
 * 现在（1.33.35）改成两条同时成立才显示：
 *
 * - 这一层挂在**键盘视图的父容器**里（不是窗口根）→ 与键盘同级，语音面板之类的兄弟视图
 *   天然盖在它上面，不会再"浮在一切之上"；
 * - 一个 250 毫秒的巡检：只要**给坐标的那枚键盘视图**已经不在窗口里、不可见、或者尺寸为 0，
 *   立刻把图标清掉。正常显示时这三个条件恒真，所以不会闪；切到语音/中文/符号页时其中一条立刻为假，
 *   所以不会残留。
 *
 * 触摸一律放行（`onTouchEvent` 恒返回 false，且不可点击/不可获得焦点）。
 */
internal object SwipeIconLayer {

    /**
     * 输入法整体换了 input view（语音面板、手写全屏等）时，把我们画的图标收掉。
     *
     * 语音面板是宿主的另一支 input view，键盘视图那一支未必会 detach 也未必会变不可见，
     * 所以除了可见性巡检与容器子视图监听，这里再挂一层"换 view 就清"的兜底。
     * 清掉之后若还在英文面板，宿主下一帧画键面时会重新把图标喂回来（不会缺）。
     */
    fun install(loader: ClassLoader) {
        runCatching {
            val service = Class.forName("android.inputmethodservice.InputMethodService", false, loader)
            val hook = object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                    clear("ime-view-changed:${param.method?.name}")
                }
            }
            de.robv.android.xposed.XposedBridge.hookAllMethods(service, "setInputView", hook)
            de.robv.android.xposed.XposedBridge.hookAllMethods(service, "onStartInputView", hook)
            log("swipe-icon: ime view-change hooks installed")
        }.onFailure { log("swipe-icon: ime view-change hook failed: ${it.message}") }
    }

    /** 开启态（宿主设置里英文候选 = true）的图标颜色。 */
    const val ON_COLOR: Int = 0xFF0A59F7.toInt()

    /** 关闭态（常规黑）。 */
    const val OFF_COLOR: Int = 0xFF000000.toInt()

    private const val TOAST_MS = 1500L

    /** 巡检间隔：只做几次可见性判断，开销可以忽略。 */
    private const val WATCH_MS = 250L

    /** 一次绘制所需的图标描述；坐标一律是**窗口坐标**。 */
    class Spec(
        val bitmap: Bitmap,
        val src: Rect,
        /** 图标在窗口里的目标矩形。 */
        val dst: Rect,
        /** 键盘视图在窗口里的矩形（提示条的位置参照）。 */
        val keyboard: Rect,
    )

    private var layerRef: WeakReference<Layer>? = null

    /** 最近一次提供坐标的键盘视图：巡检按它判断"键面还在不在"。 */
    private var hostRef: WeakReference<View>? = null

    /** 最近一次已知的键盘矩形；提示条要它来定位（此时可能已经没有图标了）。 */
    @Volatile
    private var lastKeyboardRect: Rect? = null

    private class Layer(context: Context) : View(context) {

        @Volatile
        var spec: Spec? = null

        @Volatile
        var iconEnabled: Boolean = false

        @Volatile
        var toastText: String? = null

        @Volatile
        var toastAt: Long = 0L

        @Volatile
        var watchRunning: Boolean = false

        /** 已经挂过"子视图变化"监听的容器，避免重复注册（同一容器只挂一次）。 */
        @Volatile
        var hierarchyWatched: ViewGroup? = null

        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val ownLocation = IntArray(2)
        private val scratch = Rect()

        init {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setWillNotDraw(false)
        }

        /** 只负责画，不碰触摸：返回 false，事件继续交给下面的键盘视图。 */
        override fun onTouchEvent(event: MotionEvent): Boolean = false

        /**
         * 尺寸永远跟容器一致。
         *
         * 覆盖层是"平铺在键盘上的一层"，所以不能让它参与容器的尺寸计算：
         * 这里直接按父容器当前尺寸上报，避免影响宿主自己的测量结果。
         */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val parentView = parent as? View
            val width = parentView?.width ?: 0
            val height = parentView?.height ?: 0
            if (width > 0 && height > 0) {
                setMeasuredDimension(width, height)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        /** 位置也钉死在容器左上角覆盖整块，容器怎么排都不会把我们挪走。 */
        override fun layout(l: Int, t: Int, r: Int, b: Int) {
            val parentView = parent as? View
            val width = parentView?.width ?: 0
            val height = parentView?.height ?: 0
            if (width > 0 && height > 0) super.layout(0, 0, width, height) else super.layout(l, t, r, b)
        }

        override fun onDraw(canvas: Canvas) {
            getLocationInWindow(ownLocation)
            val originX = ownLocation[0]
            val originY = ownLocation[1]
            spec?.let { s ->
                iconPaint.colorFilter = PorterDuffColorFilter(
                    if (iconEnabled) ON_COLOR else OFF_COLOR,
                    PorterDuff.Mode.SRC_IN,
                )
                // `Spec` 里的坐标是窗口坐标，画的时候换算成本层坐标。
                scratch.set(
                    s.dst.left - originX,
                    s.dst.top - originY,
                    s.dst.right - originX,
                    s.dst.bottom - originY,
                )
                canvas.drawBitmap(s.bitmap, s.src, scratch, iconPaint)
            }
            drawToast(canvas, originX, originY)
        }

        private fun drawToast(canvas: Canvas, originX: Int, originY: Int) {
            val text = toastText ?: return
            val age = SystemClock.uptimeMillis() - toastAt
            if (age > TOAST_MS) {
                toastText = null
                return
            }
            val fade = if (age > TOAST_MS - 350) {
                ((TOAST_MS - age).toFloat() / 350f).coerceIn(0f, 1f)
            } else 1f
            textPaint.textSize = 34f
            val label = text
            val pad = 30f
            val w = textPaint.measureText(label) + pad * 2
            val h = 82f
            val anchor = spec?.keyboard ?: lastKeyboardRect ?: Rect(0, 0, width, height)
            val cx = anchor.exactCenterX() - originX
            val top = anchor.top + anchor.height() * 0.05f - originY
            val box = RectF(cx - w / 2f, top, cx + w / 2f, top + h)
            bubblePaint.color = Color.BLACK
            bubblePaint.alpha = (170 * fade).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(box, h / 2f, h / 2f, bubblePaint)
            textPaint.color = Color.WHITE
            textPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val fm = textPaint.fontMetrics
            val baseline = box.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, box.centerX(), baseline, textPaint)
        }
    }

    /**
     * 宿主每画一次键面就更新一次图标位置；`enabled` 由调用方按真机设置读回。
     *
     * `view` = 正在绘制的那枚键盘视图，既当"键面还在不在"的判据，也用来定位覆盖层的挂载点。
     */
    fun update(view: View, spec: Spec, enabled: Boolean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            view.post { update(view, spec, enabled) }
            return
        }
        lastKeyboardRect = Rect(spec.keyboard)
        hostRef = WeakReference(view)
        val container = (view.parent as? ViewGroup) ?: (view.rootView as? ViewGroup) ?: return
        val layer = ensureLayer(container) ?: return
        layer.spec = spec
        layer.iconEnabled = enabled
        layer.invalidate()
        watchHierarchy(layer, container)
        startWatch(layer)
    }

    /**
     * 键盘视图已经不在窗口里（切页、收起、宿主换视图）时立刻清掉图标。
     *
     * 由键盘视图的 `onDetachedFromWindow` 与"非英文页"分支调用；
     * 巡检也会在 250 毫秒内做同样的事（覆盖没有 detach 回调的语音面板等情形）。
     */
    fun clear(reason: String) {
        val layer = layerRef?.get() ?: return
        layer.post {
            if (layer.spec != null) {
                layer.spec = null
                layer.invalidate()
                log("swipe-icon: overlay cleared reason=$reason")
            }
        }
    }

    /** 开关切换后立刻按新状态重画（不经过宿主，立刻可见）。 */
    fun setEnabled(enabled: Boolean) {
        val layer = layerRef?.get() ?: run {
            log("swipe-icon: overlay not attached yet; immediate repaint skipped")
            return
        }
        layer.post {
            layer.iconEnabled = enabled
            layer.invalidate()
            log("swipe-icon: overlay repaint enabled=$enabled attached=${layer.parent != null}")
        }
    }

    /** 输入法窗口内的提示条（替代宿主 Context 上显示不出来的系统 Toast）。 */
    fun toast(view: View?, message: String): Boolean {
        val root = runCatching { view?.rootView }.getOrNull() ?: return false
        val container = runCatching {
            ((view?.parent as? ViewGroup) ?: (root as? ViewGroup))
        }.getOrNull() ?: return false
        container.post {
            runCatching {
                val layer = ensureLayer(container) ?: return@runCatching
                layer.toastText = message
                layer.toastAt = SystemClock.uptimeMillis()
                layer.invalidate()
                layer.postDelayed({ layer.invalidate() }, TOAST_MS + 80)
                log("swipe-icon: overlay toast message=$message")
            }.onFailure { log("swipe-icon: toast failed ${it.message}") }
        }
        return true
    }

    /**
     * 巡检：宿主还可能画着图标吗？
     *
     * 判据只有"那枚键盘视图此刻真的在屏幕上"，与时间无关 ——
     * 所以英文面板长时间不重绘也不会把图标闪掉，而语音面板一旦顶掉键盘
     * （键盘被隐藏/移除）就会在 250 毫秒内清干净。
     */
    private fun startWatch(layer: Layer) {
        if (layer.watchRunning) return
        layer.watchRunning = true
        val tick = object : Runnable {
            override fun run() {
                val host = hostRef?.get()
                val hostVisible = host != null && host.isAttachedToWindow && host.isShown &&
                    host.width > 0 && host.height > 0
                val alive = layer.isAttachedToWindow && layer.spec != null && hostVisible
                if (!alive) {
                    if (layer.spec != null) {
                        layer.spec = null
                        layer.invalidate()
                        log(
                            "swipe-icon: icon cleared (keyboard no longer visible)" +
                                " layerAttached=${layer.isAttachedToWindow}" +
                                " host=${host?.javaClass?.name}" +
                                " hostAttached=${host?.isAttachedToWindow}" +
                                " hostShown=${host?.isShown}"
                        )
                    }
                    layer.watchRunning = false
                    return
                }
                layer.postDelayed(this, WATCH_MS)
            }
        }
        layer.postDelayed(tick, WATCH_MS)
    }

    /**
     * 键盘视图这一支被整体换掉时立刻收干净（不依赖"键盘被销毁/不可见"）。
     *
     * ## 为什么必须再加这一条（1.33.34 真机反馈）
     *
     * 用户开了**语音模式**，那枚字典图标照样浮在屏幕上不消失。语音面板是宿主在同一层
     * 容器里换上的另一支视图：旧的键盘视图既没有被销毁，也没有变 `isShown=false`
     * （它可能只是被盖住或被移出当前分支），于是"可见性巡检"看不到任何异常，
     * 图标就留下来了。
     *
     * 这里的判据换成**结构关系**：图标那一层挂在哪一层容器里，就盯住那一层的子视图变化；
     * 一旦当初画出图标的那枚键盘视图不再是这层容器的后代，说明宿主已经换页/换面板，
     * 立刻收掉。这样语音、手写、符号、九键都覆盖得到，而且**与时间无关**，
     * 英文面板挂在那里不动也不会被误清（不会闪）。
     */
    private fun watchHierarchy(layer: Layer, container: ViewGroup) {
        if (layer.hierarchyWatched === container) return
        layer.hierarchyWatched = container
        runCatching {
            container.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) = revalidate(layer, container)

                override fun onChildViewRemoved(parent: View?, child: View?) = revalidate(layer, container)
            })
        }.onFailure { log("swipe-icon: hierarchy watch failed: ${it.message}") }
    }

    /** 当初提供坐标的键盘视图是否还在这层容器里；不在就立刻收掉图标。 */
    private fun revalidate(layer: Layer, container: ViewGroup) {
        if (layer.spec == null) return
        val host = hostRef?.get() ?: return
        if (isDescendant(host, container)) return
        layer.post {
            if (layer.spec != null) {
                layer.spec = null
                layer.invalidate()
                log("swipe-icon: icon cleared (host view left container) host=${host.javaClass.name}")
            }
        }
    }

    private fun isDescendant(view: View, ancestor: ViewGroup): Boolean {
        var node: ViewParent? = view.parent
        var depth = 0
        while (node != null && depth < 32) {
            if (node === ancestor) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun ensureLayer(container: ViewGroup): Layer? {
        layerRef?.get()?.let { existing ->
            if (existing.parent === container) return existing
            runCatching { (existing.parent as? ViewGroup)?.removeView(existing) }
        }
        val layer = Layer(container.context)
        return runCatching {
            val params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            runCatching { container.addView(layer, params) }
                .onFailure { container.addView(layer) }
            layerRef = WeakReference(layer)
            log("swipe-icon: overlay attached to ${container.javaClass.name}")
            layer
        }.onFailure { log("swipe-icon: overlay attach failed: ${it.message}") }.getOrNull()
    }
}
