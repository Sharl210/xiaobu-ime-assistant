package com.oplusime.panel

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

internal object HookDiagnostics {
    internal const val PREFS = "hook_diagnostics"
    internal const val ACTION_RECORD = "com.oplusime.panel.RECORD_HOOK_DIAGNOSTIC"
    internal const val ACTION_RESET = "com.oplusime.panel.RESET_HOOK_DIAGNOSTIC"
    internal const val ACTION_FINISH = "com.oplusime.panel.FINISH_HOOK_DIAGNOSTIC"
    private val local = ConcurrentHashMap<String, Point>()

    data class Point(val name: String, val matched: Boolean, val detail: String, val updatedAt: Long) {
        val description: String get() = descriptionFor(name)
    }

    @Volatile
    var hostContextOverride: Context? = null

    private fun descriptionFor(name: String): String =
        expected.firstOrNull { it.name == name }?.description ?: "运行时补充检测点。"

    private data class Expected(val name: String, val description: String)

    /**
     * This is deliberately a point-level registry, not a feature summary. A feature may have
     * several independent DexKit matches and runtime hooks; every one gets its own row so a
     * green aggregate cannot hide a missing sub-hook.
     */
    private val expected = listOf(
        Expected("模块安装入口", "宿主进程安装状态项，不是 DexKit Hook 匹配点。"),
        Expected("日志开关", "模块日志配置状态项，不是 DexKit Hook 匹配点。"),
        Expected("键盘类型切换", "定位宿主主键盘切换入口；英文候选切换不应使用它。"),
        Expected("符号完整页入口", "定位并接入宿主完整符号页的入口。"),
        Expected("符号完整页:键盘分支", "将中英文符号入口交给宿主完整页面分支。"),
        Expected("符号完整页:公开API", "检查宿主公开符号页面接口的实现方法。"),
        Expected("符号完整页:公开API:showSymbolsView", "宿主公开的完整符号页面入口之一。"),
        Expected("符号完整页:公开API:showSymbolsViewWithLockSelect", "宿主公开的带锁定选择完整符号页面入口。"),
        Expected("上滑键位绘制", "按当前键盘语言改写上滑字符并记录实际绘制。"),
        Expected("上滑键位绘制:绘制入口", "定位 SoftKey 绘制方法。"),
        Expected("上滑动作:远程输入连接", "拦截输入法向目标应用提交上滑动作的远程连接。"),
        Expected("上滑动作:宿主输入连接", "拦截宿主自有 InputConnection 实现。"),
        Expected("上滑动作:内部提交出口", "拦截宿主内部提交汇聚点。"),
        Expected("英文候选读写", "定位英文候选真实设置的读取和写入方法。"),
        Expected("英文候选:读取器", "直接读取 key_en_predict 当前值。"),
        Expected("英文候选:写入器", "通过宿主设置写入入口修改 key_en_predict。"),
        Expected("输入提交拦截", "安装输入提交和宿主文案相关的拦截。"),
        Expected("引号:输入连接", "在真实输入连接出口抑制自动补出的闭合符号。"),
        Expected("引号:框架输入连接", "挂载框架输入连接代理的提交和组合文本入口。"),
        Expected("引号:宿主输入连接", "挂载宿主自有 InputConnection 实现。"),
        Expected("引号:宿主提交分发", "挂载宿主 common/final/direct 提交分发器。"),
        Expected("引号:引擎提交汇聚点", "挂载 native 引擎回调和最终提交汇聚点。"),
        Expected("引号:成对符号源头", "在宿主配对表产生闭合符号之前截断自动后半部分；手动后符号不经过此点。"),
        Expected("宿主编辑界面输入码隐藏", "复用宿主编辑模板时隐藏不需要的输入码布局。"),
        Expected("搜索界面原生模板", "搜索框复用宿主常用语编辑模板。"),
        Expected("编辑界面原生模板", "剪贴板编辑框复用宿主常用语编辑模板。"),
        Expected("宿主常用语编辑入口", "复用宿主常用语编辑器的输入框和弹窗模板。"),
        Expected("搜索过滤适配器", "接入剪贴板分页数据过滤。"),
        Expected("剪贴板面板", "定位剪贴板面板及其分页/分段切换。"),
        Expected("剪贴板编辑绑定", "安装剪贴板编辑功能总入口。"),
        Expected("剪贴板编辑绑定:实体匹配", "定位当前版本剪贴板实体和正文成员。"),
        Expected("剪贴板编辑绑定:写回合同", "定位 Room 事务、SQL 适配器和写回入口。"),
        Expected("剪贴板编辑绑定:行绑定", "定位剪贴板分页适配器的行绑定方法。"),
        Expected("剪贴板编辑绑定:按钮插入", "在真实剪贴板行的动作排插入编辑按钮。"),
        Expected("剪贴板写回", "编辑确认后调用宿主数据库写回链。"),
        Expected("文本编辑面板点击", "定位文本编辑面板点击分发。"),
        Expected("文本编辑面板排版", "定位文本编辑面板排版回调。"),
        Expected("返回键路由", "把编辑/剪贴板面板返回动作接回主键盘。"),
        Expected("返回键:宿主onKeyDown", "接管宿主 InputMethodService 的返回键分发。"),
        Expected("返回键:系统手势", "在 Android 系统返回手势到达隐藏输入法前消费面板返回。"),
        Expected("容量限制解除", "解除宿主剪贴板和常用语容量限制。"),
        Expected("候选拼音光标", "定位候选拼音视图并把点击位置写回真实输入状态。"),
        Expected("候选拼音光标:真实编辑出口", "拼音输入与退格的真实处理入口是否安装；视觉光标不能代替此项。"),
        Expected("符号键盘切换入口", "运行时补充检测点。"),
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
                    when {
                        method == null -> null
                        method.name == "currentApplication" -> method.invoke(null) as? Context
                        else -> {
                            val thread = method.invoke(null)
                            if (thread == null) null else {
                                cls.methods.firstOrNull {
                                    it.name == "getApplication" && it.parameterTypes.isEmpty()
                                }?.invoke(thread) as? Context
                            }
                        }
                    }
                }.getOrNull()
            }.firstOrNull()
        }.getOrNull()
    }

    @Volatile
    private var installRoundAt: Long = 0L

    @Volatile
    private var installRoundDetail: String = ""

    /** 统一识别“不是结构匹配失败”的占位/状态记录，所有跨进程合并都必须使用同一口径。 */
    internal fun isPlaceholderDetail(detail: String): Boolean =
        detail.startsWith("安装期批量结算：") ||
            detail.startsWith("未产生安装期记录") ||
            detail.startsWith("安装轮次未执行") ||
            detail.startsWith("安装轮次已完成但未提交") ||
            detail.startsWith("未提供") ||
            detail.startsWith("不适用") ||
            detail.startsWith("待运行时验证")

    /** 安装期占位记录不代表真实失败；只用于说明尚未观察到运行时证据。 */
    private fun isInstallPlaceholder(point: Point): Boolean =
        isPlaceholderDetail(point.detail)

    fun mergePoint(old: Point?, point: Point): Point {
        if (old == null) return point
        val oldPlaceholder = isInstallPlaceholder(old)
        val newPlaceholder = isInstallPlaceholder(point)
        return when {
            // 结构匹配成功是本轮的主判据；后续运行时安装数量或回调状态不能降级为失败。
            old.matched && !point.matched -> old
            oldPlaceholder && !newPlaceholder -> point
            !oldPlaceholder && newPlaceholder -> old
            point.matched && !old.matched -> point
            point.updatedAt > old.updatedAt -> point
            point.updatedAt == old.updatedAt && point.matched && !old.matched -> point
            else -> old
        }
    }

    /**
     * Every diagnostic point is published immediately. The receiver is only a transport;
     * the diagnostic page never waits for a later runtime callback to decide whether a point
     * exists. Points that are not positively matched in this install round remain an explicit
     * false result with the install-round timestamp.
     */
    private fun publish(point: Point, context: Context?) {
        HookDiagnosticsStore.write(
            context ?: hostContext(null),
            point.name,
            point.matched,
            point.detail,
            point.updatedAt,
        )
        runCatching {
            val host = hostContext(context) ?: return@runCatching
            host.sendBroadcast(Intent(ACTION_RECORD).apply {
                component = ComponentName(
                    "com.oplusime.panel",
                    "com.oplusime.panel.HookDiagnosticsReceiver",
                )
                setPackage("com.oplusime.panel")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("name", point.name)
                putExtra("matched", point.matched)
                putExtra("detail", point.detail.take(4096))
                putExtra("updatedAt", point.updatedAt)
                putExtra("roundId", installRoundAt)
            })
            log("hook-diagnostics: broadcast queued point=${point.name} matched=${point.matched} round=$installRoundAt")
        }.onFailure {
            log("hook-diagnostics: broadcast record failed point=${point.name} error=${it.message}")
        }
    }

    fun record(context: Context?, name: String, matched: Boolean, detail: String) {
        val incoming = Point(name, matched, detail, System.currentTimeMillis())
        val point = mergePoint(local[name], incoming)
        local[name] = point
        publish(point, context)
    }

    /**
     * 将一个 DexKit/反射结构候选直接登记为诊断结果。
     *
     * 诊断页的绿色含义是“规则匹配到了目标方法/类”，不是“运行时已经触发”或
     * “Xposed hook 回调已经执行”。因此调用方必须在解析出候选后立刻调用本方法。
     */
    fun recordMatch(name: String, signatures: Collection<String>, note: String = "") {
        val cleaned = signatures
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(24)
        val detail = if (cleaned.isNotEmpty()) {
            buildString {
                append("DexKit/结构匹配：已找到 ").append(cleaned.size).append(" 个目标；")
                if (note.isNotBlank()) append(note).append(';')
                append("方法签名：").append(cleaned.joinToString(" | "))
            }
        } else {
            "DexKit/结构匹配：未找到目标方法；${note.ifBlank { "候选为空" }}"
        }
        record(null, name, cleaned.isNotEmpty(), detail)
    }

    /** 非 Hook 的宿主/模块状态项，不参与 DexKit 匹配成败判定。 */
    fun recordNotApplicable(name: String, detail: String) {
        record(null, name, false, "不适用：$detail")
    }

    /** 用反射得到稳定、可读、与运行时一致的方法签名。 */
    fun methodSignature(method: java.lang.reflect.Method): String = buildString {
        append(method.declaringClass.name).append('#').append(method.name).append('(')
        append(method.parameterTypes.joinToString(",") { it.name })
        append(')').append(':').append(method.returnType.name)
    }

    /** 结构匹配只关心方法是否找到，不把 hook 安装数量混入成功判据。 */
    fun recordStructural(name: String, matched: Boolean, detail: String) {
        record(null, name, matched, if (matched) "DexKit/结构匹配：$detail" else "DexKit/结构匹配：未找到目标；$detail")
    }

    /** 兼容旧调用点，但不再读取旧目录来决定是否跳过本轮结构结果。 */
    fun recordIfAbsent(context: Context?, name: String, matched: Boolean, detail: String) {
        local[name]?.let {
            publish(it, context)
            return
        }
        record(context, name, matched, detail)
    }

    /**
     * Start one installation transaction. Defaults are real, timestamped false results rather
     * than a pending/"waiting for callback" state; later successful structural matches replace
     * the corresponding point with a newer true result.
     */
    fun beginInstallRound(context: Context?, detail: String) {
        val now = System.currentTimeMillis()
        installRoundAt = now
        installRoundDetail = detail
        local.clear()
        val host = context ?: hostContext(null)
        HookDiagnosticsStore.reset(host)
        // 不再单独发送“清空”广播：它可能与点位广播交错，导致新一轮结果被异步清掉。
        // 每个点位和结束标记都携带本轮时间戳，Receiver 按轮次原子切换到新快照。
        logCritical("hook-diagnostics: install round started points=${expected.size}; previous round cleared round=$now")
    }

    /** 安装流程结束时为未提交的结构点写入明确的 DexKit 未匹配结果。 */
    fun finishInstallRound(context: Context?, reason: String) {
        expected.forEach { spec ->
            if (local[spec.name] == null) {
                record(
                    context,
                    spec.name,
                    false,
                    "DexKit/结构匹配：未找到目标方法；本轮没有提交该点位的匹配结果；$reason",
                )
            }
        }
        val finishedAt = System.currentTimeMillis()
        val resolvedContext = context ?: hostContext(null)
        // 首轮广播可能早于模块进程中的 Receiver 启动而丢失；安装结束前重发整轮快照。
        // 这只是传输补偿，不改变任何点位的匹配结果或时间戳。
        flush(resolvedContext)
        HookDiagnosticsStore.markRoundFinished(resolvedContext, finishedAt, reason)
        runCatching {
            resolvedContext?.sendBroadcast(Intent(ACTION_FINISH).apply {
                component = ComponentName(
                    "com.oplusime.panel",
                    "com.oplusime.panel.HookDiagnosticsReceiver",
                )
                setPackage("com.oplusime.panel")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("finishedAt", finishedAt)
                putExtra("reason", reason)
                putExtra("roundId", installRoundAt)
            })
        }.onFailure { logCritical("hook-diagnostics: finish broadcast failed: ${it.stackTraceToString()}") }
        logCritical("hook-diagnostics: install round finished recorded=${local.size}/${expected.size} at=$finishedAt")
    }

    /** Publish the already-computed local round without changing its timestamps. */
    fun flush(context: Context?) {
        local.values.toList().forEach { publish(it, context) }
    }

    fun snapshot(context: Context): List<Point> {
        val dirs = HookDiagnosticsStore.candidateDirs(context)
        val byName = linkedMapOf<String, Point>()

        // 多进程诊断以时间戳为准合并。不能让模块进程自己的旧 local 快照覆盖
        // 宿主刚写入的更新结果；否则宿主已经 matched=true，页面仍会显示“未回传”。
        fun merge(point: Point) {
            val old = byName[point.name]
            if (old == null) {
                byName[point.name] = point
                return
            }
            val oldPlaceholder = isInstallPlaceholder(old)
            val newPlaceholder = isInstallPlaceholder(point)
            when {
                oldPlaceholder && !newPlaceholder -> byName[point.name] = point
                !oldPlaceholder && newPlaceholder -> Unit
                // 诊断页的成功含义是规则已经匹配到目标；运行时回调尚未触发
                // 或安装数量为 0，不能把已匹配的结构点重新画成红色。
                old.matched && !point.matched -> Unit
                point.matched && !old.matched -> byName[point.name] = point
                point.updatedAt > old.updatedAt -> byName[point.name] = point
                point.updatedAt == old.updatedAt && point.matched && !old.matched -> byName[point.name] = point
            }
        }

        // 主通道：直接读取宿主进程写入的共享外部诊断文件。
        expected.forEach { spec ->
            HookDiagnosticsStore.read(dirs, spec.name)?.let { (matched, at, detail) ->
                if (at > 0L) merge(Point(spec.name, matched, detail, at))
            }
        }
        // Receiver 与此页面同属模块进程；不依赖跨应用 Android/data 的读取权限。
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (!key.startsWith("point.")) return@forEach
            val lines = (value as? String)?.split('\n', limit = 3) ?: return@forEach
            val at = lines.getOrNull(1)?.toLongOrNull() ?: return@forEach
            if (lines.size == 3 && at > 0L) {
                merge(Point(key.removePrefix("point."), lines[0] == "true" || lines[0] == "1", lines[2], at))
            }
        }

        // 兼容已经声明过 Provider 的安装环境；Provider 不可用时不影响文件主通道。
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
                    merge(Point(
                        name,
                        value.getBoolean("matched", false),
                        value.getString("detail").orEmpty(),
                        at,
                    ))
                }
            }
        }.onFailure { log("hook-diagnostics: provider snapshot read failed: ${it.message}") }

        // 本进程临时结果只在它比文件/Provider 更新时才覆盖，避免旧缓存污染页面。
        local.values.forEach(::merge)
        val round = HookDiagnosticsStore.readRound(dirs)
        return expected.map { spec ->
            byName[spec.name] ?: Point(
                spec.name,
                false,
                "DexKit/结构匹配：未找到目标方法；诊断通道没有收到该点位的匹配记录；${installRoundDetail.ifBlank { "宿主解析流程尚未提交" }}",
                round?.first ?: installRoundAt,
            )
        } + byName.values.filter { point -> expected.none { it.name == point.name } }.sortedBy { it.name }
    }

    private fun isUnobserved(point: Point): Boolean =
        point.updatedAt == 0L ||
            point.detail.startsWith("未产生安装期记录") ||
            point.detail.startsWith("安装轮次未执行") ||
            point.detail.startsWith("安装轮次已完成但未提交") ||
            point.detail.startsWith("未提供") ||
            point.detail.startsWith("不适用") ||
            point.detail.startsWith("待运行时验证")

    fun export(points: List<Point>): String = buildString {
        append("小布输入法助手 Hook 诊断\n")
        append("生成时间：")
        append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date()))
        append("\n点位数量：").append(points.size).append("\n\n")
        points.forEachIndexed { index, point ->
            val status = when {
                point.matched -> "✅ 已匹配"
                point.detail.startsWith("不适用") -> "⚪ 不适用"
                isUnobserved(point) -> "⚪ 未产生结构结果"
                else -> "❌ 未匹配"
            }
            append(index + 1).append(". ").append(status)
            append(" ").append(point.name).append("\n")
            append("说明：").append(point.description).append("\n")
            append("时间：").append(if (point.updatedAt == 0L) "无" else point.updatedAt).append("\n")
            append("详情：").append(point.detail).append("\n\n")
        }
    }
}
