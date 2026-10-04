package com.oplusime.panel

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Hook 匹配结果的**跨进程落盘**。
 *
 * ## 为什么需要它
 *
 * 模块跑在两个进程里：
 *  - Hook 逻辑跑在宿主进程 `com.oplus.keyboard`；
 *  - 诊断页跑在模块进程 `com.oplusime.panel`。
 *
 * 第一版用 `ContentProvider.call()` 从宿主进程回传结果，但真机日志里每一行都是：
 *
 * ```text
 * hook-diagnostics: IPC failed point=上滑键位绘制 error=Unknown authority com.oplusime.panel.hookdiagnostics
 * ```
 *
 * `Unknown authority` 说明宿主进程里查不到这个 provider。用户看到的现象就是
 * 「诊断页里全是叉」，即所有点位都读不到匹配结果 —— 与有没有真的匹配上无关，
 * 纯粹是**证据通道断了**。
 *
 * ## 现在的做法
 *
 * 不再依赖 provider。每个点位写成一个独立文本文件，写到所有可写的位置
 * （外部存储的公共目录 + 模块私有目录），诊断页把这几处都读一遍，谁有读谁。
 * 文件内容固定三行（是否命中 / 时间戳 / 详情），读端按行位置取值，不做格式猜测。
 */
internal object HookDiagnosticsStore {

    private const val PREFIX = "point."
    private const val SUFFIX = ".txt"

    /** 公共目录：宿主进程与模块进程都能读写，是主通道。 */
    fun publicDir(): File {
        val external = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        return if (external != null) File(File(external, "OplusImePanel"), "diagnostics")
        else File("/sdcard/OplusImePanel/diagnostics")
    }

    /** 某个进程可用的全部候选目录，按优先级排列。 */
    fun candidateDirs(context: Context?): List<File> = buildList {
        add(File(context?.filesDir ?: return@buildList, "diagnostics"))
        context?.getExternalFilesDir(null)?.let { add(File(it, "diagnostics")) }
        add(publicDir())
    }.distinctBy { it.absolutePath }

    /** 写一条点位结果：尽力写到每一处可写位置，失败只留日志。 */
    fun write(context: Context?, name: String, matched: Boolean, detail: String, updatedAt: Long) {
        val payload = buildString {
            append(if (matched) "1" else "0").append('\n')
            append(updatedAt).append('\n')
            append(detail.replace('\n', ' ')).append('\n')
        }
        val fileName = PREFIX + safeName(name) + SUFFIX
        var ok = false
        for (base in candidateDirs(context)) {
            val done = runCatching {
                base.mkdirs()
                val file = File(base, fileName)
                file.writeText(payload)
                log("hook-diagnostics: wrote point=$name matched=$matched path=${file.absolutePath}")
                true
            }.onFailure {
                log("hook-diagnostics: write failed point=$name path=${base.absolutePath} error=${it.message}")
            }.getOrDefault(false)
            if (done) ok = true
        }
        if (!ok) log("hook-diagnostics: 落盘失败 point=$name")
    }

    /** 读一条点位结果；所有候选目录都找不到时返回 null。 */
    fun read(dirs: List<File>, name: String): Triple<Boolean, Long, String>? {
        val fileName = PREFIX + safeName(name) + SUFFIX
        for (base in dirs) {
            val file = File(base, fileName)
            if (!file.isFile) continue
            val parsed = runCatching {
                val lines = file.readLines()
                if (lines.size < 3) null
                else Triple(lines[0].trim() == "1", lines[1].trim().toLongOrNull() ?: 0L, lines[2])
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /** 文件名里不能出现路径分隔符；其余字符原样保留。 */
    private fun safeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').replace('\n', '_')
}
