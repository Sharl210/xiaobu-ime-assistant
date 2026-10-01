package com.oplusime.panel

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 「符号」键直达完整符号页。
 *
 * ## 取证（com.oplus.keyboard 1.7.38.17-os，全部来自输入法自身 dex）
 *
 * 宿主的符号页由一枚枚举分三档，而**枚举常量名没有被混淆**，本身就是语义串：
 *
 * ```text
 * SymbolKeyboardType = { NONE_SYMBOL, SYMBOL1, SYMBOL2 }
 * ```
 *
 * 当前档位挂在输入法管理器的一个实例字段上（`input.manager.l->v`），
 * 由管理器自己的少数几个方法写入：
 *
 * ```text
 * l->v(KeyboardType, boolean)    普通键盘切符号：写 NONE_SYMBOL，或 isFull 时写 SYMBOL2
 * l->h0(boolean)                 「显示符号页」总入口：分支里写 SYMBOL1 然后切键盘视图
 * l->k0(...)   / l->e0()         换键盘视图时同步写这个字段
 * ```
 *
 * 宿主的 `IInputApi.showSymbolsView()` 走的是 `l->e0()`（完整符号页那条路）；
 * 而键盘上那个「符号」键走分档那条路，先落在 **`SYMBOL1`（简洁页）**，
 * 要再点页内的「更多」才升到 `SYMBOL2`（完整页）。
 *
 * ## 做法
 *
 * 不猜「更多」按钮长什么样，而是**在档位被写下的时刻把它抬到最高档**：
 *
 * 1. 按枚举常量名（语义串）找到这枚枚举；
 * 2. 按 `h0` 里那句独有的日志串 `"show symbol view shown "` 找到输入法管理器类；
 * 3. 取它上面那个「该枚举类型」的实例字段；
 * 4. 给该类的所有方法挂钩子：**调用前后各查一次**，只把 `SYMBOL1` 改写成 `SYMBOL2`。
 *
 * 调用**前**也查一次是必要的：宿主是「先写档位、再切键盘视图」，
 * 切视图那一步才知道该建哪一页；在它之前把档位抬上去，建出来的就是完整页。
 * 调用**后**再查一次，用于兜住内部又写回去的情况。
 *
 * `NONE_SYMBOL`（非符号页）与本来就是 `SYMBOL2` 的一律不动，因此不影响普通键盘、
 * 也不会把别处顶到符号页。类名、字段名只在日志里作为证据输出，不进查询条件。
 */
internal object SymbolPageRedirect {

    private const val NAME_NONE = "NONE_SYMBOL"
    private const val NAME_SIMPLE = "SYMBOL1"
    private const val NAME_FULL = "SYMBOL2"

    /** `l->h0` 内部独有的日志串，用来在不写死类名的前提下定位输入法管理器。 */
    private const val HOLDER_ANCHOR = "show symbol view shown "

    /** 同一状态下的改写间隔下限，避免抖动。 */
    private const val MIN_INTERVAL_MS = 16L

    @Volatile
    private var lastPromoteAt: Long = 0L

    @Volatile
    private var promoteCount: Int = 0

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

        var hooked = 0
        holder.declaredMethods.forEach { method ->
            if (Modifier.isAbstract(method.modifiers)) return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        promote(param.thisObject, fields, simple, full, "before")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        promote(param.thisObject, fields, simple, full, "after")
                    }
                })
                hooked++
            }.onFailure { log("symbol-page: hook failed on ${method.name}: ${it.message}") }
        }
        log(
            "symbol-page: installed holder=${holder.name} fields=${fields.size} hooks=$hooked"
        )
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
        val now = System.currentTimeMillis()
        if (now - lastPromoteAt < MIN_INTERVAL_MS) return
        fields.forEach { field ->
            val current = runCatching { field.get(target) }.getOrNull() ?: return@forEach
            if (current !== simple) return@forEach
            val ok = runCatching {
                field.set(target, full)
                true
            }.getOrDefault(false)
            if (ok) {
                lastPromoteAt = now
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
