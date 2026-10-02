package com.oplusime.panel

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 「符号」键直达完整符号页。
 *
 * ## 取证（com.oplus.keyboard 1.7.38.17-os，全部来自输入法自身 dex）
 *
 * 符号页由一枚枚举分三档，常量名没有被混淆，本身就是语义串：
 *
 * ```text
 * SymbolKeyboardType = { NONE_SYMBOL, SYMBOL1, SYMBOL2 }   // 字段 a / b / c
 * ```
 *
 * 档位挂在输入法管理器（`input.manager.l`，混淆名）的一个实例字段上，由管理器自己写入：
 *
 * ```text
 * h0(boolean shown)                      「显示符号页」总入口：
 *                                        按当前键盘类型分支，先写 SYMBOL1(简洁页)，
 *                                        紧接着调用视图切换方法把键盘换成符号键盘
 * ...;->f0(l, boolean, KeyboardType)     视图切换（静态）：h0 各分支都走它
 * v(KeyboardType, boolean)               z == true  → 写 SYMBOL2(完整页)，只改档位
 *                                        z == false → 写 NONE_SYMBOL 并切回键盘
 * ```
 *
 * 也就是说：**符号键永远先落简洁页**，宿主自己的「更多」才升到完整页。
 *
 * ## 上一版为什么没用
 *
 * 1.17/1.18 的做法是"在 `h0` 返回之后把档位从 SYMBOL1 改成 SYMBOL2，再调用候选的
 * 完整页入口 `e0()`"。但 `h0` 是"先档位、后切视图"，等它返回时**简洁页已经建好了**；
 * 再调一次别的方法，只会让"档位状态"和"当前实际显示的页面"对不上，
 * 于是出现用户实测的现象：页面还是简洁页，而返回箭头又回不去主键盘。
 *
 * ## 这一版的做法
 *
 * 只拦**视图切换方法之前**这一瞬间，条件是该方法的形状唯一：
 *
 * ```text
 * static  void  (管理器自身, boolean, 键盘类型枚举)   // 参数 0 是管理器实例
 * ```
 *
 * 在它执行前把档位从 SYMBOL1 抬到 SYMBOL2，那么这一次切换建出来的就是完整页；
 * 因为改的是宿主自己的状态字段、走的也是宿主自己的切换流程，档位与实际页面始终一致，
 * 返回箭头因此不受影响。`NONE_SYMBOL`（回主键盘）与本来就是 `SYMBOL2` 的一律不动。
 */
internal object SymbolPageRedirect {

    private const val NAME_NONE = "NONE_SYMBOL"
    private const val NAME_SIMPLE = "SYMBOL1"
    private const val NAME_FULL = "SYMBOL2"

    /** `h0` 内部独有的日志串，用来在不写死类名的前提下定位输入法管理器。 */
    private const val HOLDER_ANCHOR = "show symbol view shown "

    /**
     * 完整符号页（纯符号列表页）对应的键盘类型常量名。
     *
     * 取证（宿主 dex 的 KeyboardType 枚举）：所有具体键盘类型都成对出现且一律叫
     * `QWERTY_XXX_SYMBOL`（拼音/英文/注音/仓颉/蒙/藏/维……），而通用的完整符号页只有
     * 一个独立常量 `SYMBOLS`，且全包内**没有任何代码**把符号档位字段写成 SYMBOL2。
     * 这两点合起来说明：决定"建哪一页"的是这个键盘类型参数，不是档位字段。
     */
    private const val NAME_SYMBOLS_PAGE = "SYMBOLS"

    /** 用来定位键盘类型枚举的语义串（是一个具体键盘类型的名字，不是类名）。 */
    private const val KEYBOARD_TYPE_ANCHOR = "QWERTY_PINYIN_SYMBOL"

    @Volatile
    private var promoteCount: Int = 0

    /** 输入法管理器类与其单例实例，供「返回 = 回主键盘」调用。 */
    @Volatile
    private var holderClass: Class<*>? = null

