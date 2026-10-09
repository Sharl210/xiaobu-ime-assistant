package com.oplusime.panel

import android.view.View
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** 仅用于返回主打字页；枚举显示名与宿主协议值必须分开，不用于英文候选刷新。 */
internal object HostKeyboardSwitch {

    @Volatile private var switchMethod: Method? = null
    @Volatile private var apiInstance: Any? = null
    @Volatile private var resolved = false
    @Volatile private var switchSignature: String? = null
    @Volatile private var accessorSignature: String? = null

    fun diagnosticSignatures(): List<String> = listOfNotNull(switchSignature, accessorSignature)

    /** 宿主 `KeyboardType` 枚举的全部常量名（默认打字页要从里面挑）。 */
    @Volatile private var typeNames: List<String> = emptyList()
    @Volatile private var typeTokens: Map<String, String> = emptyMap()

    /**
     * 最近一次绘制过的键盘类型。
     *
     * ## 为什么不能再用"按视图记的弱表"（1.33.33 的事故）
     *
     * 那时用 `WeakHashMap<View, String>` 存"每个键盘视图是什么类型"，取的时候
     * `firstOrNull { it.isAttachedToWindow }` —— **遍历顺序完全不确定**，而且
     * 表里会残留历史键盘视图（九键、手写、符号都算）。真机日志的后果是：
     *
     * ```text
     * keyboard-switch: switched to T9_PINYIN   ← 上滑一下，26 键被切成九键
     * ```
     *
     * 现在只保留**最后一条**记录，并加一个很短的时效（[TYPE_FRESH_MS]）：
     * 键盘正在绘制时它一直在刷新，所以永远是当下这一枚；一旦超过时效就当作
     * "不知道"，宁可返回 null 也不拿旧值去切键盘。
     */
    @Volatile
    private var lastTypeName: String? = null

    @Volatile
    private var lastTypeAt: Long = 0L

    private const val TYPE_FRESH_MS = 800L

    /** 由 [com.oplusime.panel.SoftKeySwipeMap] 每次绘制时喂进来。 */
    fun rememberKeyboardType(view: View, typeName: String?) {
        if (typeName.isNullOrEmpty()) return
        lastTypeName = typeName
        lastTypeAt = android.os.SystemClock.uptimeMillis()
    }

    fun currentTypeName(): String? {
        if (lastTypeName == null) return null
        val age = android.os.SystemClock.uptimeMillis() - lastTypeAt
        return if (age <= TYPE_FRESH_MS) lastTypeName else null
    }

    fun isEnglish(typeName: String?): Boolean = typeName?.contains("EN", ignoreCase = true) == true

    /**
     * 当前的「打字主键盘」类型名。
     *
     * 英文页 → 英文 26 键；其余（拼音、手写、符号、数字…）→ 中文拼音 26 键。
     * 名字全部从宿主枚举里现取，不写死字面量。
     */
    fun mainTypingTypeName(): String? {
        val names = typeNames
        if (names.isEmpty()) return null
        val en = names.firstOrNull { it.contains("QWERTY_EN", true) }
            ?: names.firstOrNull { it.contains("EN", true) && it.contains("QWERTY", true) }
        val zh = names.firstOrNull { it.contains("QWERTY_PINYIN", true) }
            ?: names.firstOrNull { it.contains("PINYIN", true) && it.contains("QWERTY", true) }
            ?: names.firstOrNull { it.contains("QWERTY", true) && !it.contains("EN", true) }
        return if (isEnglish(currentTypeName())) (en ?: zh) else (zh ?: en)
    }

    /** 切到指定键盘类型；字符串直接交给宿主自己的解析入口。 */
    fun applyType(typeName: String?): Boolean {
        if (typeName.isNullOrEmpty()) return false
        val method = switchMethod
        val instance = apiInstance
        if (method == null || instance == null) {
            log("keyboard-switch: unavailable, skip type=$typeName")
            return false
        }
        // 枚举名字仅用于识别，宿主解析入口接收的是枚举内的 schema/type 值。
        // 将 QWERTY_PINYIN 当作协议值传入会走宿主默认分支，可能落到九键。
        val token = typeTokens[typeName] ?: run {
            log("keyboard-switch: missing verified token for name=$typeName; skip")
            return false
        }
        return runCatching {
            method.invoke(instance, token)
            log("keyboard-switch: requested name=$typeName token=$token")
            true
        }.onFailure { log("keyboard-switch: switch failed type=$typeName error=${it.message}") }
            .getOrDefault(false)
    }

