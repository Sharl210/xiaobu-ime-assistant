package com.oplusime.panel

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.MethodData

/**
 * 26 键键盘的**上滑字符**，按百度输入法一一对应（中文页、英文页各一套）。
 *
 * ## 宿主里"上滑字符"是什么
 *
 * dex 事实：每个键位是 `base/entity/c`（SoftKey），它有两个字符串字段：
 *
 * ```text
 * s : String   keyText  —— 键面主字符（q / a / , / 。 等）
 * t : String   keyMark  —— **上滑字符**（键面右上角那个小字）
 * ```
 *
 * 取值链条：`base/preset/c`（预设表，`<clinit>` 有 543 个字符串常量）构造出 SoftKey 集合，
 * `base/entity/d`（SoftKeyboard）再按键盘类型排布。绘制与上滑提交都读 `keyMark`，
 * 所以**改这一个字段**就能同时改"显示的小字"和"上滑真正上屏的字符"。
 *
 * ## 为什么在绘制前改，而不是在构造时改
 *
 * SoftKey 对象是**跨键盘类型共享**的（同一份预设表），构造期不知道当前是中/英。
 * 绘制入口能拿到当前键盘类型（`BaseKeyboardView.getKeyboardType()`），
 * 因此在那里按类型选中对应的映射表 —— 中文页一套、英文页一套。
 *
 * ## 映射来源
 *
 * 由用户提供的百度输入法英文页 / 中文页截图逐键抄录（见 [TABLE_EN] / [TABLE_CN]）。
 */
internal object SoftKeySwipeMap {

    /**
     * 英文逗号上滑的标记 —— 百度那一枚「小册子」图标。
     *
     * 百度 `en_26.ini` 里英文逗号键是 `UP=F25`：F25 不是字符，而是一个**功能图标**
     * （小册子＝英文联想/候选开关）。小布这枚图标**不在资源表里**（全库没有任何与它对应的
     * drawable），而宿主键面的「上滑小字」这个槽位（`base/entity/c.t`）**只接受字符串**、
     * 不接受图片，所以这里用书本字形 U+1F4D6 近似它。
     *
     * 完整字符串是候选词表里不可能产出的，因此在**上屏出口**按它精确拦截、零误伤。
     */
    const val CN_COMMA_MARK: String = "\uD83D\uDCD6"

