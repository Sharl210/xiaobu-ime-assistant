package com.oplusime.panel

import android.content.Context
import android.content.DialogInterface
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.MethodData
import java.util.Collections
import java.util.WeakHashMap

/**
 * 剪贴板面板的「搜索」按钮与条目过滤。
 *
 * ## 位置（这是本文件的第三次返工，写清楚为什么）
 *
 * 宿主布局 `res/IB.xml` 里，计数行本来是「左计数 + 右控件」的两端结构：
 *
 * ```text
 * tv_clip_count (13/∞)   end → tv_phrase_count.start           ← 左端计数
 * tv_phrase_count        end → parent 右端，top 与计数同一行    ← 右端槽位
 * ```
 *
 * 第 1 次尝试：**新建** View 加到面板根 → 面板根是 ConstraintLayout，新 View 一条约束都没有，
 * 被摆到 (0,0)，表现为按钮飘在左上角。
 *
 * 第 2 次尝试：**接管**宿主那个右端槽位。这个方向是错的——那个控件是宿主
 * 「剪贴板页 / 常用语页」**共用**的一位：
 *  - 宿主每次切页都会给它 `setText`（常用语计数），于是我们的「搜索」字样被改写；
 *  - 它一旦可见，计数控件的 `end` 锚点就被它挤住，`13/∞` 的居中随之偏掉。
 *
 * 第 3 次（本版）：**自己新建、把约束显式写全**。具体做法是
 *  - 新按钮加进计数行所在的容器；
 *  - `end → parent`（贴右端，与宿主右端槽位同一位置）；
 *  - `top → 计数控件的 top`、`bottom → 计数控件的 bottom`（与计数行垂直居中对齐，
 *    高度由计数行决定，不会向下延伸去贴住下面的列表项）；
 *  - 宿主那个槽位**保持隐藏、一个字不碰** → 计数控件的锚点链完好 → `13/∞` 恢复居中。
 *
 * 宿主不认识这个新控件，因此不会再有人来改写它的文字。
 *
 * ## 过滤
 *
 * 剪贴板列表由分页数据源驱动，分页源会把查到的行「转换」成列表项。在该转换结果上按
 * 「条目里任一字符串字段 / 无参字符串取值器是否包含关键字」过滤，不依赖任何混淆名。
 * 另有一层行级兜底（`onBindViewHolder` 绑定后按命中与否收放行高），保证分页实现形态变化时
 * 搜索仍然可用。
 */
