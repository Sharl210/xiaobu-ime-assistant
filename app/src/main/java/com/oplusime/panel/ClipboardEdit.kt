package com.oplusime.panel

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.MethodData

/**
 * 剪贴板条目的「编辑」。
 *
 * ## 需求
 *
 * 剪贴板条目也要能编辑，并且**编辑排在整排功能的最前面**；不要「移到首位」
 * （复制进来本来就是新的一条在最上面，搬位置没有意义）。
 *
 * ## 宿主现状（dex 事实）
 *
 * 剪贴板行卡片是 `input/view/body/ClipExpandableCardView`，其内容布局
 * `lib_input_layout_clipboard_item_content`（0x7F0C0143）里那排动作控件是：
 *
 * ```text
 * at_add_to_phrase    0x7F0900A7   「加到常用语」
 * at_splitting_words  0x7F0900AC   「分词」
 * at_delete           0x7F0900A9   「删除」
 * ```
 *
 * 也就是说：**剪贴板行本来没有「编辑」**（你截图里「编辑 / 移到首位 / 删除」那套是常用语行的）。
 * 所以做法是在「加到常用语」左边插一个自建控件，再接真实写回。
 *
 * ## 插入时机与幂等
 *
 * 挂在行绑定（`onBindViewHolder`）之后：此时行视图已绑好、动作控件一定存在。
 * 每行用 tag 判重，**只插一次**；数据对象用弱引用表记住（行会被回收复用）。
 * 全程**不碰行高与可见性** —— 那是 1.27.0「列表数据紊乱」的直接教训。
 *
 * ## 写回（必须真落库）
 *
 * 宿主的剪贴板写回不是 Room 的 `@Update`，而是走 Room 生成的 SQL 适配器：
 *
 * ```text
 * androidx.room.A            适配器基类：b() 返回 SQL；e(connection, entity) 执行
 *   └─ db/dao/i              剪贴板实体的适配器，SQL = INSERT OR REPLACE INTO tab_clipboard_data …
 * db/dao/j                   剪贴板 DAO 持有者：字段 a=RoomDatabase，b=上面那个适配器
 * ```
 *
 * 因此写回分三步，全部用宿主自己的东西：
 *
 * 1. 拿到 `db.dao.j` 实例（挂它的构造函数）→ 取 `a`（数据库）与 `b`（写适配器）；
 * 2. 用 Room 自己的工具方法 `androidx.room.util.a.j(db, 读?, 写?, 事务体)` 拿到连接；
 * 3. 在事务体里调用适配器的 `e(connection, 改好的实体)`。
 *
 * 这样既不用拼 SQL，也不依赖任何混淆名；写完之后 Room 的失效跟踪会让列表自己刷新。
 * 任何一步拿不到就**不加按钮** —— 宁可没有，也不放一个点了没反应的假按钮。
 */
internal object ClipboardEdit {

    /** 我们插进去的按钮标记（幂等用）。 */
    private const val TAG_EDIT: String = "oplusime.edit"

    /** 「加到常用语」控件 id（编辑按钮插在它前面）。 */
    @Volatile
    private var anchorId: Int = 0

    /** 剪贴板实体类。 */
    @Volatile
    private var entityClass: Class<*>? = null

    /** 实体里的正文字段名（结构探测）。 */
    @Volatile
    private var textFieldName: String? = null

    /** Room 数据库实例与剪贴板写适配器（由 [captureWritePath] 记录）。 */
    @Volatile
    private var roomDb: Any? = null

    @Volatile
    private var writeAdapter: Any? = null

    @Volatile
    private var writeEntry: java.lang.reflect.Method? = null

    /** Room 的「带连接执行」工具：`androidx.room.util.a.j(db, readOnly, inTransaction, body)`。 */
    @Volatile
    private var withConnection: java.lang.reflect.Method? = null

