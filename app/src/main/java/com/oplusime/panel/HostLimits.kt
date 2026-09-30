package com.oplusime.panel

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 解除宿主的几处“容量上限”，全部走 DexKit 结构/语义匹配，不写死任何混淆类名或方法名。
 *
 * 一、记录表的“到顶就丢最旧”裁剪（剪贴板 500 条、常用语表 500 条）
 *
 * 宿主把「插入 + 到顶先删最旧」写在一个事务 lambda 里，形状固定：
 *
 * ```text
 * invoke(Object)Object                       <- 事务 lambda
 *   usingNumbers(500)                        <- 上限阈值
 *   invokes Number.intValue()                <- 读“当前条数”
 *   switch(id):
 *     简单分支  -> 直接调插入/删除适配器
 *     默认分支  -> 查重 -> 数条数 -> if (条数 == 500) 删除最旧 -> 插入
 * ```
 *
 * 需要做的只是让“条数 == 500”这个条件永不成立，宿主自己的代码就会跳过裁剪、
 * 直接走它自己的插入分支（两个候选类里，一个走「跳过后插入」、一个走「跳过后仍插入」，
 * 都成立）。做法不是猜分支 id，而是：
 *
 *  1. 命中所有这种形状的事务 lambda（`record-trim`）；
 *  2. 收集这些 lambda 内部构造出来的“子 lambda 类”；
 *  3. 在这段 lambda 执行期间（线程内计数）把这些子 lambda 返回的 **Number 结果改成 0**。
 *
 * 子 lambda 里返回数字的只有“数条数”那一次；查重返回的是实体对象、删除返回 Unit，
 * 都不是 Number，因此不会被误改。事务 lambda 自身被排除在改写之外，
 * 所以插入返回值（行号）保持原样。
 *
 * 二、常用语正文的 500 字输入上限
 *
 * 宿主的输入过滤器 `filter(CharSequence, int, int, Spanned, int, int)` 内部用一个常量做
 * “剩余可输入字数”判定，超限就丢弃本次输入并弹提示。同一个方法里还有另一条分支用别的常量
 * （搜索框）。这里**不按分支 id 判断**，而是复算同一个式子：只有“剩余可输入字数已经不足”
 * （即正好踩到 500 上限）时才放行本次输入，其余情况一律交给宿主原逻辑，
 * 搜索框那条更小的上限因此不受影响。
 */
internal object HostLimits {

    /** 宿主用来表示这两张表容量上限的常量（换版本若改了数值，查询会自然落空并在日志里写明）。 */
    private const val RECORD_LIMIT = 500

    /** 字数监听器里同时出现的两个字符串（计数控件名与超限告警色），用于结构命中。 */
    private val CONTENT_WATCHER_STRINGS = listOf("contentNumber", "#DB382C")

    /** 计数文本形状：`数字/`（例如 11/500、11/∞）。 */
    private val COUNTER_PATTERN = Regex("""\d+\s*[/／]""")

