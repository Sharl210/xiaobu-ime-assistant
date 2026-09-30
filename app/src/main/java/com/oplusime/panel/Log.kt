package com.oplusime.panel

import de.robv.android.xposed.XposedBridge

internal const val LOG_TAG = "OplusImePanel"

internal fun log(message: String) {
    XposedBridge.log("$LOG_TAG: $message")
}