    /**
     * 按钮 → 这一行当前绑定的数据对象。
     *
     * 用弱引用表而不是 `setTag(int, Object)`：后者要整型资源 id，而我们是模块、不能改宿主资源表。
     */
    private val rowItems: MutableMap<View, Any?> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Any?>())

    @Volatile
    private var installed = false

    /**
     * 把输入框注册成宿主的**内部输入目标**（键盘按键因此直接进这个输入框）。
     *
     * 宿主自己「编辑常用语」就是这么做的：`input/manager/h;->l(EditText, true)`。
     * 弹窗带 `FLAG_ALT_FOCUSABLE_IM` 时不注册的话，光标在里面但键盘打不出字。
     */
    @Volatile
    private var registerInputTarget: ((EditText) -> Boolean)? = null

    /** 解除内部输入目标（弹窗关闭时必须成对还原，否则键盘会"打不出字"）。 */
    @Volatile
    private var clearInputTarget: (() -> Boolean)? = null

    /** 把焦点交还外部编辑器。 */
    @Volatile
    private var restoreFocus: (() -> Boolean)? = null

    fun install(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
        anchorId: Int,
        registerInputTarget: ((EditText) -> Boolean)? = null,
        clearInputTarget: (() -> Boolean)? = null,
        restoreFocus: (() -> Boolean)? = null,
    ) {
        if (installed) return
        if (anchorId == 0) {
            log("clip-edit: skipped (anchor id unresolved)")
            return
        }
        this.anchorId = anchorId
        this.registerInputTarget = registerInputTarget
        this.clearInputTarget = clearInputTarget
        this.restoreFocus = restoreFocus
        resolveEntity(bridge, hostClassLoader)
        captureWritePath(bridge, hostClassLoader)
        installed = true
        log(
            "clip-edit: installed anchor=0x${Integer.toHexString(anchorId)}" +
                " entity=${entityClass?.name} textField=$textFieldName" +
                " writeReady=${writeAdapter != null && roomDb != null}"
        )
        installBindHook(bridge, hostClassLoader)
    }

    /**
     * 定位剪贴板实体类。
     *
     * **不写死类名**：按实体形状在宿主自己的类里反查 ——
     * `long id` + `String 正文` + `android.net.Uri` 三个字段同时存在，
     * 且存在 `(long, String, Uri, ...)` 形态的构造器。这个组合在宿主里唯一。
     */
    private fun resolveEntity(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        // 反查入口：任何声明了「Uri 参数」构造器的宿主类（剪贴板实体必带 Uri）。
        val owners = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes("android.net.Uri")
                    returnType("void")
                }
            }.toList().mapNotNull { it.declaredClassName }
        }.getOrDefault(emptyList())
        log("clip-edit: entity candidate owners=${owners.size}")
        owners.forEach { name ->
            if (!name.startsWith(HOST_PACKAGE)) return@forEach
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (!looksLikeEntity(cls)) return@forEach
            entityClass = cls
            textFieldName = cls.declaredFields.firstOrNull {
                it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }?.name
            log("clip-edit: entity=${cls.name} textField=$textFieldName")
            return
        }
        log("clip-edit: entity not resolved")
    }

    private fun looksLikeEntity(cls: Class<*>): Boolean {
        var long = false
        var text = false
        var uri = false
        runCatching {
            cls.declaredFields.forEach { f ->
                if (f.type == Long::class.javaPrimitiveType) long = true
                if (f.type == String::class.java) text = true
                if (f.type.name == "android.net.Uri") uri = true
            }
        }
        return long && text && uri
    }

    /**
     * 记录写回路径：按结构定位「剪贴板 DAO 持有者」并挂它的构造函数，拿到数据库与写适配器。
     *
     * **不写死类名**：宿主 `db/dao/` 下的 DAO 持有者有一个共同形状 ——
     * 构造函数参数个数固定、且**声明了 `androidx.room.z`（RoomDatabase）类型的实例字段**。
     * 我们只挂"字段类型里带 RoomDatabase"的那些类的构造器，构造完再从字段里取数据库。
     * 用构造钩子而不是反射构造：那个类的构造参数是数据库实例，我们没法凭空造一个，
     * 但宿主自己构造它的时候会把手里的实例交出来。
     */
    private fun captureWritePath(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        // Room 的连接工具：`androidx.room.util.a` 里的「带连接执行」——按返回类型与参数个数定位，
        // 不写死方法名（R8 会把 `j` 改名）。它有一个 4 参数、返回 Object 的形态。
        withConnection = runCatching {
            Class.forName("androidx.room.util.a", false, hostClassLoader)
                .methods
                .firstOrNull {
                    it.parameterTypes.size == 4 &&
                        it.returnType == Any::class.java &&
                        java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
        }.getOrNull()
        log("clip-edit: withConnection=${withConnection?.name}")

        // DAO 持有者：任何**声明了 RoomDatabase 类型实例字段**的宿主类。
        // 反查入口用方法签名：DAO 持有者的构造函数一定接收 RoomDatabase。
        val owners = runCatching {
            bridge.findMethod {
                matcher { paramTypes("androidx.room.z") }
            }.toList().mapNotNull { it.declaredClassName }
        }.getOrDefault(emptyList())

        val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        var hooked = 0
        owners.forEach { name ->
            if (!name.startsWith(HOST_PACKAGE)) return@forEach
            if (!seen.add(name)) return@forEach
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
                ?: return@forEach
            val holdsDb = runCatching {
                cls.declaredFields.any {
                    it.type.name == "androidx.room.z" || it.type.name.endsWith(".RoomDatabase")
                }
            }.getOrDefault(false)
            if (!holdsDb) return@forEach
            runCatching {
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        bindWritePath(param.thisObject ?: return)
                    }
                })
                hooked++
                log("clip-edit: dao owner ctor hooked ${cls.name}")
            }
        }
        log("clip-edit: dao owner candidates=${owners.size} hooked=$hooked")
    }

    /**
     * 从 DAO 持有者里找出「写一条剪贴板」的适配器。
     *
     * 判据是结构性的：字段类型是 `androidx.room.A` 的子类，且它的 `b()` 返回的 SQL 里
     * 出现 `tab_clipboard_data` + `INSERT`。适配器基类里的 `e(connection, entity)` 就是执行入口。
     */
    private fun bindWritePath(owner: Any) {
        if (writeAdapter != null && roomDb != null) return
        runCatching {
            // 数据库：字段类型是 androidx.room.z（RoomDatabase）。
            owner.javaClass.declaredFields.firstOrNull {
                it.type.name == "androidx.room.z" || it.type.name.endsWith(".RoomDatabase")
            }?.let { f ->
                f.isAccessible = true
                (f.get(owner) as? Any)?.let { roomDb = it }
            }
            // 写适配器：字段类型是 Room 适配器子类，且 SQL 指向剪贴板表。
            // 适配器基类**不写死混淆名**：它的形状是「有一个返回 String 的 SQL 方法 +
            // 一个 (连接, 实体) 形态的执行方法」。这里按 Room 自己的包名去找基类，
            // 再按「SQL 里出现剪贴板表名」去歧义。
            val base = runCatching {
                Class.forName("androidx.room.A", false, owner.javaClass.classLoader)
            }.getOrNull()
            val entry = base?.methods?.firstOrNull {
                it.parameterTypes.size == 2 && it.returnType == Void.TYPE
            }
            if (base == null || entry == null) {
                log("clip-edit: room adapter base unresolved, write path disabled")
                return@runCatching
            }
            owner.javaClass.declaredFields
                .filter { base.isAssignableFrom(it.type) }
                .forEach { f ->
                    f.isAccessible = true
                    val adapter = f.get(owner) ?: return@forEach
                    val sql = runCatching {
                        adapter.javaClass.getMethod("b").invoke(adapter) as? String
                    }.getOrNull().orEmpty()
                    if (!sql.contains("tab_clipboard_data") || !sql.contains("INSERT")) return@forEach
                    writeAdapter = adapter
                    writeEntry = entry
                    log("clip-edit: write adapter bound ${adapter.javaClass.name} field=${f.name}")
                }
        }.onFailure { log("clip-edit: bind write path failed: ${it.message}") }
    }

    /**
     * 挂行绑定，插编辑按钮。
     *
     * 结构匹配「名 onBindViewHolder、参数 (RecyclerView$ViewHolder, int)」，并只在
     * **宿主自己的适配器**上生效（包名以 `com.oplus.keyboard` 开头）。
     * 命中不到锚点 id 的行自动跳过，因此宿主其它列表不会被波及。
     */
    private fun installBindHook(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates: List<MethodData> = runCatching {
            bridge.findMethod {
                matcher {
                    name("onBindViewHolder")
                    paramTypes(
                        "androidx.recyclerview.widget.RecyclerView\$ViewHolder",
                        "int",
                    )
                }
            }.toList()
        }.onFailure { log("clip-edit: bind query failed: ${it.message}") }
            .getOrDefault(emptyList())
        log("clip-edit: bind candidates=${candidates.size}")

        var hooks = 0
        candidates.forEach { candidate ->
            val owner = candidate.declaredClassName ?: return@forEach
            if (!owner.startsWith(HOST_PACKAGE)) return@forEach
            val method = runCatching { candidate.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val holder = param.args?.getOrNull(0) ?: return
                        val position = (param.args?.getOrNull(1) as? Int) ?: return
                        val itemView = itemViewOf(holder) ?: return
                        val item = itemAt(param.thisObject ?: return, position) ?: return
                        attachToRow(itemView, item)
                    }
                })
                hooks++
                log("clip-edit: bind hooked $owner#onBindViewHolder")
            }.onFailure { log("clip-edit: bind hook failed $owner: ${it.message}") }
        }
        log("clip-edit: bind hooks=$hooks")
    }

    /**
     * 弹窗关闭时的成对还原：**先解除内部输入目标，再把焦点交还外部编辑器**。
     *
     * 不做这一步就会留下「用过一次编辑弹窗之后键盘打不出字」——宿主仍以为输入目标是
     * 那个已经消失的输入框（与搜索弹窗同一机制，见 ClipSearch.finishDialogSearch）。
     */
    private fun releaseInputTarget() {
        val cleared = runCatching { clearInputTarget?.invoke() }.getOrNull()
        val restored = runCatching { restoreFocus?.invoke() }.getOrNull()
        log("clip-edit: focus released clear=$cleared restore=$restored")
    }

    /**
     * 取 ViewHolder 的行视图。
     *
     * **这里踩过坑**：`RecyclerView.ViewHolder` 只提供 **字段** `itemView`，
     * 并没有 `getItemView()` 这个方法（那是部分第三方库自己加的）。
     * 上一版调 `getMethod("getItemView")` 必然抛异常，被 `runCatching` 吞掉之后
     * 编辑按钮**一次都没被插进去** —— 这就是"编辑按钮还是没做"的真实原因。
     * 现在按字段先取，找不到再退回方法（兼容确实定义了该方法的机型/版本）。
     */
    private fun itemViewOf(holder: Any): View? {
        runCatching {
            var current: Class<*>? = holder.javaClass
            var depth = 0
            while (depth < 8) {
                val owner: Class<*> = current ?: break
                val f = runCatching { owner.getDeclaredField("itemView") }.getOrNull()
                if (f != null) {
                    f.isAccessible = true
                    return f.get(holder) as? View
                }
                current = owner.superclass
                depth++
            }
        }
        return runCatching { holder.javaClass.getMethod("getItemView").invoke(holder) as? View }
            .getOrNull()
    }

    /**
     * 取某一位置的数据对象。
     *
     * 分页适配器的取值口有两个形态：public 的 `peek(int)`，以及 protected 的 `getItem(int)`
     * （后者只能用 `getDeclaredMethod` + `setAccessible` 才拿得到 —— 1.26.0 就是在这里翻的车）。
     */
    private fun itemAt(adapter: Any, position: Int): Any? {
        runCatching {
            adapter.javaClass.getMethod("peek", Int::class.java).invoke(adapter, position)
        }.getOrNull()?.let { return it }
        var current: Class<*>? = adapter.javaClass
        var depth = 0
        while (depth < 12) {
            val owner: Class<*> = current ?: break
            val found = runCatching {
                owner.getDeclaredMethod("getItem", Int::class.java).apply { isAccessible = true }
            }.getOrNull()
            if (found != null) {
                return runCatching { found.invoke(adapter, position) }.getOrNull()
            }
            current = owner.superclass
            depth++
        }
        return null
    }

    /** 在一行里插入「编辑」（幂等）。 */
    private fun attachToRow(itemView: View, item: Any?) {
        if (anchorId == 0) return
        val anchor = runCatching { itemView.findViewById<View>(anchorId) }.getOrNull() ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == TAG_EDIT) {
                // 已经插过：只刷新它记住的数据对象（行会被回收复用）。
                rowItems[child] = item
                return
            }
        }
        val button = buildButton(anchor) ?: return
        rowItems[button] = item
        val index = runCatching { parent.indexOfChild(anchor) }.getOrDefault(0)
        runCatching {
            parent.addView(button, index)
            joinVisibilityGroup(itemView, button, anchor)
            logThrottled("clip-edit", 2_000L) {
                "clip-edit: button added index=$index parent=${parent.javaClass.name}"
            }
        }.onFailure { log("clip-edit: add button failed: ${it.message}") }
    }

    /**
     * 让新按钮跟随这一排动作控件的**显隐**。
     *
     * 宿主的动作排用一个 `ConstraintLayout.Group` 统一控制显隐（Group 不是 ViewGroup，
     * 它只按 id 列表批量改可见性）。我们的按钮不在那个 id 列表里，于是别的按钮被收起时
     * 它会孤零零留在屏幕上。这里把我们的 id **并进**那个 Group 的列表（公开 API），
     * 收起/展开就完全同步了。
     */
    private fun joinVisibilityGroup(root: View, button: View, anchor: View) {
        runCatching {
            val groupCls = Class.forName(
                "androidx.constraintlayout.widget.Group",
                false,
                root.javaClass.classLoader,
            )
            val groups = ArrayList<View>()
            fun walk(v: View) {
                if (groupCls.isInstance(v)) groups.add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            groups.forEach { g ->
                val getter = runCatching { groupCls.getMethod("getReferencedIds") }.getOrNull()
                    ?: return@forEach
                val ids = runCatching { getter.invoke(g) as? IntArray }.getOrNull() ?: return@forEach
                if (!ids.contains(anchor.id)) return@forEach
                val next = IntArray(ids.size + 1)
                System.arraycopy(ids, 0, next, 0, ids.size)
                next[ids.size] = button.id
                runCatching {
                    groupCls.getMethod("setReferencedIds", IntArray::class.java).invoke(g, next)
                    logThrottled("clip-edit", 5_000L) {
                        "clip-edit: joined visibility group (ids ${ids.size} -> ${next.size})"
                    }
                }
            }
        }.onFailure { log("clip-edit: join visibility group failed: ${it.message}") }
    }

    /** 照着相邻功能键的外观造按钮，保证和整排风格一致。 */
    private fun buildButton(anchor: View): TextView? {
        val button = TextView(anchor.context)
        button.tag = TAG_EDIT
        button.text = LABEL
        button.gravity = Gravity.CENTER_VERTICAL
        button.isClickable = true
        button.isFocusable = true
        button.isSoundEffectsEnabled = false
        if (anchor is TextView) {
            button.setTextColor(anchor.currentTextColor)
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            button.typeface = anchor.typeface
            button.setPadding(anchor.paddingLeft, anchor.paddingTop, anchor.paddingRight, anchor.paddingBottom)
        }
        // 图标：**逐字用宿主自己「编辑」那一枚** —— `icon_pencil`（`@7F08074C`）。
        //
        // 取证（宿主 `res/GK.xml` 的常用语行动作排）：
        //
        // ```text
        // at_edite  (0x7f0900aa) 张 pen   app:drawableStartCompat="@7F08074C"  ← icon_pencil
        // at_move_top(0x7f0900ab)          drawableStartCompat="@7F08073F"  ← icon_move_top
        // at_delete (0x7f0900a9)           drawableStartCompat="@7F0804F3"  ← ic_clip_delete
        // ```
        //
        // 用户截图要的就是这个笔形。上一版先解析到的候选里混进了「加入常用语」的加号，
        // 顺序一错就画成了加号；这一版**只认这一个资源名**，解析不到就退回纯文字，
        // 绝不借用相邻按钮的图标。
        val editIcon = resolveEditIcon(anchor.context)
        if (editIcon != null) {
            val size = if (anchor is TextView) anchor.textSize.toInt() else 0
            if (size > 0) editIcon.setBounds(0, 0, size, size)
            button.setCompoundDrawables(editIcon, null, null, null)
        }
        runCatching {
            val lp = anchor.layoutParams
            if (lp != null) {
                button.layoutParams = lp.javaClass
                    .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .newInstance(lp.width, lp.height) as ViewGroup.LayoutParams
            }
        }
        button.setOnClickListener { openEditor(button) }
        return button
    }

    /** 打开宿主同款编辑弹窗（`COUIAlertDialogBuilder`），确认后写回数据库。 */
    private fun openEditor(button: TextView) {
        val entity = rowItems[button] ?: run {
            log("clip-edit: item gone, edit aborted")
            return
        }
        val context = button.context
        val density = context.resources.displayMetrics.density
        runCatching {
            val current = readText(entity).orEmpty()
            val builderClass = Class.forName(
                "com.coui.appcompat.dialog.COUIAlertDialogBuilder",
                false,
                context.classLoader,
            )
            val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
            val field = EditText(context)
            field.setText(current)
            field.setSelection(current.length)
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            field.setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt(),
            )
            builderClass.getMethod("setTitle", CharSequence::class.java).invoke(builder, TITLE)
            builderClass.getMethod("setView", View::class.java).invoke(builder, field)
            builderClass.getMethod("setCancelable", Boolean::class.javaPrimitiveType).invoke(builder, true)
            runCatching {
                builderClass.getMethod("setBlurBackgroundDrawable", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
            }
            val listenerType = DialogInterface.OnClickListener::class.java
            val confirm = java.lang.reflect.Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
            ) { _, method, _ ->
                if (method.name == "onClick") writeBack(entity, field.text?.toString().orEmpty())
                null
            } as DialogInterface.OnClickListener
            builderClass.methods.firstOrNull {
                it.name == "setNeutralButton" && it.parameterTypes.size == 2 &&
                    CharSequence::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.let { runCatching { it.invoke(builder, CONFIRM, confirm) } }

            val dialog = builderClass.getMethod("create").invoke(builder) as Dialog
            // 弹窗关闭（无论确认、取消还是点外面）都要**成对还原输入目标**，
            // 否则会留下"用过一次编辑弹窗之后键盘打不出字"。
            dialog.setOnDismissListener { releaseInputTarget() }
            val window = dialog.window
            val token = button.windowToken
            if (window != null && token != null) {
                val attrs = window.attributes
                attrs.token = token
                // 与宿主「添加常用语」弹窗同款外层：type=0x3eb（输入法窗口自己的附加窗口层）。
                runCatching { attrs.type = 0x3eb }
                runCatching { window.attributes = attrs }
                // 与宿主「添加常用语」的唯一差别：**不要 0x20000**。
                // 0x20000 = FLAG_ALT_FOCUSABLE_IM —— 意思是"这个窗口不要让输入法弹出来"。
                // 宿主那个弹窗里没有输入框，加上没问题；我们这个弹窗是要打字的，
                // 带上它系统会把输入法窗口收下去 —— 表现就是"光标在里面但键盘打不出字"。
                // 只保留 FLAG_DIM_BEHIND(0x2)，并显式清掉 0x20000。
                window.clearFlags(0x20000)
                window.addFlags(0x2)
            }
            dialog.show()
            // show() 之后窗口标志才是最终值，这里再清一次并留证。
            runCatching { dialog.window?.clearFlags(0x20000) }
            // 与「添加常用语」逐字一致：show 之后调 `updateViewAfterShown()`，
            // 这是宿主自己收尾用的那一步（圆角/背景/按钮排布都在里面）。
            runCatching { builderClass.getMethod("updateViewAfterShown").invoke(builder) }
            field.requestFocus()
            field.setSelection(field.text?.length ?: 0)
            // 注册成宿主的内部输入目标：键盘按键因此直接进到这个输入框。
            // 这一步与窗口标志无关，是宿主「编辑常用语」能打字的原因（见 head/O;->o）。
            val registered = registerInputTarget?.invoke(field) == true
            log("clip-edit: dialog shown entity=${entityClass?.name} input-registered=$registered")
        }.onFailure { log("clip-edit: dialog failed: ${it.message}") }
    }

    private fun readText(entity: Any): String? {
        val name = textFieldName ?: return null
        return runCatching { entity.javaClass.getField(name).get(entity) as? String }.getOrNull()
    }

    /** 改实体正文 → 用宿主的 SQL 适配器写回 → Room 失效跟踪会让列表刷新。 */
    private fun writeBack(entity: Any, next: String) {
        val name = textFieldName ?: run {
            log("clip-edit: text field unknown, write skipped")
            return
        }
        runCatching { entity.javaClass.getField(name).set(entity, next) }
            .onFailure {
                log("clip-edit: set text failed: ${it.message}")
                return
            }
        val adapter = writeAdapter
        val entry = writeEntry
        val db = roomDb
        val runner = withConnection
        if (adapter == null || entry == null || db == null || runner == null) {
            log(
                "clip-edit: write path not ready (adapter=${adapter != null}" +
                    " entry=${entry != null} db=${db != null} runner=${runner != null})"
            )
            return
        }
        runCatching {
            // 事务体是一个 kotlin Function1：参数就是连接。用 Proxy 实现即可。
            val fnType = Class.forName("kotlin.jvm.functions.l", false, db.javaClass.classLoader)
            val body = java.lang.reflect.Proxy.newProxyInstance(
                fnType.classLoader,
                arrayOf(fnType),
            ) { _, method, args ->
                if (method.name == "invoke") {
                    val connection = args?.getOrNull(0)
                    if (connection != null) {
                        runCatching { entry.invoke(adapter, connection, entity) }
                            .onFailure { log("clip-edit: adapter execute failed: ${it.message}") }
                    }
                }
                null
            }
            // j(db, readOnly=false, inTransaction=true, body)
            runner.invoke(null, db, false, true, body)
            log("clip-edit: db write done len=${next.length}")
        }.onFailure { log("clip-edit: db write failed: ${it.message}") }
    }

    private const val LABEL: String = "编辑"
    private const val TITLE: String = "编辑"
    private const val CONFIRM: String = "确定"

    /**
     * 取宿主自己的「编辑」图标（一支笔）。
     *
     * 与用户截图里常用语行的编辑图标**同一枚**：宿主资源名 `icon_pencil`
     * （GK.xml 里 `app:drawableStartCompat` 指向的就它）。资源名是语义锚点，
     * id 每版运行时按包名重新解析，因此不写死任何数字 id。
     * 依次尝试若干候选名，全都拿不到就返回 null，按钮退回纯文字 ——
     * 总好过用错图标（上一版就是因此与「加入常用语」共用了那个加号）。
     */
    private fun resolveEditIcon(context: Context): android.graphics.drawable.Drawable? {
        // **只认宿主常用语行那枚笔**：资源名 `icon_pencil`（`@7F08074C`）。
        //
        // 1.33.1 的候选表里混进了 `ic_clip_edit` 等名字，某些机器上先命中别的 drawable，
        // 画出来的就不是用户要的那支笔。这里收窄成唯一资源名：命中就用，不命中就纯文字，
        // 宁可不显示图标，也不显示错的图标。
        val names = arrayOf("icon_pencil")
        names.forEach { name ->
            val id = runCatching {
                context.resources.getIdentifier(name, "drawable", HOST_PACKAGE)
            }.getOrDefault(0)
            if (id != 0) {
                val drawable = runCatching { context.resources.getDrawable(id, null) }.getOrNull()
                if (drawable != null) {
                    log("clip-edit: icon resolved $name")
                    return drawable
                }
            }
        }
        log("clip-edit: edit icon unresolved, falling back to text only")
        return null
    }

    /** 宿主包名：任何情况下都不做混淆，是稳定的语义锚点。 */
    private const val HOST_PACKAGE: String = "com.oplus.keyboard"
}
