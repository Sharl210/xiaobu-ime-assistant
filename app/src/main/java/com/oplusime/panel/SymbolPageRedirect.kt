package com.oplusime.panel

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 所有符号入口统一走宿主自己的完整符号覆盖层。
 *
 * 入口由两类结构证据组成：
 * 1. `h0(boolean)` 的日志语义串用于捕获键盘符号按钮入口；
 * 2. 同一管理器中的无参 `void` 完整符号入口，按其完整覆盖层初始化语义串定位。
 * 不依赖宿主混淆类名、混淆方法名或固定类路径。
 */
internal object SymbolPageRedirect {
    private const val ENTRY_ANCHOR = "show symbol view shown "
    private const val FULL_ENTRY_ANCHOR = "Failed to instantiate class "

    private var entryMethod: Method? = null
    private var fullMethod: Method? = null
    private var holderClass: Class<*>? = null
    private var holderInstance: Any? = null
    private var managerAccessor: Method? = null
    private var symbolSwitchMethod: Method? = null
    private val opening = ThreadLocal<Boolean>()
    private var apiHookCount = 0

    fun install(bridge: DexKitBridge, loader: ClassLoader) {
        runCatching {
            val entryData = bridge.findMethod {
                matcher {
                    usingStrings(listOf(ENTRY_ANCHOR), StringMatchType.Equals)
                    paramTypes("boolean")
                    returnType("void")
                }
            }.single()
            val entry = entryData.getMethodInstance(loader).apply { isAccessible = true }
            val holder = entry.declaringClass

            val full = resolveFullEntry(bridge, loader, holder)
            check(full != null) { "full symbol entry unresolved" }

            val accessor = bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType(holder.name)
                    modifiers(Modifier.STATIC)
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()
            }.firstOrNull { it.parameterTypes.isEmpty() && it.returnType == holder }

            entryMethod = entry
            fullMethod = full
            holderClass = holder
            managerAccessor = accessor
            holderInstance = accessor?.let { runCatching { it.invoke(null) }.getOrNull() }

            log(
                "symbol-page: resolved entry=${entry.declaringClass.name}#${entry.name}" +
                    " full=${full.declaringClass.name}#${full.name}" +
                    " accessor=${accessor?.declaringClass?.name}#${accessor?.name}" +
                    " instance=${holderInstance != null}"
            )

            fun showFull(manager: Any, source: String): Boolean {
                if (opening.get() == true) return false
                opening.set(true)
                return try {
                    full.invoke(manager)
                    holderInstance = manager
                    log("symbol-page: full entry invoked source=$source manager=${manager.javaClass.name}")
                    HookDiagnostics.record(null, "符号完整页入口", true,
                        "entry=${full.declaringClass.name}#${full.name}; source=$source")
                    true
                } catch (t: Throwable) {
                    log("symbol-page: full entry failed source=$source: ${t.cause ?: t}")
                    false
                } finally {
                    opening.remove()
                }
            }

            // 中文/英文分支按 h0 日志中的当前键盘枚举选择。
            // 只在日志确认的主键盘枚举下处理中英文符号入口。
            XposedBridge.hookMethod(entry, object : XC_MethodHook(1000) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() != true) return
                    val currentType = runCatching {
                        holder.declaredFields.firstOrNull { field ->
                            Modifier.isStatic(field.modifiers) && field.type.isEnum &&
                                field.type.enumConstants.any { (it as? Enum<*>)?.name?.contains("SYMBOL") == true }
                        }?.apply { isAccessible = true }?.get(null) as? Enum<*>
                    }.getOrNull()
                    val rawType = currentType?.let { type ->
                        runCatching {
                            type.javaClass.declaredFields.firstOrNull {
                                !Modifier.isStatic(it.modifiers) && it.type == String::class.java
                            }?.apply { isAccessible = true }?.get(type) as? String
                        }.getOrNull().orEmpty()
                    }.orEmpty()
                    val keyboardName = currentType?.name.orEmpty()
                    log("symbol-page: h0 before type=$rawType enum=$keyboardName")
                    // 本轮日志显示 h0 确实命中，但上一版把 shown 改为 false 后按钮不响应。
                    // 不再改 h0 参数；恢复宿主原有分派，只将随后 k0 使用的档位在最终切页前修正。
                    // h0 的数字/中英文分支均可继续完成宿主切换，避免提前返回或改变其分支语义。
                    if (keyboardName.contains("QWERTY_PINYIN", ignoreCase = true) ||
                        keyboardName.contains("QWERTY_EN", ignoreCase = true)) {
                        val variant = holder.declaredMethods.firstOrNull { method ->
                            val p = method.parameterTypes
                            !Modifier.isStatic(method.modifiers) && p.size == 2 && p[0].isEnum &&
                                p[1] == Boolean::class.javaPrimitiveType && method.returnType == Boolean::class.javaPrimitiveType
                        }
                        val symbolType = enumConstantsForKeyboardSymbol(currentType)
                        if (variant != null && symbolType != null) {
                            runCatching {
                                val result = variant.invoke(param.thisObject, symbolType, false) as? Boolean
                                log("symbol-page: host full branch invoked enum=$keyboardName symbolType=${symbolType.name} result=$result")
                            }.onFailure { log("symbol-page: host full branch failed enum=$keyboardName error=${it.cause ?: it}") }
                        } else {
                            log("symbol-page: full branch unavailable enum=$keyboardName variant=${variant != null} symbolType=${symbolType?.name}")
                        }
                    }
                }
            })
            fun promoteFullTier(manager: Any): Boolean {
                val field = manager.javaClass.declaredFields.firstOrNull { field ->
                    !Modifier.isStatic(field.modifiers) && field.type.isEnum &&
                        field.type.enumConstants.any { (it as? Enum<*>)?.name == "SYMBOL2" }
                } ?: return false
                return runCatching {
                    field.isAccessible = true
                    val full = field.type.enumConstants.first { (it as Enum<*>).name == "SYMBOL2" }
                    field.set(manager, full)
                    log("symbol-page: promoted host symbol tier to SYMBOL2 field=${field.name}")
                    true
                }.getOrDefault(false)
            }

            val switches = bridge.findMethod {
                matcher {
                    declaredClass(holder.name)
                    paramCount(5)
                    returnType("void")
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()
            }.filter { method ->
                val p = method.parameterTypes
                Modifier.isStatic(method.modifiers) &&
                    p.size == 5 && p[0] == holder && p[1].isEnum &&
                    p[2] == Boolean::class.javaPrimitiveType &&
                    p[3] == Boolean::class.javaPrimitiveType &&
                    p[4] == Int::class.javaPrimitiveType
            }
            switches.distinctBy { it.toGenericString() }.forEach { switch ->
                symbolSwitchMethod = switch
                XposedBridge.hookMethod(switch, object : XC_MethodHook(100) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val keyboard = param.args.getOrNull(1) as? Enum<*> ?: return
                        val typeValue = runCatching {
                            keyboard.javaClass.declaredFields.firstOrNull {
                                !Modifier.isStatic(it.modifiers) && it.type == String::class.java
                            }?.apply { isAccessible = true }?.get(keyboard) as? String
                        }.getOrNull().orEmpty()
                        if (!typeValue.contains("symbol", ignoreCase = true)) return
                        val manager = param.args.getOrNull(0) ?: return
                        holderInstance = manager
                        val promoted = promoteFullTier(manager)
                        log("symbol-page: keyboard switch observed type=$typeValue promoted=$promoted; preserve host k0 dispatch")
                    }
                })
            }
            // 中文/英文符号键实际走管理器中的实例方法：(KeyboardType, boolean) -> boolean。
            // DexKit 用于确定宿主管理器；方法本身再按 JVM 可观察签名筛选，避免
            // DexKit 对 primitive returnType/混淆枚举参数的查询差异导致 hooks=0。
            val variantMethods = holder.declaredMethods.filter { method ->
                val p = method.parameterTypes
                !Modifier.isStatic(method.modifiers) &&
                    p.size == 2 && p[0].isEnum &&
                    p[1] == Boolean::class.javaPrimitiveType &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
            variantMethods.distinctBy { it.toGenericString() }.forEach { variant ->
                variant.isAccessible = true
                XposedBridge.hookMethod(variant, object : XC_MethodHook(100) {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val keyboard = param.args.getOrNull(0) as? Enum<*> ?: return
                        val typeValue = runCatching {
                            keyboard.javaClass.declaredFields.firstOrNull {
                                !Modifier.isStatic(it.modifiers) && it.type == String::class.java
                            }?.apply { isAccessible = true }?.get(keyboard) as? String
                        }.getOrNull().orEmpty()
                        if (!typeValue.contains("symbol", ignoreCase = true)) return
                        val manager = param.thisObject ?: return
                        holderInstance = manager
                        // h0 对中文/英文符号入口传入 true 时，v() 会直接写回简洁档 c，
                        // 随后不会走完整切换。数字完整入口对应的是 v(..., false)：
                        // 由宿主 v() 自己设置档位并调用 k0()，再由 k0 完成页面创建。
                        val originalFlag = param.args.getOrNull(1) as? Boolean
                        if (originalFlag == true) {
                            param.args[1] = false
                            log("symbol-page: variant flag redirected type=$typeValue true->false")
                        }
                        log("symbol-page: variant before type=$typeValue flag=${param.args.getOrNull(1)}; preserve host v->k0 dispatch")
                    }
                })
            }
            log("symbol-page: variant hooks=${variantMethods.size}; candidates=" +
                holder.declaredMethods.filter { it.parameterTypes.size == 2 }.joinToString { it.toGenericString() })
            // 宿主公开 API 本身已经调用完整入口；这里只记录实际命中，方便诊断页显示调用覆盖。
            //
            // 注意：`IInputApi` 里的这两个方法是**接口抽象方法**，直接 hook 会报
            // `Cannot hook abstract methods`（真机日志已出现两次），必须挂到实现类的那一份。
            listOf("showSymbolsView", "showSymbolsViewWithLockSelect").forEach { apiName ->
                val methods = bridge.findMethod {
                    matcher {
                        name(apiName)
                        paramCount(0)
                        returnType("void")
                    }
                }.mapNotNull { data ->
                    runCatching { data.getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()
                }.filterNot { Modifier.isAbstract(it.modifiers) }
                var hooked = 0
                methods.distinctBy { it.toGenericString() }.forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                apiHookCount++
                                log("symbol-page: public full API invoked name=$apiName count=$apiHookCount")
                                HookDiagnostics.record(null, "符号完整页入口", true,
                                    "${method.declaringClass.name}#${method.name}; source=public-api")
                            }
                        })
                        hooked++
                    }.onFailure { log("symbol-page: public API hook failed name=$apiName error=${it.message}") }
                }
                log("symbol-page: public API $apiName hooks=$hooked")
            }

            HookDiagnostics.record(null, "符号完整页入口", true,
                "full=${full.declaringClass.name}#${full.name}; apiHooks=$apiHookCount")
        }.onFailure {
            HookDiagnostics.record(null, "符号完整页入口", false, it.message.orEmpty())
            log("symbol-page: unified entry resolution failed: ${it.cause ?: it}")
        }
    }

    private fun enumConstantsForKeyboardSymbol(current: Enum<*>?): Enum<*>? {
        val enumClass = current?.javaClass ?: return null
        return enumClass.enumConstants?.filterIsInstance<Enum<*>>()
            ?.firstOrNull { it.name.contains("SYMBOL", ignoreCase = true) }
    }

    private fun resolveFullEntry(bridge: DexKitBridge, loader: ClassLoader, holder: Class<*>): Method? {
        val semantic = bridge.findMethod {
            matcher {
                declaredClass(holder.name)
                paramCount(0)
                returnType("void")
                usingStrings(listOf(FULL_ENTRY_ANCHOR), StringMatchType.Equals)
            }
        }.mapNotNull { data ->
            runCatching { data.getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull()
        if (semantic != null) return semantic

        // 第二个结构锚点覆盖同一宿主升级中日志文本拆分的情况。
        return bridge.findMethod {
            matcher {
                declaredClass(holder.name)
                paramCount(0)
                returnType("void")
                usingStrings(listOf("getInterfaces(...)"), StringMatchType.Equals)
            }
        }.mapNotNull { data ->
            runCatching { data.getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull()
    }

    fun backToMainKeyboard() {
        val entry = entryMethod ?: run {
            log("symbol-page: back skipped: entry unavailable")
            return
        }
        val manager = holderInstance
            ?: managerAccessor?.let { runCatching { it.invoke(null) }.getOrNull() }
            ?: holderClass?.let { Reflect.selfSingleton(it) }
            ?: run {
                log("symbol-page: back skipped: manager unavailable")
                return
            }
        holderInstance = manager
        runCatching { entry.invoke(manager, false) }
            .onSuccess { log("symbol-page: back -> host keyboard reset invoked") }
            .onFailure { log("symbol-page: back failed: ${it.cause ?: it}") }
    }
}
