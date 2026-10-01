package com.oplusime.panel

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.content.DialogInterface
import android.app.Dialog
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

/**
 * 引号「成对补全」抑制。
 *
 * ## 为什么放在这一层
 *
 * 取证结论（com.oplus.keyboard 1.7.38.17-os）：
 *
 * - Java 侧**没有**任何"输入左引号 → 补右引号"的判定。全包内含 `“`/`”` 的类只有三处，
 *   全部是"符号清单/候选表"（`input/utils/l` 的符号集合、`input/view/O` 的符号键候选、
 *   `base/data/s` 的符号数据）；字符码 `0x201c` 在 `com/oplus/keyboard` 全包内只出现在
 *   markdown 的 HTML 解析器里；`SmartPunctuation` 相关类属于**语音输入**加标点
 *   （`VoiceInputPanelHelper`），与符号面板无关。
 * - 因此引号配对由**输入引擎（native：libjni_ime / libIQQILib / libokim_shared 等）**完成，
 *   Java 层没有可以直接关闭它的开关或函数。
 *
 * ## 因此采取的策略：在上屏结果上做修正
 *
 * 无论配对是谁做的、也无论它是"一次提交两个字符"还是"提交左引号后再补右引号并移动光标"，
 * 最终编辑框里必然出现同一个可检测的状态：
 *
 * ```text
 * 光标前面是左引号  且  光标后面是配对的右引号        →  “ | ”
 * ```
 *
 * 检测到这个状态就删掉光标后面的那个右引号，于是剩下：
 *
 * ```text
 * 光标前面是左引号，光标在它后面                      →  “ |
 * ```
 *
 * 这正是用户要的"把引号当普通符号输入，光标留在引号后面"。
 *
 * ## 挂点
 *
 * 输入法进程内承载"输入法 → 编辑框"全部调用的，是 framework 的 InputConnection 代理实现
 * （`com.android.internal.view.IInputConnectionWrapper` 及其同族）。native 引擎与 Java 代码
 * 最终都经过它，所以在它的 `commitText` / `setSelection` 之后各检查一次，即可覆盖两种形态：
 *
 * - 若配对是"一次提交两个字符" → `commitText` 之后就能看到 `“ | ”`；
 * - 若配对是"提交左引号后再补右引号并 setSelection 到中间" → `setSelection` 之后才成立。
 *
 * 两个检查都是幂等的：删掉之后状态不再成立，不会重复删除。
 *
 * ## 误伤控制
 *
 * 只在**本次动作确实与引号有关**时才检查（提交文本含引号，或涉及的选区间隔极小），
 * 因此用户平时"手动把光标放进一对引号中间"的正常编辑不会被干扰。
 */
internal object QuotePairSuppressor {

    /**
     * 左符号 → 配对的右符号。
     *
     * 范围按用户要求放到**全量成对符号**：引号、圆括号、方括号、花括号、尖括号，
     * 以及中文/全角与各语言变体。半角引号左右同形，因此映射到自身。
     *
     * 这张表只用于两件事：①识别「一次提交上来的正好是一对」；
     * ②识别「光标正夹在一对中间」。都不涉及对用户输入的额外改写。
     */
    private val PAIRS: Map<Char, Char> = mapOf(
        // 引号
        '\u201C' to '\u201D', // “ ”
        '\u2018' to '\u2019', // ‘ ’
        '\u201E' to '\u201C', // „ “（德语式低引号）
        '\u00AB' to '\u00BB', // « »
        '\u2039' to '\u203A', // ‹ ›
        '\u201A' to '\u2018', // ‚ ‘
        '\u300C' to '\u300D', // 「 」
        '\u300E' to '\u300F', // 『 』
        '\u301D' to '\u301E', // 〝 〞
        '"' to '"',           // 英文双引号
        '\'' to '\'',         // 英文单引号
        // 圆括号
        '(' to ')',
        '\uFF08' to '\uFF09', // （ ）
        // 方括号
        '[' to ']',
        '\u3010' to '\u3011', // 【 】
        '\uFF3B' to '\uFF3D', // ［ ］
        // 花括号
        '{' to '}',
        '\uFF5B' to '\uFF5D', // ｛ ｝
        // 尖括号 / 书名号
        '<' to '>',
        '\u3008' to '\u3009', // 〈 〉
        '\u300A' to '\u300B', // 《 》
        '\uFF1C' to '\uFF1E', // ＜ ＞
    )

