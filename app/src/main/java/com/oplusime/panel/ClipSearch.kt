package com.oplusime.panel

import android.content.Context
import android.content.DialogInterface
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
        showDialog(anchor)
    }

    private fun showDialog(anchor: View) {
        runCatching {
            val context = anchor.context
            val density = context.resources.displayMetrics.density
            val builderClass = Class.forName("com.coui.appcompat.dialog.COUIAlertDialogBuilder", false, context.classLoader)
            val builder = builderClass.getConstructor(android.content.Context::class.java).newInstance(context)
            val field = createInputField?.invoke(context) ?: EditText(context)
            field.hint = "输入要搜索的关键字"
            field.isFocusableInTouchMode = true
            field.setShowSoftInputOnFocus(true)
            field.setSingleLine(true)
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
                }
                null
            } as DialogInterface.OnClickListener
            val cancel = java.lang.reflect.Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
            ) { _, method, _ ->
                if (method.name == "onClick") dialogBox[0]?.dismiss()
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
                window.addFlags(0x20002)
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setDimAmount(0.3f)
            }
            dialog.show()
            runCatching { builderClass.getMethod("updateViewAfterShown").invoke(builder) }
            field.requestFocus()
            field.setSelection(field.text?.length ?: 0)
            val registered = registerInputTarget?.invoke(field) == true
            field.postDelayed({
                runCatching {
                    val imm = context.getSystemService(InputMethodManager::class.java)
                    imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 120L)
            log("clip-search: host dialog shown, input-registered=$registered windowType=${window?.attributes?.type}")
        }.onFailure { log("clip-search: host dialog failed: ${it.message}") }
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
     * 让列表按新关键字重新走一遍：
     *  1. `refresh()` 让分页层重新取数；
     *  2. 再触发一次重新绑定，让行级过滤对当前已加载的行重算。
     * PagingDataAdapter 禁用了 `notifyDataSetChanged`，只能用 `notifyItemRangeChanged`。
     */
    private fun reloadLists(page: Page = currentPage) {
        synchronized(buttons) {
            buttons.keys.forEach { panel ->
                val targetId = if (page == Page.CLIPBOARD) listId else phraseListId
                val recycler = runCatching { panel.findViewById<ViewGroup>(targetId) }.getOrNull()
                    ?: return@forEach
                val adapter = runCatching { Reflect.readObject(recycler, "mAdapter") }.getOrNull()
                    ?: return@forEach
                runCatching {
                    adapter.javaClass.getMethod("refresh").invoke(adapter)
                    log("clip-search: adapter refreshed page=$page")
                }.onFailure { log("clip-search: refresh failed: ${it.message}") }
                runCatching {
                    val count = adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int ?: 0
                    adapter.javaClass
                        .getMethod(
                            "notifyItemRangeChanged",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                        )
                        .invoke(adapter, 0, count)
                }.onFailure { log("clip-search: rebind failed: ${it.message}") }
            }
        }
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
                        val item = runCatching {
                            param.thisObject.javaClass
                                .getMethod("getItem", Int::class.javaPrimitiveType)
                                .invoke(param.thisObject, position)
                        }.getOrNull()
                        val current = currentKeyword()
                        applyRowVisibility(view, current == null || matches(item, current))
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

    private companion object {
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
