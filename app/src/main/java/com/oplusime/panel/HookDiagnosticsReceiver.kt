package com.oplusime.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Only the keyboard UID may publish a diagnostic result to the module process. */
class HookDiagnosticsReceiver : BroadcastReceiver() {
    private companion object {
        const val ROUND_KEY = "round_at"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HookDiagnostics.ACTION_RECORD &&
            intent.action != HookDiagnostics.ACTION_RESET &&
            intent.action != HookDiagnostics.ACTION_FINISH) return
        if (Build.VERSION.SDK_INT >= 34) {
            val uid = runCatching { sentFromUid }.getOrDefault(-1)
            val sender = runCatching { sentFromPackage }.getOrNull()
            val packages = if (uid > 0) {
                runCatching { context.packageManager.getPackagesForUid(uid).orEmpty().toSet() }
                    .getOrDefault(emptySet())
            } else {
                emptySet()
            }
            val knownKeyboard = sender == "com.oplus.keyboard" ||
                packages.contains("com.oplus.keyboard")
            if (!knownKeyboard && uid > 0 && !sender.isNullOrBlank()) {
                // Android 14+/部分定制系统对显式跨进程广播可能不给出稳定的
                // sentFromPackage；只有身份明确且明确不是宿主时才拒绝，避免把
                // 正常诊断广播当成伪造广播而丢掉整轮结果。
                logCritical("hook-diagnostics: receiver rejected sender uid=$uid package=$sender action=${intent.action}")
                return
            }
            if (!knownKeyboard) {
                logCritical("hook-diagnostics: receiver sender identity unavailable; accept explicit action=${intent.action} uid=$uid package=$sender")
            }
        }
        val prefs = context.getSharedPreferences(HookDiagnostics.PREFS, Context.MODE_PRIVATE)
        val incomingRound = intent.getLongExtra("roundId", 0L)

        // 广播可能因进程调度交错到达；按 roundId 做幂等切换，不能依赖 RESET 广播先到。
        fun switchRoundIfNeeded(): Boolean {
            if (incomingRound <= 0L) return true
            val currentRound = prefs.getLong(ROUND_KEY, 0L)
            if (currentRound > incomingRound) return false
            if (currentRound != incomingRound) {
                val switched = prefs.edit().clear().putLong(ROUND_KEY, incomingRound).commit()
                HookDiagnosticsStore.reset(context)
                logCritical("hook-diagnostics: receiver switched round from=$currentRound to=$incomingRound committed=$switched")
                if (!switched) return false
            }
            return true
        }
        if (intent.action == HookDiagnostics.ACTION_RESET) {
            prefs.edit().clear().commit()
            HookDiagnosticsStore.reset(context)
            logCritical("hook-diagnostics: receiver cleared previous round")
            return
        }
        if (!switchRoundIfNeeded()) {
            logCritical("hook-diagnostics: receiver ignored stale round=$incomingRound action=${intent.action}")
            return
        }
        if (intent.action == HookDiagnostics.ACTION_FINISH) {
            val at = intent.getLongExtra("finishedAt", 0L)
            if (at > 0L) {
                HookDiagnosticsStore.markRoundFinished(context, at, intent.getStringExtra("reason").orEmpty())
                logCritical("hook-diagnostics: receiver stored round finish at=$at")
            }
            return
        }
        val name = intent.getStringExtra("name")?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return
        val at = intent.getLongExtra("updatedAt", 0L)
        if (at <= 0L) return
        val old = prefs.getString("point.$name", null)?.split('\n', limit = 3)?.getOrNull(1)?.toLongOrNull() ?: 0L
        if (old > at) return
        val incomingMatched = intent.getBooleanExtra("matched", false)
        val existing = prefs.getString("point.$name", null)
        if (existing != null) {
            val oldLines = existing.split('\n', limit = 3)
            val oldAt = oldLines.getOrNull(1)?.toLongOrNull() ?: 0L
            val oldMatched = oldLines.getOrNull(0) == "true" || oldLines.getOrNull(0) == "1"
            // 结构匹配成功优先于后续运行时状态；同一轮不能因 hook 尚未触发
            // 或安装计数为 0 而把绿色匹配结果降成失败。
            if (oldMatched && !incomingMatched) return
            val oldPlaceholder = oldLines.getOrNull(2)?.startsWith("安装期批量结算：") == true ||
                oldLines.getOrNull(2)?.startsWith("未产生安装期记录") == true ||
                oldLines.getOrNull(2)?.startsWith("安装轮次未执行") == true ||
                oldLines.getOrNull(2)?.startsWith("安装轮次已完成但未提交") == true ||
                oldLines.getOrNull(2)?.startsWith("待运行时验证") == true
            val incomingDetail = intent.getStringExtra("detail").orEmpty()
            val incomingPlaceholder = incomingDetail.startsWith("安装期批量结算：") ||
                incomingDetail.startsWith("未产生安装期记录") ||
                incomingDetail.startsWith("安装轮次未执行") ||
                incomingDetail.startsWith("安装轮次已完成但未提交") ||
                incomingDetail.startsWith("待运行时验证")
            if ((oldPlaceholder && !incomingPlaceholder) ||
                (!oldPlaceholder && incomingPlaceholder) ||
                (oldMatched && !incomingMatched && incomingPlaceholder) ||
                (incomingMatched && !oldMatched) ||
                (oldAt == at && oldMatched && !incomingMatched)) return
        }
        val detail = intent.getStringExtra("detail").orEmpty().take(4096).replace('\n', ' ')
        // Receiver 在模块进程内执行，写入模块自己的 SharedPreferences；诊断页与 Receiver 属于同一 UID，
        // 不再依赖模块读取宿主的 Android/data 私有目录。
        val saved = prefs.edit()
            .putString("point.$name", "${intent.getBooleanExtra("matched", false)}\n$at\n$detail")
            .commit()
        if (saved) {
            log("hook-diagnostics: receiver stored point=$name matched=${intent.getBooleanExtra("matched", false)} at=$at round=$incomingRound")
        } else {
            logCritical("hook-diagnostics: receiver commit failed point=$name round=$incomingRound")
        }
    }
}
