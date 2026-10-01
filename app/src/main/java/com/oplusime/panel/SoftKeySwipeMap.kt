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
     * 中文逗号上滑的标记。
     *
     * 用「英」＋零宽连接符（U+200D）拼成：屏幕上看到的就是「英」（零宽字符不占位），
     * 但完整字符串是候选词表里不可能出现的，因此在**上屏出口**按它精确拦截、零误伤。
     */
    const val CN_COMMA_MARK: String = "英\u200D"

    /**
     * 英文页（百度输入法英文 26 键）。
     *
     * 逗号、句号也在这里：小布原本这两个键**没有上滑**，用户要求补齐，
     * 并照百度英文页映射（逗号 → `"`、句号 → `?`）。
     */
    private val TABLE_EN: Map<Char, String> = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "!", 's' to "@", 'd' to "#", 'f' to "$", 'g' to "%",
        'h' to "&", 'j' to "*", 'k' to "(", 'l' to ")",
        'z' to "'", 'x' to "/", 'c' to "-", 'v' to "_", 'b' to ":",
        'n' to ";", 'm' to "?",
        ',' to "\"", '.' to "'",
    )

    /** 中文页（百度输入法中文 26 键）。 */
    private val TABLE_CN: Map<Char, String> = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "~", 's' to "@", 'd' to "#", 'f' to "$", 'g' to "%",
        'h' to "&", 'j' to "*", 'k' to "(", 'l' to ")",
        'z' to "'", 'x' to "/", 'c' to "-", 'v' to "_", 'b' to ":",
        'n' to ";", 'm' to "`",
        // 逗号：百度中文页这里是**英文候选开关**（那个小册子图标）而不是字符，
        // 所以标记成「英」+ 一个不可见的私用区字符：键面提示看起来就是「英」，
        // 而拦截判据是「英\uE000」这个两字符串 —— 候选词/手输都不可能产出它，零误伤。
        ',' to CN_COMMA_MARK, '.' to "?",
    )

    /**
     * 中文页逗号的上滑动作：切换"英文候选"开关（百度输入法那个小册子图标的功能）。
     *
     * 百度那枚图标在小布的资源表里**不存在**（全库只有 `settings_memory_codebook_title` 一处
     * 含 "book"，与键盘无关），所以这里用汉字「英」当标记；功能本身照做。
     */
    const val CN_COMMA_ACTION: String = "toggle_english_suggestion"

    /** 判断某个键是不是"上滑要拦截成开关"的中文逗号。 */
    private fun isCnCommaToggle(text: String?, lang: String): Boolean =
        lang == "zh" && text != null && (text == "," || text == "，")

    /** 宿主设置里"英文候选"的开关 key（dex 字符串常量，未混淆）。 */
    const val KEY_ENGLISH_SUGGESTION: String = "key_english_suggestion"

    /** 宿主设置用的 SharedPreferences 名（`body/s.onAttachedToWindow` 里出现）。 */
    const val PREFS_NAME: String = "com.oplus.keyboard.restore.preference"

    /** 标记这个 SoftKey 已经被我们按当前语言刷过，避免每帧重复写。 */
    private const val TAG_LANG: String = "oplusime.swipe.lang"

    @Volatile
    private var installed = false

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /** 最近一次生效的语言（zh / en），用于只在切换语言时重写整表。 */
    @Volatile
    private var lastLang: String? = null

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        if (installed) return
        this.hostClassLoader = hostClassLoader
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
    private fun installSwipeToggleHook(hostClassLoader: ClassLoader) {
        val hookedIcClasses = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
        )
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
    }

    /** 在远程输入连接上拦「上滑标记」。 */
    private fun hookIc(cls: Class<*>, context: android.content.Context?) {
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
                        if (text.toString() != CN_COMMA_MARK) return
                        val ctx = context ?: return
                        toggleEnglishSuggestion(ctx)
                        // 吞掉这次提交：开关动作本身不该在输入框里留下字符。
                        param.result = true
                        log("swipe-map: comma swipe toggled english suggestion (submission suppressed)")
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
     * 开关 key 由 dex 字符串常量确认：`key_english_suggestion`。
     * 写入后 commit（宿主的 `body/s` 注册了 `OnSharedPreferenceChangeListener`，
     * 会立刻按新值重绘 —— 所以不需要重启输入法）。
     */
    fun toggleEnglishSuggestion(context: Context): Boolean {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = !prefs.getBoolean(KEY_ENGLISH_SUGGESTION, true)
        val ok = runCatching {
            prefs.edit().putBoolean(KEY_ENGLISH_SUGGESTION, next).commit()
        }.getOrDefault(false)
        log("swipe-map: english suggestion -> $next (commit=$ok)")
        return ok
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
