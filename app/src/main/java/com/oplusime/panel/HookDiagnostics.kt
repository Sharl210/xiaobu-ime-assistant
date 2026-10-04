package com.oplusime.panel

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

internal object HookDiagnostics {
    private const val PREFS = "hook_diagnostics"
    private val local = ConcurrentHashMap<String, Point>()

    data class Point(val name: String, val matched: Boolean, val detail: String, val updatedAt: Long)

    /**
     * Hook 进程里拿到的宿主 Context。
     *
     * 安装阶段（`handleLoadPackage` 的后台线程）就已经拿得到，这里先存下来，
     * 后续所有点位写入都能直接用它落盘，不必再逐个点位去反射找 Application。
     */
    @Volatile
    var hostContextOverride: Context? = null

    private val expected = listOf(
        "模块安装入口", "符号完整页入口", "符号键盘切换入口", "上滑键位绘制", "英文候选读写",
        "输入提交拦截", "宿主常用语编辑入口", "搜索过滤适配器", "剪贴板编辑绑定", "剪贴板写回",
        "文本编辑面板点击", "文本编辑面板排版", "返回键路由", "容量限制解除", "日志开关",
        "候选拼音光标",
    )

    private fun hostContext(explicit: Context?): Context? {
        if (explicit != null) return explicit
        hostContextOverride?.let { return it }
        return runCatching {
            val candidates = listOf(
                "de.robv.android.xposed.AndroidAppHelper",
                "android.app.ActivityThread",
            )
            candidates.asSequence().mapNotNull { name ->
                runCatching {
                    val cls = Class.forName(name)
                    val method = cls.methods.firstOrNull { it.name == "currentApplication" && it.parameterTypes.isEmpty() }
                        ?: cls.methods.firstOrNull { it.name == "currentActivityThread" && it.parameterTypes.isEmpty() }
                    if (method?.name == "currentApplication") method.invoke(null) as? Context
                    else {
                        val thread = method?.invoke(null) ?: return@runCatching null
                        cls.methods.firstOrNull { it.name == "getApplication" && it.parameterTypes.isEmpty() }
                            ?.invoke(thread) as? Context
                    }
                }.getOrNull()
            }.firstOrNull()
        }.getOrNull()
    }

    fun record(context: Context?, name: String, matched: Boolean, detail: String) {
        val point = Point(name, matched, detail, System.currentTimeMillis())
        local[name] = point
        // 主通道：跨进程落盘（两个进程读同一批文件）。
        HookDiagnosticsStore.write(context ?: hostContext(null), name, matched, detail, point.updatedAt)
        // 次通道：ContentProvider 若在宿主进程里可用，仍然回传一次（便于旧版本环境）。
        runCatching {
            val host = hostContext(context) ?: return@runCatching
            val result = Bundle().apply {
                putString("name", name)
                putBoolean("matched", matched)
                putString("detail", detail)
                putLong("updatedAt", point.updatedAt)
            }
            val uri = Uri.parse("content://com.oplusime.panel.hookdiagnostics")
            val resolved = runCatching {
                host.packageManager.resolveContentProvider("com.oplusime.panel.hookdiagnostics", 0)?.authority
            }.getOrNull()
            val response = host.contentResolver.call(uri, "record", null, result)
            log("hook-diagnostics: provider record point=$name resolved=$resolved response=${response?.getBoolean("ok", false)}")
        }.onFailure { log("hook-diagnostics: provider 通道不可用 point=$name error=${it.message}") }
    }

    fun flush(context: Context) {
        local.values.toList().forEach { point ->
            record(context, point.name, point.matched, point.detail)
        }
    }

    fun snapshot(context: Context): List<Point> {
        val dirs = HookDiagnosticsStore.candidateDirs(context)
        val fromDisk = expected.associateWith { HookDiagnosticsStore.read(dirs, it) }
        val byName = linkedMapOf<String, Point>()
        // 宿主进程的 filesDir 与模块进程隔离；通过已验证的 ContentProvider 回读宿主写入的记录。
        // 不能把“写入成功”当作 UI 已消费，Provider snapshot 才是模块界面可见结果的读取通道。
        runCatching {
            val response = context.contentResolver.call(
                Uri.parse("content://com.oplusime.panel.hookdiagnostics"),
                "snapshot", null, null,
            ) ?: return@runCatching
            if (!response.getBoolean("ok", false)) return@runCatching
            val names = response.getStringArrayList("names").orEmpty()
            val records = response.getBundle("points") ?: return@runCatching
            names.forEach { name ->
                val value = records.getBundle(name) ?: return@forEach
                val at = value.getLong("updatedAt", 0L)
                if (at > 0L) {
                    byName[name] = Point(
                        name,
                        value.getBoolean("matched", false),
                        value.getString("detail").orEmpty(),
                        at,
                    )
                }
            }
        }.onFailure { log("hook-diagnostics: provider snapshot read failed: ${it.message}") }
        local.values.forEach { byName[it.name] = it }
        return expected.map { byName[it] ?: Point(it, false, "尚未记录匹配结果", 0L) } +
            byName.values.filter { it.name !in expected }.sortedBy { it.name }
    }
}