    /** 光标两侧都是引号时，两次删除之间的最小间隔，避免同一状态被连续处理。 */
    private const val MIN_INTERVAL_MS = 60L

    /**
     * 「夹在中间」修正的**有效时间窗**。
     *
     * 这是本文件最重要的一道安全闸。原因：`setSelection` 挂在输入连接的每次光标移动上，
     * 而用户手动把光标点到一段已有文字里的「（）」中间时，光标同样会呈现"被一对符号夹住"
     * 的状态——若不加限制，就会**误删用户自己的右括号**。
     *
     * 因此只有在"刚刚确实有一次成对符号的提交"之后的极短时间内才允许修正：
     * 那才是宿主自动补全产生的状态；其余时刻一律只观察、不动手。
     */
    private const val SANDWICH_WINDOW_MS = 1200L
    private val CLEANUP_DELAYS_MS = longArrayOf(0L, 40L, 90L, 160L, 280L, 450L, 700L)

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastFixAt: Long = 0L

    /** 最近一次"提交里含成对符号"的时刻；夹缝修正必须发生在这个时刻之后的时间窗内。 */
    @Volatile
    private var lastPairCommitAt: Long = 0L

    @Volatile
    private var installed = false

    @Volatile
    private var fixCount: Int = 0

    /** 引擎提交证据去重用的两个字段，避免连续按键刷屏。 */
    @Volatile
    private var lastObserveAt: Long = 0L

    @Volatile
    private var lastObservedText: String = ""

    /**
     * 在宿主进程内安装。输入法进程里承载编辑框调用的代理类可能不止一个名字，
     * 逐个尝试，命中即装；全部不可用时如实记日志（功能退化为原生行为，不会崩）。
     */
    fun install(hostClassLoader: ClassLoader, extraClasses: List<Class<*>> = emptyList()) {
        if (installed) return
        val candidates = listOf(
            // 框架侧的代理实现：native 引擎与 Java 代码最终都经过它。
            "com.android.internal.view.IInputConnectionWrapper",
            "com.android.internal.view.InputConnectionWrapper",
            "android.view.inputmethod.InputConnectionWrapper",
            // 框架基类：宿主自定义的 InputConnection 一般继承它。
            "android.view.inputmethod.BaseInputConnection",
        )
        var hooked = 0
        candidates.forEach { name ->
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
            if (cls == null) {
                log("quote-pair: $name not present")
                return@forEach
            }
            hooked += hookAll(cls)
        }
        // 宿主自己实现的 InputConnection（由入口用 DexKit 查出后传进来），
        // 逐一挂上——这类实现不会经过框架代理，必须单独覆盖。
        extraClasses.forEach { cls ->
            val added = hookAll(cls)
            if (added > 0) {
                hooked += added
                log("quote-pair: host InputConnection hooked ${cls.name} points=$added")
            }
        }
        if (hooked == 0) {
            log("quote-pair: no InputConnection proxy hooked; quotes keep host behaviour")
        } else {
            installed = true
            log("quote-pair: installed, hook points=$hooked")
        }
    }

