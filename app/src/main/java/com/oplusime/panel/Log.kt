package com.oplusime.panel

import android.os.SystemClock
import android.util.Log as AndroidLog
import java.util.concurrent.ConcurrentHashMap

internal const val LOG_TAG = "OplusImePanel"

/**
 * 模块开关（跨进程共享）。
 *
 * Hook 运行在 LSPosed 宿主进程时，优先通过反射调用 XposedBridge/XSharedPreferences；
 * 模块自己的设置进程或 BroadcastReceiver 进程没有 Xposed API 类，必须安全回退，不能因为
 * 排障日志把模块 App 进程打崩。
 */
internal object ModuleSwitches {
    const val MODULE_PACKAGE: String = "com.oplusime.panel"
    const val PREFS_NAME: String = "oplusime_panel"
    const val KEY_LOG_ENABLED: String = "log_enabled"
    const val DEFAULT_LOG_ENABLED: Boolean = BuildConfig.DEFAULT_LOG_ENABLED

    @Volatile
    private var cachedLogEnabled: Boolean = DEFAULT_LOG_ENABLED

    @Volatile
    private var lastReloadAt: Long = 0L

    private const val RELOAD_INTERVAL_MS = 5_000L

    fun logEnabled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReloadAt >= RELOAD_INTERVAL_MS) {
            lastReloadAt = now
            cachedLogEnabled = readLogEnabled()
        }
        return cachedLogEnabled
    }

    fun refreshNow(): Boolean {
        lastReloadAt = SystemClock.elapsedRealtime()
        cachedLogEnabled = readLogEnabled()
        return cachedLogEnabled
    }

    /** XSharedPreferences 是 compileOnly 依赖，模块 App 进程里可能根本没有这个类。 */
    private fun readLogEnabled(): Boolean = runCatching {
        val cls = Class.forName("de.robv.android.xposed.XSharedPreferences")
        val prefs = cls.getConstructor(String::class.java, String::class.java)
            .newInstance(MODULE_PACKAGE, PREFS_NAME)
        cls.getMethod("reload").invoke(prefs)
        cls.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(prefs, KEY_LOG_ENABLED, DEFAULT_LOG_ENABLED) as Boolean
    }.getOrDefault(DEFAULT_LOG_ENABLED)
}

/**
 * 通过反射使用 XposedBridge，失败时回退到 Android logcat。
 *
 * 不能在这里直接引用 XposedBridge：该类只存在于被 LSPosed 注入的宿主进程，
 * 不存在于模块自己的 APK 进程。直接引用会让 HookDiagnosticsReceiver 触发
 * NoClassDefFoundError。
 */
private fun writePlatformLog(message: String): Boolean = runCatching {
    val bridge = Class.forName("de.robv.android.xposed.XposedBridge")
    bridge.getMethod("log", String::class.java).invoke(null, "$LOG_TAG: $message")
    true
}.getOrDefault(false)

private fun writeLogFallback(message: String, throwable: Throwable? = null) {
    if (throwable == null) AndroidLog.i(LOG_TAG, message)
    else AndroidLog.e(LOG_TAG, message, throwable)
}

internal fun log(message: String) {
    if (!ModuleSwitches.logEnabled()) return
    if (!writePlatformLog(message)) writeLogFallback(message)
}

/** 关键交互取证不受普通日志开关影响，且可安全运行在模块自己的 App 进程。 */
internal fun logCritical(message: String) {
    if (!writePlatformLog(message)) writeLogFallback(message)
}

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
    val full = "${message()}$suffix"
    if (!writePlatformLog(full)) writeLogFallback(full)
}

private val lastLogAt = ConcurrentHashMap<String, Long>()
private val suppressedCounts = ConcurrentHashMap<String, Int>()
