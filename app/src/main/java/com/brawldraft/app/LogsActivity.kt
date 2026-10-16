package com.brawldraft.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Everything the coach did: transcripts, prompts, replies, which provider answered, failures. */
class LogsActivity : Activity() {

    private lateinit var body: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tablet = resources.configuration.screenWidthDp >= 600
        val pad = dp(if (tablet) 24 else 14)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "Logs"
            textSize = if (tablet) 24f else 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Newest at the bottom. Survives restarts. Long transcripts are trimmed in the middle."
            textSize = if (tablet) 14f else 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(4), 0, dp(10))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Refresh"
            textSize = if (tablet) 16f else 14f
            setOnClickListener { render() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Clear logs"
            textSize = if (tablet) 16f else 14f
            setOnClickListener {
                LogStore.clear(this@LogsActivity)
                render()
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        body = TextView(this).apply {
            textSize = if (tablet) 14f else 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        col.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f })

        setContentView(col)
        render()
    }

    private fun render() {
        val lines = LogStore.lines(this)
        body.text = if (lines.isEmpty()) "Nothing logged yet." else lines.joinToString("\n")
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
