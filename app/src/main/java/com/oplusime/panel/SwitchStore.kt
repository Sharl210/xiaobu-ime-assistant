package com.oplusime.panel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * App 侧（模块自己的进程 `com.oplusime.panel`）的开关读写。
 *
 * ## 为什么不能只写自己的 SharedPreferences
 *
 * Hook 逻辑跑在**宿主进程** `com.oplus.keyboard` 里，它要读同一份开关值。
 * 跨进程读文件有两个前提：
 *
 *  1. 文件内容格式是 SharedPreferences 的 XML（[de.robv.android.xposed.XSharedPreferences] 读的就是它）；
 *  2. 宿主进程对该文件（以及 `shared_prefs` 目录）有读权限。
 *
 * 第 2 点需要主动放开权限：App 进程改完自己的文件后，把文件与目录的权限位放大到「其他用户可读」。
 * 这是 LSPosed 生态里跨进程读模块配置的常规做法；失败也不致命 —— Hook 侧会退回默认值。
 */
internal object SwitchStore {

    private const val TAG: String = "OplusImePanel"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ModuleSwitches.PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前日志开关（App 界面用）。 */
    fun logEnabled(context: Context): Boolean = prefs(context).getBoolean(
        ModuleSwitches.KEY_LOG_ENABLED,
        ModuleSwitches.DEFAULT_LOG_ENABLED,
    )

    /** 是否被用户手动设置过（界面上用来显示"默认值 / 已手动设置"）。 */
    fun logEnabledCustomized(context: Context): Boolean =
        prefs(context).contains(ModuleSwitches.KEY_LOG_ENABLED)

    /** 写入日志开关，并立刻放开文件权限，让宿主进程能读到。 */
    fun setLogEnabled(context: Context, enabled: Boolean): Boolean {
        val ok = runCatching {
            prefs(context).edit().putBoolean(ModuleSwitches.KEY_LOG_ENABLED, enabled).commit()
        }.getOrDefault(false)
        makeReadable(context)
        return ok
    }

    /**
     * 把 `shared_prefs` 目录与其中的 XML 放开到「其他用户可读」。
     *
     * 目录必须可**进入**（x 位），否则宿主进程即使文件可读也打不开路径。
     * 全部包在 runCatching 里：部分机型/沙箱会拒绝 chmod，此时静默降级为"宿主侧用默认值"。
     */
    fun makeReadable(context: Context) {
        runCatching {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val file = File(dir, "${ModuleSwitches.PREFS_NAME}.xml")
            // 664：文件本身可读；771：目录可进入、可列出。
            runCatching { setPerm(file, "664") }
            runCatching { setPerm(dir, "771") }
            runCatching { setPerm(File(context.applicationInfo.dataDir), "771") }
            Log.i(TAG, "switch-store: perms relaxed dir=${dir.exists()} file=${file.exists()}")
        }
    }

    private fun setPerm(target: File, mode: String) {
        // 反射调用 FileUtils.setPermissions：避免依赖隐藏 API 的编译期可见性。
        val cls = Class.forName("android.os.FileUtils")
        val method = cls.getMethod(
            "setPermissions",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.invoke(null, target.absolutePath, mode.toInt(8), -1, -1)
    }
}
