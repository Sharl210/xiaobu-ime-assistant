package com.oplusime.panel

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 面板六个按钮的资源 id（全部由当前 APK 按资源名称解析而来）。
 */
internal class PanelIds(
    val selectAll: Int,
    val clip: Int,
    val copy: Int,
    val paste: Int,
    val deleteLayout: Int,
    val returnLayout: Int,
)

/**
 * 把「文本编辑」面板重排为目标版式：
 *
 *   左列第一格 全选／剪切   右列第一格 删除
 *   左列第二格 复制         右列第二格 回车
 *   左列第三格 粘贴         右列第三格 剪贴板
 *
 * 排版做法是「格子置换」：六个格子各自带一套完整的排版属性（锚点、边距、宽高），目标版式
 * 与现版式的差别只是“每个格子交给谁”，因此搬运整份排版属性即可，模块自己**不解释也不重算**
 * 任何锚点语义。
 *
 * 但置换必须尊重宿主原有的**锚定依赖链**，这是 1.2.0 版整片错乱的根因，务必保留以下约束：
 *
 * ```text
 * 宿主原始依赖                                 目标版式里的对应关系
 * 左列的宽度/位置 <- [剪切] 的排版属性          -> 不变：[剪切] 永远留在左列第一格
 * 左列第二格 <- [复制] end/start 锚定 [剪切]    -> 必须保持锚定 [剪切]（不能改锚 [全选]）
 * 左列第三格 <- [粘贴] end/start 锚定 [复制]    -> 保持不变
 * 右列第二格 <- [删除] 锚定 [全选]              -> 改锚 [删除]（新的右列第一格占位者）
 * 右列第三格 <- [回车] 锚定 [全选]              -> 改锚 [删除]
 * 左面板右边缘 <- 锚定 [剪切] 的 start          -> 不变（[剪切] 尺寸与位置恒定）
 * ```
 *
 * 因此本版只在**右列**做整列下移（全选格→删除、删除格→回车、回车格→剪贴板），
 * 并把「全选」搬进左列第一格与「剪切」共用同一份排版属性；**左列第二、三格的水平锚点
 * 一律不动**。
 *
 * 连带规则：左列第一格的两位占用者（全选／剪切）永远保持**实际尺寸**，切换只用
 * `INVISIBLE`，绝不用 `GONE`、也绝不把宽高改成 0 —— 否则锚定在它们上面的左列格子会
 * 一起塌成 0 宽，#出现“复制只剩半个字、粘贴与剪贴板粘成一条”的错乱。左面板的右边缘同样
 * 锚在「剪切」的 start 上，因此左列第一格尺寸恒定时，面板宽度在任何状态下都不会变化。
 *
 * 双态的信号来源是宿主自己：面板构造时启动的选择状态观察者会在“有选中文本”时执行
 * `剪切.setEnabled(true)`、无选中时 `setEnabled(false)`。这个开关就是宿主自己对
 * “现在能不能剪切”的判定，比模块去问 InputConnection 更贴合宿主语义，也不依赖混淆名。
 *
 * 面板**不自动关闭**：宿主六个按钮的原生行为就是"动作做完、面板留在原地"，用户需要连续
 * 操作时不必反复重新打开面板；退出面板由用户按返回箭头完成。模块既不接管也不模拟关闭动作。
 * （曾实现过"点完即收"，因与用户要求相反已整体移除。）
 */
