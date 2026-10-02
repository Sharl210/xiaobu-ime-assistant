package com.oplusime.panel

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * 「剪贴板」按钮的真实落点。
 *
 * 宿主证据（com.oplus.keyboard 1.7.38.17-os）：
 *
 * 1) 剪贴板面板由 BoxEnums 常量 `BOX_CLIP` 标识（该枚举常量名是语义串，不是混淆短名）。
 * 2) 存在唯一一个“按 BoxEnums 打开面板”的分发方法：
 *      void <dispatcher>(BoxEnums type, boolean fromToolbox, String payload)
 *    其内部是覆盖全部 BoxEnums 的 when 表（packed-switch on ordinal），
 *    ordinal=5（BOX_CLIP）分支原样代码为：
 *        manager = c0.b()                                  // input.manager.d
 *        manager.q(base.manager.g.a, new b0(1))            // 打开剪贴板面板
 *        input.manager.l.A = true
 *    该分支完全不使用第二、第三个参数，因此本模块传 (BOX_CLIP, false, null)。
 * 3) 同一条链在宿主的头部剪贴板按钮 onClick（view id==4 分支）中独立出现，
 *    两处互相印证。
 *
 * 主路径完全结构化：枚举类由字符串 `BOX_CLIP` 命中，分发方法由参数形状命中，
 * 单例由“自类型静态字段”命中。只有兜底路径使用本版类名，并在日志中明确标注。
 */
internal class ClipboardOpener private constructor(
    private val dispatcher: Method?,
    private val dispatcherOwner: Any?,
    private val clipBoxEnum: Any?,
    private val legacy: LegacyPath?,
    private val enumClass: Class<*>? = null,
) {

    /**
     * 按 BoxEnums 常量名打开任意一个面板（`BOX_CLIP` / `BOX_PHRASE` …）。
     * 常量名是语义串，不是混淆名；只在这一个入口用名字，其余仍走结构匹配。
     */
    fun openByName(boxName: String): Boolean = runCatching {
        val d = dispatcher ?: error("dispatcher unresolved")
        val owner = dispatcherOwner ?: error("dispatcher singleton unresolved")
        val value = enumClass?.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == boxName }
            ?: error("box enum $boxName unresolved")
        d.invoke(owner, value, false, null)
        true
    }.getOrElse { error ->
        log("panel open ($boxName) failed: ${error.message}")
        false
    }

    fun open(context: Context) {
        val primary = runCatching {
            val d = dispatcher ?: error("dispatcher unresolved")
            val owner = dispatcherOwner ?: error("dispatcher singleton unresolved")
            val enumValue = clipBoxEnum ?: error("BOX_CLIP enum unresolved")
            val args = arrayOf<Any?>(enumValue, false, null)
            d.invoke(owner, *args)
            true
        }.getOrElse { error ->
            log("clipboard open (structural path) failed: ${error.message}")
            false
        }
        if (primary) {
            log("clipboard opened via structural dispatcher")
            return
        }
        val fallback = runCatching { legacy?.open() ?: false }
            .onFailure { log("clipboard open (fallback path) failed: ${it.message}") }
            .getOrDefault(false)
        if (fallback) {
            log("clipboard opened via VERSION_PINNED fallback")
        } else {
            log("clipboard could not be opened by any known path on this host build")
        }
    }

    companion object {
        fun resolve(
            bridge: DexKitBridge,
            hostClassLoader: ClassLoader,
            enumName: String,
        ): ClipboardOpener {
            val enumClass = resolveClipEnumClass(bridge, hostClassLoader, enumName)
            val enumValue = enumClass?.enumConstants?.firstOrNull {
                (it as? Enum<*>)?.name == enumName
            }
            if (enumClass == null || enumValue == null) {
                log("BOX_CLIP enum unresolved, structural path disabled")
            }

            val dispatcherData = enumClass?.let { findDispatcher(bridge, hostClassLoader, it.name) }
            val dispatcher = dispatcherData?.let {
                runCatching { it.getMethodInstance(hostClassLoader) }
                    .onFailure { e -> log("dispatcher instance failed: ${e.message}") }
                    .getOrNull()
            }
            val dispatcherOwner = dispatcher?.let { Reflect.selfSingleton(it.declaringClass) }
            if (dispatcher != null) {
                log("clipboard dispatcher selected=${dispatcherData.descriptor}")
            }

            return ClipboardOpener(
                dispatcher = dispatcher,
                dispatcherOwner = dispatcherOwner,
                clipBoxEnum = enumValue,
                legacy = LegacyPath(hostClassLoader),
                enumClass = enumClass,
            )
        }

        /** 枚举类由语义字符串 `BOX_CLIP` 命中，不用类名。 */
        private fun resolveClipEnumClass(
            bridge: DexKitBridge,
            hostClassLoader: ClassLoader,
            enumName: String,
        ): Class<*>? {
            val candidates: List<ClassData> = runCatching {
                bridge.findClass {
                    matcher {
                        usingStrings(listOf(enumName), StringMatchType.Equals, false)
                    }
                }.toList()
            }
                .onFailure { log("box-enums query failed: ${it.message}") }
                .getOrDefault(emptyList())
            log("box-enums candidates=${candidates.size}")
            val selected = candidates.firstOrNull {
                runCatching { it.getInstance(hostClassLoader).isEnum }.getOrDefault(false)
            } ?: return null
            log("box-enums selected=${selected.name}")
            return runCatching { selected.getInstance(hostClassLoader) }.getOrNull()
        }

        /**
         * 分发方法：void (BoxEnums, boolean, String)。参数形状即语义。
         * 多个候选时用“声明类带自类型静态单例”去歧义（宿主 object 单例的形态），
         * 仍无法唯一化则取第一个并把全部候选写进日志供审计。
         */
        private fun findDispatcher(
            bridge: DexKitBridge,
            hostClassLoader: ClassLoader,
            enumClassName: String,
        ): MethodData? {
            val candidates = runCatching {
                bridge.findMethod {
                    matcher {
                        returnType("void")
                        paramCount(3)
                        paramTypes(enumClassName, "boolean", "java.lang.String")
                    }
                }.toList()
            }
                .onFailure { log("clipboard-dispatch query failed: ${it.message}") }
                .getOrDefault(emptyList())
            log("clipboard-dispatch candidates=${candidates.size}")
            if (candidates.size > 1) {
                candidates.take(5).forEach { log("  candidate ${it.descriptor}") }
            }
            if (candidates.isEmpty()) return null
            return candidates.firstOrNull { data ->
                val owner = runCatching {
                    data.declaredClass?.getInstance(hostClassLoader)
                }.getOrNull()
                owner != null && Reflect.selfSingleton(owner) != null
            } ?: candidates.first()
        }
    }

    /**
     * 版本绑定兜底路径 —— **已删除**（1.33.2）。
     *
     * 它把宿主混淆类名直接写进代码（`input.view.c0` / `base.manager.g` / `input.view.b0` …），
     * 违反本项目的全局硬约束：**任何功能都只能由 DexKit 通用结构匹配定位，不得硬编码宿主混淆类名**。
     * 这种写法在宿主升级改名后必然静默失效，还会掩盖主路径的真实问题。
     *
     * 现在只有一条路：按 `BoxEnums` 常量名 + 分发方法参数形状定位的**结构路径**。
     * 结构路径不可用时如实记日志（`clipboard could not be opened by any known path`），
     * 不靠猜测兜底。
     */
    private class LegacyPath(@Suppress("UNUSED_PARAMETER") hostClassLoader: ClassLoader) {
        fun open(): Boolean = false
    }
}
