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

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader, anchorId: Int) {
        if (installed) return
        if (anchorId == 0) {
            log("clip-edit: skipped (anchor id unresolved)")
            return
        }
        this.anchorId = anchorId
        resolveEntity(hostClassLoader)
        captureWritePath(hostClassLoader)
        installed = true
        log(
            "clip-edit: installed anchor=0x${Integer.toHexString(anchorId)}" +
                " entity=${entityClass?.name} textField=$textFieldName" +
                " writeReady=${writeAdapter != null && roomDb != null}"
        )
        installBindHook(bridge, hostClassLoader)
    }

    /** 定位剪贴板实体类（long id + String 正文 + Uri）。 */
    private fun resolveEntity(hostClassLoader: ClassLoader) {
        val candidates = listOf(
            "com.oplus.keyboard.db.clipboard.a",
            "com.oplus.keyboard.db.clipboard.ClipboardData",
        )
        candidates.forEach { name ->
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull() ?: return@forEach
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
     * 记录写回路径：挂 `db/dao/j` 的构造函数，拿到数据库与写适配器。
     *
     * 用构造钩子而不是 `Class.forName` + 反射构造：那个类的构造参数是数据库实例，
     * 我们没法凭空造一个，但宿主自己构造它的时候会把手里的实例交出来。
     */
    private fun captureWritePath(hostClassLoader: ClassLoader) {
        withConnection = runCatching {
            Class.forName("androidx.room.util.a", false, hostClassLoader)
                .methods
                .firstOrNull {
                    it.name == "j" && it.parameterTypes.size == 4 &&
                        it.returnType == Any::class.java
                }
        }.getOrNull()
        log("clip-edit: withConnection=${withConnection?.name}")
        val ownerCls = runCatching {
            Class.forName("com.oplus.keyboard.db.dao.j", false, hostClassLoader)
        }.getOrNull() ?: run {
            log("clip-edit: dao owner class not present")
            return
        }
        runCatching {
            XposedBridge.hookAllConstructors(ownerCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    bindWritePath(param.thisObject ?: return)
                }
            })
            log("clip-edit: dao owner ctor hooked ${ownerCls.name}")
        }.onFailure { log("clip-edit: owner ctor hook failed: ${it.message}") }
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
            // 写适配器：字段类型是 androidx.room.A 的子类，且 SQL 指向剪贴板表。
            val base = Class.forName("androidx.room.A", false, owner.javaClass.classLoader)
            val entry = base.methods.firstOrNull {
                it.name == "e" && it.parameterTypes.size == 2
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
            if (!owner.startsWith("com.oplus.keyboard")) return@forEach
            val method = runCatching { candidate.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val holder = param.args?.getOrNull(0) ?: return
                        val position = (param.args?.getOrNull(1) as? Int) ?: return
                        val itemView = runCatching {
                            holder.javaClass.getMethod("getItemView").invoke(holder)
                        }.getOrNull() as? View ?: return
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
            logThrottled("clip-edit", 2_000L) {
                "clip-edit: button added index=$index parent=${parent.javaClass.name}"
            }
        }.onFailure { log("clip-edit: add button failed: ${it.message}") }
    }

    /** 照着相邻功能键的外观造按钮，保证和整排风格一致。 */
    private fun buildButton(anchor: View): TextView? {
        val button = TextView(anchor.context)
        button.tag = TAG_EDIT
        button.text = LABEL
        button.gravity = Gravity.CENTER
        button.isClickable = true
        button.isFocusable = true
        button.isSoundEffectsEnabled = false
        if (anchor is TextView) {
            button.setTextColor(anchor.currentTextColor)
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            button.typeface = anchor.typeface
            button.setPadding(anchor.paddingLeft, anchor.paddingTop, anchor.paddingRight, anchor.paddingBottom)
            // 图标沿用「编辑」那条 drawable（拿不到就只显示文字）。
            anchor.compoundDrawables.getOrNull(0)?.let {
                button.setCompoundDrawables(it, null, null, null)
            }
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
            val window = dialog.window
            val token = button.windowToken
            if (window != null && token != null) {
                val attrs = window.attributes
                attrs.token = token
                // 逐字照抄宿主「添加常用语」：type=0x3eb，附加标志含「不抢焦点、不挡输入法」。
                runCatching { attrs.type = 0x3eb }
                runCatching { window.addFlags(0x20002) }
            }
            dialog.show()
            log("clip-edit: dialog shown entity=${entityClass?.name}")
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
}
