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

    /** 当前档位字段的可读值，仅用于日志取证。 */
    private fun describeField(target: Any, fields: List<Field>): String =
        fields.mapNotNull { field ->
            runCatching { (field.get(target) as? Enum<*>)?.name }.getOrNull()
        }.joinToString(",")

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
                        val keyboardType = param.args?.firstOrNull {
                            it is Enum<*> && it.javaClass != enumClass
                        }
                        // 1.21.0 的教训（真机崩溃栈，不是推断）：
                        //   把参数里的键盘类型替换成 SYMBOLS 会让宿主 k0 走进需要 T9 键盘实例的分支，
                        //   抛 `lateinit property t9PinYin has not been initialized`，点「符号」即闪退。
                        //   结论：宿主不接受从外部把符号键盘替换成通用符号页类型。
                        //
                        // 1.20.0 的教训（真机日志）：
                        //   改档位字段 SYMBOL1 -> SYMBOL2 确实执行成功（有 promoted 行），但界面仍是简洁页。
                        //
                        // 两条合起来说明：这条"从外部改状态"的路走不通。本版**只观察、不改行为**，
                        // 把宿主行为完整交还（不闪退、返回箭头恢复原样），同时把真实调用链留成证据，
                        // 供下一轮按证据定位宿主自己那个「更多」按钮到底做了什么。
                        switchLogCount++
                        if (switchLogCount <= SWITCH_LOG_LIMIT) {
                            log(
                                "symbol-page: switch observed ${method.declaringClass.simpleName}#" +
                                    "${method.name} args=" + method.parameterTypes.size +
                                    " current=" + describeField(target, fields) +
                                    " target=" + (keyboardType as? Enum<*>)?.name
                            )
                        }
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
     * 已删除：1.21.0 在这里把参数里的「简洁符号键盘类型」替换成通用符号页类型 `SYMBOLS`，
     * 结果是宿主 `k0` 抛出 `lateinit property t9PinYin has not been initialized`，
     * 点「符号」当场闪退。本模块不再从外部改写宿主参数。
     *
     * 同理也删除了「把档位字段 SYMBOL1 抬成 SYMBOL2」的做法：1.20.0 真机日志证明它执行成功
     * 但界面不变（`promoted SYMBOL1 -> SYMBOL2` 出现两次，画面仍是简洁页），属于无效且多余的改动。
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
     * 「返回 = 回输入法主键盘」。
     *
     * 宿主文本编辑面板左上角的返回箭头（`iv_back`）只做「隐藏当前容器」；用户实测按完之后
     * 输入法一路退了出去，而不是回到键盘主页面。这里在宿主动作完成后，借宿主自己的
     * 「重置键盘」入口（`h0(false)`，也就是 `IInputApi.resetKeyboard()` 的实现）把键盘恢复成主键盘页。
     *
     * 全程留证：调用成功与失败都会写日志，便于下一轮直接核对。
     */
    fun backToMainKeyboard() {
        val holder = holderClass
        val method = entryMethod
        if (holder == null || method == null) {
            log("symbol-page: back ignored (holder/entry unresolved)")
            return
        }
        val instance = holderInstance ?: Reflect.selfSingleton(holder)?.also { holderInstance = it }
        if (instance == null) {
            log("symbol-page: back ignored (holder instance unresolved)")
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
