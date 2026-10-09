package com.oplusime.panel

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/** 宿主进程向模块进程回传 Hook 匹配结果的最小 IPC 通道。 */
class HookDiagnosticsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "snapshot") {
            val prefs = getContext()?.getSharedPreferences("hook_diagnostics", 0) ?: return null
            val points = Bundle()
            val names = ArrayList<String>()
            prefs.all.forEach { (key, value) ->
                if (!key.startsWith("point.")) return@forEach
                val name = key.removePrefix("point.")
                val lines = (value as? String)?.split('\n', limit = 3) ?: return@forEach
                if (lines.size < 3) return@forEach
                val at = lines[1].toLongOrNull() ?: 0L
                if (at <= 0L) return@forEach
                names += name
                points.putBundle(name, Bundle().apply {
                    putBoolean("matched", lines[0].trim() == "true" || lines[0].trim() == "1")
                    putLong("updatedAt", at)
                    putString("detail", lines[2])
                })
            }
            return Bundle().apply {
                putStringArrayList("names", names)
                putBundle("points", points)
                putBoolean("ok", true)
            }
        }
        if (method != "record") return null
        // 诊断结果只允许宿主通过显式广播提交；Provider 仅保留模块自身的 snapshot 兼容读取。
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()) return null
        val name = extras?.getString("name") ?: return null
        val matched = extras.getBoolean("matched")
        val detail = extras.getString("detail").orEmpty()
        val at = extras.getLong("updatedAt", System.currentTimeMillis())
        val saved = getContext()?.getSharedPreferences("hook_diagnostics", 0)?.edit()
            ?.putString("point.$name", "$matched\n$at\n$detail")
            ?.commit() == true
        return Bundle().apply { putBoolean("ok", saved) }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
