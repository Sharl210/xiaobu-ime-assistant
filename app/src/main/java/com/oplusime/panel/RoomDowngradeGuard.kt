package com.oplusime.panel

import android.database.sqlite.SQLiteDatabase
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 处理宿主在旧版回退时没有提供 Room downgrade migration 的情况。
 *
 * 宿主 1.7.38 与 1.8.33 的取证显示 onDowngrade 最终都会进入 Room/宿主自定义回调，
 * 后者会直接抛出异常。这里只在真实的 SQLiteOpenHelper.onDowngrade 回调中介入：
 * 清空旧 schema，再调用当前宿主 RoomOpenHelper 的 onCreate 重新建表，避免继续走
 * 不存在的 31 -> 26 migration。它是破坏性降级，目标是先保证旧版能启动；用户数据
 * 是否需要迁移保留，必须由宿主自己提供正式 migration 才能保证。
 */
internal object RoomDowngradeGuard {
    private val hooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun install(loader: ClassLoader) {
        listOf(
            "androidx.sqlite.db.framework.e",
            "androidx.sqlite.db.framework.f",
        ).forEach { name ->
            runCatching {
                val cls = Class.forName(name, false, loader)
                if (!hooked.add(cls.name)) return@runCatching
                XposedBridge.hookAllMethods(cls, "onDowngrade", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val db = param.args?.getOrNull(0) as? SQLiteDatabase ?: return
                        val oldVersion = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                        val newVersion = (param.args?.getOrNull(2) as? Number)?.toInt() ?: return
                        if (oldVersion <= newVersion) return
                        val repaired = runCatching {
                            destructiveRecreate(param.thisObject, db)
                        }.onFailure {
                            logCritical("room-downgrade: recreate failed ${it.stackTraceToString()}")
                        }.getOrDefault(false)
                        if (repaired) {
                            param.result = null
                            logCritical("room-downgrade: destructive fallback applied $oldVersion->$newVersion helper=${cls.name}")
                        }
                    }
                })
                log("room-downgrade: hooked ${cls.name}#onDowngrade")
            }.onFailure { log("room-downgrade: hook unavailable $name error=${it.message}") }
        }
    }

    private fun destructiveRecreate(helper: Any, db: SQLiteDatabase): Boolean {
        val names = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            null,
        ).use { c ->
            val typeIndex = c.getColumnIndex("type")
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) {
                val type = c.getString(typeIndex) ?: continue
                val name = c.getString(nameIndex) ?: continue
                if (name.isNotBlank()) names += type to name
            }
        }
        // 先移除索引/触发器/视图，再移除表，避免旧 schema 残留阻断建表。
        listOf("trigger", "index", "view", "table").forEach { kind ->
            names.filter { it.first.equals(kind, true) }.forEach { (_, name) ->
                db.execSQL("DROP ${kind.uppercase()} IF EXISTS ${quote(name)}")
            }
        }

        val callbackField = findField(helper.javaClass, "c")
            ?: helper.javaClass.declaredFields.firstOrNull { it.name == "c" }
            ?: error("Room helper callback field missing")
        callbackField.isAccessible = true
        val callback = callbackField.get(helper) ?: error("Room helper callback missing")
        val wrapperMethod = helper.javaClass.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == SQLiteDatabase::class.java
        } ?: error("SQLite wrapper method missing")
        wrapperMethod.isAccessible = true
        val supportDb = wrapperMethod.invoke(helper, db)
        val create = callback.javaClass.methods.firstOrNull {
            it.name == "b" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == "androidx.sqlite.db.framework.c"
        } ?: callback.javaClass.declaredMethods.firstOrNull {
            it.name == "b" && it.parameterTypes.size == 1
        } ?: error("Room onCreate callback missing")
        create.isAccessible = true
        create.invoke(callback, supportDb)
        return true
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun quote(name: String): String = "`" + name.replace("`", "``") + "`"
}