    /**
     * 把「宿主自己实现的 InputConnection」也挂上。
     *
     * 宿主用 `onCreateInputConnection` 返回它自己的实现类（不是框架的包装类），
     * 这类实现完全绕过框架代理，前面那批候选类一个都覆盖不到——这正是上一版"hook 装上了
     * 但引号照样成对"的最可能原因。这里按方法返回值把实现类找出来，逐一挂载：
     * 只要宿主的输入连接还是由 `onCreateInputConnection` 产出的，这条链就能被重新找到。
     */
    fun attachHostImplementations(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val methods = runCatching {
            bridge.findMethod {
                matcher {
                    name("onCreateInputConnection")
                    paramTypes("android.view.inputmethod.EditorInfo")
                }
            }.toList()
        }.onFailure { log("quote-pair: host IC query failed: ${it.message}") }
            .getOrDefault(emptyList())

        val names = methods.mapNotNull { it.returnTypeName }.distinct()
        var hooked = 0
        names.forEach { name ->
            if (name.isEmpty() || name == "android.view.inputmethod.InputConnection") return@forEach
            val cls = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (cls.isInterface) return@forEach
            val added = hookAll(cls)
            if (added > 0) {
                hooked += added
                log("quote-pair: host IC hooked ${cls.name} points=$added")
            }
        }
        log("quote-pair: host IC classes=${names.size} hooks=$hooked")
    }