    /** 档位字段（install 时定下来，供延迟复核使用）。 */
    @Volatile
    private var fieldList: List<Field> = emptyList()

    @Volatile
    private var holderInstance: Any? = null

    /** 管理器里的「符号页总入口」方法（`h0`），宿主 IInputApi.resetKeyboard() 走的就是它。 */
    @Volatile
    private var entryMethod: Method? = null

    @Volatile
    private var backCount: Int = 0

    /**
     * 已记录多少条「切换方法被调用」的观察日志。
     *
     * 本版**只观察、不改行为**：1.21.0 曾把参数里的键盘类型换成 `SYMBOLS`，导致宿主
     * `k0` 走进一条需要 T9 键盘实例的分支，抛出
     * `lateinit property t9PinYin has not been initialized`，点「符号」直接闪退。
     * 也就是说宿主不接受从外部把符号键盘换成通用符号页类型。这里退回纯留证。
     */
    @Volatile
    private var switchLogCount: Int = 0

    /** 观察日志上限，避免每次按键刷屏。 */
    private const val SWITCH_LOG_LIMIT = 20

    /**
     * 档位字段当前值（**只取第一个**枚举类型字段）。
     *
     * 上一版把**所有**枚举类型字段拼成一个逗号串再和 `NAME_SIMPLE` 比较，
     * 结果是 `"SYMBOL1,SYMBOL1"` 这类串永远不等于 `"SYMBOL1"`，
     * `after-switch` 的补调判定因此恒为"不需要"，静默跳过。
     * 对单个字段的档位判断必须返回单个常量名。
     */
    private fun describeField(target: Any, fields: List<Field>): String =
        fields.firstNotNullOfOrNull { field ->
            runCatching { (field.get(target) as? Enum<*>)?.name }.getOrNull()
        }.orEmpty()

    /** 全部枚举字段的取值（仅日志取证用，不参与判等）。 */
    private fun describeAll(target: Any, fields: List<Field>): String =
        fields.mapNotNull { field ->
            runCatching { (field.get(target) as? Enum<*>)?.name }.getOrNull()
        }.joinToString(",")

    /** 已替用户按下「更多」的次数。 */
    @Volatile
    private var fullPageCount: Int = 0

    /**
     * 正在替用户按「更多」。
     *
     * 我们自己调用 `h0(true)` 会再次进入被钩的切换方法，这个标记保证嵌套那一层直接返回，
     * 不会无限递归。
     */
    @Volatile
    private var inPromote: Boolean = false

    /** 记录过多少次"这一步之后没停在简洁页、因此不需要补"（把曾经的静默路径变成可查证据）。 */
    @Volatile
    private var skippedCount: Int = 0

