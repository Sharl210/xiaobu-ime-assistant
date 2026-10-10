package com.oplusime.panel

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Path
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

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
     * 英文逗号上滑的内部动作标记。显示层不再伪造字母 A：百度定制版的真实键面是
     * `assets/1080/res/more.png` 的第 2/3 贴图，动作链仍使用不可见哨兵文本。
     */
    const val EN_SUGGEST_MARK_OFF: String = "\u200B"

    /** 开启态使用同一个不可见哨兵；图标状态由宿主设置回读决定。 */
    const val EN_SUGGEST_MARK_ON: String = "\u200B"

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
        // 英文页逗号：**功能标记**（大写 A，开启时变粗体 A），由 applyLang 按开关实时决定，
        // 这里放关闭态作为默认值。
        ',' to EN_SUGGEST_MARK_OFF,
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
        // 中文页逗号：全角叹号（用户明确要求中文逗号上滑就是**符号本身**，不是开关功能）。
        ',' to "\uFF01", '\uFF0C' to "\uFF01",
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

    /** 中文资源及设置点击链验证：predict 为英文候选，suggestion 为英文联想。 */
    const val KEY_EN_PREDICT: String = "key_en_predict"
    const val KEY_EN_SUGGESTION: String = "key_en_suggestion"

    /** 宿主设置用的 SharedPreferences 名（未被混淆的字符串常量）。 */
    const val PREFS_NAME: String = "com.oplus.keyboard.restore.preference"

    /** 宿主包名：稳定语义锚点，不参与混淆。 */
    private const val HOST_PACKAGE: String = "com.oplus.keyboard"

    /** 最近一次拿到的 Context（上下滑标记命中时用来切开关）。 */
    @Volatile
    private var contextRef: android.content.Context? = null

    /** 英文候选键的原始黑色符号图，仅绘制黑色，不读取或维护颜色状态。 */
    @Volatile
    private var candidateIcon: Bitmap? = null
    @Volatile
    private var candidateIconLoadAttempted = false
    private val candidateIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val candidateIconSource = Rect(139, 0, 163, 31)

    /** 标记这个 SoftKey 已经被我们按当前语言刷过，避免每帧重复写。 */
    private const val TAG_LANG: String = "oplusime.swipe.lang"

    @Volatile
    private var drawHookCount: Int = 0
    @Volatile
    private var remoteProviderHooked = false
    @Volatile
    private var hostInputConnectionHookCount = 0
    @Volatile
    private var internalCommitHookCount = 0
    @Volatile
    private var remoteProviderSignature: String? = null
    private val drawMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val hostInputMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val internalCommitMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var installed = false

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /** 安装阶段结束后补交各个独立子挂点的真实数量，避免只记录总入口。 */
    fun publishDiagnostics() {
        HookDiagnostics.recordMatch("上滑键位绘制:绘制入口", drawMatchSignatures,
            "SoftKey 绘制规则匹配；运行时 hook 数量=$drawHookCount")
        HookDiagnostics.recordMatch("上滑动作:远程输入连接",
            listOfNotNull(remoteProviderSignature),
            "InputMethodService#getCurrentInputConnection 运行时提供者入口")
        HookDiagnostics.recordMatch("上滑动作:宿主输入连接", hostInputMatchSignatures,
            "InputConnection 实现类的方法规则匹配；运行时类 hook 数量=$hostInputConnectionHookCount")
        HookDiagnostics.recordMatch("上滑动作:内部提交出口", internalCommitMatchSignatures,
            "(int, CharSequence) 提交出口规则匹配；运行时 hook 数量=$internalCommitHookCount")
        HookDiagnostics.recordMatch("英文候选:读取器", listOfNotNull(flagReader?.toGenericString()),
            "key_en_predict 读取方法已解析")
        HookDiagnostics.recordMatch("英文候选:写入器", listOfNotNull(flagWriter?.toGenericString()),
            "key_en_predict 写入方法已解析")
    }

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
        // 所有静态查询必须在 HookEntry 的 DexKitBridge.use 关闭之前完成。
        // 运行时只能使用已经解析好的 Method，不能再次访问已释放的原生桥。
        resolveFlagAccessors()
        // 输入法换 input view（语音/手写全屏等）时把自绘图标收干净；
        // 语音面板不会 detach 键盘视图，只靠可见性巡检会漏（真机已见）。
        installDrawHook(bridge, hostClassLoader)
        installMarkTypography(bridge, hostClassLoader)
        installReturnDrawing()
        installSwipeToggleHook(hostClassLoader)
        bridgeRef = null
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
            remoteProviderHooked = true
            remoteProviderSignature = "${serviceClass.name}#getCurrentInputConnection():android.view.inputmethod.InputConnection"
        HookDiagnostics.recordMatch("上滑动作:远程输入连接", listOf(remoteProviderSignature!!),
            "提供者入口已由反射解析；实例方法在运行时首次返回时再挂载")
        }.onFailure {
            HookDiagnostics.record(null, "上滑动作:远程输入连接", false, "provider hook failed: ${it.message}")
            log("swipe-map: remote IC provider hook failed: ${it.message}")
        }

        // ② 宿主自己的 InputConnection 实现（不经框架代理的那条）。
        runCatching {
            val icClass = android.view.inputmethod.InputConnection::class.java
            val candidates: List<MethodData> = bridgeRef?.findMethod {
                matcher {
                    paramTypes("java.lang.CharSequence", "int")
                    returnType("boolean")
                }
            }?.toList().orEmpty()
            hostInputMatchSignatures.clear()
            var count = 0
            candidates.forEach { data ->
                val owner = data.declaredClassName ?: return@forEach
                if (!owner.startsWith(HOST_PACKAGE)) return@forEach
                val cls = runCatching { Class.forName(owner, false, hostClassLoader) }.getOrNull()
                    ?: return@forEach
                if (!icClass.isAssignableFrom(cls)) return@forEach
                runCatching { data.getMethodInstance(hostClassLoader) }
                    .getOrNull()
                    ?.let { hostInputMatchSignatures.add(HookDiagnostics.methodSignature(it)) }
                if (!hookedIcClasses.add(cls.name)) return@forEach
                hookIc(cls, null)
                count++
            }
            log("swipe-map: host IC impl classes hooked=$count")
            hostInputConnectionHookCount = count
        HookDiagnostics.recordMatch("上滑动作:宿主输入连接", hostInputMatchSignatures,
            "规则候选数量=${hostInputMatchSignatures.size}；运行时类 hook 数量=$count")

        }.onFailure {
            HookDiagnostics.record(null, "上滑动作:宿主输入连接", false, "scan failed: ${it.message}")
            log("swipe-map: host IC impl scan failed: ${it.message}")
        }

        // ③ 宿主内部的"提交文本"汇聚点：静态 `(int, CharSequence) -> boolean`
        //    宿主自己的日志串就是 "commitInternalText text="，说明这是内部提交入口。
        runCatching {
            val booleanCandidates = runCatching {
                bridgeRef?.findMethod {
                    matcher {
                        paramTypes("int", "java.lang.CharSequence")
                        returnType("boolean")
                    }
                }?.toList().orEmpty()
            }.getOrDefault(emptyList())
            val voidCandidates = runCatching {
                bridgeRef?.findMethod {
                    matcher {
                        paramTypes("int", "java.lang.CharSequence")
                        returnType("void")
                        addInvoke("Landroid/view/inputmethod/InputConnection;->commitText(Ljava/lang/CharSequence;I)Z")
                    }
                }?.toList().orEmpty()
            }.getOrDefault(emptyList())
            val candidates = (booleanCandidates + voidCandidates).distinctBy {
                "${it.declaredClassName}#${it.name}#${it.paramTypeNames.joinToString(",")}"
            }
            val matchedMethods = candidates.mapNotNull { data ->
                runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
            }
            internalCommitMatchSignatures.clear()
            internalCommitMatchSignatures.addAll(matchedMethods.map { HookDiagnostics.methodSignature(it) })
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
            internalCommitHookCount = count
            HookDiagnostics.recordMatch("上滑动作:内部提交出口", internalCommitMatchSignatures,
                "规则候选数量=${internalCommitMatchSignatures.size}；运行时 hook 数量=$count")
        }.onFailure {
            HookDiagnostics.record(null, "上滑动作:内部提交出口", false, "scan failed: ${it.message}")
            log("swipe-map: internal commit hook failed: ${it.message}")
        }
    }

    /** 文本是不是英文逗号上滑那枚标记（容忍首尾空白）。 */
    private fun isCommaMarker(text: CharSequence?): Boolean {
        val value = text?.toString()?.trim() ?: return false
        return value == EN_SUGGEST_MARK_OFF || value == EN_SUGGEST_MARK_ON
    }

    /** 命中标记：切开关 + 吞掉这次提交，并留一行证据。 */
    private fun onMarkerSeen(source: String): Boolean {
        val oldContext = contextRef
        val refreshView = lastKeyboardView?.get()
        val ctx = refreshView?.context ?: oldContext
        if (ctx == null) {
            log("swipe-map: marker seen from $source but host context unavailable")
            return true
        }
        contextRef = ctx
        val toggled = toggleEnglishSuggestion(ctx)
        // 真正生效与否以**落盘快照**为准：宿主"写"与"读"不是同一份内存副本，
        // 切换那一刻读回可能还是旧值（真机日志里就是 读取=false 而快照已经写入 false，
        // 但更早那次是被这条判据误判成"切换失败"并弹了失败提示）。
        val enabled = readFlag() == true
        val message = if (toggled && enabled) "单词模式" else if (toggled) "字母模式" else "英文候选切换失败"
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            // 首选**画在输入法窗口里**。
            //
            // 真机日志里系统 Toast 那一行一直打印"已显示"，用户却看不到 —— 输入法窗口
            // 层级上弹的 Toast 归属不对，系统会把它丢掉。自有覆盖层画在自己窗口里，
            // 一定看得见。只有覆盖层挂不上时才退回系统 Toast。
            val overlayShown = runCatching { SwipeIconLayer.toast(refreshView, message) }
                .getOrDefault(false)
            if (!overlayShown) {
                runCatching { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
                    .onFailure { log("swipe-map: toast failed message=$message error=${it.message}") }
            }
            log("swipe-map: toast shown message=$message overlay=$overlayShown")
        }
        log(
            "swipe-map: comma swipe candidates verified=$toggled state=$enabled" +
                " (source=$source, submission suppressed)"
        )
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
    /**
     * 结构特征：`(android.graphics.Canvas, 键实体类) → void`，且是**实例方法**
     * （静态那些拿不到视图、判不了语言）。宿主自己的 `f/g(Canvas, SoftKey)` 就是这条。
     *
     * 键实体类**不写死类名**：由 DexKit 按「实体形状」反查 ——
     * 该类含 `String keyText` / `String keyMark` 两个公开字符串字段（宿主自己的构造器
     * 参数名就是这两个词），并且存在 `(int, String, String)` 构造器。
     * 这样即使宿主换版本改了混淆名，匹配依然成立。
     */
    private fun installDrawHook(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val entityName = resolveSoftKeyClass(bridge, hostClassLoader) ?: run {
            HookDiagnostics.record(null, "上滑键位绘制:绘制入口", false, "SoftKey class unresolved")
            HookDiagnostics.record(null, "上滑键位绘制", false, "SoftKey class unresolved")
            log("swipe-map: SoftKey class unresolved; swipe map keeps host behaviour")
            return
        }
        val candidates: List<MethodData> = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes("android.graphics.Canvas", entityName)
                    returnType("void")
                }
            }.toList()
        }.onFailure { log("swipe-map: query failed: ${it.message}") }
            .getOrDefault(emptyList())
        log("swipe-map: draw candidates=${candidates.size} entity=$entityName")
        drawMatchSignatures.clear()
        drawMatchSignatures.addAll(candidates.mapNotNull { data ->
            runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?.let { HookDiagnostics.methodSignature(it) }
        })

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
                        val previousView = lastKeyboardView?.get()
                        if (previousView != null && previousView !== view) {
                            appliedLang.clear()
                            lastLang = null
                        }
                        param.setObjectExtra("oplusime.draw.previous.key", drawingKey.get())
                        param.setObjectExtra("oplusime.draw.previous.view", drawingView.get())
                        drawingKey.set(key)
                        drawingView.set(view)
                        lastKeyboardView = java.lang.ref.WeakReference(view)
                        rememberKeyboardView(view)
                        applyLang(view, key)
                    }

                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val view = param.thisObject as? View
                        val canvas = param.args?.getOrNull(0) as? Canvas
                        val key = param.args?.getOrNull(1)
                        if (view != null && canvas != null && key != null) {
                            drawEnglishCandidateSymbol(view, canvas, key)
                        }
                        drawingView.set(param.getObjectExtra("oplusime.draw.previous.view") as? View)
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
        drawHookCount = hooks
        log("swipe-map: installed hooks=$hooks")
        HookDiagnostics.recordMatch(
            "上滑键位绘制:绘制入口",
            drawMatchSignatures,
            "SoftKey 绘制规则匹配；运行时 hook 数量=$hooks；entity=$entityName",
        )
        HookDiagnostics.recordMatch(
            "上滑键位绘制",
            drawMatchSignatures,
            "绘制入口结构匹配；语言映射与英文候选黑色符号绘制在该入口执行",
        )
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
     * 已经挂过"脱离窗口即清图标"监听的键盘视图。
     *
     * 只挂一次；用弱引用身份表，避免把已经销毁的键盘视图钉在内存里。
     */
    private val watchedKeyboardViews: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /**
     * 键盘视图被移出窗口时，立刻清掉我们画在输入法窗口上的那层图标。
     *
     * 这是 1.33.31 那个"图标一直浮在输入法上、切到哪个界面都在"的回归修复的一半：
     * 另一半是覆盖层自己的心跳过期（见 [SwipeIconLayer] 注释）。这里覆盖的是
     * "键盘整个视图被换掉/销毁"这种宿主行为，不必再等 350 毫秒心跳。
     */
    private fun rememberKeyboardView(view: View) {
        if (!watchedKeyboardViews.add(view)) return
        runCatching {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    // 不再维护英文候选图标覆盖层或颜色状态。
                }
            })
        }.onFailure { log("swipe-map: attach-state listener failed: ${it.message}") }
    }

    /**
     * 用百度定制版 APK 的真实 `more.png` 精灵图覆盖英文逗号上滑标记。
     *
     * 资源已经随模块打包进 `assets/baidu_more.png`；运行时不能依赖设备安装百度输入法，
     * 因为百度 APK 只是取证来源，不是目标设备的运行时依赖。
     */
    // 英文候选图标不再由模块绘制。宿主原始图标保持原样，模块只负责开关功能和提示。

    /** 绘制英文页逗号键的原始黑色候选符号；不依赖开关，也不改变 keyMark。 */
    private fun drawEnglishCandidateSymbol(view: View, canvas: Canvas, key: Any) {
        if (resolveLang(view) != "en") return
        val text = readString(key, "s") ?: return
        if (text != "," && text != "\uFF0C") return
        val bitmap = loadCandidateIcon() ?: return
        val bounds = boundsOf(key) ?: return
        val width = (bounds.width() * 0.25f).toInt().coerceAtLeast(16)
        val height = (width * candidateIconSource.height() / candidateIconSource.width())
            .coerceAtLeast(20)
        val dst = Rect(
            bounds.left + (bounds.width() - width) / 2,
            bounds.top + (bounds.height() * 0.08f).toInt(),
            bounds.left + (bounds.width() - width) / 2 + width,
            bounds.top + (bounds.height() * 0.08f).toInt() + height,
        )
        candidateIconPaint.colorFilter = null
        candidateIconPaint.alpha = 255
        canvas.drawBitmap(bitmap, candidateIconSource, dst, candidateIconPaint)
        logThrottled("candidate-symbol", 3000L) {
            "swipe-map: english candidate symbol drawn black dst=${dst.left},${dst.top},${dst.right},${dst.bottom}"
        }
    }

    private fun loadCandidateIcon(): Bitmap? {
        candidateIcon?.let { return it }
        if (candidateIconLoadAttempted) return null
        synchronized(this) {
            candidateIcon?.let { return it }
            if (candidateIconLoadAttempted) return null
            candidateIconLoadAttempted = true
            candidateIcon = runCatching {
                val path = HookEntry.modulePath
                check(path.isNotBlank()) { "module APK path unavailable" }
                val assets = AssetManager::class.java.getDeclaredConstructor()
                    .apply { isAccessible = true }.newInstance()
                val cookie = assets.javaClass.getMethod("addAssetPath", String::class.java)
                    .invoke(assets, path) as? Number
                check(cookie?.toInt() != 0) { "module asset path rejected" }
                assets.open("baidu_more.png").use(BitmapFactory::decodeStream)
            }.onFailure { logCritical("swipe-map: candidate symbol load failed: ${it.message}") }
                .getOrNull()
            return candidateIcon
        }
    }

    /**
     * 按当前键盘语言重写一个 SoftKey 的上滑字符。
     *
     * 逐键判重：同一个键在同一语言下只写一次（键对象会被长期复用），
     * 语言切换则整批重写。写入失败会留一行日志，不会静默失效。
     */
    private fun applyLang(view: View?, key: Any) {
        val lang = resolveLang(view) ?: return
        if (view != null && contextRef == null) {
            contextRef = view.context
            HookDiagnostics.flush(view.context)
        }
        if (appliedLang[key] == lang) return
        runCatching {
            val text = readString(key, "s") ?: return
            if (text.length != 1) return
            val ch = text[0]
            val table = if (lang == "en") TABLE_EN else TABLE_CN
            var mapped = table[ch.lowercaseChar()] ?: return
            // 逗号位是**功能标记**，不是固定字符：关=普通 A，开=粗体 A。
            // 必须每帧按当前开关重取，否则用户上滑开关之后键面还是旧字重。
            // **只有英文页**的逗号位才是功能标记。
            //
            // 这里踩过一个真实的坑：原判据写成 `lang == "en" || ch == ','`，于是中文页上
            // 只要该键的字符是半角逗号，也会被替换成标记 —— 中文页逗号上滑因此变成了
            // "切英文候选"而不是用户明确要的「感叹号」。用户原话：
            // 「中文模式下它是对应的一个符号（感叹号），并不是开启候选的一个功能」。
            // 判据必须以**当前页面语言**为准，不能看字符本身。
            if (lang == "en" && (ch == ',' || ch == '\uFF0C')) {
                mapped = EN_SUGGEST_MARK_OFF
                logThrottled("candidate-marker", 3000L) {
                    "swipe-map: english candidate action marker retained; host icon remains unchanged"
                }
            } else if (lang != "en" && (mapped == "\uFF01" || mapped == "!")) {
                log(
                    "swipe-map: apply exclamation lang=$lang keyText=$ch" +
                        " rawMark=${readString(key, "t") ?: "<null>"} mapped=$mapped"
                )
            }
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
        // 把类型名交给键盘切换器：返回主键盘、以及"重切当前页让设置生效"都要用它。
        (view as? View)?.let { HostKeyboardSwitch.rememberKeyboardType(it, name) }
        val lang = if (name.startsWith("QWERTY_EN")) "en" else "zh"
        if (lang != lastLang) {
            appliedLang.clear()
            lastLang = lang
        }
        return lang
    }

    private val drawingKey = ThreadLocal<Any?>()
    private val drawingView = ThreadLocal<View?>()
    @Volatile private var lastKeyboardView: java.lang.ref.WeakReference<View>? = null
    private var keyBounds: List<java.lang.reflect.Field> = emptyList()

    private fun boundsOf(key: Any): Rect? = runCatching {
        if (keyBounds.size != 4) return null
        Rect(keyBounds[0].getInt(key), keyBounds[2].getInt(key),
            keyBounds[1].getInt(key), keyBounds[3].getInt(key))
    }.getOrNull()

    /**
     * 英文页半角 `!` 的参考画笔，按「键盘视图 + 键位对象」缓存。
     *
     * ## 为什么需要它（这是"感叹号偏大"的真正根因）
     *
     * 真机日志（`1.33.28-test`）里，中文页逗号上滑那一枚标记是：
     *
     * ```text
     * mark draw main=， mark=！ text=! targetExclamation=true
     * paintBefore=72.0/1.0/false paintAfter=72.0/1.0/false
     * ```
     *
     * 而同一帧里其它所有上滑符号都是 `36.0`。也就是说宿主给中文页的感叹号
     * **自己就用了 72（其它符号的两倍）**，而模块的修正分支没有真正改掉它 ——
     * `paintAfter` 与 `paintBefore` 完全一样，等于白改。
     *
     * 用户给出的正确参照就在手边：「英文面板里字母 A 上面那个感叹号」。
     * 那一枚宿主是用正常字号画的，所以这里改成**直接从英文页的同名键位取画笔**：
     * 先记下它，等中文页要画感叹号时用这份参考画笔的字号/字重/字面宽画。
     * 这样不写死任何数值，宿主改字号时两边一起变，大小天然一致。
     */
    private val englishMarkPaints = java.util.WeakHashMap<View, MutableMap<String, Paint>>()

    private fun rememberEnglishMarkPaint(view: View, text: String, paint: Paint) {
        val map = englishMarkPaints.getOrPut(view) { java.util.WeakHashMap() }
        map[text] = Paint(paint)
    }

    private fun englishMarkPaint(view: View): Paint? {
        val map = englishMarkPaints[view] ?: return null
        return map["!"] ?: map["\uFF01"]
    }

    /**
     * 普通字母键上滑标记的画笔，按键盘视图缓存 —— 也就是「周围那些符号」的基准量度。
     *
     * 真机日志里同一视图同一帧：字母键 / 标点键的标记全是 `36.000004`，
     * 只有中文页逗号键那枚感叹号是 `72.0`。用户指定的参照「句号上面那个符号」
     * 正是这一档。这里在绘制字母键标记时顺手记下来，中文页画感叹号时直接照抄。
     */
    private val normalMarkPaints = java.util.WeakHashMap<View, Paint>()

    private fun rememberNormalMarkPaint(view: View, paint: Paint) {
        normalMarkPaints[view] = Paint(paint)
    }

    private fun normalMarkPaint(view: View): Paint? = normalMarkPaints[view]

    private fun matchingMarkPaint(view: View, key: Any, text: String, paint: Paint): Paint? {
        val mark = readString(key, "t") ?: return null
        val main = readString(key, "s") ?: return null
        val chinesePage = resolveLang(view) != "en"
        val isExclamation = text == "！" || text == "!"
        val isComma = text == "," || text == "\uFF0C"
        val isChineseExclamation = chinesePage && isExclamation
        if (!isChineseExclamation && !(isComma && isExclamation)) return null
        // 注意：**不能**要求 `text == mark`。
        //
        // 真机日志里中文页这一枚是 `mark=！ text=!`：宿主的文本布局分支已经把全角
        // 换成了半角再交给绘制，两者本来就不同。上一版在这里写了 `if (text != mark) return null`，
        // 于是每一次都返回空 → `paintAfter` 与 `paintBefore` 一模一样（日志里 72.0 对 72.0），
        // 字号当然改不动。判据只按**键位那一栏是不是逗号位 + 当前是中文页**即可。
        // 中文页的感叹号：照抄「同一帧里普通字母键上滑标记」的画笔量度。
        //
        // 参照系来自真机日志（1.33.29，同一键盘视图同一帧）：
        //   main=q mark=1 textSize=36.000004
        //   main=. mark=? textSize=36.000004   ← 用户指定的参照：句号上方那枚符号
        //   main=， mark=！ textSize=72.0       ← 目标：宿主自己用了两倍
        // 字母键与标点键的上滑标记一律 36，只有逗号键那一枚是 72。
        // 因此以"字母键标记"的画笔为基准，天然与周围一致；宿主改字号时两边一起变，
        // 不写死任何数值。
        if (isChineseExclamation) {
            val reference = englishMarkPaint(view) ?: normalMarkPaint(view)
            if (reference == null) {
                logThrottled("exclamation-noref", 3_000L) {
                    "swipe-map: 感叹号暂无参照画笔（宿主原样绘制）main=$main mark=$mark text=$text"
                }
                // 参照还没采到的极少数帧序：把当前帧的重绘排上，
                // 等同一帧的字母键画完后再画一次，避免用户看到 72 的那一帧。
                runCatching { view.postInvalidateOnAnimation() }
                return null
            }
            log(
                "swipe-map: mark metrics main=$main text=$text" +
                    " refTextSize=${reference.textSize} refScale=${reference.textScaleX}" +
                    " refBold=${reference.isFakeBoldText} view=${view.javaClass.name}"
            )
            return Paint(reference).apply { color = paint.color }
        }
        // 英文页逗号键那一枚不是目标，保持宿主原样绘制。
        return null
    }

    /** 同时调整宿主小字坐标计算与最终绘制，缓存限定在同一个键盘视图。 */
    private fun installMarkTypography(bridge: DexKitBridge, loader: ClassLoader) {
        val entity = softKeyClassName ?: return
        val layouts = bridge.findMethod {
            matcher { paramTypes(entity, "android.graphics.Paint", "java.lang.String", "boolean") }
        }.map { it.getMethodInstance(loader) }.filter {
            View::class.java.isAssignableFrom(it.declaringClass) &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        layouts.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val key = param.args[0] ?: return
                    val paint = param.args[1] as? Paint ?: return
                    val text = param.args[2] as? String ?: return
                    matchingMarkPaint(view, key, text, paint)?.let { param.args[1] = it }
                }
            })
        }
        Canvas::class.java.declaredMethods.filter { method ->
            method.name == "drawText" && method.parameterTypes.firstOrNull() == String::class.java &&
                method.parameterTypes.lastOrNull() == Paint::class.java
        }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = drawingView.get() ?: return
                    val key = drawingKey.get() ?: return
                    val text = param.args.firstOrNull() as? String ?: return
                    val paint = param.args.lastOrNull() as? Paint ?: return
                    val main = readString(key, "s") ?: return
                    val mark = readString(key, "t") ?: return
                    val keyBox = boundsOf(key)
                    val isUpperMark = text == mark
                    val isTargetExclamation = resolveLang(view) != "en" &&
                        (text == "\uFF01" || text == "!")
                    if (!isUpperMark && !isTargetExclamation) return
                    val displayText = if (isTargetExclamation) "!" else text
                    val before = Paint(paint)
                    val rendered = when {
                        isTargetExclamation -> matchingMarkPaint(view, key, text, paint) ?: Paint(paint)
                        else -> {
                            // 英文页的上滑符号是中文页的**参照系**：把这一枚的量度记下来，
                            // 中文页画感叹号时直接照抄，两边大小才会真正一致。
                            if (resolveLang(view) == "en" && text == "!") {
                                rememberEnglishMarkPaint(view, text, paint)
                            }
                            // 字母键的上滑标记就是「周围那些符号」的基准：中文页
                            // 那一枚感叹号要跟它一样大。同一帧里字母键先画、逗号键后画
                            // （真机日志顺序：q→p、a→l、z→m，然后才是逗号键），
                            // 所以采到之后当帧就能用上。
                            if (main.length == 1 && (main[0] in 'a'..'z' || main[0] in 'A'..'Z')) {
                                rememberNormalMarkPaint(view, paint)
                            }
                            Paint(paint)
                        }
                    }
                    param.args[param.args.lastIndex] = rendered
                    param.args[0] = displayText
                    // ---------------------------------------------------------------
                    // 垂直对齐（用户反馈：字号对了，但比"句号上面那枚问号"低一截）。
                    //
                    // 真机日志（1.33.30，同一帧同一键盘视图）：
                    //   main=.  mark=？  这一枚宿主自己用 36 画，位置正常
                    //   main=， mark=！  paintBefore=72.0  paintAfter=36.000004
                    //
                    // 也就是说：宿主的**基线 y 是用它自己那把 72 的画笔算出来的**，
                    // 我们只在 drawText 这一层把画笔换成 36，字号小了、基线却还留在
                    // 大字号的位置上 —— 字形视觉中心因此整体往下掉，正是用户看到的
                    // "低一点"。
                    //
                    // 修正量不需要任何写死数值：把"旧画笔"和"新画笔"的字体量度差值
                    // 折算成半个高度差补回 y 上。旧新一致时 dy 自然是 0（不影响别的符号）。
                    // 上半部分用 (ascent+descent)/2 表示字形视觉中心相对基线的偏移，
                    // 差值即两把画笔之间需要补偿的基线位移。
                    // ---------------------------------------------------------------
                    val yIndex = param.args.lastIndex - 1
                    val hostY = param.args.getOrNull(yIndex) as? Float
                    var dy = 0f
                    if (hostY != null) {
                        // 用 `fontMetrics`（API 1 起就有）而不是 `ascent()/descent()`（API 29+），
                        // 免得在低版本设备上直接抛 NoSuchMethodError。
                        val oldMetrics = before.fontMetrics
                        val newMetrics = rendered.fontMetrics
                        dy = ((oldMetrics.ascent + oldMetrics.descent) -
                            (newMetrics.ascent + newMetrics.descent)) / 2f
                        if (kotlin.math.abs(dy) > 0.01f) param.args[yIndex] = hostY + dy
                    }
                    val beforeBounds = Rect()
                    val afterBounds = Rect()
                    before.getTextBounds(displayText, 0, displayText.length, beforeBounds)
                    rendered.getTextBounds(displayText, 0, displayText.length, afterBounds)
                    log(
                        "swipe-map: mark draw main=$main mark=$mark text=$text" +
                            " targetExclamation=$isTargetExclamation" +
                            " paintBefore=${before.textSize}/${before.textScaleX}/${before.isFakeBoldText}" +
                            " paintAfter=${rendered.textSize}/${rendered.textScaleX}/${rendered.isFakeBoldText}" +
                            " boundsBefore=${beforeBounds.width()}x${beforeBounds.height()}" +
                            " boundsAfter=${afterBounds.width()}x${afterBounds.height()}" +
                            " measureBefore=${before.measureText(displayText)}" +
                            " measureAfter=${rendered.measureText(displayText)}" +
                            " yHost=$hostY yApplied=${hostY?.let { it + dy }} dy=$dy" +
                            " keyBounds=${keyBox?.toShortString() ?: "<null>"}" +
                            " view=${view.javaClass.name} key=${key.javaClass.name}"
                    )
                    if (isTargetExclamation) {
                        HookDiagnostics.record(
                            view.context,
                            "上滑键位绘制",
                            true,
                            "${view.javaClass.name}; mark=$text; size=${rendered.textSize}; scale=${rendered.textScaleX}",
                        )
                    }
                }
            })
        }
        log("swipe-map: mark typography layout hooks=${layouts.size}")
    }

    /** 只替换回车键的绘制；文字标记仍保留给宿主按键行为判定。 */
    private fun installReturnDrawing() {
        Canvas::class.java.declaredMethods.filter { method ->
            method.name == "drawText" && method.parameterTypes.firstOrNull() == String::class.java &&
                method.parameterTypes.lastOrNull() == Paint::class.java
        }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val label = param.args.firstOrNull() as? String ?: return
                    // 保留宿主资源字符串“换行”，只在 Canvas 绘制层投影为回车符号。
                    // 不能在 Resources.getString/getText 层全局改写，否则宿主可能用
                    // 原始文案或资源结果判断主键盘动作，导致显示正常但点击链失效。
                    if (label != "换行" && label != "\u21B5") return
                    val key = drawingKey.get() ?: return
                    val box = boundsOf(key) ?: return
                    val hostPaint = param.args.lastOrNull() as? Paint ?: return
                    val canvas = param.thisObject as? Canvas ?: return
                    // 与功能键图标同量级，避免按小号文字的字号渲染回车。
                    val w = minOf(box.width(), box.height()) * 0.46f
                    val h = w * 0.64f
                    val x = box.exactCenterX() - w / 2f
                    val y = box.exactCenterY() - h / 2f
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = hostPaint.color
                        style = Paint.Style.STROKE
                        strokeWidth = w * 0.075f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    val line = Path().apply {
                        moveTo(x + w, y)
                        lineTo(x + w, y + h * 0.36f)
                        quadTo(x + w, y + h * 0.55f, x + w * 0.82f, y + h * 0.55f)
                        lineTo(x, y + h * 0.55f)
                        moveTo(x + w * 0.24f, y + h * 0.08f)
                        lineTo(x, y + h * 0.55f)
                        lineTo(x + w * 0.24f, y + h)
                    }
                    canvas.drawPath(line, paint)
                    param.result = null
                    logThrottled("return-icon", 5000L) { "return-icon: vector drawn bounds=$box width=$w" }
                }
            })
        }
    }

    /** 键实体类的类名（DexKit 反查，不写死混淆名）。 */
    @Volatile
    private var softKeyClassName: String? = null

    /**
     * 结构化反查「键位实体类」（宿主自己的 SoftKey）。
     *
     * ## 为什么不写死类名
     *
     * 全局硬约束：本模块**不允许**把宿主混淆类名写进匹配表达式。这里改成按**实体形状**查：
     *
     * ```text
     * 1. 存在构造器 (int, String, String)          —— keyCode + keyText + keyMark
     * 2. 该类有 ≥2 个 public String 实例字段        —— 就是 keyText / keyMark
     * 3. 该类没有父类实体（排除继承来的容器类）
     * ```
     *
     * 这三条一起，在整个宿主里只命中一个类（宿主自己的 SoftKey）。
     * 命中后把类名缓存下来，绘制匹配语句用它当参数类型。
     */
    private fun resolveSoftKeyClass(bridge: DexKitBridge, hostClassLoader: ClassLoader): String? {
        softKeyClassName?.let { return it }
        // 定位走方法签名：凡声明了 `(int, String, String)` 构造器的类都是候选
        // （keyCode + keyText + keyMark 是这个实体的唯一形状）。
        val ctorOwners = runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes("int", "java.lang.String", "java.lang.String")
                    returnType("void")
                }
            }.toList().mapNotNull { it.declaredClassName }
        }.getOrDefault(emptyList())
        log("swipe-map: softkey candidate owners=${ctorOwners.size}")
        ctorOwners.forEach { owner ->
            val cls = runCatching { Class.forName(owner, false, hostClassLoader) }.getOrNull()
                ?: return@forEach
            val strings = cls.declaredFields.count {
                it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            // 两个字符串字段 + 直接继承 Object —— 就是键位实体（排除带父类的容器/包装类）。
            if (strings >= 2 && cls.superclass == Any::class.java) {
                softKeyClassName = owner
                keyTextField = cls.declaredFields.single {
                    it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        !java.lang.reflect.Modifier.isFinal(it.modifiers)
                }.apply { isAccessible = true }
                keyMarkField = cls.declaredFields.single {
                    it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        java.lang.reflect.Modifier.isFinal(it.modifiers)
                }.apply { isAccessible = true }
                val boundaryMethods = bridge.findMethod {
                    matcher { declaredClass(owner); paramCount(5); returnType("void") }
                }.filter { it.paramTypeNames.take(4) == listOf("int", "int", "int", "int") }
                val bounds = boundaryMethods.singleOrNull()?.usingFields
                    ?.filter { it.usingType.toString().contains("WRITE", true) && it.field.typeName == "int" }
                    ?.map { it.field }?.distinctBy { it.descriptor }?.take(4).orEmpty()
                if (bounds.size == 4) {
                    keyBounds = bounds.map { it.getFieldInstance(hostClassLoader).apply { isAccessible = true } }
                }
                log("swipe-map: softkey class resolved=$owner stringFields=$strings bounds=${keyBounds.size}")
                return owner
            }
        }
        return null
    }

    /** 上滑只切中文设置页的“英文候选”，不改变“英文联想”或键盘类型。 */
    fun toggleEnglishSuggestion(context: Context): Boolean {
        val current = readFlag() ?: return false
        val next = !current
        val before = readFlagSnapshot()
        val written = writeFlag(next, context)
        val actual = readFlag()
        val ok = written && actual == next
        if (actual != null) {
            // 只更新宿主设置；不刷新、绘制或维护任何图标颜色状态。
        }
        log("swipe-map: candidates setting key=$KEY_EN_PREDICT before=$before requested=$next actual=$actual writeVerified=$ok after=${readFlagSnapshot()} keyboardSwitch=false")
        return ok
    }

    /** 宿主设置读写入口（结构匹配结果，进程内缓存）。 */
    @Volatile
    private var flagReader: java.lang.reflect.Method? = null

    @Volatile
    private var flagWriter: java.lang.reflect.Method? = null

    @Volatile
    private var flagResolved = false

    @Volatile
    private var candidateSettingGetter: java.lang.reflect.Method? = null

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
            val settingRead = bridge.findMethod {
                matcher {
                    usingStrings(listOf(KEY_EN_PREDICT), StringMatchType.Equals, false)
                    paramCount(0)
                    returnType("boolean")
                }
            }.single()
            candidateSettingGetter = settingRead.getMethodInstance(loader).apply { isAccessible = true }
            val readerData = settingRead.invokes.single {
                it.paramTypeNames == listOf("java.lang.String", "java.lang.String", "boolean") &&
                    it.returnTypeName == "boolean"
            }
            val writerData = bridge.findMethod {
                matcher {
                    declaredClass(readerData.declaredClassName)
                    paramTypes("java.lang.String", "java.lang.String", "boolean")
                    returnType("void")
                }
            }.single()
            flagReader = readerData.getMethodInstance(loader).apply { isAccessible = true }
            flagWriter = writerData.getMethodInstance(loader).apply { isAccessible = true }
            log("swipe-map: candidates accessors cached getter=$candidateSettingGetter writer=$flagWriter key=$KEY_EN_PREDICT")
            val readerSignature = flagReader?.let { HookDiagnostics.methodSignature(it) }
            val writerSignature = flagWriter?.let { HookDiagnostics.methodSignature(it) }
            HookDiagnostics.recordMatch(
                "英文候选:读取器",
                listOfNotNull(readerSignature),
                "key_en_predict 读取规则已解析",
            )
            HookDiagnostics.recordMatch(
                "英文候选:写入器",
                listOfNotNull(writerSignature),
                "key_en_predict 写入规则已解析",
            )
            HookDiagnostics.recordMatch(
                "英文候选读写",
                listOfNotNull(readerSignature, writerSignature),
                "同一宿主设置助手的读取/写入方法已解析",
            )
        }.onFailure {
            HookDiagnostics.record(null, "英文候选:读取器", false, "resolve failed: ${it.message}")
            HookDiagnostics.record(null, "英文候选:写入器", false, "resolve failed: ${it.message}")
            log("swipe-map: resolve settings accessors failed: ${it.message}")
        }
        return flagReader != null && flagWriter != null
    }

    private fun readFlagSnapshot(): String {
        if (!resolveFlagAccessors()) return "reader=UNRESOLVED"
        fun read(key: String): String = runCatching {
            (flagReader?.invoke(null, PREFS_NAME, key, true) as? Boolean)?.toString() ?: "null"
        }.getOrElse { "error:${it.javaClass.simpleName}:${it.message}" }
        return "$KEY_EN_PREDICT=${read(KEY_EN_PREDICT)},$KEY_EN_SUGGESTION=${read(KEY_EN_SUGGESTION)}"
    }

    private fun readFlag(): Boolean? {
        if (!resolveFlagAccessors()) return null
        return runCatching {
            (candidateSettingGetter?.invoke(null) as? Boolean)
        }.onFailure {
            log("swipe-map: read setting failed key=$KEY_EN_SUGGESTION error=${it.message}")
        }.getOrNull()
    }

    private fun writeFlag(value: Boolean, context: Context): Boolean {
        if (!resolveFlagAccessors()) return false
        return runCatching {
            val writer = flagWriter ?: return@runCatching false
            writer.invoke(null, PREFS_NAME, KEY_EN_PREDICT, value)
            // 宿主 writer 已自行通知监听，只调用一次；getter 在安装期缓存。
            val actual = readFlag()
            log("swipe-map: candidates write key=$KEY_EN_PREDICT expected=$value settingsReadback=$actual")
            actual == value
        }.onFailure { log("swipe-map: candidates write failed ${it.message}") }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 字段读写

    private var keyTextField: java.lang.reflect.Field? = null
    private var keyMarkField: java.lang.reflect.Field? = null

    /** 运行时仅访问安装阶段按字符串字段角色解析的成员。 */
    private fun readString(target: Any, field: String): String? = runCatching {
        (if (field == "s") keyTextField else keyMarkField)?.get(target) as? String
    }.getOrNull()

    private fun writeString(target: Any, field: String, value: String) {
        (if (field == "s") keyTextField else keyMarkField)?.set(target, value)
    }
}