    /** 事务 lambda 执行深度（按线程计数），只在这段区间内改写子 lambda 的数字返回。 */
    private val trimDepth = ThreadLocal<Int>()

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        suppressRecordTrim(bridge, hostClassLoader)
        relieveContentLengthLimit(bridge, hostClassLoader)
        relieveContentLengthGuard(bridge, hostClassLoader)
        neutralizeContentTruncation(bridge, hostClassLoader)
    }

    // ------------------------------------------------------------ 到顶裁剪

    private fun suppressRecordTrim(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val wrappers = findMethods(bridge, "record-trim") {
            matcher {
                name("invoke")
                returnType("java.lang.Object")
                paramTypes("java.lang.Object")
                usingNumbers(listOf(RECORD_LIMIT))
                addInvoke("Ljava/lang/Number;->intValue()I")
            }
        }
        if (wrappers.isEmpty()) {
            log("record-trim: no transaction lambda matched; limits stay as host set them")
            return
        }

        var installed = 0
        wrappers.forEach { data ->
            val wrapperClass = runCatching { data.declaredClass?.getInstance(hostClassLoader) }
                .getOrNull()
            if (wrapperClass == null) {
                log("record-trim: wrapper class unavailable for ${data.descriptor}")
                return@forEach
            }
            // 该 lambda 内部构造出来的子 lambda（查重 / 数条数 / 删除 / 插入）
            val childClasses = runCatching {
                data.invokes
                    .filter { it.name == "<init>" }
                    .mapNotNull { it.declaredClass?.getInstance(hostClassLoader) }
                    .filter { it != wrapperClass }
                    .distinct()
            }.getOrDefault(emptyList())

            var childrenHooked = 0
            childClasses.forEach { child ->
                val invoke = child.declaredMethods.firstOrNull { method ->
                    method.name == "invoke" &&
                        method.parameterCount == 1 &&
                        method.returnType == Any::class.java &&
                        !Modifier.isStatic(method.modifiers)
                } ?: return@forEach
                runCatching {
                    XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if ((trimDepth.get() ?: 0) <= 0) return
                            if (param.result is Number) param.result = 0
                        }
                    })
                    childrenHooked++
                }.onFailure { log("record-trim: hook child ${child.name} failed: ${it.message}") }
            }

            val invoke = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: run {
                    log("record-trim: wrapper instance unavailable for ${data.descriptor}")
                    return@forEach
                }
            runCatching {
                XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        trimDepth.set((trimDepth.get() ?: 0) + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        trimDepth.set(((trimDepth.get() ?: 1) - 1).coerceAtLeast(0))
                    }
                })
                installed++
                log(
                    "record-trim: wrapper=${data.declaredClassName} children=$childrenHooked " +
                        "limit=$RECORD_LIMIT"
                )
            }.onFailure { log("record-trim: wrapper hook failed: ${it.message}") }
        }
        log("record-trim: wrappers=${wrappers.size} installed=$installed")
    }

    // ------------------------------------------------------ 正文长度上限

    private fun relieveContentLengthLimit(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates = findMethods(bridge, "content-length-filter") {
            matcher {
                name("filter")
                returnType("java.lang.CharSequence")
                paramTypes(
                    "java.lang.CharSequence",
                    "int",
                    "int",
                    "android.text.Spanned",
                    "int",
                    "int",
                )
                usingNumbers(listOf(RECORD_LIMIT))
            }
        }
        if (candidates.isEmpty()) {
            log("content-length-filter: no filter matched; host limit stays")
            return
        }
        var installed = 0
        candidates.forEach { data ->
            val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args
                        if (args.size < 6) return
                        val source = args[0] as? CharSequence ?: return
                        if (source.isEmpty()) return
                        val start = args[1] as? Int ?: return
                        val end = args[2] as? Int ?: return
                        val dest = args[3] as? CharSequence
                        val destStart = args[4] as? Int ?: return
                        val destEnd = args[5] as? Int ?: return
                        if (start < 0 || end > source.length || start > end) return
                        // 与宿主同式复算：仅当“剩余可输入字数已不足”时放行本次输入。
                        val destLength = dest?.length ?: 0
                        val addLength = end - start
                        val remaining = RECORD_LIMIT - (destLength - (destEnd - destStart))
                        // 只要「按宿主算式本次输入装不下」就整段放行，宿主此后不再截断。
                        // 对照：搜索框那条更小的上限用 500 复算时永远装得下（搜索框自身被 100 卡住，
                        // 到不了 500），所以本分支只会命中常用语正文这条 500 上限。
                        if (remaining < addLength) {
                            param.result = source.subSequence(start, end)
                            log("content-length-filter: over-limit input allowed (limit=$RECORD_LIMIT)")
                        }
                    }
                })
                installed++
            }.onFailure { log("content-length-filter: hook failed: ${it.message}") }
        }
        log(
            "content-length-filter: candidates=${candidates.size} installed=$installed " +
                "limit=$RECORD_LIMIT"
        )
    }

    // ------------------------------------------------------ 正文长度预检

    /**
     * 常用语正文的「提交前长度预检」。
     *
     * 宿主除输入过滤器外，还有一个静态无参 boolean 预检：内部同时读「搜索框 100 字」与
     * 「常用语正文 500 字」两条上限，超限时弹提示并返回 true。它比过滤器更早拦住下一次输入，
     * 因此必须一并解除。
     *
     * 形状锚点（不写死混淆名）：静态 + 无参 + 返回 boolean + 使用字面量 500 +
     * 使用语义串 `contentEditText`（宿主自己的 Kotlin 空值检查文案，始终保留）。
     * 命中后直接令其返回 false（= 未超限）。
     */
    private fun relieveContentLengthGuard(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates = findMethods(bridge, "content-length-guard") {
            matcher {
                returnType("boolean")
                paramCount(0)
                usingNumbers(listOf(RECORD_LIMIT))
                usingStrings(listOf("contentEditText"), StringMatchType.Equals, false)
            }
        }
        if (candidates.isEmpty()) {
            log("content-length-guard: no pre-check matched; host pre-check stays")
            return
        }
        var installed = 0
        candidates.forEach { data ->
            val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (!Modifier.isStatic(method.modifiers)) return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
                installed++
                log("content-length-guard: hooked ${data.descriptor} -> always false")
            }.onFailure { log("content-length-guard: hook failed: ${it.message}") }
        }
        log("content-length-guard: candidates=${candidates.size} installed=$installed")
    }

    // -------------------------------------------- 常用语正文的「变红 + 截断」

    /**
     * 常用语正文字数到达上限后「计数变红 + 文本被截断」的落点。
     *
     * 这是输入被吞掉的真正原因：上一个版本只解除了输入过滤器（输入被放行），
     * 但字数的 TextWatcher 随后又做三件事：
     *
     * ```text
     * contentNumber.setTextColor(Color.parseColor("#DB382C"))   <- 变红
     * contentEditText.setText(s.subSequence(0, 500))            <- 截断
     * contentEditText.setSelection(500)                         <- 光标钉在 500
     * ```
     *
     * 结果是「输入能进来、又被立刻截回去」，用户看到的就是「变红后怎么打都不增长」。
     *
     * 命中方式纯结构：一个方法同时使用数字 500、同时使用字符串 `contentNumber` 与
     * `#DB382C`，并且调用 `CharSequence.subSequence` 与 `TextView.setText`。
     * 不写死任何类名、方法名、字段名。
     *
     * 处理方式：**只在本次文本确实超过上限时**拦下宿主这段逻辑（不再变红、不再截断），
     * 并由模块自己把计数文本写成 `当前字数/∞`；未超限时原样交给宿主动作，
     * 因此正常字数统计、颜色与其它校验全都不受影响。
     */
    private fun neutralizeContentTruncation(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val candidates = findMethods(bridge, "content-truncation") {
            matcher {
                usingNumbers(listOf(RECORD_LIMIT))
                usingStrings(CONTENT_WATCHER_STRINGS, StringMatchType.Equals, false)
                addInvoke("Ljava/lang/CharSequence;->subSequence(II)Ljava/lang/CharSequence;")
                addInvoke("Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V")
            }
        }
        if (candidates.isEmpty()) {
            log("content-truncation: no truncating watcher matched; host limit stays")
            return
        }
        var installed = 0
        candidates.forEach { data ->
            val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = firstCharSequence(param.thisObject) ?: return
                        if (text.length <= RECORD_LIMIT) return
                        // 跳过宿主这一段：既不变红，也不截断、不移动光标。
                        param.result = Unit
                        refreshCounter(param.thisObject, text.length)
                    }
                })
                installed++
                log("content-truncation: hooked ${data.descriptor} -> truncation disabled")
            }.onFailure { log("content-truncation: hook failed: ${it.message}") }
        }
        log(
            "content-truncation: candidates=${candidates.size} installed=$installed " +
                "limit=$RECORD_LIMIT"
        )
    }

    /** 合成 lambda 把「本次文本」放在一个 `CharSequence` 字段里；按类型取，不按名字取。 */
    private fun firstCharSequence(owner: Any?): CharSequence? {
        val target = owner ?: return null
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (field.type == CharSequence::class.java) {
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(target) as? CharSequence
                    }.getOrNull()
                    if (value != null) return value
                }
            }
            current = current.superclass
        }
        return null
    }

    /** 合成 lambda 上的宿主视图字段（宿主界面类本身是 View）。 */
    private fun hostViewOf(owner: Any?): View? {
        val target = owner ?: return null
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (View::class.java.isAssignableFrom(field.type)) {
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(target)
                    }.getOrNull()
                    if (value is View) return value
                }
            }
            current = current.superclass
        }
        return null
    }

    /** 把计数文本写成「当前字数/∞」，字号与颜色沿用宿主当前值。 */
    private fun refreshCounter(owner: Any?, length: Int) {
        val host = hostViewOf(owner) ?: return
        val counter = counterCache[host] ?: findCounter(host)?.also { counterCache[host] = it } ?: return
        runCatching { counter.text = "$length/∞" }
    }

    /** 计数控件按「文本形状 `数字/`」识别，不依赖任何 id 或字段名。 */
    private fun findCounter(host: View): TextView? {
        val stack = ArrayDeque<View>()
        stack.addLast(host)
        var visited = 0
        while (stack.isNotEmpty() && visited < 500) {
            val view = stack.removeLast()
            visited++
            if (view is TextView && COUNTER_PATTERN.containsMatchIn(view.text?.toString().orEmpty())) {
                return view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack.addLast(view.getChildAt(i))
            }
        }
        return null
    }

    private val counterCache: MutableMap<View, TextView> =
        Collections.synchronizedMap(WeakHashMap())

    private fun findMethods(
        bridge: DexKitBridge,
        label: String,
        init: FindMethod.() -> Unit,
    ): List<MethodData> = runCatching { bridge.findMethod(init).toList() }
        .onFailure { log("$label query failed: ${it.message}") }
        .getOrDefault(emptyList())
        .also { log("$label candidates=${it.size}") }
}
