package com.brawldraft.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The provider list and the fallback order.
 *
 * Order is priority: the first entry is the primary, and every request starts at the top. Add as
 * many as you like, including your own OpenAI-compatible servers. Every keystroke is saved straight
 * away, so there is no save button to forget.
 */
class ProvidersActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val endpoints = mutableListOf<Endpoint>()
    private var tablet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tablet = resources.configuration.screenWidthDp >= 600
        val pad = dp(if (tablet) 24 else 14)

        endpoints.clear()
        endpoints.addAll(ProviderStore.load(this))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Providers and fallback"
            textSize = if (tablet) 24f else 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Order is priority. Every request starts at number 1; if it fails the next one is " +
                "tried, and the next request goes back to number 1 again. Failures and fallbacks " +
                "are recorded on the Logs page."
            textSize = if (tablet) 14f else 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(4), 0, dp(10))
        })

        status = TextView(this).apply {
            textSize = if (tablet) 14f else 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        col.addView(status)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(TextView(this).apply {
            text = "Add a preset"
            textSize = if (tablet) 18f else 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, 0)
        })
        for (p in Providers.ALL) {
            if (p.baseUrl.isBlank()) continue
            col.addView(Button(this).apply {
                text = p.label
                textSize = if (tablet) 16f else 14f
                setOnClickListener { addPreset(p) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        col.addView(Button(this).apply {
            text = "Add blank custom provider"
            textSize = if (tablet) 16f else 14f
            setOnClickListener {
                endpoints.add(Endpoint(name = "Custom", enabled = false))
                persist("Added a blank provider. Fill in its base URL, key and model.")
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        val root = ScrollView(this)
        root.addView(col, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        render()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        list.removeAllViews()
        if (endpoints.isEmpty()) {
            list.addView(TextView(this).apply { text = "No providers yet. Add one below." })
            return
        }
        for (i in endpoints.indices) list.addView(card(endpoints[i], i))
        status.text = if (endpoints.isEmpty()) "" else
            "Primary: " + (endpoints.firstOrNull { it.enabled && it.isValid() }?.label ?: "none working yet")
    }

    private fun card(e: Endpoint, index: Int): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            setBackgroundColor(0xFFF2F2F2.toInt())
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.topMargin = dp(10)
        box.layoutParams = lp

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "${index + 1}. " + (e.name.ifBlank { "provider" })
            textSize = if (tablet) 17f else 15f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(small("Up") { if (index > 0) move(index, index - 1) })
        header.addView(small("Down") { if (index < endpoints.size - 1) move(index, index + 1) })
        header.addView(small("Remove") {
            endpoints.removeAt(index)
            persist("Removed.")
        })
        box.addView(header)

        box.addView(CheckBox(this).apply {
            text = "Enabled, try this one"
            textSize = if (tablet) 15f else 13f
            isChecked = e.enabled
            setOnCheckedChangeListener { _, checked ->
                e.enabled = checked
                persist(if (checked) "Enabled ${e.label}." else "Disabled ${e.label}.")
            }
        })

        box.addView(field("Name", e.name, "Groq, Gemini, my server...") { e.name = it })
        box.addView(field("Base URL", e.baseUrl, "https://api.example.com/v1") { e.baseUrl = it })
        box.addView(field("API key", e.apiKey, "", secret = true) { e.apiKey = it })
        box.addView(field("Chat model", e.chatModel, "model id for replies") { e.chatModel = it })
        box.addView(field("Name-check model", e.cleanModel, "optional, defaults to chat model") { e.cleanModel = it })
        return box
    }

    private fun small(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = if (tablet) 13f else 11f
            setOnClickListener { onClick() }
        }

    private fun field(
        label: String,
        value: String,
        hint: String,
        secret: Boolean = false,
        onChange: (String) -> Unit
    ): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply {
            text = label
            textSize = if (tablet) 14f else 12f
            setPadding(0, dp(8), 0, 0)
        })
        val et = EditText(this)
        // setSingleLine first: it rewrites the input type and would otherwise drop the password
        // variation, leaving keys visible on screen.
        et.setSingleLine(true)
        et.textSize = if (tablet) 15f else 13f
        if (secret) {
            et.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        et.hint = hint
        et.setText(value)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
                ProviderStore.save(this@ProvidersActivity, endpoints)
            }
        })
        wrap.addView(et, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return wrap
    }

    private fun move(from: Int, to: Int) {
        val item = endpoints.removeAt(from)
        endpoints.add(to, item)
        persist("Reordered.")
    }

    private fun addPreset(p: Provider) {
        endpoints.add(
            Endpoint(
                name = p.label,
                baseUrl = p.baseUrl,
                apiKey = "",
                chatModel = p.chatModel,
                cleanModel = p.cleanModel,
                enabled = false
            )
        )
        persist("Added ${p.label}. Paste its API key, then tick Enabled once the model is filled in.")
    }

    /** Saves now, synchronously, and re-renders so the page always matches what was stored. */
    private fun persist(message: String) {
        ProviderStore.save(this, endpoints)
        status.text = message
        render()
        status.text = message
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