    /**
     * 英文页 —— 逐字抄自**百度输入法自己的布局文件**。
     *
     * 来源：`百度输入法定制版_8.5.302.769.apk` → `assets/1080/port/en_26.ini`
     * 每个 `[KEYn]` 段落里的 `UP=` 就是该键的上滑字符：
     *
     * ```text
     * q..p UP=1..0      │ a..l UP=! @ # $ % & * ( )   │ z..m UP=' / - _ : ; ?
     * 逗号 KEY27 UP=F25  ← 那个「小册子」图标，即英文联想/候选开关（不是字符）
     * 句号 KEY29 UP=?   ← 半角问号
     * ```
     */
    private val TABLE_EN: Map<Char, String> = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "!", 's' to "@", 'd' to "#", 'f' to "$", 'g' to "%",
        'h' to "&", 'j' to "*", 'k' to "(", 'l' to ")",
        'z' to "'", 'x' to "/", 'c' to "-", 'v' to "_", 'b' to ":",
        'n' to ";", 'm' to "?",
        // 英文页逗号：百度这里是**功能图标**（小册子＝英文联想开关），不是符号。
        ',' to CN_COMMA_MARK,
        // 英文页句号：百度是半角问号。
        '.' to "?",
    )

    /**
     * 中文页 —— 逐字抄自百度输入法布局文件 `assets/1080/port/py_26.ini` 的 `UP=` 字段。
     *
     * ```text
     * q..p UP=1..0
     * a UP=～(全角波浪)  s..j UP=@ # $ % & *
     * k UP=（   l UP=）        ← 全角圆括号
     * z UP='  x UP=/  c UP=-  v UP=_
     * b UP=：（全角冒号）  n UP=；（全角分号）  m UP=、(顿号)
     * 逗号 KEY27 UP=！（全角叹号）
     * 句号 KEY29 UP=？（全角问号）
     * ```
     *
     * 注意中英两页**并不相同**：中文页的括号/冒号/分号是**全角**，`～` 也是全角，
     * 标点是中文标点；英文页则是半角。之前一版两边用同一套半角，所以"不太准确"。
     */
    private val TABLE_CN: Map<Char, String> = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "\uFF5E", 's' to "@", 'd' to "#", 'f' to "$", 'g' to "%",
        'h' to "&", 'j' to "*", 'k' to "\uFF08", 'l' to "\uFF09",
        'z' to "'", 'x' to "/", 'c' to "-", 'v' to "_",
        'b' to "\uFF1A", 'n' to "\uFF1B", 'm' to "\u3001",
        // 中文页逗号：百度这里是**功能图标**（小册子＝英文联想/候选开关），不是符号。
        ',' to CN_COMMA_MARK, '\uFF0C' to CN_COMMA_MARK,
        // 中文页句号：全角问号。
        '.' to "\uFF1F", '\u3002' to "\uFF1F",
    )

    /**
     * 中文页逗号的上滑动作：切换"英文候选"开关（百度输入法那个小册子图标的功能）。
     *
     * 百度 `en_26.ini` 里这一格是 `UP=F25` —— 一个**功能号**而不是字符；中英两页的逗号
     * 在小布上都用来承担这个开关（用户要求"逗号上滑切英文候选"）。
     */
    const val CN_COMMA_ACTION: String = "toggle_english_suggestion"

    /**
     * 引擎真正读取的**存储键**。
     *
     * 注意区分：设置页那一行的 preference key 是 `key_english_suggestion`，
     * 而引擎侧读的是 `key_en_suggestion` —— 宿主自己就是这样"界面 key → 存储 key"翻译的
     * （`settings/English26KeyFragment.onPreferenceTreeClick` 里两个字符串同时出现，
     *  随后调 `utils/storage/a.k(名字, "key_en_suggestion", 值)`）。
     * 直接写存储键，才是真正改到引擎行为。
     */
    const val KEY_EN_SUGGESTION: String = "key_en_suggestion"

    /** 宿主设置用的 SharedPreferences 名（未被混淆的字符串常量）。 */
    const val PREFS_NAME: String = "com.oplus.keyboard.restore.preference"

    /** 宿主包名：稳定语义锚点，不参与混淆。 */
    private const val HOST_PACKAGE: String = "com.oplus.keyboard"

    /** 最近一次拿到的 Context（上下滑标记命中时用来切开关）。 */
    @Volatile
    private var contextRef: android.content.Context? = null

    /** 标记这个 SoftKey 已经被我们按当前语言刷过，避免每帧重复写。 */
    private const val TAG_LANG: String = "oplusime.swipe.lang"

    @Volatile
    private var installed = false

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /** 安装时拿到的 DexKit 桥（定位设置读写入口时复用）。 */
    @Volatile
    private var bridgeRef: DexKitBridge? = null

    /** 最近一次生效的语言（zh / en），用于只在切换语言时重写整表。 */
    @Volatile
    private var lastLang: String? = null

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        if (installed) return
        this.hostClassLoader = hostClassLoader
        bridgeRef = bridge
        installDrawHook(bridge, hostClassLoader)
        installSwipeToggleHook(hostClassLoader)
        installed = true
    }

    /**
     * 中文逗号上滑 → 切换「英文候选」。
     *
     * 做法：把中文页逗号的上滑字符设成标记 [CN_COMMA_MARK]；上滑时这个字符会被提交出去，
     * 于是在**输入法的真正出口**（框架侧远程输入连接）上拦一次：文本正好等于标记时，
     * 切开关并**吞掉这次提交**（不给输入框上屏任何字符）。
     *
     * 为什么挂在远程连接上：1.21.1 的真机日志已经证明，符号/上滑这类提交最终都从这条链出去
     * （`RemoteInputConnection.commitText`），挂在别处收不到。
     */
    /**
     * 上滑标记的**拦截出口**集合。
     *
     * ## 为什么不能只挂一处
     *
     * 用户实测「上滑了但英文候选没被打开」。上滑字符最终从哪条链提交，取决于键盘类型与
     * 宿主当时的状态机（框架侧远程输入连接 / 宿主自己的 InputConnection 实现 / 引擎回调），
     * 之前几个版本每次只挂其中一条，就出现过"挂上了但这条链根本没走"。
     *
     * 因此这里一次挂全三条，并且**任何一次提交都留一行证据**（含未命中标记的），
     * 这样下次日志可以直接看出"标记到底有没有被提交、从哪条链提交"。
     *
     * 判据全部是结构化的，不含任何混淆类名/方法名。
     */
    private fun installSwipeToggleHook(hostClassLoader: ClassLoader) {
        val hookedIcClasses = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
        )
        // ① 框架侧远程输入连接：输入法进程对外写字的最终出口（1.22.0 日志已证符号走这条）。
        runCatching {
            val serviceClass = Class.forName(
                "android.inputmethodservice.InputMethodService",
                false,
                hostClassLoader,
            )
            XposedBridge.hookAllMethods(
                serviceClass,
                "getCurrentInputConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ic = param.result as? android.view.inputmethod.InputConnection ?: return
                        if (!hookedIcClasses.add(ic.javaClass.name)) return
                        hookIc(ic.javaClass, param.thisObject as? android.content.Context)
                    }
                },
            )
            log("swipe-map: remote IC provider hooked")
        }.onFailure { log("swipe-map: remote IC provider hook failed: ${it.message}") }

        // ② 宿主自己的 InputConnection 实现（不经框架代理的那条）。
        runCatching {
            val icClass = android.view.inputmethod.InputConnection::class.java
            val candidates: List<MethodData> = bridgeRef?.findMethod {
                matcher {
                    paramTypes("java.lang.CharSequence", "int")
                    returnType("boolean")
                }
            }?.toList().orEmpty()
            var count = 0
            candidates.forEach { data ->
                val owner = data.declaredClassName ?: return@forEach
                if (!owner.startsWith(HOST_PACKAGE)) return@forEach
                val cls = runCatching { Class.forName(owner, false, hostClassLoader) }.getOrNull()
                    ?: return@forEach
                if (!icClass.isAssignableFrom(cls)) return@forEach
                if (!hookedIcClasses.add(cls.name)) return@forEach
                hookIc(cls, null)
                count++
            }
            log("swipe-map: host IC impl classes hooked=$count")
        }.onFailure { log("swipe-map: host IC impl scan failed: ${it.message}") }

        // ③ 宿主内部的"提交文本"汇聚点：静态 `(int, CharSequence) -> boolean`
        //    宿主自己的日志串就是 "commitInternalText text="，说明这是内部提交入口。
        runCatching {
            val candidates: List<MethodData> = bridgeRef?.findMethod {
                matcher {
                    paramTypes("int", "java.lang.CharSequence")
                    returnType("boolean")
                }
            }?.toList().orEmpty()
            var count = 0
            candidates.forEach { data ->
                val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                    ?: return@forEach
                if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) return@forEach
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val text = param.args?.getOrNull(1) as? CharSequence ?: return
                            if (!isCommaMarker(text)) return
                            onMarkerSeen("internal-commit")
                            param.result = java.lang.Boolean.TRUE
                        }
                    })
                    count++
                }
            }
            log("swipe-map: internal commit hooks=$count")
        }.onFailure { log("swipe-map: internal commit hook failed: ${it.message}") }
    }

    /** 文本是不是中文逗号上滑那枚标记（容忍首尾空白）。 */
    private fun isCommaMarker(text: CharSequence?): Boolean {
        val value = text?.toString()?.trim() ?: return false
        if (value == CN_COMMA_MARK) return true
        // 有些路径会带回车或零宽字符，这里放宽一点：包含标记即命中。
        return value.contains(CN_COMMA_MARK)
    }

    /** 命中标记：切开关 + 吞掉这次提交，并留一行证据。 */
    private fun onMarkerSeen(source: String): Boolean {
        val ctx = contextRef
        if (ctx == null) {
            log("swipe-map: marker seen from $source but no context yet")
            return true
        }
        toggleEnglishSuggestion(ctx)
        log("swipe-map: comma swipe toggled english suggestion (source=$source, submission suppressed)")
        return true
    }

    /** 在远程输入连接上拦「上滑标记」。 */
    private fun hookIc(cls: Class<*>, context: android.content.Context?) {
        if (context != null) contextRef = context
        val intPrimitive: Class<*> = Int::class.javaPrimitiveType ?: return
        val points: List<Pair<String, Array<Class<*>>>> = listOf(
            "commitText" to arrayOf<Class<*>>(CharSequence::class.java, intPrimitive),
            "setComposingText" to arrayOf<Class<*>>(CharSequence::class.java, intPrimitive),
        )
        var added = 0
        points.forEach { (name, params) ->
            runCatching {
                val method = cls.getMethod(name, *params)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = param.args?.getOrNull(0) as? CharSequence ?: return
                        if (!isCommaMarker(text)) return
                        onMarkerSeen("${cls.name}#$name")
                        // 吞掉这次提交：开关动作本身不该在输入框里留下字符。
                        param.result = true
                    }
                })
                added++
            }
        }
        if (added > 0) log("swipe-map: swipe toggle hooked ${cls.name} points=$added")
    }

    /**
     * 绘制入口：宿主「BaseKeyboardView」里画每个键的方法。
     *
     * 结构特征：`(android.graphics.Canvas, 键实体类) → void`，且是**实例方法**
     * （静态那些拿不到视图、判不了语言）。宿主自己的 `f/g(Canvas, SoftKey)` 就是这条。
     */
    private fun installDrawHook(bridge: DexKitBridge, hostClassLoader: ClassLoader) {        val candidates: List<MethodData> = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes("android.graphics.Canvas", "com.oplus.keyboard.base.entity.c")
                    returnType("void")
                }
            }.toList()
        }.onFailure { log("swipe-map: query failed: ${it.message}") }
            .getOrDefault(emptyList())
        log("swipe-map: draw candidates=${candidates.size}")

        var hooks = 0
        candidates.forEach { candidate ->
            val method = runCatching { candidate.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            val isStatic = java.lang.reflect.Modifier.isStatic(method.modifiers)
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 静态方法拿不到视图（`param.thisObject` 为 null），此时无法判语言，
                        // 只有实例方法（宿主 `f(Canvas, SoftKey)` / `g(Canvas, SoftKey)`）才动手。
                        val view = param.thisObject as? View ?: return
                        val key = param.args?.getOrNull(1) ?: return
                        applyLang(view, key)
                    }
                })
                hooks++
                log(
                    "swipe-map: draw hooked ${candidate.declaredClassName}#${candidate.name}" +
                        " static=$isStatic"
                )
            }.onFailure { log("swipe-map: draw hook failed: ${it.message}") }
        }
        installed = true
        log("swipe-map: installed hooks=$hooks")
    }

    /**
     * 每个键位对象上一次按哪套表刷过。
     *
     * 必须**逐键**记录，而不是记一个全局"当前语言"：同一帧里会连续画几十个键，
     * 全局标记会让第二个键起就被跳过（这正是"改了一帧就没反应"的经典坑）。
     * 键位对象是共享的（跨键盘类型复用），所以用身份表。
     */
    private val appliedLang: MutableMap<Any, String> =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap())

    /**
     * 按当前键盘语言重写一个 SoftKey 的上滑字符。
     *
     * 逐键判重：同一个键在同一语言下只写一次（键对象会被长期复用），
     * 语言切换则整批重写。写入失败会留一行日志，不会静默失效。
     */
    private fun applyLang(view: View?, key: Any) {
        val lang = resolveLang(view) ?: return
        if (view != null && contextRef == null) contextRef = view.context
        if (appliedLang[key] == lang) return
        runCatching {
            val text = readString(key, "s") ?: return
            if (text.length != 1) return
            val ch = text[0]
            val table = if (lang == "en") TABLE_EN else TABLE_CN
            val mapped = table[ch.lowercaseChar()] ?: return
            val mark = readString(key, "t") ?: ""
            if (mark != mapped) {
                writeString(key, "t", mapped)
                // 复读校验：`t` 是 final 字段，若某些机型上写不进去，这里会立刻暴露，
                // 而不是等到用户发现"小字没变"再回头查。
                val after = readString(key, "t")
                if (after != mapped) {
                    log("swipe-map: write rejected (final field?) '$mark' -> '$after', want '$mapped'")
                    return
                }
            }
            appliedLang[key] = lang
            logThrottled("swipe-map", 2_000L) {
                "swipe-map: '$text' mark '$mark' -> '$mapped' (lang=$lang)"
            }
        }.onFailure { log("swipe-map: apply failed: ${it.message}") }
    }

    /** 从键盘视图取当前语言：英文键盘 → "en"，其余 → "zh"。 */
    private fun resolveLang(view: View?): String? {
        val name = runCatching {
            val m = view?.javaClass?.getMethod("getKeyboardType")
            m?.invoke(view)?.toString()
        }.getOrNull() ?: return null
        if (name.isEmpty()) return null
        val lang = if (name.startsWith("QWERTY_EN")) "en" else "zh"
        if (lang != lastLang) lastLang = lang
        return lang
    }

    /**
     * 中文逗号上滑 → 切换"英文候选"开关（实时生效）。
     *
     * ## 为什么必须走宿主自己的写入口
     *
     * 宿主设置层有一对结构化特征明确的静态方法（同一类里成对出现）：
     *
     * ```text
     * (String 名字, String 键, boolean 默认值) -> boolean   读
     * (String 名字, String 键, boolean 值)     -> void      写
     * ```
     *
     * 写方法内部除了写盘，还会**逐个通知注册过的键监听**（宿主自己的 `i(值, 键)`），
     * 输入法因此立刻按新值生效。如果我们自己 `getSharedPreferences(...).edit()`，
     * 值虽然写进去了，但宿主那套监听不会被触发 —— 观感就是"改了但不实时生效"。
     *
     * 所以优先调用宿主写入口；只在它定位不到时才退回直接写偏好（至少值是对的）。
     */
    fun toggleEnglishSuggestion(context: Context): Boolean {
        val current = readFlag() ?: runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_EN_SUGGESTION, true)
        }.getOrDefault(true)
        val next = !current
        val viaHost = writeFlag(next)
        if (!viaHost) {
            // 宿主入口没定位到时的兜底：至少把值写对，并**自己通知一次**
            // （找一个宿主进程里注册过该键的监听者做不到，所以这里只落盘 + 记日志，
            //  用户下次进设置页手动切换一次即可让引擎同步）。
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_EN_SUGGESTION, next).apply()
            }
        }
        val verify = readFlag()
        log(
            "swipe-map: english suggestion $current -> $next (viaHost=$viaHost" +
                " notify=${flagNotifier?.name} readback=$verify)"
        )
        return true
    }

    /** 宿主设置读写入口（结构匹配结果，进程内缓存）。 */
    @Volatile
    private var flagReader: java.lang.reflect.Method? = null

    @Volatile
    private var flagWriter: java.lang.reflect.Method? = null

    /**
     * 宿主设置变更的**通知**入口。
     *
     * 光写盘不会实时生效：宿主的设置层把"值变了"和"通知监听者"拆成两步 ——
     * 写方法只落盘，另有一个静态 `(值, 键名) -> void` 负责遍历该键上的监听者并逐个回调。
     * 不调它，引擎手里的还是旧值，表现就是"开关动了但输入行为没变"。
     */
    @Volatile
    private var flagNotifier: java.lang.reflect.Method? = null

    @Volatile
    private var flagResolved = false

    /**
     * 结构化定位宿主的设置读写入口。
     *
     * 判据（全是类型/形状，不含任何混淆名）：
     * 同一个**宿主类**里同时存在
     * `(String,String,boolean) -> boolean` 与 `(String,String,boolean) -> void` 两个静态方法。
     * 这个成对特征在整个宿主里是唯一的（设置读写助手）。
     */
    private fun resolveFlagAccessors(): Boolean {
        if (flagResolved) return flagReader != null && flagWriter != null
        flagResolved = true
        val loader = hostClassLoader ?: return false
        val bridge = bridgeRef ?: return false
        runCatching {
            val boolCls = Boolean::class.javaPrimitiveType ?: return false
            val candidates: List<MethodData> = bridge.findMethod {
                matcher {
                    paramTypes("java.lang.String", "java.lang.String", "boolean")
                    returnType("void")
                }
            }.toList()
            candidates.forEach { candidate ->
                val owner = candidate.declaredClassName ?: return@forEach
                // 只认宿主自己的类（排除框架/第三方）。
                if (owner.startsWith("android.") || owner.startsWith("androidx.") ||
                    owner.startsWith("kotlin.") || owner.startsWith("java.")
                ) {
                    return@forEach
                }
                val cls = runCatching { Class.forName(owner, false, loader) }.getOrNull()
                    ?: return@forEach
                val reader = cls.methods.firstOrNull {
                    it.name != candidate.name && it.parameterTypes.contentEquals(
                        arrayOf(String::class.java, String::class.java, boolCls),
                    ) && it.returnType == boolCls
                } ?: return@forEach
                val writer = runCatching { candidate.getMethodInstance(loader) }.getOrNull()
                    ?: return@forEach
                flagReader = reader
                flagWriter = writer
                // 通知入口：同一个类里的静态 `(Object, String) -> void`
                // 宿主自己的实现就是「按 key 取出监听者列表，逐个 invoke(value, key)」。
                flagNotifier = cls.declaredMethods.firstOrNull {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.returnType == Void.TYPE &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Any::class.java &&
                        it.parameterTypes[1] == String::class.java
                }
                log(
                    "swipe-map: settings accessors resolved ${cls.name} read=${reader.name}" +
                        " write=${writer.name} notify=${flagNotifier?.name}"
                )
            }
        }.onFailure { log("swipe-map: resolve settings accessors failed: ${it.message}") }
        return flagReader != null && flagWriter != null
    }

    private fun readFlag(): Boolean? {
        if (!resolveFlagAccessors()) return null
        return runCatching {
            flagReader?.invoke(null, PREFS_NAME, KEY_EN_SUGGESTION, true) as? Boolean
        }.getOrNull()
    }

    private fun writeFlag(value: Boolean): Boolean {
        if (!resolveFlagAccessors()) return false
        return runCatching {
            flagWriter?.invoke(null, PREFS_NAME, KEY_EN_SUGGESTION, value)
            // 显式通知监听者：只写盘不通知，引擎拿的还是旧值（"改了不生效"就是这个）。
            runCatching {
                flagNotifier?.invoke(null, value, KEY_EN_SUGGESTION)
            }.onFailure { log("swipe-map: notify failed: ${it.message}") }
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 字段读写

    private fun readString(target: Any, field: String): String? = runCatching {
        target.javaClass.getField(field).get(target) as? String
    }.getOrNull()

    private fun writeString(target: Any, field: String, value: String) {
        runCatching {
            // `t` 是 **final** 字段：`setAccessible(true)` 之后 `Field.set` 在 Android 上
            // 对非静态 final 引用字段同样有效（只读语义由优化器在编译期固化时才拦得住），
            // 宿主自己的代码就是这么读它的，所以不需要动 ArtMethod。
            val f = target.javaClass.getField(field)
            f.isAccessible = true
            f.set(target, value)
        }
    }
}