    fun install(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val enumClass = findSymbolEnum(bridge, hostClassLoader)
        if (enumClass == null) {
            log("symbol-page: SymbolKeyboardType unresolved; symbol key keeps host behaviour")
            return
        }
        val simple = constant(enumClass, NAME_SIMPLE)
        val full = constant(enumClass, NAME_FULL)
        if (simple == null || full == null) {
            log("symbol-page: enum constants missing; abort")
            return
        }

        val holder = findHolderClass(bridge, hostClassLoader, enumClass)
        if (holder == null) {
            log("symbol-page: holder unresolved; symbol key keeps host behaviour")
            return
        }
        val fields = holder.declaredFields.filter {
            it.type == enumClass && !Modifier.isStatic(it.modifiers)
        }
        if (fields.isEmpty()) {
            log("symbol-page: no enum-typed instance field on ${holder.name}; abort")
            return
        }
        fields.forEach { runCatching { it.isAccessible = true } }
        holderClass = holder
        fieldList = fields
        // **必须有这一行**：下面所有"取值/判档"都要靠 `constantOfField`，
        // 而它读的就是 `enumClassRef`。1.33.1 之前这里漏了赋值，
        // 于是 `constantOfField(NAME_SIMPLE)` 恒为 null，`promoteToFull` 在第一行就返回 ——
        // 「符号键直达完整页」这个功能**从来没真正执行过一次**。
        // 这就是用户说的"改了三四个版本，体感没有任何区别"的直接原因。
        enumClassRef = enumClass
        entryMethod = findEntryMethod(bridge, hostClassLoader, holder)
        log(
            "symbol-page: entryMethod=" + (
                entryMethod?.let { "${it.declaringClass.simpleName}#${it.name}" } ?: "unresolved"
                )
        )

        // 视图切换方法（1.19.0 就挂错在这里，这次按宿主真实形状挂）：
        //
        //   `h0(boolean)` 里每一个"写 SYMBOL1"的分支，紧接着调用的是
        //       static void k0(管理器自身, KeyboardType, boolean, boolean, int)   ← 5 个参数
        //   参数 0 是管理器实例、参数 1 是"另一个枚举"（键盘类型），这两点把 k0 和普通静态工具方法分开。
        //
        //   1.19.0 挂的是 3 参数形状 (管理器, boolean, KeyboardType) 的 `f0`；符号键根本不走它，
        //   所以真机日志里连一次 `view-switch-before` 都没有出现 —— 挂点选错，等于没改。
        val fiveArg = holder.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 5 &&
                method.parameterTypes[0] == holder &&
                method.parameterTypes[1].isEnum &&
                method.parameterTypes[1] != enumClass &&
                method.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[4] == Int::class.javaPrimitiveType
        }
        // 兼容形状：少数分支走 3 参数版本，一并挂上，避免某条分支漏掉。
        val threeArg = holder.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == holder &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[2].isEnum &&
                method.parameterTypes[2] != enumClass
        }
        val switches = fiveArg + threeArg

        var hooked = 0
        switches.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val target = param.args?.getOrNull(0) ?: return
                        if (holderInstance == null) holderInstance = target
                        val keyboardType = param.args?.firstOrNull {
                            it is Enum<*> && it.javaClass != enumClass
                        }
                        // **符号键直达完整页（1.33.2）**
                        //
                        // 宿主 `h0` 的每一个"打开符号页"分支都是：先把档位写成 `SYMBOL1`（简洁页），
                        // 再调 `k0` 建页；只有用户手点「更多」时才走 `v(type, true)` 把档位写成整洁页。
                        //
                        // 既然决定建哪一页的是这个**档位字段**，那就在建页之前把它从简洁抬到完整即可 ——
                        // 这与用户手点「更多」之后的状态完全等价，因此宿主自己的返回箭头、
                        // 状态机、后续切页都不受影响。
                        //
                        // 关键：**不改参数**。1.21.0 曾把参数里的键盘类型换成通用 `SYMBOLS`，
                        // 结果走进一条需要 T9 键位的分支，抛
                        // `lateinit property t9PinYin has not been initialized` 当场闪退。
                        // 这里只改宿主自己的字段，参数原样放行，所以不会触发那条分支。
                        val targetName = (keyboardType as? Enum<*>)?.name.orEmpty()
                        if (targetName.contains("SYMBOL", ignoreCase = true)) {
                            promoteToFull(target, "before-build[$targetName]")
                        }
                        switchLogCount++
                        if (switchLogCount <= SWITCH_LOG_LIMIT) {
                            log(
                                "symbol-page: switch observed ${method.declaringClass.simpleName}#" +
                                    "${method.name} args=" + method.parameterTypes.size +
                                    " current=" + describeField(target, fields) +
                                    " target=" + targetName
                            )
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 替用户按一次宿主自己的「更多」。
                        //
                        // 取证依据（宿主 dex）：符号列表适配器 `SymbolsAdapter`（含内部类
                        // `onBindViewHolder$6$1$1`）与符号页回调 `body/x`（就是符号页左上角那个返回箭头
                        // 所在类的兄弟回调）都调用同一个管理器入口 `h0(boolean)`；而 `h0` 在
                        // "当前已经是符号键盘"时走 `v(前一个键盘类型, true)` 分支，那是全包内**唯一**
                        // 会把档位写成 `SYMBOL2` 的地方 —— 也就是完整页。
                        //
                        // 关键差别（这解释了 1.20.0 为什么白改）：
                        // 1.20.0 是在 k0 **执行之前**改档位，而 k0 随后会按 body 类型把它重置；
                        // 这里是在 k0 **执行之后**、页面已经建好的那一刻调用宿主自己的入口，
                        // 与用户手点「更多」完全同一条路，因此不需要猜类型、也不会崩。
                        // 按**当前实际档位**实时判定：只有"这一步之后真的停在简洁符号页"才补一次「更多」。
                        //
                        // 1.23.0 真机日志（18:45:30.386 / 18:45:31.062 两次落在简洁页）证明：
                        // 判定本身没错，错的是判定之后还有一道 **500ms 静默节流**——
                        // 连点时间隔常在 300~470ms，正好被它吃掉，而且它返回时不写任何日志，
                        // 所以表现为"偶尔落回简洁页、日志里看不出原因"。
                        // 本版彻底删除该节流：判定完全按现场档位，调用是幂等的（只有停在简洁页才补）。
                        if (inPromote) return
                        val manager = param.args?.getOrNull(0) ?: return
                        val target = (param.args?.firstOrNull {
                            it is Enum<*> && it.javaClass != enumClass
                        } as? Enum<*>) ?: return
                        if (!target.name.contains("SYMBOL", ignoreCase = true)) return
                        val state = describeField(manager, fields)
                        if (state != NAME_SIMPLE) {
                            // 退出符号页（NONE_SYMBOL）或已经是完整页：不需要补，但要留一行，
                            // 避免以后又出现"看不出原因的跳过"。
                            skippedCount++
                            if (skippedCount <= 20) {
                                log("symbol-page: follow-up not needed state=$state target=${target.name}")
                            }
                            return
                        }
                        followFullPage(manager, "after-switch[" + target.name + "]")
                    }
                })
                hooked++
            }.onFailure { log("symbol-page: view switch hook failed: ${it.message}") }
        }
        switches.forEach {
            log(
                "symbol-page: switch hooked ${it.declaringClass.simpleName}#${it.name}" +
                    "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")"
            )
        }

        log(
            "symbol-page: installed holder=${holder.name} fields=${fields.size}" +
                " switches=${switches.size}(5arg=${fiveArg.size},3arg=${threeArg.size}) hooks=$hooked"
        )
    }

    /**
     * 把宿主自己的「符号页档位」从简洁抬到完整。
     *
     * 档位枚举三档（常量名未被混淆，本身就是语义）：
     *
     * ```text
     * SymbolKeyboardType.a = NONE   → 非符号页
     * SymbolKeyboardType.b = SYMBOL1 → 简洁符号页（宿主「符号」键默认落这里）
     * SymbolKeyboardType.c = SYMBOL2 → 完整符号页（宿主「更多」才升到这里）
     * ```
     *
     * 证据：宿主 `input/manager/l;->v(KeyboardType, boolean)` 里，`shown=true` 分支写的正是 `c`，
     * 而 `shown=false` 写 `a`；所有"打开符号页"的分支（`h0` 内）一律先写 `b`。
     *
     * 只动档位字段、不动任何参数，因此与用户手点「更多」后的状态完全等价。
     */
    private fun promoteToFull(manager: Any?, phase: String) {
        if (manager == null) return
        val simple = constantOfField(NAME_SIMPLE) ?: return
        val full = constantOfField(NAME_FULL) ?: return
        fieldList.forEach { field ->
            val current = runCatching { field.get(manager) }.getOrNull() ?: return@forEach
            if (current !== simple) return@forEach
            val ok = runCatching {
                field.set(manager, full)
                true
            }.getOrDefault(false)
            if (ok) {
                promoteCount++
                log("symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=$phase)")
            }
        }
    }

    /** 档位枚举里某个常量名对应的值（惰性取，避免安装顺序耦合）。 */
    private fun constantOfField(name: String): Any? = runCatching {
        enumClassRef?.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == name }
    }.getOrNull()

    /** 档位枚举类（install 时记下来，供上面的常量取值复用）。 */
    @Volatile
    private var enumClassRef: Class<*>? = null

    /**
     * 已删除：1.21.0 在这里把参数里的「简洁符号键盘类型」替换成通用符号页类型 `SYMBOLS`，
     * 结果是宿主 `k0` 抛出 `lateinit property t9PinYin has not been initialized`，
     * 点「符号」当场闪退。本模块不再从外部改写宿主参数。
     */

    /**
     * 管理器里的「符号页总入口」方法。
     *
     * 判据是它内部那句独有日志串 `show symbol view shown `（同一个锚点已经用来定位过宿主类），
     * 加上签名 `(boolean) -> void` 与声明类一致。宿主自己的 `IInputApi.resetKeyboard()`
     * 实现就是调用这个方法（传入 false），因此这里可以安全地借它做「回主键盘」。
     */
    private fun findEntryMethod(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
        holder: Class<*>,
    ): Method? {
        val candidates = runCatching {
            bridge.findMethod {
                matcher {
                    usingStrings(listOf(HOLDER_ANCHOR), StringMatchType.Equals)
                    paramCount(1)
                    paramTypes("boolean")
                    returnType("void")
                }
            }.toList()
        }.onFailure { log("symbol-page: entry query failed: ${it.message}") }
            .getOrDefault(emptyList())

        candidates.forEach { data ->
            val method = runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
                ?: return@forEach
            if (method.declaringClass == holder) return method
        }
        return null
    }

    /**
     * 替用户按一次「更多」：调用宿主自己的符号页总入口 `h0(true)`。
     *
     * 与用户手点「更多」完全同一条路（宿主自己的状态机、自己的视图流程），因此不会改坏返回键。
     *
     * 三条纪律（都是真机日志换来的）：
     *  1. **不做跨调用节流**：1.23.0 的 500ms 节流会在连点（300~470ms 间隔）时静默吃掉补调，
     *     表现就是"偶尔落回简洁页"。这里只按现场档位判定，幂等。
     *  2. **就地同步补，不用延迟任务**：延迟任务可能在用户已经离开符号页之后才触发，
     *     那时再调 `h0(true)` 会把他重新拉进简洁页 —— 属于"模块自己造成掉页"。
     *  3. **最多补两次**：宿主若在补调之后又把档位写回简洁页，再补一次；仍写回就放弃，
     *     交给下一轮用 `not needed / invoked` 两类日志继续定位，不进入死循环。
     */
    private fun followFullPage(manager: Any?, phase: String) {
        val method = entryMethod ?: run {
            log("symbol-page: follow-up 'more' skipped (entry unresolved)")
            return
        }
        val instance = manager ?: holderInstance ?: run {
            log("symbol-page: follow-up 'more' skipped (manager instance unresolved)")
            return
        }
        if (inPromote) return
        inPromote = true
        try {
            var attempt = 0
            while (attempt < 2 && describeField(instance, fieldList) == NAME_SIMPLE) {
                attempt++
                method.isAccessible = true
                method.invoke(instance, true)
                fullPageCount++
                log(
                    "symbol-page: follow-up 'more' invoked h0(true) phase=$phase" +
                        " attempt=$attempt total=$fullPageCount"
                )
            }
        } catch (t: Throwable) {
            log("symbol-page: follow-up 'more' failed: ${t.message}")
        } finally {
            inPromote = false
        }
    }

    /**
     * 「返回 = 回输入法主键盘」。
     *
     * 宿主文本编辑面板左上角的返回箭头（`iv_back`）只做「隐藏当前容器」；用户实测按完之后
     * 输入法一路退了出去，而不是回到键盘主页面。这里在宿主动作完成后，借宿主自己的
     * 「重置键盘」入口（`h0(false)`，也就是 `IInputApi.resetKeyboard()` 的实现）把键盘恢复成主键盘页。
     *
     * 全程留证：调用成功与失败都会写日志，便于下一轮直接核对。
     */
    fun backToMainKeyboard() {
        val method = entryMethod
        if (method == null) {
            log("symbol-page: back ignored (entry unresolved)")
            return
        }
        // 实例来源优先级：切换调用里抓到的真实实例 → 自类型静态单例（部分宿主形态）。
        // 1.21.1 的日志是 `holder instance unresolved`，所以这里必须两条都试，并写清用的是哪条。
        val instance = holderInstance ?: holderClass?.let { Reflect.selfSingleton(it) }?.also {
            holderInstance = it
            log("symbol-page: manager instance captured via self-singleton")
        }
        if (instance == null) {
            log("symbol-page: back ignored (manager instance unresolved)")
            return
        }
        runCatching {
            method.isAccessible = true
            method.invoke(instance, false)
            backCount++
            log("symbol-page: back -> host keyboard reset invoked (total=$backCount)")
        }.onFailure { log("symbol-page: back reset failed: ${it.message}") }
    }

    /** 档位只从「简洁页」抬到「完整页」；其余取值原样放行。 */
    private fun promote(
        target: Any?,
        fields: List<Field>,
        simple: Any,
        full: Any,
        phase: String,
    ) {
        if (target == null) return
        fields.forEach { field ->
            val current = runCatching { field.get(target) }.getOrNull() ?: return@forEach
            if (current !== simple) return@forEach
            val ok = runCatching {
                field.set(target, full)
                true
            }.getOrDefault(false)
            if (ok) {
                promoteCount++
                log("symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=$phase total=$promoteCount)")
            }
        }
    }

    /** 按枚举常量名找枚举类：三个常量同时命中才认。 */
    private fun findSymbolEnum(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ): Class<*>? {
        val candidates = runCatching {
            bridge.findClass {
                matcher { usingStrings(listOf(NAME_NONE), StringMatchType.Equals) }
            }.toList()
        }.onFailure { log("symbol-page: enum query failed: ${it.message}") }
            .getOrDefault(emptyList())

        candidates.forEach { data ->
            val cls = runCatching { data.getInstance(hostClassLoader) }.getOrNull() ?: return@forEach
            if (!cls.isEnum) return@forEach
            val names = runCatching { cls.enumConstants.map { (it as Enum<*>).name } }.getOrNull()
                ?: return@forEach
            if (names.contains(NAME_NONE) &&
                names.contains(NAME_SIMPLE) &&
                names.contains(NAME_FULL)
            ) {
                log("symbol-page: enum=${cls.name} constants=$names")
                return cls
            }
        }
        return null
    }

    private fun constant(enumClass: Class<*>, name: String): Any? =
        runCatching { enumClass.enumConstants.firstOrNull { (it as Enum<*>).name == name } }
            .getOrNull()

    /**
     * 输入法管理器类：用 `h0` 里那句独有的日志串定位，再核对它确实持有该枚举的实例字段。
     * 这样即使类名被混淆，也能稳定拿到。
     */
    private fun findHolderClass(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
        enumClass: Class<*>,
    ): Class<*>? {
        val candidates = runCatching {
            bridge.findClass {
                matcher { usingStrings(listOf(HOLDER_ANCHOR), StringMatchType.Equals) }
            }.toList()
        }.onFailure { log("symbol-page: holder query failed: ${it.message}") }
            .getOrDefault(emptyList())

        candidates.forEach { data ->
            val cls = runCatching { data.getInstance(hostClassLoader) }.getOrNull() ?: return@forEach
            if (cls.isEnum || cls.isInterface) return@forEach
            val holds = runCatching {
                cls.declaredFields.any {
                    it.type == enumClass && !Modifier.isStatic(it.modifiers)
                }
            }.getOrDefault(false)
            if (holds) return cls
        }
        return null
    }
}
