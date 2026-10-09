package com.oplusime.panel

import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference

/**
 * 让宿主自绘的候选拼音区域支持字符级光标定位。
 *
 * 宿主原本只把候选拼音画在自定义 View 上，触摸只负责水平拖动，
 * 通过组合文本更新链、绘制字段和引擎调用链定位，不固定混淆类名。
 * 真实插入/退格在引擎锁内修改 raw input；编辑态绘制保留文字间的光标空隙。
 */
internal object PinyinCursorEditor {
    @Volatile private var installed = false
    @Volatile private var targetClass: Class<*>? = null
    @Volatile private var textField: Field? = null
    @Volatile private var paintField: Field? = null
    @Volatile private var offsetField: Field? = null
    @Volatile private var kernelInstance: Any? = null
    @Volatile private var selectedLengthGetter: Method? = null
    @Volatile private var engineInstance: Any? = null
    private var engineSetInputMethod: Method? = null
    private var engineRawInputGetter: Method? = null
    private var engineSetCaretPosMethod: Method? = null
    private var engineEditCursorChangeMethod: Method? = null
    private var kernelSetInputMethod: Method? = null
    private var kernelEditCursorChangeMethod: Method? = null
    private var engineLockGetter: Method? = null
    @Volatile private var stableProcessorHookInstalled = false
    private val stableProcessorMatchSignatures = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var textStartMethod: Method? = null
    private var textTopMethod: Method? = null
    private var decorationMethod: Method? = null
    private var contentWidthField: Field? = null
    private var maxScrollMethod: Method? = null
    private var blankTouchCaptured = false
    private var blankTouchMoved = false
    @Volatile private var editingRawInput: String? = null


    /**
     * 目标自绘控件的实例（弱引用）。
     *
     * 真机两个版本（1.33.29 / 1.33.30）的日志里 `pinyin-cursor: tap` / `down` **一次都没有**，
     * 说明触摸事件压根到不了这一枚控件：宿主很可能在更外层的容器里就把手势接走了。
     * 因此本版不再只依赖它自己的 `onTouchEvent`，而是从**输入法窗口根**监听触摸，
     * 再按落点坐标判断是不是点在拼音区上 —— 这条路必然能收到事件。
     * 判断落点需要它的实例与屏幕矩形，所以在这里把它记下来。
     */
    @Volatile private var targetViewRef: WeakReference<View>? = null

    /** 我们自己记下的光标位置（组合文本下标）；-1 表示没有光标，不绘制。 */
    @Volatile private var caretIndex: Int = -1

    /**
     * 光标对应的**原始输入下标**（去掉拼音分隔符）。
     *
     * 引擎真正吃的是这个下标，界面显示用的是 [caretIndex]（带分隔符的文本下标）。
     * 两者都在日志里打出来，"光标画在这里但编辑落别处"这类问题才能一眼对齐。
     */
    @Volatile private var caretRawIndex: Int = -1

    /** 屏幕坐标下的按下点，用于"点击 vs 拖动"判定（根级触摸用）。 */
    @Volatile private var downRawX: Float = 0f
    @Volatile private var downRawY: Float = 0f
    @Volatile private var downInsidePinyin: Boolean = false

    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 触摸判定用的落点状态：DOWN 只记录，UP 时按位移区分"点击"与"拖动"。 */
    @Volatile private var downX: Float = 0f
    @Volatile private var downY: Float = 0f
    @Volatile private var downAt: Long = 0L
    @Volatile private var moved: Boolean = false

    /** 同一个事件会被 dispatchTouchEvent 与 onTouchEvent 各看到一次，用它去重。 */
    @Volatile private var lastEventSig: Long = 0L

    private fun eventSig(event: MotionEvent): Long {
        val x = (event.x * 10f).toLong()
        val y = (event.y * 10f).toLong()
        return event.downTime * 31L + event.actionMasked * 7L + x * 131L + y * 17L
    }

    // ---------------------------------------------------------------- 光标渲染
    //
    // 0.5 秒亮、0.5 秒灭；只隐藏墨迹，编辑位置与占位间隔保留。
    // 组合文本清空后同时释放显示下标与原始输入下标。
    @Volatile private var caretVisible: Boolean = true
    @Volatile private var blinkRunning: Boolean = false

    fun publishDiagnostics() {
        HookDiagnostics.recordMatch("候选拼音光标:真实编辑出口", stableProcessorMatchSignatures,
            "处理器结构匹配；运行时 hook 已安装=$stableProcessorHookInstalled")
    }

    fun install(bridge: DexKitBridge, loader: ClassLoader) {
        if (installed) return
        val owner = resolveTargetOwner(bridge, loader) ?: run {
            log("pinyin-cursor: target view unresolved")
            return
        }
        targetClass = owner
        resolveCompositionEditor(bridge, loader)
        installKernelProcessorCursorHook(bridge, loader)
        installTouchHook(bridge, loader, owner)
        installInstanceTracker(bridge, loader, owner)
        installRootTouchHook(owner)
        installCaretDrawing(owner)
        installed = true
        log("pinyin-cursor: installed target=${owner.name}")
    }

