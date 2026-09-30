package com.oplusime.panel

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** 模块说明页：LSPosed 里点进模块时展示，同时让模块设置入口可用。 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.module_title)

        val text = TextView(this).apply {
            text = getString(R.string.module_body)
            textSize = 14f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
