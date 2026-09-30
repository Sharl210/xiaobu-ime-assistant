package com.oplusime.panel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.util.Collections
import java.util.WeakHashMap

/**
 * 剪贴板面板的「搜索」按钮与条目过滤。
 *
 * 位置：剪贴板面板底部的计数行（`tv_clip_count`，即 “n/∞” 那一行）最右侧，
 * 白底圆角气泡 + 「搜索」两字。
 *
 * 过滤落点：剪贴板列表由分页数据源驱动，分页源会把查到的行「转换」成列表项
 * （`convertRows(List) -> List`，宿主里由生成类实现）。在这个转换结果上做过滤，
 * 既不需要重建分页结果对象，也不依赖任何混淆名：只按“结果里每个字符串字段是否包含关键字”
 * 判断，因此条目的文本或链接任一命中都算命中。
 *
 * 关键字为空的页面原样返回；命中的目标类限定为发起搜索时列表里已有条目的类型，
 * 避免影响同进程内其它分页列表。
 */
internal class ClipSearch(
    private val counterId: Int,
    private val listId: Int,
    private val label: String,
    /** 创建宿主同款输入框（拿不到时退回普通输入框）。 */
    private val createInputField: ((android.content.Context) -> EditText)? = null,
    /** 把输入框注册成宿主的当前输入目标；返回是否成功。 */
    private val registerInputTarget: ((EditText) -> Boolean)? = null,
) {
    @Volatile
    private var keyword: String? = null

    private val panels: MutableMap<View, View> =
        Collections.synchronizedMap(WeakHashMap())

    // ------------------------------------------------------------------ UI

    /** 面板每次排布后调用；按钮只在第一次创建。 */
    fun attach(panel: ViewGroup) {
        val counter = panel.findViewById<View>(counterId) ?: run {
            log("clip-search: counter 0x${Integer.toHexString(counterId)} not found in panel")
            return
        }
        val existing = panels[panel]
        if (existing != null && existing.parent === panel) {
            alignToCounter(existing, counter)
            bindCounter(counter, existing)
            applyActiveStyle(existing)
            return
        }
        val button = createButton(counter) ?: return
        panel.addView(button)
        alignToCounter(button, counter)
        bindCounter(counter, button)
        panels[panel] = button
        applyActiveStyle(button)
        log("clip-search: button attached id=0x${Integer.toHexString(button.id)}")
    }

    private fun createButton(template: View): View? {
        val context = template.context
        val button = runCatching {
            val cls = template.javaClass
            val ctor = cls.constructors.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == android.content.Context::class.java
            }
            (ctor?.newInstance(context) as? TextView)
        }.getOrNull() ?: runCatching { TextView(context) }.getOrNull() ?: return null

        button.id = View.generateViewId()
        button.text = label
        button.gravity = Gravity.CENTER
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        button.isClickable = true
        button.isFocusable = true
        applyButtonStyle(button, active = false)
        button.setOnClickListener { onButtonClicked(it) }
        val lp = template.layoutParams
        if (lp != null) {
            runCatching { button.layoutParams = lp.javaClass.getConstructor().newInstance() as ViewGroup.LayoutParams }
        }
        return button
    }

    private fun alignToCounter(button: View, counter: View) {
        val lp = button.layoutParams ?: return
        Reflect.writeInt(lp, "startToStart", UNSET)
        Reflect.writeInt(lp, "startToEnd", UNSET)
        Reflect.writeInt(lp, "endToStart", UNSET)
        Reflect.writeInt(lp, "endToEnd", PARENT_ID)
        Reflect.writeInt(lp, "topToTop", counter.id)
        Reflect.writeInt(lp, "bottomToBottom", counter.id)
        Reflect.writeInt(lp, "leftToLeft", UNSET)
        Reflect.writeInt(lp, "rightToRight", UNSET)
        if (lp is ViewGroup.MarginLayoutParams) {
            // 必须显式给尺寸：约束布局的参数默认宽高可能是 0dp（MATCH_CONSTRAINT），
            // 那样按钮会被算成 0 宽而完全看不见。
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.marginEnd = (8 * counter.resources.displayMetrics.density).toInt()
        }
        button.layoutParams = lp
        button.requestLayout()
    }

    // -------------------------------------------------------------- 搜索开关与弹窗

    /**
     * 点一次：进入搜索（弹窗输入关键字）。
     * 再点一次：退出搜索，恢复全部条目，按钮回到未激活样式。
     */
    private fun onButtonClicked(anchor: View) {
        if (keyword != null) {
            applyKeyword("")
            applyActiveStyle(anchor)
            log("clip-search: search cleared by second tap")
            return
        }
        showInput(anchor)
    }

    /** 未激活＝白底气泡黑字；激活＝浅蓝气泡蓝字（可一眼看出正在过滤）。 */
    private fun applyButtonStyle(button: TextView, active: Boolean) {
        val density = button.resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (4 * density).toInt()
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
        applyButtonStyle(text, active = keyword != null)
    }

    /**
     * 计数行与按钮「左右护法」：计数留在左端（它自身的 start 锚在 parent 上），
     * 搜索按钮贴右边缘，计数器的右端让给按钮。
     *
     * **每次排布都重写一次**：宿主的布局流程会自己重算计数控件的约束，只写一次会被覆盖回去，
     * 表现为按钮位置乱跑。写入是幂等的（目标状态唯一），不会反复触发重排放大。
     */
    private fun bindCounter(counter: View, button: View) {
        val lp = counter.layoutParams ?: return
        runCatching {
            var dirty = false
            if (Reflect.readInt(lp, "endToEnd") != UNSET) {
                Reflect.writeInt(lp, "endToEnd", UNSET)
                dirty = true
            }
            if (Reflect.readInt(lp, "rightToRight") != UNSET) {
                Reflect.writeInt(lp, "rightToRight", UNSET)
                dirty = true
            }
            if (Reflect.readInt(lp, "endToStart") != button.id) {
                Reflect.writeInt(lp, "endToStart", button.id)
                dirty = true
            }
            if (dirty) counter.layoutParams = lp
        }.onFailure { log("clip-search: bind counter failed: ${it.message}") }
    }

    /**
     * 搜索弹窗：点按钮弹出 → 输入关键字 → 点「搜索」才开始过滤。
     *
     * 为什么不能只丢一个输入框就完事：输入法本身就是"输入源"，系统不会为输入法进程的窗口
     * 弹出软键盘，所以"光标放上去键盘自己出来"在 IME 进程内走不通。这里的做法是——弹窗显示后
     * 把输入框**注册成宿主自己的输入目标**（宿主自带一套 IME 内编辑框的输入链路，它自己的
     * 常用语编辑界面用的就是这条链路），注册成功后键盘输入就会进入这个输入框。
     * 注册失败时如实记日志，弹窗上的「取消 / 搜索」与后续过滤不受影响。
     */
    private fun showInput(anchor: View) {
        runCatching {
            val context = anchor.context
            val density = context.resources.displayMetrics.density
            val field = createInputField?.invoke(context) ?: EditText(context)
            field.hint = "输入要搜索的关键字"
            field.setSingleLine()
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            field.setText(keyword ?: "")
            field.setPaddingRelative(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt(),
            )
            val confirm = TextView(context).apply {
                text = "搜索"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ACTIVE_FG)
                setPadding(
                    (18 * density).toInt(), (10 * density).toInt(),
                    (18 * density).toInt(), (10 * density).toInt(),
                )
                isClickable = true
            }
            val cancel = TextView(context).apply {
                text = "取消"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(INACTIVE_FG)
                setPadding(
                    (18 * density).toInt(), (10 * density).toInt(),
                    (18 * density).toInt(), (10 * density).toInt(),
                )
                isClickable = true
            }
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(cancel)
                addView(confirm)
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (14 * density).toInt(), (12 * density).toInt(),
                    (14 * density).toInt(), (8 * density).toInt(),
                )
                addView(field)
                addView(actions)
            }
            val popup = PopupWindow(
                column,
                (260 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            )
            popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 12 * density
                },
            )
            confirm.setOnClickListener {
                applyKeyword(field.text?.toString().orEmpty())
                applyActiveStyle(anchor)
                popup.dismiss()
            }
            cancel.setOnClickListener { popup.dismiss() }
            popup.showAsDropDown(anchor, 0, -(anchor.height + (72 * density).toInt()))
            field.requestFocus()
            val registered = runCatching { registerInputTarget?.invoke(field) ?: false }
                .getOrDefault(false)
            log("clip-search: dialog shown, host-input-registered=$registered")
        }.onFailure { log("clip-search: input dialog failed: ${it.message}") }
    }

    private fun applyKeyword(raw: String) {
        val next = raw.trim().ifEmpty { null }
        if (next == keyword) return
        keyword = next
        log("clip-search: keyword=${next ?: "<cleared>"}")
        reloadLists()
    }

    /**
     * 让列表按新关键字重新走一遍：
     *  1. `refresh()` 让分页层重新取数（关键字变化后分页过滤才会重新生效）；
     *  2. 再触发一次重新绑定，让行级过滤对当前已加载的行重算。
     * PagingDataAdapter 禁用了 `notifyDataSetChanged`，只能用 `notifyItemRangeChanged`。
     */
    private fun reloadLists() {
        synchronized(panels) {
            panels.keys.forEach { panel ->
                val recycler = runCatching { panel.findViewById<ViewGroup>(listId) }.getOrNull()
                    ?: return@forEach
                val adapter = runCatching { Reflect.readObject(recycler, "mAdapter") }.getOrNull()
                    ?: return@forEach
                runCatching {
                    adapter.javaClass.getMethod("refresh").invoke(adapter)
                    log("clip-search: adapter refreshed")
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
                        val current = keyword ?: return
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
     *
     * 适配器用继承链判定（链上出现 paging 包名即认为成立），条目的取用通过反射调用其
     * `getItem(int)`，因此不写死任何宿主混淆名。
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
                        val current = keyword
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

    private fun findClasses(
        bridge: DexKitBridge,
        label: String,
        init: FindClass.() -> Unit,
    ): List<ClassData> = runCatching { bridge.findClass(init).toList() }
        .onFailure { log("$label query failed: ${it.message}") }
        .getOrDefault(emptyList())
        .also { log("$label candidates=${it.size}") }

    private companion object {
        /** ConstraintLayout.LayoutParams.PARENT_ID */
        const val PARENT_ID = 0

        /** ConstraintLayout.LayoutParams.UNSET */
        const val UNSET = -1

        /** Room 分页包装查询的固定前缀（库层字符串，非宿主混淆名）。 */
        const val PAGING_WRAPPER_SQL = "SELECT * FROM ("

        /** 行级过滤用来暂存「原始行高」的 tag key。 */
        val ROW_HEIGHT_TAG: Int = "oplusime_panel_row_height".hashCode()

        /** 搜索未激活：白底黑字。 */
        val INACTIVE_FG: Int = Color.parseColor("#E5000000")

        /** 搜索激活：浅蓝气泡底 + 蓝字。 */
        const val ACTIVE_BG: Int = 0x1A0A59F7
        val ACTIVE_FG: Int = Color.parseColor("#0A59F7")
    }
}