internal class PanelArranger(
    private val ids: PanelIds,
    private val labelId: Int,
    private val fallbackLabel: String,
    private val openClipboard: (Context) -> Unit,
    /** 宿主自己的按键反馈（震动/音效）；返回 true 表示已调用，不要再叠加框架触感。 */
    private val keyFeedback: (() -> Boolean)?,
) {
    private companion object {
        /** ConstraintLayout.LayoutParams.PARENT_ID */
        const val PARENT_ID = 0

        /** ConstraintLayout.LayoutParams.UNSET */
        const val UNSET = -1

        /** 六个格子宽的放大倍数（观感调整，需求 R-05）。 */
        const val WIDTH_SCALE = 1.2f


        /**
         * 承载“锚点 id 引用”的字段一律**按取值**识别：只有取值命中被置换控件 id 时才重映射。
         * 本版宿主把 ConstraintLayout$LayoutParams 混淆成了 androidx.constraintlayout.widget.d
         * （topToTop / endToStart 等字段名保留），换一个版本字段名就可能变，所以判断依据必须是取值。
         */
        val ANCHOR_NAMES = listOf(
            "startToStart", "startToEnd", "endToStart", "endToEnd",
            "topToTop", "topToBottom", "bottomToTop", "bottomToBottom",
            "leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
        )

        /** 每个 LayoutParams 类的 int 字段清单（含父类；子类字段优先，只取非静态）。 */
        private val intFieldsCache = HashMap<String, List<Field>>()

        fun intFieldsOf(cls: Class<*>): List<Field> {
            val key = cls.name
            intFieldsCache[key]?.let { return it }
            val byName = LinkedHashMap<String, Field>()
            var current: Class<*>? = cls
            while (current != null && current != Any::class.java) {
                current.declaredFields.forEach { field ->
                    if (field.type == Int::class.javaPrimitiveType &&
                        !Modifier.isStatic(field.modifiers) &&
                        !byName.containsKey(field.name)
                    ) {
                        byName[field.name] = field
                    }
                }
                current = current.superclass
            }
            val list = byName.values.toList()
            list.forEach { field -> runCatching { field.isAccessible = true } }
            intFieldsCache[key] = list
            return list
        }
    }

    private class State {
        var transplanted: Boolean = false
        var clipboardButton: View? = null
        /** 剪贴板按钮的槽位排版属性（已含 id 重映射），用于丢失后原位重建。 */
        var clipboardLp: Map<String, Int>? = null
        /** 剪贴板按钮的 View id；重建时必须复用，否则「粘贴」的锚点会指向不存在的 id。 */
        var clipboardId: Int = 0
        var verifyScheduled: Boolean = false
        /** 选择状态观察：记录上一轮判定，只在翻转时切换可见性，避免每帧重复写 LayoutParams。 */
        var lastSelecting: Boolean? = null
        var watcher: ViewTreeObserver.OnPreDrawListener? = null
        var watcherOwner: ViewTreeObserver? = null
        /** 每个格子「宿主写进来的原始宽度」。放大以它为基准，保证缩放幂等、不累乘。 */
        val baseWidths: MutableMap<View, Int> = HashMap()
    }

    private class Box(val name: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private val states: MutableMap<View, State> = Collections.synchronizedMap(WeakHashMap())

    /**
     * 重入保护。
     *
     * 本模块改写 `layoutParams` 会再次触发宿主那条被钩住的 `setLayoutParams`，从而再次回调本对象。
     * 没有这层守卫时，`scaleCellWidths` 会在每一轮里再乘一次 1.2（1.2 的 n 次方），
     * 按钮最终被撑到互相重叠——这正是「粘贴与剪贴板贴在一起」的直接成因。
     */
    private val applying: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    fun apply(panel: View) {
        if (panel !is ViewGroup) return
        if (applying.contains(panel)) return
        applying.add(panel)
        try {
            runCatching { applyInternal(panel) }
                .onFailure { log("arrange failed: ${it.stackTraceToString()}") }
        } finally {
            applying.remove(panel)
        }
    }

    private fun applyInternal(panel: ViewGroup) {
        val selectAll = panel.findViewById<View>(ids.selectAll) ?: return
        val clip = panel.findViewById<View>(ids.clip) ?: return
        val copy = panel.findViewById<View>(ids.copy) ?: return
        val paste = panel.findViewById<View>(ids.paste) ?: return
        val deleteLayout = panel.findViewById<View>(ids.deleteLayout) ?: return
        val returnLayout = panel.findViewById<View>(ids.returnLayout) ?: return

        val state = states[panel] ?: State().also { states[panel] = it }

        if (!state.transplanted) {
            state.transplanted = transplant(
                panel, state, selectAll, clip, copy, paste, deleteLayout, returnLayout,
            )
        }
        if (!state.transplanted) return

        // 宿主每次排布之后只做轻量维护：不重复搬运，避免二次置换。
        restoreClipboardIfLost(panel, state)
        syncSelectionCell(selectAll, clip, state)
        ensureSelectionWatcher(panel, state, selectAll, clip)
        scaleCellWidths(panel, state)
        scheduleVerify(panel, state, selectAll, clip)
    }

    // ------------------------------------------------- 按钮宽度 ×1.2（R-05）

    /**
     * 六个格子（含新建的「剪贴板」）宽度统一放大 [WIDTH_SCALE] 倍。
     *
     * 宽度由宿主在每次 `setLayoutParams` 里按 `params.b()` 重写；本方法挂在宿主写入**之后**，
     * 所以每轮都以宿主刚写好的宽度为基准放大一次，不会逐轮累乘。
     *
     * 右列变宽后，左侧白色面板（其右边缘锚定在「剪切」的 start 上）会自动让位收窄，
     * 不需要模块另外去改左面板宽度 —— 这也是「加宽按钮、左面板相应变窄」的正确做法，
     * 避免自己算宽度把 1.3.0 修好的锚点依赖链再次打塌。
     */
    private fun scaleCellWidths(panel: ViewGroup, state: State) {
        val targets = listOfNotNull(
            panel.findViewById<View>(ids.selectAll),
            panel.findViewById<View>(ids.clip),
            panel.findViewById<View>(ids.copy),
            panel.findViewById<View>(ids.paste),
            panel.findViewById<View>(ids.deleteLayout),
            panel.findViewById<View>(ids.returnLayout),
            state.clipboardButton?.takeIf { it.parent === panel },
        )
        targets.forEach { view ->
            val lp = view.layoutParams ?: return@forEach
            if (lp.width <= 0) return@forEach
            val previousBase = state.baseWidths[view]
            val base = when {
                previousBase == null -> lp.width
                // 宿主重算过基准宽度（旋转 / 单手 / 悬浮形态），以新值重新取基准
                lp.width != previousBase && lp.width != scaled(previousBase) -> lp.width
                else -> previousBase
            }
            state.baseWidths[view] = base
            val target = scaled(base)
            if (lp.width == target) return@forEach
            lp.width = target
            view.layoutParams = lp
        }
    }

    private fun scaled(width: Int): Int = (width * WIDTH_SCALE).toInt()

    // ---------------------------------------------------------------- 置换

    /**
     * 一次性把各格子的排版属性交给新的占用者。成功返回 true。
     * 快照必须先于任何写入完成，否则会读到已被改写的数据。
     */
    private fun transplant(
        panel: ViewGroup,
        state: State,
        selectAll: View,
        clip: View,
        copy: View,
        paste: View,
        deleteLayout: View,
        returnLayout: View,
    ): Boolean {
        val clipboard = ensureClipboardButton(panel, paste, state) ?: run {
            log("clipboard button creation failed, skip transplant")
            return false
        }

        // 快照：全部读取完成之前不写任何 LayoutParams。
        val snapshots = LinkedHashMap<View, Map<String, Int>>()
        listOf(clip, selectAll, copy, paste, deleteLayout, returnLayout).forEach { view ->
            val lp = view.layoutParams ?: run {
                log("layout params missing on ${viewName(view)}, skip transplant")
                return false
            }
            snapshots[view] = readInts(lp)
        }

        // 右列第一格的旧占位者是「全选」；目标版式里该格由「删除」占用，
        // 因此凡是指向「全选」的锚点都要改指「删除」。这就是唯一的整图锚点重映射。
        val rightTopRemap = mapOf(selectAll.id to deleteLayout.id)
        val clipboardId = clipboard.id

        // 左列第一格：原占用者「剪切」原地保留，并与搬进来的「全选」共享同一份排版属性。
        // 注意：两者的锚点都必须改指「删除」（原锚点是「全选」自己，会造成自引用）。
        install(clip, snapshots.getValue(clip), rightTopRemap)
        install(selectAll, snapshots.getValue(clip), rightTopRemap)

        // 右列整体下移一格：全选格 -> 删除、删除格 -> 回车、回车格 -> 剪贴板。
        install(deleteLayout, snapshots.getValue(selectAll), emptyMap())
        install(
            returnLayout,
            snapshots.getValue(deleteLayout),
            rightTopRemap + (returnLayout.id to clipboardId),
        )
        install(clipboard, snapshots.getValue(returnLayout), rightTopRemap)
        state.clipboardLp = remapValues(snapshots.getValue(returnLayout), rightTopRemap)

        // 左列第二、三格：水平锚点**必须保持**锚定「剪切」/「复制」（这两个控件都留在左列，
        // 位置与尺寸恒定），只把“行”改到新的右列同排占位者。
        install(copy, snapshots.getValue(copy), mapOf(deleteLayout.id to returnLayout.id))
        install(paste, snapshots.getValue(paste), mapOf(returnLayout.id to clipboardId))

        panel.requestLayout()
        log(
            "panel transplanted: 剪切/全选共用左列第一格 删除<-全选格 回车<-删除格 剪贴板<-回车格 " +
                "clipboard=${clipboard.javaClass.simpleName}"
        )
        return true
    }

    private fun install(
        view: View,
        source: Map<String, Int>,
        remap: Map<Int, Int>,
        quiet: Boolean = false,
    ) {
        val lp = view.layoutParams
        if (lp == null) {
            log("install skipped: ${viewName(view)} has no layout params")
            return
        }
        val fields = intFieldsOf(lp.javaClass)
        var applied = 0
        val remapped = ArrayList<String>()
        fields.forEach { field ->
            val value = source[field.name] ?: return@forEach
            val target = remap[value]
            if (target != null) {
                remapped.add("${field.name}:${shortId(value)}->${shortId(target)}")
            }
            runCatching {
                field.setInt(lp, target ?: value)
                applied++
            }
        }
        // 同一对象重新设置一次，触发 requestLayout，让 ConstraintLayout 重解约束。
        view.layoutParams = lp
        if (!quiet) log("install ${viewName(view)} fields=$applied remapped=$remapped")
    }

    private fun readInts(lp: ViewGroup.LayoutParams): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        intFieldsOf(lp.javaClass).forEach { field ->
            runCatching { out[field.name] = field.getInt(lp) }
        }
        return out
    }

    private fun remapValues(source: Map<String, Int>, remap: Map<Int, Int>): Map<String, Int> =
        source.mapValues { (_, value) -> remap[value] ?: value }

    // -------------------------------------------------- 左列第一格的双态切换

    /**
     * 「全选」与「剪切」共用左列第一格。
     *
     * 信号来源：宿主自带的选择状态观察者在“有选中文本”时执行 `剪切.setEnabled(true)`、
     * 无选中时 `setEnabled(false)`——这是宿主自己对“能不能剪切”的判定。
     *
     * 切换只动可见性：两位占用者的排版属性在 [transplant] 时已对齐到同一格，此后不再改写，
     * 因此切换不会引起任何尺寸变化（尤其是锚定在它们身上的左列第二格）。
     * 可见性一律用 INVISIBLE：`GONE` 会让 ConstraintLayout 把它当 0 尺寸的点，
     * 锚在它上面的控件会跟着塌陷。
     */
    private fun syncSelectionCell(selectAll: View, clip: View, state: State) {
        val gap = (clip.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0
        val selecting = clip.isEnabled
        val flipped = state.lastSelecting != selecting

        if (selecting) {
            if (clip.visibility != View.VISIBLE) clip.visibility = View.VISIBLE
            if (selectAll.visibility != View.INVISIBLE) selectAll.visibility = View.INVISIBLE
        } else {
            if (clip.visibility != View.INVISIBLE) clip.visibility = View.INVISIBLE
            if (selectAll.visibility != View.VISIBLE) selectAll.visibility = View.VISIBLE
        }
        // 两态共用一个格子，因此列间距也共用宿主写在「剪切」格上的那个值。
        applyEndMargin(selectAll, gap)

        if (flipped) {
            log(
                "selection cell switched: " +
                    (if (selecting) "全选->剪切 (宿主已启用剪切)" else "剪切->全选 (宿主已禁用剪切)")
            )
            state.lastSelecting = selecting
        }
    }

    private fun applyEndMargin(view: View, margin: Int) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.marginEnd == margin && lp.rightMargin == margin) return
        lp.marginEnd = margin
        lp.rightMargin = margin
        view.layoutParams = lp
    }

    /**
     * 选择状态变化不会触发 layout，因此不能用 OnGlobalLayout 监听；
     * 用 pre-draw 逐帧比对“宿主是否启用了剪切”，只在翻转时切换一次可见性。
     */
    private fun ensureSelectionWatcher(
        panel: ViewGroup,
        state: State,
        selectAll: View,
        clip: View,
    ) {
        val observer = panel.viewTreeObserver
        if (state.watcher != null && state.watcherOwner === observer) return
        val listener = state.watcher ?: ViewTreeObserver.OnPreDrawListener {
            runCatching {
                if (selectAll.parent != null) syncSelectionCell(selectAll, clip, state)
            }
            true
        }.also { state.watcher = it }
        runCatching {
            val previous = state.watcherOwner
            if (previous != null && previous.isAlive) previous.removeOnPreDrawListener(listener)
            observer.addOnPreDrawListener(listener)
            state.watcherOwner = observer
        }.onFailure { log("selection watcher attach failed: ${it.message}") }
    }

    // ------------------------------------------------------------ 轻量维护

    private fun restoreClipboardIfLost(panel: ViewGroup, state: State) {
        val existing = state.clipboardButton
        if (existing != null && existing.parent === panel) return
        val button = createStyledButton(panel, state, template = null) ?: return
        state.clipboardButton = button
        state.clipboardLp?.let { install(button, it, emptyMap()) }
        panel.requestLayout()
        log("clipboard button restored")
    }

    // --------------------------------------------------------- 剪贴板按钮

    private fun ensureClipboardButton(panel: ViewGroup, paste: View, state: State): View? {
        state.clipboardButton?.let { if (it.parent === panel) return it }
        val created = createStyledButton(panel, state, template = paste) ?: return null
        state.clipboardButton = created
        log("clipboard button created class=${created.javaClass.name} id=${created.id}")
        return created
    }

    /**
     * 新建「剪贴板」按钮：克隆“粘贴”的文字样式（字号/字色/背景/内边距/对齐/音效开关），
     * 点击先调用宿主自己的按键反馈（与其余五个按钮同一条震动/音效链），再打开剪贴板面板。
     */
    private fun createStyledButton(panel: ViewGroup, state: State, template: View?): View? {
        val reference = template ?: panel.findViewById<View>(ids.paste)
        val context = (reference ?: panel).context
        val sameClass = reference?.let { constructLikeTemplate(it, context) }
        val button = sameClass
            ?: runCatching { TextView(context) as View }.getOrNull()
            ?: return null
        button.id = if (state.clipboardId != 0) {
            state.clipboardId
        } else {
            View.generateViewId().also { state.clipboardId = it }
        }
        panel.addView(button)
        state.clipboardLp?.let { install(button, it, emptyMap()) }
        reference?.let { styleLike(button, it) }
        (button as? TextView)?.text = labelText(context)
        button.setOnClickListener {
            val hostFeedback = runCatching { keyFeedback?.invoke() ?: false }.getOrDefault(false)
            if (!hostFeedback) {
                // 宿主反馈链没能拿到时的兜底：框架级按键触感。
                button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            openClipboard(button.context)
        }
        return button
    }

    private fun labelText(context: Context): String =
        if (labelId != 0) {
            runCatching { context.getString(labelId) }.getOrDefault(fallbackLabel)
        } else {
            fallbackLabel
        }

    private fun constructLikeTemplate(template: View, context: Context): View? {
        val cls = template.javaClass
        cls.constructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java
        }?.let { return runCatching { it.newInstance(context) as View }.getOrNull() }
        cls.constructors.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Context::class.java &&
                AttributeSet::class.java.isAssignableFrom(it.parameterTypes[1])
        }?.let { return runCatching { it.newInstance(context, null) as View }.getOrNull() }
        return null
    }

    private fun styleLike(target: View, template: View) {
        val t = template as? TextView ?: return
        val v = target as? TextView ?: return
        v.text = labelText(t.context)
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.textSize)
        v.setTextColor(t.currentTextColor)
        v.gravity = t.gravity
        t.typeface?.let { v.typeface = it }
        v.includeFontPadding = t.includeFontPadding
        v.setPaddingRelative(t.paddingStart, t.paddingTop, t.paddingEnd, t.paddingBottom)
        val background = t.background
        if (background != null) {
            val cloned = runCatching { background.constantState?.newDrawable()?.mutate() }.getOrNull()
            v.background = cloned ?: background
        }
        // 音效开关随模板：宿主的功能键都是 soundEffectsEnabled=false，靠自己的反馈链发声震动，
        // 新按钮若保持默认 true 会多出一层系统点击音。
        v.isSoundEffectsEnabled = t.isSoundEffectsEnabled
        // 剪贴板与选择状态无关，必须始终可用，避免被宿主的状态机置灰
        v.isEnabled = true
        v.isClickable = true
        v.isFocusable = true
        v.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ 自检日志

    private fun scheduleVerify(
        panel: ViewGroup,
        state: State,
        selectAll: View,
        clip: View,
    ) {
        if (state.verifyScheduled) return
        state.verifyScheduled = true
        runCatching {
            panel.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        runCatching {
                            val observer = panel.viewTreeObserver
                            if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                            verify(panel, state, selectAll, clip)
                        }
                    }
                },
            )
        }.onFailure { log("verify scheduling failed: ${it.message}") }
    }

    /**
     * 布局完成后自证：把六个格子的实际位置分组成行、检测重叠、并检查有没有哪个格子塌成 0 尺寸。
     * 目标是三行、每行两个、无重叠、无零尺寸；结论直接打进日志，供真机核对。
     * 同时把六个格子的锚点关系也打出来——一旦再出现版式异常，从这一组行就能直接定位是哪条锚点错。
     */
    private fun verify(panel: ViewGroup, state: State, selectAll: View, clip: View) {
        val selecting = clip.isEnabled
        val leftTop = if (selecting) clip else selectAll
        val leftTopName = if (selecting) "剪切" else "全选"
        val cells = listOfNotNull(
            boxOf(leftTop, leftTopName),
            cellOf(panel, ids.copy, "复制"),
            cellOf(panel, ids.paste, "粘贴"),
            cellOf(panel, ids.deleteLayout, "删除"),
            cellOf(panel, ids.returnLayout, "回车"),
            state.clipboardButton?.let { Box("剪贴板", it.left, it.top, it.right, it.bottom) },
        )
        if (cells.size < 6) {
            log("panel verify skipped cells=${cells.size}/6")
            return
        }
        cells.forEach {
            log(
                "panel verify box ${it.name} l=${it.left} t=${it.top} r=${it.right} b=${it.bottom} " +
                    "w=${it.width} h=${it.height}"
            )
        }
        log("panel verify anchors 剪切=${anchorSummary(clip)}")
        log("panel verify anchors 全选=${anchorSummary(selectAll)}")
        log("panel verify anchors 复制=${anchorSummary(panel.findViewById(ids.copy))}")
        log("panel verify anchors 粘贴=${anchorSummary(panel.findViewById(ids.paste))}")

        val rowTolerance = ((cells.map { it.height }.minOrNull() ?: 0) / 2).coerceAtLeast(1)
        val colTolerance = ((cells.map { it.width }.minOrNull() ?: 0) / 2).coerceAtLeast(1)
        val rows = mutableListOf<MutableList<Box>>()
        cells.sortedBy { it.top }.forEach { box ->
            val last = rows.lastOrNull()
            if (last != null && kotlin.math.abs(box.top - last.first().top) <= rowTolerance) {
                last.add(box)
            } else {
                rows.add(mutableListOf(box))
            }
        }

        val overlaps = mutableListOf<String>()
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                val a = cells[i]
                val b = cells[j]
                if (kotlin.math.abs(a.left - b.left) <= colTolerance &&
                    kotlin.math.abs(a.top - b.top) <= rowTolerance
                ) {
                    overlaps.add("${a.name}/${b.name}")
                }
            }
        }
        val collapsed = cells.filter { it.width <= 0 || it.height <= 0 }.map { it.name }

        val rowsText = rows.joinToString(" | ") { row ->
            row.sortedBy { it.left }.joinToString("+") { it.name }
        }
        val ok = rows.size == 3 && rows.all { it.size == 2 } &&
            overlaps.isEmpty() && collapsed.isEmpty()
        log(
            "panel verify rows=$rowsText overlaps=${if (overlaps.isEmpty()) "none" else overlaps} " +
                "zeroSize=${if (collapsed.isEmpty()) "none" else collapsed} " +
                "leftTop=$leftTopName clipEnabled=$selecting " +
                "verdict=${if (ok) "PASS" else "FAIL"}"
        )
    }

    private fun cellOf(panel: ViewGroup, id: Int, name: String): Box? {
        val view = panel.findViewById<View>(id) ?: return null
        return Box(name, view.left, view.top, view.right, view.bottom)
    }

    private fun boxOf(view: View, name: String): Box = Box(name, view.left, view.top, view.right, view.bottom)

    /** 只读日志用：把当前 LayoutParams 上有效的锚点关系写成可读名字，便于真机一次定位。 */
    private fun anchorSummary(view: View?): String {
        val lp = view?.layoutParams ?: return "no-lp"
        val byName = intFieldsOf(lp.javaClass).associateBy { it.name }
        val parts = ANCHOR_NAMES.mapNotNull { name ->
            val field = byName[name] ?: return@mapNotNull null
            val value = runCatching { field.getInt(lp) }.getOrNull() ?: return@mapNotNull null
            if (value == UNSET) null else "$name=${shortId(value)}"
        }
        return if (parts.isEmpty()) "-" else parts.joinToString(",")
    }

    private fun shortId(id: Int): String = when (id) {
        PARENT_ID -> "parent"
        ids.selectAll -> "全选"
        ids.clip -> "剪切"
        ids.copy -> "复制"
        ids.paste -> "粘贴"
        ids.deleteLayout -> "删除"
        ids.returnLayout -> "回车"
        else -> "0x" + Integer.toHexString(id)
    }

    private fun viewName(view: View): String = viewNameById(view.id)

    private fun viewNameById(id: Int): String = when (id) {
        ids.selectAll -> "全选"
        ids.clip -> "剪切"
        ids.copy -> "复制"
        ids.paste -> "粘贴"
        ids.deleteLayout -> "删除"
        ids.returnLayout -> "回车"
        else -> "view(0x${Integer.toHexString(id)})"
    }
}
