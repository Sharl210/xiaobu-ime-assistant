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
    private const val ROUND_FILE = "round.txt"

    /** 公共目录：宿主进程与模块进程都能读写，是主通道。 */
    fun publicDir(): File {
        val external = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        return if (external != null) File(File(external, "OplusImePanel"), "diagnostics")
        else File("/sdcard/OplusImePanel/diagnostics")
    }

    /** 宿主输入法进程的外部文件目录：宿主与模块私有目录不同，模块端需把它加入只读候选。 */
    private fun hostExternalDir(): File =
        File(
            File(File(Environment.getExternalStorageDirectory(), "Android/data/com.oplus.keyboard"), "files"),
            "diagnostics",
        )

    /** 某个进程可用的全部候选目录，按优先级排列。 */
    fun candidateDirs(context: Context?): List<File> = buildList {
        add(File(context?.filesDir ?: return@buildList, "diagnostics"))
        context?.getExternalFilesDir(null)?.let { add(File(it, "diagnostics")) }
        // 宿主进程当前实际写入的位置；在允许跨应用读取外部文件的设备上这是主回传通道。
        add(hostExternalDir())
        add(publicDir())
    }.distinctBy { it.absolutePath }

    fun reset(context: Context?) {
        candidateDirs(context).forEach { base ->
            runCatching {
                base.listFiles().orEmpty()
                    .filter { it.isFile && ((it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX)) || it.name == ROUND_FILE) }
                    .forEach { it.delete() }
            }.onFailure { logCritical("hook-diagnostics: reset failed path=${base.absolutePath} error=${it.message}") }
        }
    }

    fun markRoundFinished(context: Context?, finishedAt: Long, reason: String) {
        val payload = "$finishedAt\n${reason.replace('\n', ' ')}\n"
        candidateDirs(context).forEach { base ->
            runCatching {
                base.mkdirs()
                File(base, ROUND_FILE).writeText(payload)
            }.onFailure { logCritical("hook-diagnostics: round marker write failed path=${base.absolutePath} error=${it.message}") }
        }
    }

    fun readRound(dirs: List<File>): Pair<Long, String>? {
        for (base in dirs) {
            val file = File(base, ROUND_FILE)
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            val at = lines.firstOrNull()?.toLongOrNull() ?: continue
            if (at > 0L) return at to lines.getOrNull(1).orEmpty()
        }
        return null
    }

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
                val replace = shouldReplace(file, matched, updatedAt, detail)
                if (replace) {
                    file.writeText(payload)
                    log("hook-diagnostics: wrote point=$name matched=$matched path=${file.absolutePath}")
                }
                true
            }.onFailure {
                log("hook-diagnostics: write failed point=$name path=${base.absolutePath} error=${it.message}")
            }.getOrDefault(false)
            if (done) ok = true
        }
        if (!ok) log("hook-diagnostics: 落盘失败 point=$name")
    }

    private fun shouldReplace(file: File, matched: Boolean, updatedAt: Long, detail: String): Boolean {
        if (!file.isFile) return true
        val old = runCatching { file.readLines() }.getOrNull() ?: return true
        if (old.size < 3) return true
        val oldMatched = old[0].trim() == "1"
        val oldAt = old[1].trim().toLongOrNull() ?: 0L
        val oldPlaceholder = old[2].startsWith("安装期批量结算：") ||
            old[2].startsWith("未产生安装期记录") ||
            old[2].startsWith("安装轮次未执行") ||
            old[2].startsWith("安装轮次已完成但未提交") ||
            old[2].startsWith("待运行时验证")
        val newPlaceholder = detail.startsWith("安装期批量结算：") ||
            detail.startsWith("未产生安装期记录") ||
            detail.startsWith("安装轮次未执行") ||
            detail.startsWith("安装轮次已完成但未提交") ||
            detail.startsWith("待运行时验证")
        return when {
            oldMatched && !matched -> false
            oldPlaceholder && !newPlaceholder -> true
            !oldPlaceholder && newPlaceholder -> false
            oldMatched && !matched && newPlaceholder -> false
            matched && !oldMatched -> true
            updatedAt > oldAt -> true
            updatedAt == oldAt && matched && !oldMatched -> true
            else -> false
        }
    }


    fun read(dirs: List<File>, name: String): Triple<Boolean, Long, String>? {
        val fileName = PREFIX + safeName(name) + SUFFIX
        var newest: Triple<Boolean, Long, String>? = null
        for (base in dirs) {
            val file = File(base, fileName)
            if (!file.isFile) continue
            val parsed = runCatching {
                val lines = file.readLines()
                if (lines.size < 3) null
                else Triple(lines[0].trim() == "1", lines[1].trim().toLongOrNull() ?: 0L, lines[2])
            }.getOrNull() ?: continue
            if (parsed.second <= 0L) continue
            val parsedPlaceholder = parsed.third.startsWith("安装期批量结算：") ||
                parsed.third.startsWith("未产生安装期记录") ||
                parsed.third.startsWith("安装轮次未执行") ||
                parsed.third.startsWith("安装轮次已完成但未提交") ||
                parsed.third.startsWith("待运行时验证")
            if (newest == null) {
                newest = parsed
            } else {
                val newestPlaceholder = newest!!.third.startsWith("安装期批量结算：") ||
                    newest!!.third.startsWith("未产生安装期记录") ||
                    newest!!.third.startsWith("安装轮次未执行") ||
                    newest!!.third.startsWith("安装轮次已完成但未提交") ||
                    newest!!.third.startsWith("待运行时验证")
                when {
                    newestPlaceholder && !parsedPlaceholder -> newest = parsed
                    !newestPlaceholder && parsedPlaceholder -> Unit
                    newest!!.first && !parsed.first -> Unit
                    parsed.first && !newest!!.first -> newest = parsed
                    parsed.second > newest!!.second -> newest = parsed
                    parsed.second == newest!!.second && parsed.first && !newest!!.first -> newest = parsed
                }
            }
        }
        return newest
    }

    /** 文件名里不能出现路径分隔符；其余字符原样保留。 */
    private fun safeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').replace('\n', '_')
}
