package com.oplusime.panel

import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

internal const val LOG_TAG = "OplusImePanel"

/**
 * 模块开关（跨进程共享）。
 *
 * ## 为什么需要它
 *
 * 这个模块由两部分组成，**跑在两个不同进程里**：
 *
 *  - App 界面（[MainActivity]）跑在模块自己的进程 `com.oplusime.panel`；
 *  - Hook 逻辑跑在宿主进程 `com.oplus.keyboard`。
 *
 * 用户要的是「在自己的 App 界面里开关日志」，所以开关必须能跨进程读。
 * 这里走 Xposed 生态的标准做法：App 侧把值写进自己的 SharedPreferences 并把文件放开读权限，
 * Hook 侧用 [XSharedPreferences] 读同一份文件（LSPosed 会给模块目录放行，宿主进程才读得到）。
 *
 * ## 默认值
 *
 * [DEFAULT_LOG_ENABLED] 是**没有人为设置过**时的取值：
 *
 *  - 调试/测试版：`true`，开箱即可取证；
 *  - 正式发版：改成 `false`，全模块静默 —— 不构造任何日志字符串，也就没有拼字符串、
 *    没有跨进程写日志的开销；同时不会在 LSPosed 日志里刷屏干扰其它模块的分析。
 *
 * 用户在界面上手动设置过之后，以设置为准（两个版本都不例外），因为排障时正是需要把它打开。
 */
internal object ModuleSwitches {

    /** 与 `app/build.gradle` 的 applicationId 一致。 */
    const val MODULE_PACKAGE: String = "com.oplusime.panel"

    /** App 侧与 Hook 侧共用的 SharedPreferences 名字。 */
    const val PREFS_NAME: String = "oplusime_panel"

    /** 日志开关的键名。 */
    const val KEY_LOG_ENABLED: String = "log_enabled"

    /**
     * 未设置过时的日志默认值。
     *
     * 仓库发布的**正式版**取 `false`：模块完全不产生日志，没有拼字符串、没有跨进程写日志的
     * 开销，也不会在多个模块共用同一份日志时造成干扰。
     *
     * 需要排障时，在模块主界面把「日志开关」打开即可（改完最多 5 秒在输入法进程内生效，
     * 不必重启输入法）；用户手动设置过之后一律以设置为准，因此开关打开后照常能取证。
     */
    const val DEFAULT_LOG_ENABLED: Boolean = BuildConfig.DEFAULT_LOG_ENABLED

    /**
     * 缓存值 + 上次刷新时间。
     *
     * 之所以要缓存：`log()` 会被高频调用（部分钩子一次滚动几十次），每次都去读文件不可接受。
     * 之所以要定时刷新：用户在 App 界面改了开关之后，宿主进程并不知道，只能靠轮询发现。
     * 5 秒的粒度对"手动开关日志"这个场景完全够用，代价也只是一个 long 比较。
     */
    @Volatile
    private var cachedLogEnabled: Boolean = DEFAULT_LOG_ENABLED

    @Volatile
    private var lastReloadAt: Long = 0L

    private const val RELOAD_INTERVAL_MS = 5_000L

    /** Hook 侧调用。 */
    fun logEnabled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReloadAt >= RELOAD_INTERVAL_MS) {
            lastReloadAt = now
            cachedLogEnabled = readLogEnabled()
        }
        return cachedLogEnabled
    }

    /** 立即刷新一次（模块加载时调用，避免头 5 秒沿用旧值）。 */
    fun refreshNow(): Boolean {
        lastReloadAt = SystemClock.elapsedRealtime()
        cachedLogEnabled = readLogEnabled()
        return cachedLogEnabled
    }

    private fun readLogEnabled(): Boolean = runCatching {
        val prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
        prefs.reload()
        prefs.getBoolean(KEY_LOG_ENABLED, DEFAULT_LOG_ENABLED)
    }.getOrDefault(DEFAULT_LOG_ENABLED)
}

/**
 * 模块日志。
 *
 * 受 [ModuleSwitches.logEnabled] 控制：关掉之后本函数**立刻返回**，
 * 调用点传进来的字符串不会进入任何判断之外的开销（Kotlin 的字符串模板在调用前已构造，
 * 因此对高频路径请优先使用 [logThrottled]，它的 message 是惰性求值的）。
 */
internal fun log(message: String) {
    if (!ModuleSwitches.logEnabled()) return
    XposedBridge.log("$LOG_TAG: $message")
}

/**
 * 节流日志：同一 [key] 在 [windowMs] 窗口内只输出第一条，窗口结束时把被折叠的次数附在末尾。
 *
 * 存在的理由：有些钩子（例如窗口 insets 计算）在滚动时一秒会被调用几十次，逐条输出会把日志
 * 刷成洪水 —— 既淹没真正有用的标记，也让日志文件迅速膨胀。节流后既能确认"钩子在跑"，
 * 又不会丢信息（折叠次数会写出来）。[message] 是惰性求值的，被折叠时完全不构造字符串。
 */
internal fun logThrottled(key: String, windowMs: Long, message: () -> String) {
    if (!ModuleSwitches.logEnabled()) return
    val now = SystemClock.elapsedRealtime()
    val last = lastLogAt[key]
    if (last != null && now - last < windowMs) {
        suppressedCounts.merge(key, 1, Int::plus)
        return
    }
    lastLogAt[key] = now
    val folded = suppressedCounts.remove(key) ?: 0
    val suffix = if (folded > 0) " (suppressed=$folded)" else ""
    XposedBridge.log("$LOG_TAG: ${message()}$suffix")
}

private val lastLogAt = ConcurrentHashMap<String, Long>()
private val suppressedCounts = ConcurrentHashMap<String, Int>()