    /**
     * 宿主自己的 KeyEventHandler 在真正调用 InputConnection 之前还会经过
     * `commonCommitText` 分发器。仅 hook InputConnectionWrapper 会漏掉“成对文本先在宿主分发器生成”的路径，
     * 因此这里按宿主 dex 的结构特征再挂两层：含 `match_symbol` 的 CharSequence 分发器，
     * 以及 `(int, CharSequence)` 的直接提交入口。
     */
    fun attachHostCommitDispatchers(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        var installed = 0
        val matchSymbol = runCatching {
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    usingStrings(listOf("match_symbol"), org.luckypray.dexkit.query.enums.StringMatchType.Equals, false)
                    paramCount(9)
                }
            }.toList()
        }.getOrDefault(emptyList())
        matchSymbol.forEach { data ->
            runCatching {
                XposedBridge.hookMethod(data.getMethodInstance(hostClassLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ic = param.args?.firstOrNull { it is InputConnection } as? InputConnection
                        scheduleInspect(ic, "commonCommitText")
                    }
                })
                installed++
            }.onFailure { log("quote-pair: common dispatcher hook failed: ${it.message}") }
        }

        // p.y(InputConnection, CharSequence, String, int, ..., boolean, boolean)
        // 是宿主真正把 commonCommitText 结果送进编辑器的最后一层；B() 之后
        // 仍可能在这里重新组合文本，因此必须同时覆盖这个形状。
        val finalCommit = runCatching {
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    paramCount(7)
                    usingStrings(
                        listOf("commitText failed", "KeyEventHandler"),
                        org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                        false,
                    )
                }
            }.toList()
        }.getOrDefault(emptyList())
        finalCommit.forEach { data ->
            runCatching {
                val method = data.getMethodInstance(hostClassLoader)
                if (method.parameterTypes.firstOrNull() != InputConnection::class.java) return@runCatching
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ic = param.args?.firstOrNull { it is InputConnection } as? InputConnection
                        scheduleInspect(ic, "final-commit")
                    }
                })
                installed++
            }.onFailure { log("quote-pair: final commit hook failed: ${it.message}") }
        }

        val direct = runCatching {
            bridge.findMethod {
                matcher {
                    returnType("void")
                    paramCount(2)
                    paramTypes("int", "java.lang.CharSequence")
                    addInvoke("Landroid/view/inputmethod/InputConnection;->commitText(Ljava/lang/CharSequence;I)Z")
                }
            }.toList()
        }.getOrDefault(emptyList())
        direct.forEach { data ->
            runCatching {
                XposedBridge.hookMethod(data.getMethodInstance(hostClassLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimPairArguments(param.args)
                    }
                })
                installed++
            }.onFailure { log("quote-pair: direct dispatcher hook failed: ${it.message}") }
        }
        log("quote-pair: host commit dispatchers candidates=${matchSymbol.size + direct.size} installed=$installed")
    }

    /**
     * native 引擎 → Java 的提交汇聚点。
     *
     * 取证（宿主 dex）：native 引擎回调 Java 的实现是 `input/event/a`（类内日志串
     * `commitText, cleared compositionText`，日志 tag 为 `CjZhuyinEngineCallbackImpl`）；
     * 它的 `o(String)` 就是"引擎说要提交这段文字"，紧接着调用
     * `input/event/p;->A(CharSequence, InputConnection, c, boolean, int)` 完成提交。
     *
     * 符号键（上滑符号、符号页）同样从这里出去，所以引号成对必然在这条链上。
     * 按结构 + 语义串把两处找出来，都不写死类名或混淆方法名：
     *
     * 1. 含上述日志串、且存在 `(String) -> boolean` 实例方法的类 → 挂它的引擎回调；
     * 2. 含 `commitText failed` 串、且存在静态
     *    `(CharSequence, InputConnection, *, boolean, int) -> boolean` 的类 → 挂真正提交的分发器。
     *
     * 两个挂点只做两件事：①"一次提交上来正好是一对"就裁成单个左符号；
     * ②把这次动作记成"刚发生成对符号动作"，为 [check] 打开修正时间窗。
     */
    fun attachEngineCommit(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        var installed = 0

        val callbackClasses = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(
                        listOf("commitText, cleared compositionText"),
                        org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                    )
                }
            }.toList()
        }.onFailure { log("quote-pair: engine callback query failed: ${it.message}") }
            .getOrDefault(emptyList())

        callbackClasses.forEach { data ->
            val cls = runCatching { data.getInstance(hostClassLoader) }.getOrNull() ?: return@forEach
            cls.declaredMethods
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == String::class.java
                }
                .forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val text = param.args?.getOrNull(0) as? String ?: return
                                observeEngineCommit(text, cls.simpleName + "#" + method.name)
                                val single = unwrapPair(text) ?: return
                                param.args[0] = single.toString()
                                lastPairCommitAt = System.currentTimeMillis()
                                log(
                                    "quote-pair: engine commit pair trimmed to '" +
                                        describe(single[0]) + "'"
                                )
                            }
                        })
                        installed++
                        log("quote-pair: engine callback hooked ${cls.name}#${method.name}")
                    }.onFailure { log("quote-pair: engine callback hook failed: ${it.message}") }
                }
        }

        val dispatcherClasses = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(
                        listOf("commitText failed"),
                        org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                    )
                }
            }.toList()
        }.onFailure { log("quote-pair: dispatcher query failed: ${it.message}") }
            .getOrDefault(emptyList())

        dispatcherClasses.forEach { data ->
            val cls = runCatching { data.getInstance(hostClassLoader) }.getOrNull() ?: return@forEach
            cls.declaredMethods
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.size == 5 &&
                        method.parameterTypes[0] == CharSequence::class.java &&
                        method.parameterTypes[1] == InputConnection::class.java &&
                        method.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes[4] == Int::class.javaPrimitiveType
                }
                .forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val text = param.args?.getOrNull(0) as? CharSequence ?: return
                                observeEngineCommit(text.toString(), cls.simpleName + "#" + method.name)
                                val single = unwrapPair(text) ?: return
                                param.args[0] = single
                                lastPairCommitAt = System.currentTimeMillis()
                                log(
                                    "quote-pair: dispatcher pair trimmed to '" +
                                        describe(single[0]) + "'"
                                )
                            }
                        })
                        installed++
                        log("quote-pair: commit dispatcher hooked ${cls.name}#${method.name}")
                    }.onFailure { log("quote-pair: commit dispatcher hook failed: ${it.message}") }
                }
        }

        log("quote-pair: engine commit hooks installed=$installed")
    }

    /**
     * 只在"这次提交确实与成对符号有关"时记一行证据，避免每次按键都刷日志。
     *
     * 这一行是下一轮真机取证的判据：如果日志里出现 `len=2`（一次上来就是一对），
     * 说明配对发生在引擎之前（键位表）；如果只有 `len=1`，说明配对发生在提交之后，
     * 由 [check] 的时间窗负责拆掉。
     */
    private fun observeEngineCommit(text: String, source: String) {
        if (!mentionsPair(text)) return
        val now = System.currentTimeMillis()
        if (now - lastObserveAt < 200L && text == lastObservedText) return
        lastObserveAt = now
        lastObservedText = text
        log(
            "quote-pair: engine commit text='" +
                text.map { describe(it) }.joinToString("") +
                "' len=" + text.length + " source=" + source
        )
        if (text.length == 1 && PAIRS.containsKey(text[0])) {
            // 单个左符号提交同样是"刚发生一次成对符号动作"，要打开修正时间窗。
            lastPairCommitAt = now
        }
    }

    private fun trimPairArguments(args: Array<Any?>?) {
        if (args == null) return
        var inputConnection: InputConnection? = null
        args.indices.forEach { index ->
            val candidate = args[index]
            if (candidate is InputConnection) inputConnection = candidate
            val value = candidate as? CharSequence ?: return@forEach
            val single = unwrapPair(value) ?: return@forEach
            args[index] = single
            lastPairCommitAt = System.currentTimeMillis()
            log("quote-pair: host dispatcher pair trimmed to single ${describe(single[0])}")
        }
        scheduleInspect(inputConnection, "dispatcher-trim")
    }
    private fun hookAll(cls: Class<*>): Int {
        var count = 0
        count += runCatching {
            XposedBridge.hookAllMethods(cls, "commitText", object : XC_MethodHook() {
                /**
                 * 形态一：宿主/native **一次性提交成对的两个字符**（`“”`）。
                 * 这不需要事后删除，直接在参数上把右半边去掉，交给宿主自己的流程提交单个引号——
                 * 比"提交完再删"少一次编辑往返，也不会让光标先跳到中间再跳回来。
                 */
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    val single = unwrapPair(text) ?: return
                    param.args[0] = single
                    lastPairCommitAt = System.currentTimeMillis()
                    log("quote-pair: pair commit trimmed to single '" + describe(single[0]) + "'")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    if (!mentionsPair(text)) return
                    lastPairCommitAt = System.currentTimeMillis()
                    inspect(param.thisObject, "commitText")
                }
            }).size
        }.onFailure { log("quote-pair: commitText hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)

        count += runCatching {
            XposedBridge.hookAllMethods(cls, "setComposingText", object : XC_MethodHook() {
                /**
                 * 组合文本同样会出现「一次送来一对」的形态（不少输入法把成对符号
                 * 先作为组合文本推上去，再决定是否提交）。这里做与 commitText 相同的处理。
                 */
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    val single = unwrapPair(text) ?: return
                    param.args[0] = single
                    lastPairCommitAt = System.currentTimeMillis()
                    log("quote-pair: composing pair trimmed to single '" + describe(single[0]) + "'")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.args?.getOrNull(0) as? CharSequence ?: return
                    if (!mentionsPair(text)) return
                    lastPairCommitAt = System.currentTimeMillis()
                }
            }).size
        }.onFailure { log("quote-pair: setComposingText hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)

        count += runCatching {
            XposedBridge.hookAllMethods(cls, "setSelection", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // setSelection 是高频调用，只有"左右两侧都是成对符号"这种极窄状态才继续
                    check(param.thisObject as? InputConnection)
                }
            }).size
        }.onFailure { log("quote-pair: setSelection hook failed on ${cls.name}: ${it.message}") }
            .getOrDefault(0)
        return count
    }

    /**
     * 若整段提交内容正好是「一个可配对的左符号 + 它的右符号」（允许前后带空白），
     * 返回只含左符号的内容；否则返回 null。
     *
     * 这是宿主"成对补全"最常见的形态：一次 commitText 把两个字符一起送上屏，
     * 再 setSelection 到中间。在参数上直接砍掉右半边即可，不需要任何事后删除。
     */
    private fun unwrapPair(text: CharSequence): CharSequence? {
        if (text.length != 2) return null
        val right = PAIRS[text[0]] ?: return null
        if (text[1] != right) return null
        return text.subSequence(0, 1)
    }

    /** 本次提交的文本是否涉及成对符号（含"一次提交成对"与"提交单个左符号"两种形态）。 */
    private fun mentionsPair(text: CharSequence): Boolean {
        if (text.isEmpty()) return false
        if (text.length > 4) return false
        for (i in 0 until text.length) {
            val c = text[i]
            if (PAIRS.containsKey(c) || PAIRS.containsValue(c)) return true
        }
        return false
    }

    private fun inspect(target: Any?, source: String) {
        scheduleInspect(target as? InputConnection, source)
    }

    /**
     * 宿主可能先提交左符号、稍后再补右符号；一次 post 不够。
     * 在一次输入动作后的多个时间点重读同一个编辑器状态，直到右符号出现并删除，
     * 或时间窗结束。这样不依赖某个单一的 native/Java 时序。
     */
    private fun scheduleInspect(ic: InputConnection?, source: String) {
        if (ic == null) return
        CLEANUP_DELAYS_MS.forEach { delay ->
            main.postDelayed({
                runCatching { check(ic, source) }
                    .onFailure { log("quote-pair: inspect failed: ${it.message}") }
            }, delay)
        }
    }

    /**
     * 检测"光标夹在一对符号中间"并拆掉右边那个。
     *
     * 成功条件必须**同时**满足，缺一不可：
     * 1. 上一次成对符号提交发生在 [SANDWICH_WINDOW_MS] 之内（这是唯一允许修正的时机，
     *    否则用户手动把光标点进已有的「（）」中间也会被误删）；
     * 2. 光标前恰好一个字符，且它是某个左符号；
     * 3. 光标后恰好一个字符，且它是该左符号的配对右符号；
     * 4. 距离上次修正超过 [MIN_INTERVAL_MS]。
     *
     * 任一条不满足即不做任何事——因此不会触碰任何非"刚被补全"的文本。
     */
    private fun check(ic: InputConnection?, source: String = "setSelection") {
        if (ic == null) return
        val now = System.currentTimeMillis()
        if (now - lastPairCommitAt > SANDWICH_WINDOW_MS) return
        val before = runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull() ?: return
        if (before.length != 1) return
        val expected = PAIRS[before[0]]
        val after = runCatching { ic.getTextAfterCursor(1, 0) }.getOrNull() ?: return
        if (now - lastFixAt < MIN_INTERVAL_MS) return

        val removed: Boolean
        val removedSymbol: Char
        if (expected != null && after.length == 1 && after[0] == expected) {
            // 光标位于左右符号之间，例如 “|”；删除右侧自动补出的符号。
            removed = deleteSurrounding(ic, 0, 1)
            removedSymbol = expected
        } else {
            // 另一种宿主时序：成对文本已提交，光标位于末尾，例如 “”|。
            val beforeTwo = runCatching { ic.getTextBeforeCursor(2, 0) }.getOrNull()
            if (beforeTwo == null || beforeTwo.length != 2) return
            val pairRight = PAIRS[beforeTwo[0]]
            if (pairRight == null || beforeTwo[1] != pairRight) return
            removed = deleteSurrounding(ic, 1, 0)
            removedSymbol = beforeTwo[1]
        }
        if (removed) {
            lastFixAt = System.currentTimeMillis()
            // 修正完成即关闭时间窗，避免同一状态被反复处理。
            lastPairCommitAt = 0L
            fixCount++
            log(
                "quote-pair: removed auto-inserted closing symbol '" + describe(removedSymbol) +
                    "' (source=" + source +
                    ", total=" + fixCount + ")"
            )
        } else {
            log("quote-pair: editor rejected deleteSurroundingText (source=$source)")
        }
    }

    private fun deleteSurrounding(ic: InputConnection, before: Int, after: Int): Boolean =
        runCatching { ic.deleteSurroundingText(before, after) }.getOrDefault(false)

    /** 只输出可读字符；引号本身用码位标注，避免日志里出现成对引号引起歧义。 */
    private fun describe(c: Char): String = String(charArrayOf(c)) + " (U+" + String.format("%04X", c.code) + ")"
}