    fun install(bridge: DexKitBridge, loader: ClassLoader) {
        if (resolved) return
        resolveTypeNames(bridge, loader)
        runCatching {
            // 直接从宿主语义化的键盘切换协议入口反查声明类；不再依赖 resetKeyboard 这类具体方法名。
            val switchData = bridge.findMethod {
                matcher {
                    usingStrings(listOf("inputImpl_switchKeyboardView"), org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                    paramTypes("java.lang.String")
                    returnType("void")
                }
            }.toList().firstOrNull { data ->
                val ownerName = data.declaredClassName ?: return@firstOrNull false
                runCatching { Class.forName(ownerName, false, loader) }
                    .getOrNull()
                    ?.let { !it.isInterface && !Modifier.isAbstract(it.modifiers) } == true
            } ?: return@runCatching
            val ownerName = switchData.declaredClassName ?: return@runCatching
            val owner = Class.forName(ownerName, false, loader)

            val accessor = bridge.findMethod {
                matcher {
                    declaredClass(ownerName)
                    paramCount(0)
                    returnType(ownerName)
                    modifiers(Modifier.STATIC)
                }
            }.toList().asSequence()
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()
                }
                .firstOrNull { it.parameterTypes.isEmpty() && it.returnType == owner }
            val fieldInstance = owner.declaredFields.asSequence()
                .filter { Modifier.isStatic(it.modifiers) && it.type == owner }
                .mapNotNull { field ->
                    runCatching {
                        field.isAccessible = true
                        field.get(null)
                    }.getOrNull()
                }
                .firstOrNull()
            val instance = accessor?.let { runCatching { it.invoke(null) }.getOrNull() } ?: fieldInstance
            if (instance == null) {
                log("keyboard-switch: api instance unavailable owner=$ownerName")
                return@runCatching
            }

            val switcher = switchData.getMethodInstance(loader).apply { isAccessible = true }
            switchMethod = switcher
            apiInstance = instance
            switchSignature = HookDiagnostics.methodSignature(switcher)
            accessorSignature = accessor?.let { HookDiagnostics.methodSignature(it) }
            resolved = true
            log(
                "keyboard-switch: resolved owner=$ownerName" +
                    " method=${switcher.name} types=${typeNames.take(8)} count=${typeNames.size}"
            )
        }.onFailure { log("keyboard-switch: resolve failed: ${it.message}") }
    }

    /**
     * 从宿主枚举里收集键盘类型名。
     *
     * 不写死枚举类名：按"哪个枚举类含有 `QWERTY` 语义常量"来定位；找不到时
     * 退而求其次，扫全部枚举里名字含 `QWERTY` 的常量。两种情况都只读名字，不改宿主。
     */
    private fun resolveTypeNames(bridge: DexKitBridge, loader: ClassLoader) {
        runCatching {
            val ownerName = bridge.findClass {
                matcher { usingStrings(listOf("QWERTY_EN")) }
            }.firstOrNull()?.name
            if (ownerName != null) {
                val cls = runCatching { Class.forName(ownerName, false, loader) }.getOrNull()
                if (cls != null && cls.isEnum) {
                    typeNames = cls.enumConstants.mapNotNull { (it as? Enum<*>)?.name }
                    val tokenField = cls.declaredFields.single {
                        !Modifier.isStatic(it.modifiers) && it.type == String::class.java
                    }.apply { isAccessible = true }
                    typeTokens = cls.enumConstants.associate { constant ->
                        (constant as Enum<*>).name to (tokenField.get(constant) as String)
                    }
                    log("keyboard-switch: verified enum protocol mapping=$typeTokens")
                }
            }
            if (typeNames.isEmpty()) {
                log("keyboard-switch: keyboard type enum unresolved by string anchor")
            }
        }.onFailure { log("keyboard-switch: type name resolve failed: ${it.message}") }
    }
}
