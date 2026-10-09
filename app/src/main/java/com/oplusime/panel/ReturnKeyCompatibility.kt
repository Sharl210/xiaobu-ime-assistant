package com.oplusime.panel

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 将宿主产生的“单独换行字符”兼容转换为终端更容易识别的回车按键事件。
 *
 * 部分 SSH/终端应用不把 IME 的 commitText("\\n") 当作 Enter，
 * 但会处理 InputConnection.sendKeyEvent(KEYCODE_ENTER)。这里只处理完全等于
 * "\\n"、"\\r" 或 "\\r\\n" 的单次提交，普通文本和包含换行的多字符提交保持原样。
 */
internal object ReturnKeyCompatibility {
    private val hookedClasses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var installed = false

    @Volatile
    private var convertedCount = 0

    fun install(hostClassLoader: ClassLoader) {
        if (installed) return
        installed = true
        runCatching {
            val serviceClass = Class.forName(
                InputMethodService::class.java.name,
                false,
                hostClassLoader,
            )
            XposedBridge.hookAllMethods(
                serviceClass,
                "getCurrentInputConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val connection = param.result as? InputConnection ?: return
                        hookConnection(connection)
                    }
                },
            )
            log("return-key: current InputConnection provider hooked")
        }.onFailure {
            installed = false
            log("return-key: provider hook failed: ${it.message}")
        }
    }

    private fun hookConnection(connection: InputConnection) {
        val cls = connection.javaClass
        if (!hookedClasses.add(cls.name)) return
        var hooks = 0
        cls.methods
            .filter { it.name == "commitText" && it.parameterTypes.contentEquals(
                arrayOf(CharSequence::class.java, Int::class.javaPrimitiveType),
            ) }
            .forEach { method ->
                if (!hookedMethods.add(method.toGenericString())) return@forEach
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val text = param.args?.getOrNull(0) as? CharSequence ?: return
                            if (!isStandaloneNewline(text)) return
                            val target = param.thisObject as? InputConnection ?: return
                            if (sendEnter(target, "commitText")) {
                                param.result = true
                            }
                        }
                    })
                    hooks++
                }.onFailure { log("return-key: commitText hook failed ${it.message}") }
            }
        log("return-key: hooked ${cls.name} commitText=$hooks")
    }

    private fun isStandaloneNewline(text: CharSequence): Boolean {
        return text.toString() == "\n" || text.toString() == "\r" || text.toString() == "\r\n"
    }

    private fun sendEnter(connection: InputConnection, source: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
        return runCatching {
            val downOk = connection.sendKeyEvent(down)
            val upOk = connection.sendKeyEvent(up)
            val ok = downOk || upOk
            if (ok) {
                convertedCount++
                log("return-key: newline converted to KEYCODE_ENTER source=$source count=$convertedCount")
            } else {
                log("return-key: KEYCODE_ENTER rejected source=$source; original newline kept")
            }
            ok
        }.onFailure {
            log("return-key: send KEYCODE_ENTER failed source=$source error=${it.message}")
        }.getOrDefault(false)
    }
}