    /**
     * 记录目标控件实例。
     *
     * 挂点就是它自己的 `update(String, CandidatesFromModule, List)` —— 宿主每刷新一次
     * 候选拼音就会调它一次，于是我们总能拿到"当前真正在显示的那一枚控件"。
     * 不写死类名：挂的是结构匹配命中的那个方法。
     */
    private fun installInstanceTracker(bridge: DexKitBridge, loader: ClassLoader, owner: Class<*>) {
        runCatching {
            val updaters = bridge.findMethod {
                matcher {
                    declaredClass(owner.name)
                    paramCount(3)
                    returnType("void")
                }
            }.toList()
            var hooked = 0
            updaters.forEach { data ->
                val types = data.paramTypeNames
                if (types.size != 3 || types[0] != "java.lang.String" ||
                    types[2] != "java.util.List"
                ) return@forEach
                val method = runCatching { data.getMethodInstance(loader) }.getOrNull() ?: return@forEach
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (caretRawIndex < 0) return
                            val view = param.thisObject as? View ?: return
                            param.setObjectExtra("cursor.previousScroll", offsetField?.getFloat(view))
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            targetViewRef = WeakReference(view)
                            // 宿主每次刷新候选都会重设组合文本：文本一变（尤其是清空），
                            // 我们记下的光标位置就作废，避免"卡在上一次第几位"。
                            val text = readText(view).orEmpty()
                            if (text.isEmpty() && caretIndex >= 0) {
                                clearCaret()
                                log("pinyin-cursor: composition reset by host; caret cleared")
                            } else if (caretRawIndex >= 0) {
                                caretIndex = displayIndex(text, caretRawIndex)
                                val paint = paintField?.get(view) as? Paint
                                if (paint != null) {
                                    val previous = param.getObjectExtra("cursor.previousScroll") as? Float ?: 0f
                                    val limit = (maxScrollMethod?.invoke(view) as? Float ?: 0f).coerceAtLeast(0f)
                                    var scroll = previous.coerceIn(0f, limit)
                                    val origin = textStartMethod?.invoke(view) as? Float ?: 0f
                                    val cursor = origin + paint.measureText(text, 0, caretIndex) + caretGap(paint) / 2f
                                    val left = view.paddingLeft.toFloat()
                                    val right = (view.width - view.paddingRight).toFloat()
                                    if (cursor - scroll > right) scroll = (cursor - right + caretGap(paint)).coerceIn(0f, limit)
                                    else if (cursor - scroll < left) scroll = (cursor - left).coerceIn(0f, limit)
                                    offsetField?.setFloat(view, scroll)
                                    logThrottled("cursor-stable-update", 500L) {
                                        "pinyin-cursor: layout stable caret=$caretIndex previousScroll=$previous appliedScroll=$scroll gap=${caretGap(paint)}"
                                    }
                                }
                                view.invalidate()
                            }
                        }
                    })
                    hooked++
                }.onFailure { log("pinyin-cursor: instance tracker hook failed: ${it.message}") }
            }
            log("pinyin-cursor: instance tracker hooks=$hooked owner=${owner.name}")
        }.onFailure { log("pinyin-cursor: instance tracker failed: ${it.message}") }
    }

    private fun resolveTargetOwner(bridge: DexKitBridge, loader: ClassLoader): Class<*>? {
        val moduleName = runCatching {
            bridge.findClass {
                matcher { usingStrings(listOf("SELECT_PINYIN"), org.luckypray.dexkit.query.enums.StringMatchType.Equals) }
            }.firstOrNull()?.name
        }.getOrNull()
        val candidates: List<MethodData> = runCatching {
            if (moduleName != null) {
                bridge.findMethod {
                    matcher {
                        paramTypes("java.lang.String", moduleName, "java.util.List")
                        returnType("void")
                    }
                }.toList()
            } else {
                bridge.findMethod {
                    matcher {
                        paramCount(3)
                        returnType("void")
                    }
                }.toList()
            }
        }.onFailure { log("pinyin-cursor: update method query failed: ${it.message}") }
            .getOrDefault(emptyList())

        for (data in candidates.sortedByDescending { candidate ->
            val types = candidate.paramTypeNames
            when {
                types.size == 3 && types.getOrNull(0) == "java.lang.String" &&
                    types.getOrNull(2) == "java.util.List" &&
                    types.getOrNull(1)?.contains("CandidatesFromModule") == true -> 100
                types.size == 3 && types.getOrNull(0) == "java.lang.String" &&
                    types.getOrNull(2) == "java.util.List" -> 50
                else -> 0
            }
        }) {
            val types = data.paramTypeNames
            if (types.size != 3 || types[0] != "java.lang.String" || types[2] != "java.util.List") continue
            val ownerName = data.declaredClassName ?: continue
            val cls = runCatching { Class.forName(ownerName, false, loader) }.getOrNull() ?: continue
            if (!View::class.java.isAssignableFrom(cls)) continue
            if (types[1] == Paint::class.java.name) continue
            val fields = cls.declaredFields.toList()
            val string = fields.firstOrNull { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
            val paint = fields.firstOrNull { it.type == Paint::class.java && !Modifier.isStatic(it.modifiers) }
            val list = fields.firstOrNull { java.util.List::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            val floats = fields.filter { it.type == Float::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }
            val hasTouch = cls.declaredMethods.any {
                it.name == "onTouchEvent" &&
                    it.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))
            }
            if (string == null || paint == null || list == null || floats.isEmpty() || !hasTouch) continue
            string.isAccessible = true
            paint.isAccessible = true
            textField = string
            paintField = paint
            val originData = bridge.findMethod {
                matcher { declaredClass(cls.name); paramCount(0); returnType("float") }
            }.single { method ->
                method.invokes.any { it.name == "getPaddingLeft" } &&
                    method.invokes.any { it.name == "getTextMargin" }
            }
            textStartMethod = originData.getMethodInstance(loader).apply { isAccessible = true }
            textTopMethod = cls.declaredMethods.single {
                it.name == "getTextTopMargin" && it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType
            }.apply { isAccessible = true }
            val touchData = bridge.findMethod {
                matcher { declaredClass(cls.name); name("onTouchEvent"); paramTypes("android.view.MotionEvent") }
            }.single()
            // 滚动字段在触摸与绘制中共同使用；不再按反射字段顺序猜。
            val drawData = bridge.findMethod {
                matcher { declaredClass(cls.name); name("onDraw"); paramTypes("android.graphics.Canvas") }
            }.single()
            val scrollNames = touchData.usingFields.map { it.field }.filter { it.typeName == "float" }.map { it.name }.toSet()
            val common = drawData.usingFields.map { it.field }.filter { it.typeName == "float" && it.name in scrollNames }.distinctBy { it.name }
            offsetField = cls.getDeclaredField(common.single().name).apply { isAccessible = true }
            val maxScroll = touchData.invokes.single {
                it.declaredClassName == cls.name && it.paramTypeNames.isEmpty() && it.returnTypeName == "float"
            }.getMethodInstance(loader)
            maxScroll.isAccessible = true
            maxScrollMethod = maxScroll
            val measureData = bridge.findMethod {
                matcher { declaredClass(cls.name); name("onMeasure"); paramTypes("int", "int") }
            }.single()
            val widthData = measureData.usingFields.map { it.field }.filter {
                it.typeName == "float" && it.name != offsetField?.name
            }.distinctBy { it.name }.single()
            contentWidthField = widthData.getFieldInstance(loader).apply { isAccessible = true }
            val setSize = View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            XposedBridge.hookMethod(measureData.getMethodInstance(loader), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 保存父容器给出的整行规格，宿主原方法会改写为文字自然宽度。
                    param.setObjectExtra("cursor.rowSpec", param.args[0] as Int)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (readText(view).isNullOrEmpty()) return
                    val spec = param.getObjectExtra("cursor.rowSpec") as? Int ?: return
                    val available = View.MeasureSpec.getSize(spec)
                    val mode = View.MeasureSpec.getMode(spec)
                    if (mode == View.MeasureSpec.UNSPECIFIED || available <= 0) return
                    // 保留原生行高，宽度直接服从父容器；不改内容宽度，不重新测量。
                    setSize.invoke(view, available, view.measuredHeight)
                    val max = maxScrollMethod?.invoke(view) as? Float ?: 0f
                    offsetField?.let { it.setFloat(view, it.getFloat(view).coerceIn(0f, max.coerceAtLeast(0f))) }
                    logThrottled("pinyin-row-width", 800L) {
                        "pinyin-cursor: full row measured width=$available height=${view.measuredHeight} parent=${(view.parent as? View)?.measuredWidth} content=${contentWidthField?.getFloat(view)}"
                    }
                }
            })
            XposedBridge.hookMethod(maxScroll, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val paintNow = paintField?.get(view) as? Paint ?: return
                    val content = contentWidthField?.getFloat(view) ?: return
                    val viewport = view.measuredWidth.takeIf { it > 0 } ?: view.width
                    if (viewport <= 0) return
                    // 用整行实际视口计算滚动，含固定光标间隙，取消宿主旧的窄视口上限。
                    val gap = if (caretIndex >= 0) caretGap(paintNow) else 0f
                    param.result = (content + gap - viewport).coerceAtLeast(0f)
                }
            })
            decorationMethod = drawData.invokes.singleOrNull {
                it.declaredClassName == cls.name && it.paramTypeNames == listOf("android.graphics.Canvas") && it.returnTypeName == "void"
            }?.getMethodInstance(loader)?.apply { isAccessible = true }

            log("pinyin-cursor: candidate owner=${cls.name} update=${data.name} params=${types.joinToString()} fields string=${string.name} paint=${paint.name} offset=${offsetField?.name} floats=${floats.size}")
            return cls
        }
        log("pinyin-cursor: update candidates=${candidates.size}, no structural owner")
        return null
    }

    private fun resolveCompositionEditor(bridge: DexKitBridge, loader: ClassLoader) {
        // 旧版可以从日志字符串反推调用图；新版去掉了这些字符串，因此最终以
        // DexKit 的 engine_jni 语义锚点 + JNI 方法形状 + Kernel 锁/单例结构为准。
        runCatching {
            val processing = bridge.findMethod {
                matcher {
                    usingStrings(listOf("nothing process, get words only"), org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                    paramCount(0)
                    returnType("void")
                }
            }.single()
            val nativeCall = processing.invokes.single {
                it.paramTypeNames == listOf("int", "int") && it.returnTypeName == "boolean"
            }
            val engineClass = nativeCall.getMethodInstance(loader).declaringClass
            val kernelCall = processing.invokes.single {
                it.name == "getInput" && it.paramTypeNames.isEmpty() && it.returnTypeName == "java.lang.String"
            }
            configureEngineAndKernel(engineClass, kernelCall.getMethodInstance(loader).declaringClass, loader)
            log("pinyin-cursor: engine resolved by legacy process graph engine=${engineClass.name}")
        }.onFailure { log("pinyin-cursor: legacy composition editor resolve skipped: ${it.message}") }

        if (engineInstance == null) {
            runCatching {
                val engineClass = bridge.findClass {
                    matcher { usingStrings(listOf("engine_jni"), org.luckypray.dexkit.query.enums.StringMatchType.Equals) }
                }.mapNotNull { runCatching { it.getInstance(loader) }.getOrNull() }
                    .firstOrNull { cls ->
                        cls.declaredMethods.any { it.name == "getRawInput" && it.parameterTypes.isEmpty() } &&
                            cls.declaredMethods.any { it.name == "setInput" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) } &&
                            cls.declaredMethods.any { it.name == "setCaretPos" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
                    } ?: error("semantic Engine class unresolved")
                val inputMethod = bridge.findMethod {
                    matcher { name("getInput"); paramCount(0); returnType("java.lang.String") }
                }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
                    .firstOrNull() ?: error("Kernel input accessor unresolved")
                configureEngineAndKernel(engineClass, inputMethod.declaringClass, loader)
                log("pinyin-cursor: engine resolved by stable JNI shape engine=${engineClass.name} kernel=${inputMethod.declaringClass.name} lock=$engineLockGetter")
            }.onFailure { log("pinyin-cursor: stable composition editor resolve failed: ${it.message}") }
        }
    }

    private fun configureEngineAndKernel(engineClass: Class<*>, kernelClass: Class<*>, loader: ClassLoader) {
        engineInstance = engineClass.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == engineClass
        }?.apply { isAccessible = true }?.get(null) ?: error("Engine singleton unresolved")
        kernelInstance = kernelClass.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == kernelClass
        }?.apply { isAccessible = true }?.get(null) ?: error("Kernel singleton unresolved")
        engineLockGetter = kernelClass.declaredMethods.firstOrNull {
            it.parameterTypes.isEmpty() && java.util.concurrent.locks.ReentrantLock::class.java.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }
        selectedLengthGetter = kernelClass.declaredMethods.firstOrNull {
            it.name == "getCurrentSelectedLength" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }
        engineRawInputGetter = engineClass.declaredMethods.firstOrNull {
            it.name == "getRawInput" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
        }?.apply { isAccessible = true }
        engineSetInputMethod = engineClass.declaredMethods.firstOrNull {
            it.name == "setInput" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                it.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }
        engineSetCaretPosMethod = engineClass.declaredMethods.firstOrNull {
            it.name == "setCaretPos" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.apply { isAccessible = true }
        engineEditCursorChangeMethod = engineClass.declaredMethods.firstOrNull {
            it.name == "editCursorChange" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }
        kernelSetInputMethod = kernelClass.declaredMethods.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(String::class.java, Boolean::class.javaPrimitiveType)) &&
                it.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }
        check(engineLockGetter != null && engineRawInputGetter != null && engineSetInputMethod != null) {
            "stable engine edit methods unresolved"
        }
    }

    private fun clearCaret() {
        caretIndex = -1
        caretRawIndex = -1
        editingRawInput = null
        caretVisible = true
    }

    private fun displayIndex(text: String, raw: Int): Int {
        var count = 0
        for (i in text.indices) {
            if (count >= raw) return i
            if (!isSeparator(text[i])) count++
        }
        return text.length
    }

    /**
     * 在已获得宿主引擎锁的处理阶段修改真实 raw input。
     * 该宿主 OkimEngine::setCaretPos(int) 的实现只有 ret；selectedLength 则是
     * 已选候选前缀，不能拿它冒充编辑光标。这里只接管字母与退格，保留原候选发布链。
     */
    private fun installKernelProcessorCursorHook(bridge: DexKitBridge, loader: ClassLoader) {
        if (stableProcessorHookInstalled) return
        runCatching {
            val kernel = kernelInstance ?: error("kernel instance unavailable")
            val getRaw = engineRawInputGetter ?: error("raw input getter unavailable")
            val engine = engineInstance ?: error("native engine unavailable")
            val setInput = kernelSetInputMethod ?: error("locked kernel setInput unavailable")
            // 两版协程参数的类名不同；只用稳定的 key/traceId 语义与参数形状筛选。
            val filteredCandidates = bridge.findMethod {
                matcher {
                    usingStrings(listOf("traceId"), org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                    paramCount(11)
                    returnType("void")
                }
            }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
                .filter { method ->
                    val p = method.parameterTypes
                    !Modifier.isStatic(method.modifiers) && p.size == 11 &&
                        p.take(3).all { it == java.lang.Integer::class.java } &&
                        p[3] == String::class.java && p[4] == java.lang.Boolean::class.java &&
                        p[5] == java.lang.Boolean::class.java && p[7] == String::class.java &&
                        p[10] == Boolean::class.javaPrimitiveType &&
                        method.declaringClass.declaredFields.any { it.type == java.util.concurrent.atomic.AtomicReference::class.java } &&
                        method.declaringClass.declaredFields.any { it.type == java.util.concurrent.ConcurrentHashMap::class.java } &&
                        kernel.javaClass.declaredMethods.any { it.parameterTypes.isEmpty() && it.returnType == method.declaringClass }
                }
            stableProcessorMatchSignatures.clear()
            stableProcessorMatchSignatures.addAll(filteredCandidates.map { HookDiagnostics.methodSignature(it) })
            val method = filteredCandidates.singleOrNull()?.apply { isAccessible = true }
                ?: error("kernel input processor shape ambiguous: ${filteredCandidates.map { it.toString() }}")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (caretRawIndex < 0) return
                    val code = param.args?.getOrNull(0) as? Int ?: return
                    val action = param.args?.getOrNull(1) as? Int
                    if (action != null && action != 0) return
                    if (code != 8 && code != 0xff08 && code !in 'a'.code..'z'.code &&
                        code !in 'A'.code..'Z'.code && code != '\''.code) return
                    runCatching edit@{
                        val before = getRaw.invoke(engine) as? String ?: return@edit
                        if (before.isEmpty() || before != editingRawInput) {
                            clearCaret()
                            log("pinyin-cursor: raw session changed; release edit before=$before")
                            return@edit
                        }
                        val edit = CompositionEdit.apply(before, caretRawIndex, code) ?: return@edit
                        if (edit.text == before) { // Backspace at the start: do not delete in the target app.
                            param.result = null
                            return@edit
                        }
                        val accepted = setInput.invoke(kernel, edit.text, true) == true
                        val actual = getRaw.invoke(engine) as? String
                        if (!accepted || actual != edit.text) {
                            if (actual != before) runCatching { setInput.invoke(kernel, before, true) }
                            val restored = getRaw.invoke(engine) as? String == before
                            if (!restored) param.result = null
                            clearCaret()
                            log("pinyin-cursor: edit rejected before=$before after=$actual restored=$restored")
                            return@edit
                        }
                        caretRawIndex = edit.caret
                        editingRawInput = edit.text
                        caretIndex = displayIndex(edit.text, edit.caret)
                        param.result = null
                        log("pinyin-cursor: raw edit key=$code before=$before after=${edit.text} caret=${edit.caret} verified=true")
                    }.onFailure { error ->
                        clearCaret()
                        log("pinyin-cursor: raw edit failed ${error.message}; host processing retained")
                    }
                }
            })
            stableProcessorHookInstalled = true
            HookDiagnostics.record(null, "候选拼音光标:真实编辑出口", true,
                "${method.declaringClass.name}#${method.name}(${method.parameterTypes.joinToString { it.name }}); kernel=${setInput.declaringClass.name}#${setInput.name}")
            log("pinyin-cursor: raw edit hook installed method=$method kernelSetInput=$setInput")
        }.onFailure {
            HookDiagnostics.record(null, "候选拼音光标:真实编辑出口", false, it.message.orEmpty())
            log("pinyin-cursor: raw edit resolve failed: ${it.message}")
        }
    }

    private fun applyCompositionCursor(index: Int, text: String): Boolean {
        val lock = runCatching { engineLockGetter?.invoke(kernelInstance) as? java.util.concurrent.locks.ReentrantLock }.getOrNull()
            ?: return false
        // 不在主线程等待异步解码锁；繁忙时保留原行为，不伪报已定位。
        if (!lock.tryLock()) return false
        try {
            val raw = engineRawInputGetter?.invoke(engineInstance) as? String ?: return false
            val visibleRaw = text.filterNot { isSeparator(it) }
            // 只接管能一一映射的拼音组合；已选中文前缀等状态不猜索引。
            if (raw.isEmpty() || !raw.equals(visibleRaw, ignoreCase = true)) {
                log("pinyin-cursor: mapping unresolved raw=$raw display=$text")
                return false
            }
            val bounded = index.coerceIn(0, text.length)
            caretRawIndex = text.take(bounded).count { !isSeparator(it) }.coerceIn(0, raw.length)
            editingRawInput = raw
            caretIndex = displayIndex(text, caretRawIndex)
            caretVisible = true
            log("pinyin-cursor: edit session selected display=$caretIndex raw=$caretRawIndex input=$raw; native setter unused")
            return true
        } catch (failure: Throwable) {
            log("pinyin-cursor: select failed ${failure.message}")
            return false
        } finally {
            lock.unlock()
        }
    }

    /** 组合文本里的分隔符（拼音音节分隔），不占"原始输入"的下标。 */
    private fun isSeparator(ch: Char): Boolean = ch == '\'' || ch == '’' || ch == ' '

    private fun installTouchHook(bridge: DexKitBridge, loader: ClassLoader, owner: Class<*>) {
        val methods = runCatching {
            bridge.findMethod {
                matcher {
                    declaredClass(owner.name)
                    paramTypes("android.view.MotionEvent")
                    returnType("boolean")
                }
            }.toList()
        }.getOrDefault(emptyList())
        // 宿主那一枚控件声明了哪些 `(MotionEvent) -> boolean` 就**全挂上**。
        //
        // 真机日志（1.33.29 / 1.33.30）里 `pinyin-cursor: tap` 一次都没有出现，
        // 说明连 UP 都没进到我们的分支：宿主很可能覆写了 `dispatchTouchEvent`
        // 并且不调 super（或挂了 OnTouchListener），只挂 `onTouchEvent` 永远收不到事件。
        // 所以这里既挂宿主自己的实现，也挂一条 `View.dispatchTouchEvent` 通用兜底。
        var hooked = 0
        methods.forEach { data ->
            val m = runCatching { data.getMethodInstance(loader) }.getOrNull() ?: return@forEach
            if (!View::class.java.isAssignableFrom(m.declaringClass)) return@forEach
            runCatching {
                XposedBridge.hookMethod(m, touchHook("${m.declaringClass.name}#${m.name}"))
                hooked++
            }.onFailure { log("pinyin-cursor: touch hook failed ${data.name}: ${it.message}") }
        }
        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java,
                "dispatchTouchEvent",
                touchHook("View.dispatchTouchEvent"),
            )
            hooked++
        }.onFailure { log("pinyin-cursor: View.dispatchTouchEvent hook failed: ${it.message}") }
        if (hooked == 0) {
            log("pinyin-cursor: touch method unresolved owner=${owner.name} candidates=${methods.size}")
            return
        }
        log("pinyin-cursor: touch hooks=$hooked target=${owner.name}")
    }

    /**
     * 从**输入法窗口根**接收触摸。
     *
     * ## 为什么必须走这一条
     *
     * 两个版本的真机日志里，挂在目标控件自身 `onTouchEvent` / `dispatchTouchEvent` 上的钩子
     * 一次 `down` 记录都没有 —— 事件根本没送到那一枚控件手上（宿主更外层的容器先接走了）。
     *
     * 这一条挂在 `View.dispatchTouchEvent` 上，做一次极廉价的过滤：
     * 只有**自己就是所在窗口根**的那个 View（输入法窗口的根）才继续处理，
     * 其余全部立刻返回，所以对正常打字几乎没有额外开销。
     * 命中根之后，按事件的屏幕坐标判断是否落在拼音控件矩形内；
     * 是则记录按下，抬起时若基本没移动就当作"点击定位光标"，
     * 并消费掉这次事件（避免宿主把它当成拖动滚动）。
     */
    private fun installRootTouchHook(owner: Class<*>) {
        runCatching {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { onRootTouch(param) }
                        .onFailure { log("pinyin-cursor: root touch failed: ${it.message}") }
                }
            }
            // DecorView 的容器分发覆盖了 View 分发，必须覆盖 ViewGroup 入口。
            XposedBridge.hookAllMethods(View::class.java, "dispatchTouchEvent", hook)
            XposedBridge.hookAllMethods(android.view.ViewGroup::class.java, "dispatchTouchEvent", hook)
            log("pinyin-cursor: root touch hook installed owner=${owner.name}")
        }.onFailure { log("pinyin-cursor: root touch hook failed: ${it.message}") }
    }

    private fun onRootTouch(param: XC_MethodHook.MethodHookParam) {
        val view = param.thisObject as? View ?: return
        // 只认"输入法窗口的根"这一个 View，其它一律不处理。
        if (view.rootView !== view) return
        val event = param.args?.firstOrNull() as? MotionEvent ?: return
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            if (blankTouchCaptured) param.result = true
            blankTouchCaptured = false
            downInsidePinyin = false
            return
        }
        val target = targetViewRef?.get() ?: return
        if (targetClass?.isInstance(target) != true) return
        if (!target.isAttachedToWindow || !target.isShown || target.width <= 0 || target.height <= 0 || readText(target).isNullOrEmpty()) return
        if (target.rootView !== view) return
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val rootRect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(rootRect)) return
        val rowRect = android.graphics.Rect()
        if (!target.getGlobalVisibleRect(rowRect)) return
        val inside = event.rawX >= rowRect.left && event.rawX < rootRect.right &&
            event.rawY >= rowRect.top && event.rawY < rowRect.bottom
        val inBlank = inside && event.rawX >= rowRect.right
        if (action == MotionEvent.ACTION_MOVE) {
            if (blankTouchCaptured) {
                val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
                if (kotlin.math.abs(event.rawX - downRawX) > slop || kotlin.math.abs(event.rawY - downRawY) > slop) blankTouchMoved = true
                param.result = true
            }
            return
        }
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return
        if (action == MotionEvent.ACTION_DOWN) {
            downRawX = event.rawX
            downRawY = event.rawY
            downInsidePinyin = inside
            blankTouchCaptured = inBlank
            blankTouchMoved = false
            if (inBlank) param.result = true // 接住 DOWN，才能收到控件外的完整手势。
            downAt = android.os.SystemClock.uptimeMillis()
            logThrottled("pinyin-cursor-down", 400L) {
                "pinyin-cursor: down raw=${event.rawX},${event.rawY} insidePinyin=$inside" +
                    " rect=${loc[0]},${loc[1]},${target.width}x${target.height}" +
                    " text='${readText(target).orEmpty()}' view=${target.javaClass.name}"
            }
            return
        }
        // 抬起：只有"按下和抬起都落在拼音区、且横向几乎没动"才算点击。
        val captured = blankTouchCaptured
        blankTouchCaptured = false
        if (captured) param.result = true
        if (!downInsidePinyin || !inside || blankTouchMoved) { downInsidePinyin = false; return }
        val dx = kotlin.math.abs(event.rawX - downRawX)
        val held = android.os.SystemClock.uptimeMillis() - downAt
        val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
        if (dx > slop || kotlin.math.abs(event.rawY - downRawY) > slop || held > 800L) {
            logThrottled("pinyin-cursor-drag", 800L) {
                "pinyin-cursor: 视为拖动，交还宿主 dx=$dx held=$held"
            }
            return
        }
        downInsidePinyin = false
        val localX = event.rawX - loc[0]
        val index = (if (inBlank) readText(target)?.length else indexAtLocalX(target, localX)) ?: run {
            log("pinyin-cursor: tap raw=$localX but index unresolved (text/paint field missing)")
            return
        }
        val text = readText(target).orEmpty()
        val ok = applyCompositionCursor(index, text)
        log(
            "pinyin-cursor: tap source=root localX=$localX index=$index text='$text' blankToEnd=$inBlank rowRight=${rootRect.right}" +
                " width=${target.width} padLeft=${target.paddingLeft}" +
                " scroll=${offsetField?.let { runCatching { it.get(target) }.getOrNull() }}" +
                " hostSelected=${readHostSelected()} applied=$ok" +
                " view=${target.javaClass.name}"
        )
        HookDiagnostics.record(target.context, "候选拼音光标", true,
            "view=${target.javaClass.name}; index=$index; text=$text; applied=$ok")
        if (ok) {
            // 消费掉这次点击：宿主这一枚控件原本把横向拖动当作滚动，
            // 不消费的话它可能顺势把手势挪走。
            param.result = true
            startBlink(target)
            target.invalidate()
        }
    }

    /** 编辑态按同一坐标模型绘制左右文字与固定光标空隙。 */
    private fun installCaretDrawing(owner: Class<*>) {
        runCatching {
            val draw = owner.declaredMethods.single {
                it.name == "onDraw" && it.parameterTypes.contentEquals(arrayOf(android.graphics.Canvas::class.java))
            }
            XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val canvas = param.args[0] as? android.graphics.Canvas ?: return
                    if (caretIndex < 0 || readText(view).isNullOrEmpty()) return
                    runCatching {
                        drawCaret(view, canvas)
                        param.result = null
                    }.onFailure { log("pinyin-cursor: custom composition draw failed ${it.message}") }
                }
            })
            log("pinyin-cursor: composition gap drawing installed owner=${owner.name}")
        }.onFailure { log("pinyin-cursor: drawing hook failed ${it.message}") }
    }

    private fun caretGap(paint: Paint): Float = (paint.textSize * 0.22f).coerceAtLeast(4f)

    private fun drawCaret(view: View, canvas: android.graphics.Canvas) {
        val live = readText(view).orEmpty()
        if (live.isEmpty()) { clearCaret(); return }
        val paint = Paint(paintField?.get(view) as? Paint ?: return)
        val bounded = caretIndex.coerceIn(0, live.length)
        val gap = caretGap(paint)
        val start = textOriginX(view)
        val metrics = paint.fontMetrics
        val topMargin = (textTopMethod?.invoke(view) as? Int ?: 0).toFloat()
        val baseline = view.height / 2f - (metrics.ascent + metrics.descent) / 2f + topMargin
        val boundary = start + paint.measureText(live, 0, bounded)
        val save = canvas.save()
        try {
            canvas.clipRect(view.paddingLeft, 0, view.width - view.paddingRight, view.height)
            // 固定占位在亮灭两相位均保留；只有光标墨迹闪烁。
            canvas.drawText(live, 0, bounded, start, baseline, paint)
            canvas.drawText(live, bounded, live.length, boundary + gap, baseline, paint)
            if (caretVisible) {
                caretPaint.color = paint.color
                caretPaint.style = Paint.Style.FILL
                val width = (paint.textSize * 0.09f).coerceAtLeast(2f)
                val center = boundary + gap / 2f
                canvas.drawRect(center - width / 2f, baseline + metrics.ascent,
                    center + width / 2f, baseline + metrics.descent, caretPaint)
            }
            decorationMethod?.invoke(view, canvas)
        } finally { canvas.restoreToCount(save) }
    }

    /**
     * 光标的两个相位间隔：0.5 秒亮、0.5 秒灭（用户明确要求的节奏）。
     *
     * 注意"隐藏"只是**不画**，光标位置本身一直留着 —— 所以隐藏期间点拼音
     * 照样能定位（用户要的正是这个行为）。
     */
    private const val BLINK_PHASE_MS = 500L

    /**
     * 光标闪烁：0.5 秒显示、0.5 秒隐藏。
     *
     * 只在光标有效期间跑；组合文本清空时由 [drawCaret] 把位置归零，
     * 循环发现没有光标就自行退出，不做常驻轮询。
     */
    private fun startBlink(view: View) {
        if (blinkRunning) return
        blinkRunning = true
        caretVisible = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (caretIndex < 0 || !view.isAttachedToWindow) {
                    blinkRunning = false
                    caretVisible = true
                    return
                }
                caretVisible = !caretVisible
                runCatching { view.invalidate() }
                handler.postDelayed(this, BLINK_PHASE_MS)
            }
        }
        handler.postDelayed(tick, BLINK_PHASE_MS)
    }


    private fun touchHook(source: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching { onTouch(source, param) }
                .onFailure { log("pinyin-cursor: touch handler failed source=$source: ${it.message}") }
        }
    }

    private fun onTouch(source: String, param: XC_MethodHook.MethodHookParam) {
        val view = param.thisObject as? View ?: return
        if (targetClass?.isInstance(view) != true) return
        val event = param.args?.firstOrNull() as? MotionEvent ?: return
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_UP
        ) return
        // 同一个事件会被 dispatchTouchEvent 与 onTouchEvent 各看到一次，去重。
        val sig = eventSig(event)
        if (sig == lastEventSig) return
        lastEventSig = sig
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // **不能**在这里把按下吞掉。
                    //
                    // 宿主这一枚 View 的原始行为是「按下后横向拖动滚动候选拼音」。上一版在
                    // ACTION_DOWN 就把事件消费掉，宿主再也收不到后续的 MOVE，滚动直接失效；
                    // 而且真机日志里始终没有 `pinyin-cursor: tap` 这一行 —— 连 UP 都没进到
                    // 我们的分支（手势被上游 ViewGroup 提前接走）。因此这里改成：
                    // DOWN 只**记住落点**并放行，UP 时若位移很小才按"点击"处理，
                    // 位移大就原样交给宿主当拖动。
                    downX = event.x
                    downY = event.y
                    moved = false
                    downAt = android.os.SystemClock.uptimeMillis()
                    logThrottled("pinyin-cursor-down", 500L) {
                        "pinyin-cursor: down source=$source x=${event.x} y=${event.y}" +
                            " view=${view.javaClass.name}"
                    }
                    return
                }
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                val held = android.os.SystemClock.uptimeMillis() - downAt
                if (dx > view.width * 0.06f || dy > view.height * 0.5f || held > 600L) {
                    // 判定为拖动/长按：让宿主自己处理，不要抢。
                    logThrottled("pinyin-cursor-drag", 1000L) {
                        "pinyin-cursor: 视为拖动，交还宿主 dx=$dx dy=$dy held=$held"
                    }
                    return
                }
                val index = mapXToIndex(view, event.x)
                if (index == null) {
                    log("pinyin-cursor: tap x=${event.x} but index unresolved (text/paint field missing)")
                    return
                }
                val text = readText(view).orEmpty()
                val result = applyCompositionCursor(index, text)
                log(
                    "pinyin-cursor: tap source=$source x=${event.x} index=$index text='$text'" +
                        " measured=${runCatching { (paintField?.get(view) as? Paint)?.measureText(text) }.getOrNull()}" +
                        " width=${view.width} padLeft=${view.paddingLeft}" +
                        " scroll=${offsetField?.let { runCatching { it.get(view) }.getOrNull() }}" +
                        " hostSelected=${readHostSelected()}" +
                        " view=${view.javaClass.name}"
                )
                HookDiagnostics.record(view.context, "候选拼音光标", true,
                    "view=${view.javaClass.name}; index=$index; text=$text; applied=$result")
                if (result) {
                    param.result = true
                    startBlink(view)
                    view.invalidate()
                }
    }

    private fun readText(view: View): String? = runCatching { textField?.get(view) as? String }.getOrNull()

    /** 读宿主自己的光标位置（`getCurrentSelectedLength()`），仅用于取证日志。 */
    private fun readHostSelected(): Any? = runCatching {
        val getter = selectedLengthGetter ?: return@runCatching null
        val instance = kernelInstance ?: return@runCatching null
        getter.invoke(instance)
    }.getOrNull()

    private fun mapXToIndex(view: View, rawX: Float): Int? = indexAtLocalX(view, rawX)

    /** 原点读取宿主布局公式，并减去同一滚动字段。 */
    private fun textOriginX(view: View): Float {
        val start = (textStartMethod?.invoke(view) as? Float)
            ?: error("host text origin unresolved")
        val scroll = offsetField?.getFloat(view) ?: 0f
        return start - scroll
    }

    /** 把一个「控件内 x 坐标」映射成组合文本下标（取最近的字符边界）。 */
    private fun indexAtLocalX(view: View, localX: Float): Int? {
        val text = readText(view) ?: return null
        val paint = runCatching { paintField?.get(view) as? Paint }.getOrNull() ?: return null
        val origin = textOriginX(view)
        val local = (localX - origin).coerceAtLeast(0f)
        val textEnd = paint.measureText(text) + if (caretIndex >= 0) caretGap(paint) else 0f
        val contentRight = (view.width - view.paddingRight - origin).coerceAtLeast(0f)
        // 文本最右边到控件内容右边的整段空白都归入末尾。
        if (local >= textEnd || local >= contentRight) return text.length
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in 0..text.length) {
            var x = paint.measureText(text, 0, i)
            if (caretIndex >= 0 && i > caretIndex) x += caretGap(paint)
            if (i == caretIndex) x += caretGap(paint) / 2f
            val distance = kotlin.math.abs(x - local)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best.coerceIn(0, text.length)
    }
}