internal class ClipSearch(
    /** 剪贴板页计数控件（`tv_clip_count`）。 */
    private val counterId: Int,
    /** 常用语页计数控件（`tv_phrase_count`）。 */
    private val phraseCounterId: Int,
    /** 剪贴板列表（`rv_clipboard`）。 */
    private val listId: Int,
    /** 常用语列表（`rv_phrase_directory`）。 */
    private val phraseListId: Int,
    private val label: String,
    /** 创建宿主同款控件（拿不到时退回普通 TextView）。 */
    private val createViewLike: ((View) -> TextView?)? = null,
    /** 创建宿主同款输入框（拿不到时退回普通输入框）。 */
    private val createInputField: ((android.content.Context) -> EditText)? = null,
    /** 把输入框注册成宿主的当前输入目标；返回是否成功。 */
    private val registerInputTarget: ((EditText) -> Boolean)? = null,
    /** 解除宿主的内部输入目标（收搜索条时必须先做这一步，见 confirmSearch）。 */
    private val clearInputTarget: (() -> Boolean)? = null,
    /** 宿主自己的「收起面板回键盘」链（与 HostTweaks 用的是同一条）。 */
    private val closePanel: (() -> Boolean)? = null,
    /** 按 BoxEnums 常量名重新打开面板（搜索结果要看得到，就必须回到面板）。 */
    private val openPanel: ((String) -> Boolean)? = null,
) {
    private enum class Page { CLIPBOARD, PHRASE }

    @Volatile
    private var currentPage: Page = Page.CLIPBOARD

    @Volatile
    private var clipboardKeyword: String? = null

    @Volatile
    private var phraseKeyword: String? = null

    private fun currentKeyword(): String? = when (currentPage) {
        Page.CLIPBOARD -> clipboardKeyword
        Page.PHRASE -> phraseKeyword
    }

    private fun keywordFor(page: Page): String? = when (page) {
        Page.CLIPBOARD -> clipboardKeyword
        Page.PHRASE -> phraseKeyword
    }

    private fun setKeyword(page: Page, value: String?) {
        when (page) {
            Page.CLIPBOARD -> clipboardKeyword = value
            Page.PHRASE -> phraseKeyword = value
        }
    }

    /** 上一次锚点校验的结果，只在变化时打日志（避免每帧刷屏）。 */
    @Volatile
    private var lastAnchorReported: Boolean? = null

    /** 面板 → 我们新建的搜索按钮。 */
    private val buttons: MutableMap<ViewGroup, View> =
        Collections.synchronizedMap(WeakHashMap())

    /** 当前活动输入框只由宿主 Dialog 自身持有，不在输入法面板内叠加卡片。 */

    // ------------------------------------------------------------------ UI

    /** 面板每次排布后调用；按钮只在第一次创建，之后只重申约束。 */
    fun attach(panel: ViewGroup) {
        val clipCounter = panel.findViewById<View>(counterId)
        val phraseCounter = panel.findViewById<View>(phraseCounterId)
        val onClipboardPage = clipCounter?.visibility == View.VISIBLE
        val onPhrasePage = !onClipboardPage && phraseCounter?.visibility == View.VISIBLE
        if (!onClipboardPage && !onPhrasePage) {
            log("clip-search: no searchable page counter visible")
            return
        }
        currentPage = if (onClipboardPage) Page.CLIPBOARD else Page.PHRASE
        val counter = if (onClipboardPage) clipCounter else phraseCounter
            ?: return
        // 按钮必须加入计数控件所在的 ConstraintLayout，而不是宿主面板本身。
        val host = counter.parent as? ViewGroup ?: panel
        val existing = buttons[panel]
        if (existing != null && existing.parent === host) {
            place(existing, counter)
            if (existing.visibility != View.VISIBLE) existing.visibility = View.VISIBLE
            applyActiveStyle(existing)
            return
        }
        val button = createButton(counter) ?: return
        host.addView(button)
        buttons[panel] = button
        place(button, counter)
        applyActiveStyle(button)
        log(
            "clip-search: button created id=0x" + Integer.toHexString(button.id) +
                " class=${button.javaClass.name} parent=${host.javaClass.name}" +
                " page=$currentPage counter=0x" + Integer.toHexString(counter.id)
        )
    }

    private fun createButton(counter: View): TextView? {
        val context = counter.context
        val view = createViewLike?.invoke(counter) ?: TextView(context)
        view.id = View.generateViewId()
        view.text = label
        view.gravity = Gravity.CENTER
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.isClickable = true
        view.isFocusable = true
        // 与宿主功能键一致：不吃系统点击音（宿主的反馈链自己会发声/震动）。
        view.isSoundEffectsEnabled = false
        view.setOnClickListener { onButtonClicked(view) }

        // 与计数控件同族的 LayoutParams（约束布局的参数类型必须一致，否则写入的锚点字段无效）。
        view.layoutParams = newConstraintLp(counter)
        applyButtonStyle(view, active = false)
        return view
    }

    /**
     * 造一个与计数控件同族的 `LayoutParams`。
     *
     * **这是按钮前两版飘到左上角的真正原因，可以静态证明，不需要真机：**
     * `androidx.constraintlayout.widget.LayoutParams`（本版被 R8 改名为
     * `androidx.constraintlayout.widget.d`）只公开了
     * `(int width, int height)` 与 `(Context, AttributeSet)` 两个构造器，
     * **没有无参构造器**。上一版写的 `lp.javaClass.getConstructor()` 必然抛
     * `NoSuchMethodException`，被 `runCatching` 吞掉后退回普通
     * `ViewGroup.LayoutParams` —— 那个类里**根本没有** `topToTop` / `endToEnd` 这些字段，
     * 于是所有锚点写入静默失败，ConstraintLayout 只能把这个没有任何约束的子 View 摆在 (0,0)，
     * 表现就是「搜索」压在返回箭头上。
     *
     * 更糟的是：写入失败时日志照样打 "anchored to counter=…"，等于在骗人。
     * 本版一并修掉这两点：用 `(int,int)` 构造器造 LP，并且写入后**回读校验**。
     */
    private fun newConstraintLp(counter: View): ViewGroup.LayoutParams {
        val cls = counter.layoutParams?.javaClass
        if (cls != null) {
            runCatching {
                cls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .newInstance(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }.getOrNull()?.let { return it as ViewGroup.LayoutParams }
        }
        return ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /**
     * 把按钮钉在计数行的右端、并与计数行垂直居中。
     *
     * 每次排布都重申一次（宿主重排会重置同容器内控件的解析结果），写入是幂等的：
     * 目标状态唯一，重写不会引发新的重排。
     */
    private fun place(button: View, counter: View) {
        // 文字由我们自己写死；宿主不认识这个控件，因此这里不该出现第二个写入者。
        (button as? TextView)?.let {
            if (it.text?.toString() != label) {
                log("clip-search: label restored '${it.text}' -> '$label'")
                it.text = label
            }
        }
        val lp = button.layoutParams ?: return
        runCatching {
            // 水平：贴父容器右端（与宿主自己的右端槽位同位置），不参与计数的锚点链。
            Reflect.writeInt(lp, "startToStart", UNSET)
            Reflect.writeInt(lp, "startToEnd", UNSET)
            Reflect.writeInt(lp, "endToStart", UNSET)
            Reflect.writeInt(lp, "endToEnd", PARENT_ID)
            Reflect.writeInt(lp, "leftToLeft", UNSET)
            Reflect.writeInt(lp, "rightToRight", UNSET)
            // 垂直：与计数控件同顶同底 → 在计数行内垂直居中，不会向下延伸贴住列表项。
            Reflect.writeInt(lp, "topToTop", counter.id)
            Reflect.writeInt(lp, "bottomToBottom", counter.id)
            Reflect.writeInt(lp, "topToBottom", UNSET)
            Reflect.writeInt(lp, "bottomToTop", UNSET)
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                val density = counter.resources.displayMetrics.density
                lp.marginEnd = (12 * density).toInt()
            }
            button.layoutParams = lp
            button.requestLayout()

            // 回读校验：锚点写不进去时（LP 类型不对、字段名对不上）必须当场看见，
            // 不能让"位置没放对"变成一个只有用户才能发现的哑故障。
            val gotTop = Reflect.readInt(lp, "topToTop")
            val gotEnd = Reflect.readInt(lp, "endToEnd")
            val ok = gotTop == counter.id && gotEnd == PARENT_ID
            if (!ok || lastAnchorReported != ok) {
                lastAnchorReported = ok
                log(
                    "clip-search: anchor verify ${if (ok) "PASS" else "FAIL"}" +
                        " topToTop=$gotTop(want ${counter.id})" +
                        " endToEnd=$gotEnd(want $PARENT_ID)" +
                        " lp=${lp.javaClass.name}"
                )
            }
        }.onFailure { log("clip-search: place button failed: ${it.message}") }
    }

    /** 未激活＝白底气泡黑字；激活＝浅蓝气泡蓝字（可一眼看出正在过滤）。 */
    private fun applyButtonStyle(button: TextView, active: Boolean) {
        val density = button.resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (2 * density).toInt()
        button.setPadding(padH, padV, padH, padV)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (active) ACTIVE_BG else Color.WHITE)
            cornerRadius = 10 * density
        }
        button.setTextColor(if (active) ACTIVE_FG else INACTIVE_FG)
    }

    private fun applyActiveStyle(button: View) {
        val text = button as? TextView ?: return
        applyButtonStyle(text, active = currentKeyword() != null)
    }

    // -------------------------------------------------------------- 搜索开关与弹窗

    /**
     * 点一次：在输入法窗口内部显示搜索输入页（见 [showInputInIme]）。
     * 再点一次：退出搜索，恢复全部条目，按钮回到未激活样式。
     */
    private fun onButtonClicked(anchor: View) {
        if (currentKeyword() != null) {
            applyKeyword("")
            applyActiveStyle(anchor)
            log("clip-search: search cleared by second tap")
            return
        }
        showInput(anchor)
    }

    /**
     * 搜索输入页。
     *
     * **关键：输入框不能是"压在输入法上面的另一个窗口"，而必须长在输入法窗口内部。**
     *
     * 原因（用户实测 + 宿主代码双重印证）：
     *
     * - 输入法进程自己就是输入源。任何"叠在输入法窗口之上"的窗口（PopupWindow，或
     *   `type=0x3eb` 的附加 Dialog）都会把输入法窗口压在下面——输入法自己就是那个要弹出来的东西，
     *   它没法再在自己头上弹一次，于是键盘不出来，光标点上去也没反应。
     * - 宿主的做法是另一种：把带输入框的界面做成**输入法窗口内部的普通 View**（例如「添加常用语」
     *   那一整页），再调用它自己的内部焦点切换
     *   （`input/manager/h;->l(EditText, boolean)` → `switchInternalFocus`），
     *   让 IME 的按键事件直接进到这个 EditText 上。这也是宿主自己的常用语编辑框能打字的原因。
     *
     * 因此本版完全照这条走：
     *
     * 1. 在面板所在的容器里放一张**输入法窗口内部的**白色卡片（标题 + 输入框 + 取消/搜索）；
     * 2. 输入框用宿主自己的编辑框类，并交给宿主的内部焦点切换；
     * 3. 键盘本来就是输入法窗口的一部分，它一直在下面，卡片不会把它盖住。
     */
    private fun showInput(anchor: View) {
        showSearchBar(anchor)
    }

    /**
     * 搜索输入条**不再由实例字段持有**，改放在 companion 的 `activeBar` 上。
     *
     * 原因（1.22.0 真机现象）：宿主要在切页/重建时新建面板实例，实例字段跟着丢引用，
     * 已经排在输入法窗口根视图上的输入条就变成孤儿 —— 用户看到的就是"输入框还残留在屏幕上"。
     */
    private val liveFilterHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var liveFilterRunnable: Runnable = Runnable { }

    /**
     * 搜索输入条：**在输入法窗口内部、键盘正上方**插入一条输入条。
     *
     * ## 为什么不再做叠加窗口（Dialog / PopupWindow）
     *
     * 输入法进程自己就是输入源。任何叠在输入法窗口之上的**可获焦**窗口，都会让系统认为
     * 「当前焦点窗口不是输入法要输入的目标」，于是把输入法窗口收下去 —— 1.16~1.21 反复实测到的
     * 「只有一个光标、键盘不出来」就是这个机制。
     *
     * 这跟窗口标志无关：1.21.0 真机日志里
     * `clip-search: host dialog shown, input-registered=true windowType=1003 flags=0x1800002`
     * 已经**不含** `FLAG_ALT_FOCUSABLE_IM(0x20000)`，键盘照样不出来。所以问题不在标志。
     *
     * ## 宿主自己的做法（两条独立证据）
     *
     * 宿主把输入框做成**输入法窗口内部的普通 View**，再交给它的内部焦点切换
     * （`input/manager/h;->l(editText, true)`），让键盘按键直接进到那个输入框：
     *
     * ```text
     * input/view/head/O;->o(CustomEditText)   「编辑常用语」
     *     setImeOptions(1) → h.l(editText, true)
     * input/view/head/h0;->d()                「搜索框」emoji 搜索
     *     setImeOptions(3) → setOnEditorActionListener → h.l(editText, true)
     * ```
     *
     * ## 本版流程
     *
     * 1. 先收起面板（`res/IB.xml` 根是 match_parent，面板会占满整个键盘区域，键盘在里面没有位置）；
     * 2. 把输入条加到**输入法窗口根视图**（`anchor.rootView`，也就是 IME 窗口自己的 DecorView）
     *    顶部 —— 键盘仍在下方可见可用，输入条只是压在它上面的一条；
     * 3. 注册内部输入目标，键盘按键因此进入这个输入框；
     * 4. 确认 → 应用关键字、移除输入条、重新打开面板看过滤结果；取消 → 移除输入条、重新打开面板。
     *
     * 拿不到根视图时**不硬来**：记一行日志并退回旧的弹窗实现，避免"静默失效"。
     */
    private fun showSearchBar(anchor: View) {
        runCatching {
            val context = anchor.context
            val density = context.resources.displayMetrics.density
            val page = currentPage
            val root = anchor.rootView as? ViewGroup
            if (root == null) {
                log("clip-search: ime root view unavailable; falling back to dialog")
                showDialog(anchor)
                return
            }
            removeSearchBar()
            removeTaggedBars(root)
            val closed = runCatching { closePanel?.invoke() }.getOrNull()
            log("clip-search: panel closed before input=$closed page=$page")

            val field = createInputField?.invoke(context) ?: EditText(context)
            field.hint = "输入要搜索的关键字"
            field.isFocusableInTouchMode = true
            field.setShowSoftInputOnFocus(true)
            field.setSingleLine(true)
            // **不给"搜索/完成"动作键**（这是 1.25.0 的关键改动）。
            // 宿主 `input/manager/h;->g(EditorInfo)` 会读 actionType；只要读到 SEARCH，
            // 键盘上就会出现「搜索」键，而那一按由宿主自己处理，走的是"结束内部输入"的路径，
            // 顺手把输入法窗口收下去。1.24.0 真机日志（18:58:46
            // `ime window hidden while bar shown`）就是这个：收尾虽然跑完了，但窗口已经没了，
            // 用户看到的就是"一点搜索整个界面垮掉、输入法被收起来"。
            // 改成普通回车后，宿主不再把它当动作处理；回车仍会以 UNSPECIFIED 送到下面的
            // `setOnEditorActionListener`，成为除输入条按钮之外的第二个确认入口。
            field.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            field.inputType = InputType.TYPE_CLASS_TEXT
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            field.setText(currentKeyword() ?: "")
            field.setSelectAllOnFocus(false)
            field.setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt(),
            )

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            }
            row.addView(
                field,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(buildBarButton(context, "取消") {
                liveFilterHandler.removeCallbacks(liveFilterRunnable)
                applyKeyword("")
                log("clip-search: cancel tapped page=$page")
                finishSearch(page, "cancel")
            })
            row.addView(buildBarButton(context, "搜索") {
                confirmSearch(field, page)
            })

            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.TOP
            row.tag = BAR_TAG
            root.addView(row, lp)
            row.bringToFront()
            activeBar = row
            activeField = field
            activeInstance = this

            // 输入条被摘掉时同步"忘记"它。宿主此刻把内部输入目标指向了这个输入框，
            // 如果只摘视图、不解除指向，宿主会对着一个已脱离视图树的输入框继续操作，
            // 输入法就会自己把窗口收下去（用户实测：点「搜索」后整个界面被关掉）。
            row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (activeBar === v) activeBar = null
                    if (activeField === field) activeField = null
                }
            })

            // 边打字边过滤（300ms 防抖）：即使"确认"这一下因为任何原因没送到，
            // 关键字也已经生效，不会出现"点了等于没点"。
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    runCatching { liveFilterHandler.removeCallbacks(liveFilterRunnable) }
                    liveFilterRunnable = Runnable {
                        runCatching {
                            applyKeyword(text)
                            log("clip-search: live filter applied kw='$text'")
                        }
                    }
                    runCatching { liveFilterHandler.postDelayed(liveFilterRunnable, 300L) }
                }
            })

            // 键盘上的"搜索/回车"键同样当作确认：内部焦点链下这条最稳，不依赖触摸投递。
            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                ) {
                    confirmSearch(field, page)
                    true
                } else {
                    false
                }
            }

            field.requestFocus()
            field.setSelection(field.text?.length ?: 0)
            val registered = registerInputTarget?.invoke(field) == true
            field.postDelayed({
                val again = runCatching { registerInputTarget?.invoke(field) == true }.getOrDefault(false)
                log("clip-search: search bar input re-register=$again")
            }, 250L)
            log(
                "clip-search: search bar shown in ime window root=" + root.javaClass.name +
                    " input-registered=" + registered +
                    " imeOptions=" + field.imeOptions + " inputType=" + field.inputType
            )
        }.onFailure { log("clip-search: search bar failed: ${it.message}") }
    }

    /** 输入条上的小按钮（与搜索按钮同一套气泡样式）。 */
    private fun buildBarButton(context: android.content.Context, label: String, onClick: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        val button = TextView(context)
        button.text = label
        button.setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        button.setTextColor(ACTIVE_FG)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x1A0A59F7)
            cornerRadius = 10 * density
        }
        button.isClickable = true
        // 触摸留证：1.22/1.23 的日志里从未出现过"确认"这一步，无法判断是"按钮没被点到"
        // 还是"点到了但处理没跑完"。加这一行之后，下次日志可以直接分辨。
        button.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                log("clip-search: bar button '$label' touch down")
            }
            false
        }
        button.setOnClickListener { onClick() }
        return button
    }

    /**
     * 「确认搜索」：应用关键字 → 摘输入条 → **重新打开面板** → 让列表按关键字重绑。
     *
     * 1.23.0 真机日志（18:46:04）给出的两条硬事实：
     *  - 打字过程中关键字确实生效了（`live filter applied kw='计算机'`），所以过滤逻辑没问题；
     *  - 紧接着 `refresh failed: com.oplus.keyboard.input.adapter.Q.refresh []` —— 列表刷新这一步
     *    失败，界面因此看不到任何变化，用户的感觉就是"搜了没用"。
     *
     * 另外**不再调用"解除内部输入目标"**：那个调用会让宿主把键盘收下去（正是用户看到的
     * "一点搜索整个界面就关掉"）。摘掉视图、重新打开面板这两步，宿主会自己把内部目标重新落回面板。
     */
    private fun confirmSearch(field: EditText, page: Page) {
        liveFilterHandler.removeCallbacks(liveFilterRunnable)
        val kw = field.text?.toString().orEmpty()
        applyKeyword(kw)
        log("clip-search: confirm tapped kw='$kw' page=$page")
        finishSearch(page, "confirm")
    }

    /**
     * 搜索收尾：摘掉输入条 → 重新打开面板 → 让列表重绑。
     *
     * 抽成一条路是必要的：不管是点输入条上的按钮、还是按键盘上的「搜索」键（那条会被宿主
     * 自己接走并收起键盘），最终都必须落到同一组收尾动作，否则就会出现
     * "界面关了、输入条还留在屏幕上、结果也没过滤"这种半成品状态。
     */
    private fun finishSearch(page: Page, reason: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        runCatching {
            handler.postDelayed({
                removeSearchBar()
                reopenPanel(page)
                // 面板刚重建，行必须重走一遍绑定，行级过滤才会生效 —— 这一步就是
                // "关键词生效了但屏幕上看不到结果"的解药。
                handler.postDelayed({
                    reloadLists(page)
                    log(
                        "clip-search: finish($reason) page=$page" +
                            " kw=${keywordFor(page) ?: "<none>"}"
                    )
                }, 220L)
            }, 90L)
        }.onFailure { log("clip-search: finish($reason) failed: ${it.message}") }
    }

    /**
     * 取消搜索（返回键或输入条上的「取消」）：清空关键字、摘输入条、回面板看全部条目。
     */
    fun cancelSearch() {
        val page = currentPage
        liveFilterHandler.removeCallbacks(liveFilterRunnable)
        applyKeyword("")
        log("clip-search: cancel requested page=$page")
        finishSearch(page, "cancel")
    }

    /**
     * 输入法窗口被收起时的收尾。
     *
     * 1.24.0 真机日志（18:58:46）复现的现场：输入框带 `IME_ACTION_SEARCH`，键盘上因此出现
     * 「搜索」键；那一按由**宿主自己**处理（不会走我们的监听器），宿主按常规行为把输入法窗口
     * 收了下去。收尾动作确实跑了（关键字生效、面板重开），但**窗口已经没了** ——
     * 用户看到的就是"一点搜索整个界面垮掉、输入法被收起来"。
     *
     * 所以本版分两层处理：
     *  1. **挡住**（见 [installImeWindowHook] 的第一层）：条还在，就不让窗口被收走；
     *  2. **兜底**（本方法）：万一还是被收了，把该做的补齐 ——
     *     关键字生效 → 摘输入条 → 重新打开面板看过滤结果。
     */
    fun onImeWindowHidden(reason: String = "ime-window-hidden") {
        val bar = activeBar ?: return
        val page = currentPage
        val kw = activeField?.text?.toString().orEmpty()
        liveFilterHandler.removeCallbacks(liveFilterRunnable)
        applyKeyword(kw)
        log("clip-search: $reason -> apply kw='$kw' page=$page bar=$bar")
        removeSearchBar()
        reopenPanel(page)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            reloadLists(page)
            log("clip-search: window-hidden cleanup done page=$page reason=$reason")
        }, 220L)
    }

    /**
     * 输入法窗口守卫：只在这一个进程内、只影响本模块自己。
     *
     * ## 第一层：挡住"收窗口"
     *
     * 搜索输入条显示期间，窗口一旦被隐藏，后面再怎么补救都晚了。因此直接挂在框架的
     * `hideWindow()` / `requestHideSelf(int)` 入口上：只要输入条还在（就说明搜索还没结束），
     * 就不让这次隐藏生效，并把这次"想收窗口"当成用户按下确认的信号，立刻走收尾。
     *
     * 只拦"条还在"的那段极短窗口，正常使用输入法时的收键盘不受影响。
     *
     * ## 第二层：兜底
     *
     * 万一窗口还是被收掉了（例如其它路径直接隐藏窗口），在 `onWindowHidden` 后把收尾补齐，
     * 避免屏幕上留下一条没人清理的输入条。
     */
    fun installImeWindowHook() {
        runCatching {
            XposedBridge.hookAllMethods(
                android.inputmethodservice.InputMethodService::class.java,
                "hideWindow",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (activeBar == null) return
                        param.result = null
                        log("clip-search: hideWindow blocked while bar shown")
                        runCatching { onImeWindowHidden("hide-blocked") }
                            .onFailure { log("clip-search: hide-blocked cleanup failed: ${it.message}") }
                    }
                },
            )
            XposedBridge.hookAllMethods(
                android.inputmethodservice.InputMethodService::class.java,
                "requestHideSelf",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (activeBar == null) return
                        param.result = null
                        log("clip-search: requestHideSelf blocked while bar shown")
                        runCatching { onImeWindowHidden("hide-request-blocked") }
                            .onFailure { log("clip-search: hide-request cleanup failed: ${it.message}") }
                    }
                },
            )
            log("clip-search: ime window guard installed (hideWindow/requestHideSelf blocked while bar shown)")
        }.onFailure { log("clip-search: ime window guard failed: ${it.message}") }

        runCatching {
            XposedBridge.hookAllMethods(
                android.inputmethodservice.InputMethodService::class.java,
                "onWindowHidden",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { onImeWindowHidden("window-hidden") }
                            .onFailure { log("clip-search: window-hidden cleanup failed: ${it.message}") }
                    }
                },
            )
            log("clip-search: ime window hidden hook installed")
        }.onFailure { log("clip-search: ime window hidden hook failed: ${it.message}") }
    }

    /** 移除输入条；可重复调用。 */
    private fun removeSearchBar() {
        val bar = activeBar
        activeBar = null
        if (bar == null) {
            log("clip-search: search bar already gone")
            return
        }
        runCatching {
            (bar.parent as? ViewGroup)?.removeView(bar)
            bar.tag = null
            log("clip-search: search bar removed")
        }.onFailure { log("clip-search: search bar remove failed: ${it.message}") }
    }

    /** 扫掉根视图里所有带标记、但不是当前活动条的输入条（防残留）。 */
    private fun removeTaggedBars(container: ViewGroup) {
        val stale = mutableListOf<View>()
        fun walk(node: ViewGroup) {
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i)
                if (child.tag == BAR_TAG && child !== activeBar) stale.add(child)
                if (child is ViewGroup) walk(child)
            }
        }
        runCatching { walk(container) }
        stale.forEach { runCatching { (it.parent as? ViewGroup)?.removeView(it) } }
        if (stale.isNotEmpty()) log("clip-search: stale bars removed=${stale.size}")
    }

    private fun showDialog(anchor: View) {
        runCatching {
            val context = anchor.context
            val density = context.resources.displayMetrics.density
            val page = currentPage
            // 先收起面板：`res/IB.xml` 的根布局是 match_parent，剪贴板/常用语面板**占满整个
            // 键盘区域**（面板里只有标题栏 + 列表 + 底栏，没有任何按键）。面板开着的时候屏幕上一个
            // 键都没有 —— 所以"键盘弹不出来"的直接原因不是弹窗的窗口标志，而是面板把键盘的位置占了。
            // 收起面板走宿主自己的返回键链路（与 HostTweaks 关闭面板用的是同一条），键盘立刻回来，
            // 弹窗里的输入框才有键可按。搜索结果仍然正常：确认后重新打开面板即见到过滤后的列表。
            val closed = runCatching { closePanel?.invoke() }.getOrNull()
            log("clip-search: panel closed before input=$closed page=$page")
            val builderClass = Class.forName("com.coui.appcompat.dialog.COUIAlertDialogBuilder", false, context.classLoader)
            val builder = builderClass.getConstructor(android.content.Context::class.java).newInstance(context)
            val field = createInputField?.invoke(context) ?: EditText(context)
            field.hint = "输入要搜索的关键字"
            field.isFocusableInTouchMode = true
            field.setShowSoftInputOnFocus(true)
            field.setSingleLine(true)
            // 与宿主自己的搜索框 `input.view.head.h0`（SearchView / emoji 搜索）逐字一致的三件事：
            //   setImeOptions(3)                    → IME_ACTION_SEARCH
            //   setOnEditorActionListener(...)      → 回车=发起搜索（这里由内部焦点链处理）
            //   input/manager/h;->l(editText,true)  → 注册成输入法内部输入目标（见构造参数）
            // 前两件决定"看起来像搜索框"，第三件才是"键盘上的按键进到这个输入框"的开关。
            field.imeOptions = EditorInfo.IME_ACTION_SEARCH
            field.inputType = InputType.TYPE_CLASS_TEXT
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            field.setText(currentKeyword() ?: "")
            field.setSelectAllOnFocus(false)
            field.setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt(),
            )

            builderClass.getMethod("setTitle", CharSequence::class.java).invoke(builder, "搜索")
            builderClass.getMethod("setView", View::class.java).invoke(builder, field)
            builderClass.getMethod("setCancelable", Boolean::class.javaPrimitiveType).invoke(builder, true)
            runCatching { builderClass.getMethod("setBlurBackgroundDrawable", Boolean::class.javaPrimitiveType).invoke(builder, true) }

            val dialogBox = arrayOfNulls<Dialog>(1)
            val listenerType = DialogInterface.OnClickListener::class.java
            val confirm = java.lang.reflect.Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
            ) { _, method, _ ->
                if (method.name == "onClick") {
                    applyKeyword(field.text?.toString().orEmpty())
                    dialogBox[0]?.dismiss()
                    // 结果要看得见：过滤已生效，把面板重新打开就是过滤后的列表。
                    reopenPanel(page)
                }
                null
            } as DialogInterface.OnClickListener
            val cancel = java.lang.reflect.Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
            ) { _, method, _ ->
                if (method.name == "onClick") {
                    dialogBox[0]?.dismiss()
                    // 取消也要回到面板，不能把用户丢在空键盘上。
                    reopenPanel(page)
                }
                null
            } as DialogInterface.OnClickListener

            invokeDialogButton(builderClass, builder, "setNeutralButton", "搜索", confirm)
            invokeDialogButton(builderClass, builder, "setNegativeButton", "取消", cancel)

            val dialog = builderClass.getMethod("create").invoke(builder) as Dialog
            dialogBox[0] = dialog
            val window = dialog.window
            val token = anchor.windowToken ?: error("search anchor has no window token")
            if (window != null) {
                val attrs = window.attributes
                attrs.token = token
                // 逐字照抄宿主「添加常用语」弹窗 `body/D;->q(String, Function0)`：
                //   token = 当前输入法窗口的 token，type = 0x3eb，
                //   addFlags(0x20002) = FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM。
                // 关键是 0x20002：弹窗**不抢焦点**，输入法窗口因此不会被压下去，
                // 文字改由宿主的内部焦点切换送进输入框（见 registerInputTarget）。
                attrs.type = 0x3eb
                window.attributes = attrs
                // 与宿主「添加常用语」弹窗（body/D;->q）唯一的差别：**不要 0x20000**。
                // 0x20000 = FLAG_ALT_FOCUSABLE_IM，含义是"这个窗口不要让输入法弹出来"。
                // 宿主那个弹窗里没有输入框，所以它加上没问题；我们这个弹窗是要打字的，
                // 带上这个标志系统就会把输入法窗口收下去 —— 实测表现就是"只有一个光标，键盘不出来"。
                // 因此这里只保留 FLAG_DIM_BEHIND（0x2），并显式清掉 0x20000。
                window.clearFlags(0x20000)
                window.addFlags(0x2)
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setDimAmount(0.3f)
            }
            dialog.show()
            // show() 之后窗口标志才是最终值，这里再清一次并留证。
            runCatching { dialog.window?.clearFlags(0x20000) }
            runCatching { builderClass.getMethod("updateViewAfterShown").invoke(builder) }
            field.requestFocus()
            field.setSelection(field.text?.length ?: 0)
            val registered = registerInputTarget?.invoke(field) == true
            // 宿主自己的流程（比如面板重建）可能把内部输入目标换回它自己的编辑框，
            // 所以稍后再确认一次；成功与否都如实写日志，不靠"应该没问题"。
            field.postDelayed({
                val again = runCatching { registerInputTarget?.invoke(field) == true }.getOrDefault(false)
                log("clip-search: input target re-register=$again")
            }, 250L)
            log(
                "clip-search: host dialog shown, input-registered=$registered" +
                    " windowType=${window?.attributes?.type}" +
                    " flags=0x${window?.attributes?.flags?.let { Integer.toHexString(it) }}" +
                    " imeOptions=${field.imeOptions} inputType=${field.inputType}"
            )
        }.onFailure { log("clip-search: host dialog failed: ${it.message}") }
    }

    /** 搜索结束后回到面板：过滤结果只有面板里才看得到。 */
    private fun reopenPanel(page: Page) {
        val box = if (page == Page.CLIPBOARD) BOX_CLIP else BOX_PHRASE
        val ok = runCatching { openPanel?.invoke(box) }.getOrDefault(false)
        log("clip-search: panel reopened=$ok box=$box page=$page")
    }

    private fun invokeDialogButton(
        builderClass: Class<*>,
        builder: Any,
        methodName: String,
        label: String,
        listener: DialogInterface.OnClickListener,
    ) {
        val method = builderClass.methods.firstOrNull {
            it.name == methodName &&
                it.parameterTypes.size == 2 &&
                CharSequence::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: builderClass.methods.firstOrNull {
            it.name == methodName &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: error("$methodName unavailable")
        val first = if (method.parameterTypes[0] == Int::class.javaPrimitiveType) 0 else label
        method.invoke(builder, first, listener)
    }


    private fun applyKeyword(raw: String) {
        val page = currentPage
        val next = raw.trim().ifEmpty { null }
        if (next == keywordFor(page)) return
        setKeyword(page, next)
        log("clip-search: page=$page keyword=${next ?: "<cleared>"}")
        reloadLists(page)
    }

    /**
     * 让列表按新关键字重新走一遍：**三条路依次尝试**，只要有一条成功，屏幕上就会看到过滤结果。
     *
     *  1. 宿主自己的 `refresh()` —— 1.23.0 真机日志显示这个签名在这版宿主上不存在
     *     （`refresh failed: ...adapter.Q.refresh []`），保留但它已经不是主路；
     *  2. 用同一个 adapter 再 `setAdapter` 一次 —— 强制所有可见行重走 `onBindViewHolder`，
     *     行级过滤因此立即生效。这一条不依赖宿主任何混淆方法名，是当前的主路；
     *  3. `notifyItemRangeChanged` 兜底。
     *
     * 三条各自写日志（`rebind(...) refresh=? setAdapter=? notify=?`），下次一眼就能看出
     * 是哪一条真正在起作用。
     */
    private fun reloadLists(page: Page = currentPage) {
        synchronized(buttons) {
            buttons.keys.forEach { panel -> rebindList(panel, page, "keyword") }
        }
    }

    private fun rebindList(panel: View, page: Page, reason: String) {
        val targetId = if (page == Page.CLIPBOARD) listId else phraseListId
        val recycler = runCatching { panel.findViewById<View>(targetId) }.getOrNull()
            ?: return
        val adapter = runCatching { Reflect.readObject(recycler, "mAdapter") }.getOrNull()
            ?: return

        // 从 0 开始统计这次重绑的过滤结果，收尾日志里会写"藏了几行、留了几行"。
        rowHidden = 0
        rowShown = 0

        val refreshed = runCatching {
            adapter.javaClass.getMethod("refresh").invoke(adapter)
            true
        }.getOrElse { false }

        val rebound = runCatching {
            val loader = recycler.javaClass.classLoader
            val rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader)
            val adapterClass = Class.forName("androidx.recyclerview.widget.RecyclerView\$Adapter", false, loader)
            rvClass.getMethod("setAdapter", adapterClass).invoke(recycler, adapter)
            true
        }.getOrElse {
            log("clip-search: rebind($reason) setAdapter failed: ${it.message}")
            false
        }

        val notified = runCatching {
            val count = adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int ?: 0
            adapter.javaClass
                .getMethod(
                    "notifyItemRangeChanged",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(adapter, 0, count)
            true
        }.getOrElse { false }

        log(
            "clip-search: rebind($reason) page=$page refresh=$refreshed" +
                " setAdapter=$rebound notify=$notified adapter=${adapter.javaClass.name}"
        )
        // 过滤是否真的落到界面上，必须能自证：绑定完成后报一次"藏了几行、留了几行"。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            log(
                "clip-search: row filter summary page=$page kw=${currentKeyword() ?: "<none>"}" +
                    " hidden=$rowHidden shown=$rowShown"
            )
        }, 400L)
    }

    // -------------------------------------------------------------- 分页过滤

    fun installPagingFilter(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        // 分页源把「查到的行」转成「列表项」：受保护、List 进 List 出。
        // 具体是不是分页源，在运行时按继承链判断（链上出现 paging 包名即认为成立），
        // 因此这里不需要写死任何类名。
        val candidates = findMethods(bridge, "paging-convert") {
            matcher {
                paramTypes("java.util.List")
                returnType("java.util.List")
            }
        }
        var installed = 0
        candidates.forEach { convert ->
            val owner = runCatching { convert.declaredClass?.getInstance(hostClassLoader) }
                .getOrNull() ?: return@forEach
            if (!isPagingSource(owner)) return@forEach
            val method = runCatching { convert.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val current = currentKeyword() ?: return
                        val list = param.result as? List<*> ?: return
                        if (list.isEmpty()) return
                        val filtered = list.filter { matches(it, current) }
                        if (filtered.size == list.size) return
                        param.result = filtered
                        log("clip-search: page filtered ${list.size} -> ${filtered.size} kw=$current")
                    }
                })
                installed++
                log("clip-search: paging filter hooked ${convert.declaredClassName}")
            }.onFailure { log("clip-search: paging hook failed: ${it.message}") }
        }
        log("clip-search: convert candidates=${candidates.size} filterHooks=$installed")
    }

    // ------------------------------------------------------ 行级过滤（兜底保证）

    /**
     * 行级过滤：直接挂在列表适配器的 `onBindViewHolder` 上。
     *
     * 分页源那一层（[installPagingFilter]）是「真过滤」，但依赖宿主分页实现的具体形态；
     * 这一层不关心数据从哪来——绑定时拿到条目，不命中就把这一行收成 0 高度并隐藏，
     * 命中则还原原始高度。两层叠加：分页层生效时这里基本无事可做，分页层没接上时这里保证搜得动。
     */
    fun installRowFilter(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates = findMethods(bridge, "paging-adapter") {
            matcher {
                name("onBindViewHolder")
                paramTypes("androidx.recyclerview.widget.RecyclerView\$ViewHolder", "int")
            }
        }
        var installed = 0
        candidates.forEach { bind ->
            val owner = runCatching { bind.declaredClass?.getInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (!isPagingSource(owner)) return@forEach
            val method = runCatching { bind.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val holder = param.args.getOrNull(0) ?: return
                        val position = param.args.getOrNull(1) as? Int ?: return
                        val view = runCatching {
                            holder.javaClass.getMethod("getItemView").invoke(holder) as? View
                        }.getOrNull() ?: return
                        val adapter = param.thisObject ?: return
                        val current = currentKeyword()

                        // 没有关键字：一律还原，保证"取消搜索"后条目全部回来。
                        if (current == null) {
                            applyRowVisibility(view, true)
                            rowShown++
                            return
                        }

                        val item = readItem(adapter, position)
                        if (item == null) {
                            // 读不到条目（占位行、或宿主换了取值形态）时**保持可见**：
                            // 宁可漏过滤，也不能把整屏清空 —— 1.25.0 的真机现象就是"列表看起来垮掉"。
                            applyRowVisibility(view, true)
                            if (!itemUnreadableLogged) {
                                itemUnreadableLogged = true
                                log("clip-search: row item unreadable adapter=${adapter.javaClass.name} pos=$position")
                            }
                            return
                        }

                        val hit = matches(item, current)
                        applyRowVisibility(view, hit)
                        if (hit) rowShown++ else rowHidden++
                    }
                })
                installed++
                log("clip-search: row filter hooked ${bind.declaredClassName}")
            }.onFailure { log("clip-search: row hook failed: ${it.message}") }
        }
        log("clip-search: adapter candidates=${candidates.size} rowHooks=$installed")
    }

    /**
     * 命中 → 还原原始高度；未命中 → 收成 0 高度并隐藏。
     * 原始高度记在 itemView 的 tag 上，避免行被回收复用后还原失真。
     */
    private fun applyRowVisibility(view: View, visible: Boolean) {
        val lp = view.layoutParams ?: return
        if (visible) {
            val saved = view.getTag(ROW_HEIGHT_TAG) as? Int ?: return
            view.setTag(ROW_HEIGHT_TAG, null)
            lp.height = saved
            view.layoutParams = lp
            if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        } else {
            if (view.getTag(ROW_HEIGHT_TAG) == null) view.setTag(ROW_HEIGHT_TAG, lp.height)
            if (lp.height != 0) {
                lp.height = 0
                view.layoutParams = lp
            }
            if (view.visibility != View.GONE) view.visibility = View.GONE
        }
    }

    /**
     * 从列表适配器读某一行的条目。
     *
     * ## 这是 1.25.0 搜索"看起来没用"的直接原因（真机日志 + 宿主形态）
     *
     * 之前用的是 `getMethod("getItem", int)`。而 `PagingDataAdapter#getItem(int)` 是
     * **protected**，`Class.getMethod` 只返回 public 方法 —— 于是每次都抛
     * `NoSuchMethodException`，被 `runCatching` 吞掉，`item` 恒为 null，
     * `matches(null, kw)` 恒为 false，**每一行都被收成 0 高度隐藏**。
     * 用户看到的就是"列表被清空/页面垮掉"，而不是"只剩匹配条目"。
     *
     * 现在按三条路依次取，全部拿到再判断：
     *  1. 公开的 `peek(int)`（分页适配器自带的公开读取口，最稳）；
     *  2. 沿继承链找 `getItem(int)`（含 protected），拿到后解除访问限制再调；
     *  3. 都不行 → 返回 null，调用方按"保持可见"处理，绝不静默清空列表。
     */
    private fun readItem(adapter: Any, position: Int): Any? {
        val cls = adapter.javaClass
        runCatching {
            cls.getMethod("peek", Int::class.javaPrimitiveType).invoke(adapter, position)
        }.onSuccess { return it }

        var current: Class<*>? = cls
        var depth = 0
        while (current != null && current != Any::class.java && depth < 12) {
            val method = runCatching {
                current!!.getDeclaredMethod("getItem", Int::class.javaPrimitiveType)
            }.getOrNull()
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(adapter, position)
                }.getOrNull()
            }
            current = current.superclass
            depth++
        }
        return null
    }

    /** 继承链上出现分页包名即为分页源；只做判定，不改任何行为。 */
    private fun isPagingSource(cls: Class<*>): Boolean {
        var current: Class<*>? = cls
        var depth = 0
        while (current != null && current != Any::class.java && depth < 12) {
            if (current.name.contains("paging", ignoreCase = true)) return true
            current = current.superclass
            depth++
        }
        return false
    }

    /** 条目的任一字符串字段 / 字符串 getter 包含关键字即命中（不依赖任何混淆字段名）。 */
    private fun matches(item: Any?, kw: String): Boolean {
        if (item == null) return false
        var current: Class<*>? = item.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (field.type == String::class.java) {
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(item) as? String
                    }.getOrNull()
                    if (value != null && value.contains(kw, ignoreCase = true)) return true
                }
            }
            // 条目大多是 Kotlin data class，正文可能只暴露成 getter（getContent / getLabel 等），
            // 因此无参 String getter 一并纳入判定。
            current.declaredMethods.forEach { method ->
                if (method.parameterCount == 0 && method.returnType == String::class.java) {
                    val value = runCatching {
                        method.isAccessible = true
                        method.invoke(item) as? String
                    }.getOrNull()
                    if (value != null && value.contains(kw, ignoreCase = true)) return true
                }
            }
            current = current.superclass
        }
        return false
    }

    private fun findMethods(
        bridge: DexKitBridge,
        label: String,
        init: FindMethod.() -> Unit,
    ): List<MethodData> = runCatching { bridge.findMethod(init).toList() }
        .onFailure { log("$label query failed: ${it.message}") }
        .getOrDefault(emptyList())
        .also { log("$label candidates=${it.size}") }

    internal companion object {
        /** 当前显示中的搜索输入条（模块级：任何路径都能把它清掉，防止变成屏幕上的孤儿）。 */
        @Volatile
        var activeBar: View? = null

        /** 当前活动输入条里的输入框：确认/收尾时取它的文字，不依赖视图引用是否还在。 */
        @Volatile
        var activeField: EditText? = null

        /** 本轮重绑里被隐藏 / 保留的行数（用于"过滤真的生效了吗"自证）。 */
        @Volatile
        var rowHidden: Int = 0

        @Volatile
        var rowShown: Int = 0

        /** 条目读不到只提醒一次，避免刷屏。 */
        @Volatile
        var itemUnreadableLogged: Boolean = false

        /** 当前输入条所属的实例：返回键「取消搜索」要用（视图上拿不到实例）。 */
        @Volatile
        var activeInstance: ClipSearch? = null

        /** 搜索输入条是否正显示（供返回键接管判断）。 */
        fun isSearchBarShown(): Boolean = activeBar != null

        /** 让当前输入条执行「取消搜索」；没有活动条时什么都不做。 */
        fun cancelActiveSearch() {
            activeInstance?.cancelSearch()
        }

        /** 输入条根视图上的标记，用于"引用丢了也能扫出来清掉"。 */
        val BAR_TAG: Int = "oplusime_panel_search_bar".hashCode()

        /** 宿主 BoxEnums 里两张页的常量名（语义串，不写死混淆名）。 */
        const val BOX_CLIP = "BOX_CLIP"
        const val BOX_PHRASE = "BOX_PHRASE"

        /** ConstraintLayout.LayoutParams.PARENT_ID */
        const val PARENT_ID = 0

        /** ConstraintLayout.LayoutParams.UNSET */
        const val UNSET = -1

        /** 行级过滤用来暂存「原始行高」的 tag key。 */
        val ROW_HEIGHT_TAG: Int = "oplusime_panel_row_height".hashCode()

        /** 搜索未激活：白底黑字。 */
        val INACTIVE_FG: Int = Color.parseColor("#E5000000")

        /** 搜索激活：浅蓝气泡底 + 蓝字。 */
        const val ACTIVE_BG: Int = 0x1A0A59F7
        val ACTIVE_FG: Int = Color.parseColor("#0A59F7")
    }
}
