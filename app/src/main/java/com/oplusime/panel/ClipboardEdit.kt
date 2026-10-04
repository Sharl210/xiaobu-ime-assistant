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
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

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

    /** Room database runtime type names discovered from DexKit constructor signatures. */
    @Volatile
    private var roomDatabaseTypeNames: Set<String> = emptySet()

    @Volatile
    private var writeAdapter: Any? = null

    @Volatile
    private var writeEntry: java.lang.reflect.Method? = null

    @Volatile
    private var rowItemMethod: java.lang.reflect.Method? = null

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
        resolveWriteAdapter(bridge, hostClassLoader)
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
        // 先用稳定的实体 toString 语义串收敛候选，再用字段形状核验；不写死宿主混淆类路径。
        val semanticCandidates: List<ClassData> = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(listOf("ClipboardData(id="), StringMatchType.Equals, false)
                }
            }.toList()
        }.onFailure { log("clip-edit: entity semantic query failed: ${it.message}") }
            .getOrDefault(emptyList())

        val fallbackOwners = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes("android.net.Uri")
                    returnType("void")
                }
            }.toList().mapNotNull { it.declaredClassName }
        }.getOrDefault(emptyList())

        val names = LinkedHashSet<String>()
        semanticCandidates.mapNotNullTo(names) { it.name }
        fallbackOwners.forEach { names.add(it) }
        log("clip-edit: entity candidates semantic=${semanticCandidates.size} fallback=${fallbackOwners.size}")

        names.forEach { name ->
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
     * 宿主 Room 数据库类型由 DexKit 从构造函数签名中发现，写回路径只使用运行时解析结果。
     * 用构造钩子而不是反射构造：那个类的构造参数是数据库实例，我们没法凭空造一个，
     * 但宿主自己构造它的时候会把手里的实例交出来。
     */
    private fun captureWritePath(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        // Room 的连接执行器通过 DexKit 按静态方法形状与 Room 包内参数识别；不写死混淆工具类名。
        withConnection = runCatching {
            bridge.findMethod {
                matcher {
                    paramCount(4)
                    returnType("java.lang.Object")
                    modifiers(Modifier.STATIC)
                }
            }.toList().asSequence()
                .mapNotNull { data -> runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull() }
                .firstOrNull { method ->
                    val p = method.parameterTypes
                    Modifier.isStatic(method.modifiers) && p.size == 4 &&
                        p[0].name.startsWith("androidx.room.") &&
                        p[1] == Boolean::class.javaPrimitiveType &&
                        p[2] == Boolean::class.javaPrimitiveType &&
                        p[3].name.startsWith("kotlin.jvm.functions.Function") &&
                        method.declaringClass.name.startsWith("androidx.room.util.")
                }
        }.getOrNull()
        log("clip-edit: withConnection=${withConnection?.declaringClass?.name}#${withConnection?.name}")

        // DAO 持有者构造函数：由 DexKit 发现所有单参数构造候选，再以 Room 包参数和宿主包过滤。
        val dbConstructors = runCatching {
            bridge.findMethod { matcher { paramCount(1) } }.toList()
                .filter { it.name == "<init>" && it.paramTypeNames.singleOrNull()?.startsWith("androidx.room.") == true }
        }.getOrDefault(emptyList())
        val owners = dbConstructors.mapNotNull { it.declaredClassName }.distinct()
        roomDatabaseTypeNames = dbConstructors.mapNotNull { data ->
            runCatching { data.getMethodInstance(hostClassLoader).parameterTypes.single().name }.getOrNull()
        }.toSet()

        val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        var hooked = 0
        owners.forEach { name ->
            if (!name.startsWith(HOST_PACKAGE)) return@forEach
            if (!seen.add(name)) return@forEach
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
                ?: return@forEach
            val holdsDb = runCatching {
                cls.declaredFields.any { it.type.name in roomDatabaseTypeNames }
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
    private var insertionBase: Class<*>? = null
    private var sqlGetter: java.lang.reflect.Method? = null
    private var daoFactory: java.lang.reflect.Constructor<*>? = null

    private fun resolveWriteAdapter(bridge: DexKitBridge, loader: ClassLoader) {
        runCatching {
            val sql = "INSERT OR REPLACE INTO `tab_clipboard_data` (`clipboard_id`,`clipboard_type`,`clipboard_text`,`clipboard_uri`,`clipboard_time`,`clipboard_mime_types`) VALUES (nullif(?, 0),?,?,?,?,?)"
            val data = bridge.findMethod {
                matcher { usingStrings(listOf(sql), StringMatchType.Equals, false); paramCount(0); returnType("java.lang.String") }
            }.single()
            val adapterClass = data.getClassInstance(loader)
            val base = adapterClass.superclass
            sqlGetter = data.getMethodInstance(loader).apply { isAccessible = true }
            insertionBase = base
            writeEntry = bridge.findMethod {
                matcher {
                    declaredClass(base.name)
                    usingStrings(listOf("SELECT last_insert_rowid()"), StringMatchType.Equals, false)
                    paramCount(2)
                    returnType("long")
                }
            }.single().getMethodInstance(loader).apply { isAccessible = true }
            val owners = adapterClass.declaredConstructors.flatMap { it.parameterTypes.toList() }
                .filter { !it.isPrimitive && it != String::class.java }.distinct()
            daoFactory = owners.flatMap { it.declaredConstructors.toList() }
                .single { it.parameterCount == 1 && it.parameterTypes[0].name.startsWith("androidx.room.") }
                .apply { isAccessible = true }
            log("clip-edit: SQL insertion contract resolved")
        }.onFailure { log("clip-edit: SQL insertion contract failed: ${it.message}") }
    }

    /** 从已捕获的宿主数据库构造目标 DAO，不创建新数据库，不依赖错过的构造回调。 */
    private fun ensureWriteReady() {
        if (writeAdapter != null) return
        val db = roomDb ?: error("host database not captured")
        val factory = daoFactory ?: error("clipboard DAO constructor unresolved")
        bindWritePath(factory.newInstance(db))
        check(writeAdapter != null && writeEntry != null) { "clipboard insertion adapter unavailable" }
    }

    private fun bindWritePath(owner: Any) {
        if (writeAdapter != null && roomDb != null) return
        runCatching {
            // 数据库字段类型使用安装期 DexKit 构造签名得到的运行时类型集合。
            owner.javaClass.declaredFields.firstOrNull { it.type.name in roomDatabaseTypeNames }?.let { f ->
                f.isAccessible = true
                (f.get(owner) as? Any)?.let { roomDb = it }
            }
            // 写适配器：字段类型是 Room 适配器子类，且 SQL 指向剪贴板表。
            // 适配器基类**不写死混淆名**：它的形状是「有一个返回 String 的 SQL 方法 +
            // 一个 (连接, 实体) 形态的执行方法」。这里按 Room 自己的包名去找基类，
            // 再按「SQL 里出现剪贴板表名」去歧义。
            val base = insertionBase
            val entry = writeEntry
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
                        if (sqlGetter?.declaringClass?.isInstance(adapter) == true)
                            sqlGetter?.invoke(adapter) as? String else null
                    }.getOrNull().orEmpty()
                    if (!sql.contains("tab_clipboard_data", ignoreCase = true) ||
                        !sql.contains("INSERT", ignoreCase = true)
                    ) return@forEach
                    writeAdapter = adapter
                    writeEntry = entry
                    log("clip-edit: write adapter bound ${adapter.javaClass.name} field=${f.name} sql=$sql")
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
            var hooks = 0
        candidates.forEach { candidate ->
            val owner = candidate.declaredClassName ?: return@forEach
            if (!owner.startsWith(HOST_PACKAGE)) return@forEach
            val method = runCatching { candidate.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            val itemMethod = candidate.invokes
                .filter {
                    it.paramTypeNames == listOf("int") &&
                        it.returnTypeName != "void" &&
                        it.declaredClassName == owner
                }
                .mapNotNull { invoke ->
                    runCatching { invoke.getMethodInstance(hostClassLoader).apply { isAccessible = true } }.getOrNull()
                }
                .firstOrNull()
                ?: return@forEach
            rowItemMethod = itemMethod
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val holder = param.args?.getOrNull(0) ?: return
                        val position = param.args?.getOrNull(1) as? Int ?: return
                        val itemView = itemViewOf(holder) ?: return
                        val item = itemAt(param.thisObject ?: return, position) ?: return
                        attachToRow(itemView, item)
                    }
                })
                hooks++
                log("clip-edit: bind hooked $owner#${method.name} item=${itemMethod.name}")
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
        val restored = runCatching { restoreFocus?.invoke() }.getOrNull() == true
        val cleared = if (!restored) runCatching { clearInputTarget?.invoke() }.getOrNull() else false
        log("clip-edit: focus released restore=$restored clearFallback=$cleared")
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
        var current: Class<*>? = holder.javaClass
        var depth = 0
        while (current != null && depth++ < 8) {
            val field = current.declaredFields.firstOrNull {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    View::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    /** 把列表包装项（ClipboardWithExtract）解包成真正可写回的 ClipboardData。 */
    private fun unwrapEntity(item: Any?): Any? {
        if (item == null) return null
        val targetClass = entityClass
        if (targetClass != null && targetClass.isInstance(item)) return item
        val field = item.javaClass.declaredFields.firstOrNull { f ->
            !java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                targetClass?.let { it.isAssignableFrom(f.type) } == true
        } ?: return item
        return runCatching {
            field.isAccessible = true
            field.get(item)
        }.getOrNull() ?: item
    }

    /** 在一行绑定时取出实体本体，而不是外层 ClipboardWithExtract 包装对象。 */
    private fun itemAt(adapter: Any, position: Int): Any? {
        val method = rowItemMethod ?: return null
        if (!method.declaringClass.isAssignableFrom(adapter.javaClass)) return null
        return unwrapEntity(runCatching { method.invoke(adapter, position) }.getOrNull())
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
        val entity = rowItems[button] ?: return
        val current = readText(entity) ?: run {
            log("clip-edit: native editor skipped: entity text unavailable")
            return
        }
        HostPhraseEditor.show(
            button, TITLE, current, registerInputTarget,
            onConfirm = { writeBack(entity, it) },
            onClose = { releaseInputTarget() },
        )
    }

    private fun readText(entity: Any): String? {
        val name = textFieldName ?: return null
        return runCatching { entity.javaClass.getField(name).get(entity) as? String }.getOrNull()
    }

    /** 改实体正文 → 用宿主的 SQL 适配器写回 → Room 失效跟踪会让列表刷新。 */
    private fun writeBack(entity: Any, next: String) {
        ensureWriteReady()
        val name = textFieldName ?: error("clipboard text field unresolved")
        val field = entity.javaClass.getField(name).apply { isAccessible = true }
        val before = field.get(entity)
        val adapter = writeAdapter ?: error("clipboard adapter unavailable")
        val entry = writeEntry ?: error("clipboard insertion method unavailable")
        val db = roomDb ?: error("host database unavailable")
        val runner = withConnection ?: error("host transaction runner unavailable")
        var executed = false
        try {
            field.set(entity, next)
            val fnType = runner.parameterTypes.last()
            val body = java.lang.reflect.Proxy.newProxyInstance(
                fnType.classLoader, arrayOf(fnType),
            ) { _, method, args ->
                if (method.name == "invoke") {
                    val connection = args?.getOrNull(0) ?: error("host connection missing")
                    entry.invoke(adapter, connection, entity)
                    executed = true
                }
                null
            }
            runner.invoke(null, db, false, true, body)
            check(executed) { "host transaction did not execute insertion" }
            log("clip-edit: insertion executed len=${next.length}; persistence readback still required")
        } catch (t: Throwable) {
            field.set(entity, before)
            throw t
        }
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
