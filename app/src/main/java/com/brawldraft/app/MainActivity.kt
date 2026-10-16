package com.brawldraft.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 42
    }

    private val fields = LinkedHashMap<String, EditText>()
    private val checks = LinkedHashMap<String, CheckBox>()
    private lateinit var status: TextView

    private var tablet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tablet = resources.configuration.screenWidthDp >= 600

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(if (tablet) 28 else 18)
            setPadding(pad, pad, pad, pad)
        }

        // Phones get the full width, tablets get a centred readable column.
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val contentWidth = if (tablet) {
            min(dp(620), resources.displayMetrics.widthPixels - dp(48))
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        holder.addView(
            col,
            LinearLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val root = ScrollView(this).apply { isFillViewport = true }
        root.addView(holder, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        col.addView(TextView(this).apply {
            text = "Brawl Draft"
            textSize = if (tablet) 26f else 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        hint(col, "Voice draft coach. Tap the floating bubble to talk, tap it again to send.")

        section(col, "Speech to text")
        hint(
            col,
            "Speak into the mic, the clip is uploaded to your transcription endpoint. " +
                "OpenAI's Whisper models are free on Groq's rate-limited tier: " +
                "whisper-large-v3-turbo allows 7,200 audio seconds per hour and 28,800 per day " +
                "(about 8 hours of audio daily). OpenAI direct, Deepgram and a local Whisper " +
                "server all work too - any endpoint that accepts POST /audio/transcriptions."
        )
        addField(col, "Transcription base URL", Prefs.KEY_STT_URL, Prefs.DEFAULT_STT_URL)
        addField(col, "Transcription API key", Prefs.KEY_STT_KEY, "", secret = true)
        addField(col, "Transcription model", Prefs.KEY_STT_MODEL, Prefs.DEFAULT_STT_MODEL)
        addField(col, "Transcription hint (optional)", Prefs.KEY_STT_PROMPT, NamesRepo.hint(this))
        hint(
            col,
            "Filled from brawl_data.json. Whisper caps this parameter at 224 tokens, so very long " +
                "lists get truncated upstream."
        )
        addField(
            col,
            "Live upload clip length (ms)",
            Prefs.KEY_SEGMENT_MS,
            Prefs.DEFAULT_SEGMENT_MS.toString()
        )
        hint(
            col,
            "10 second clips matched single-request accuracy in testing, while 4 second clips " +
                "mangled brawler names. Shorter means lower latency, longer means better accuracy."
        )

        section(col, "Coach LLM")
        hint(col, "Any OpenAI-compatible /chat/completions endpoint. Groq's free tier works with the same key.")
        addField(col, "Chat base URL", Prefs.KEY_LLM_URL, Prefs.DEFAULT_LLM_URL)
        addField(col, "Chat API key", Prefs.KEY_LLM_KEY, "", secret = true)
        addField(col, "Chat model", Prefs.KEY_LLM_MODEL, Prefs.DEFAULT_LLM_MODEL)

        section(col, "Name check before the coach")
        hint(
            col,
            "A quick second pass that repairs brawler, map and game names the transcriber mangled, " +
                "using the published name lists, before the coach ever reads the transcript. " +
                "Measured at 0.76 s with qwen3.8-27b. If it fails, the raw transcript is used."
        )
        addCheck(col, "Repair names before asking the coach", Prefs.KEY_CLEAN_ENABLED, true)
        addField(col, "Name check model", Prefs.KEY_CLEAN_MODEL, Prefs.DEFAULT_CLEAN_MODEL)
        addField(
            col,
            "Name lists URL (edit brawl_data.json on GitHub)",
            Prefs.KEY_NAMES_URL,
            Prefs.DEFAULT_NAMES_URL
        )
        hint(
            col,
            "Fetched at startup and every 5 minutes, cached on the device, and falls back to the " +
                "copy bundled inside the app. The repository has to stay public for fetching to work."
        )

        section(col, "Overlay")
        addField(
            col,
            "Auto-close delay (milliseconds, 0 = never)",
            Prefs.KEY_AUTO_HIDE_MS,
            Prefs.DEFAULT_AUTO_HIDE_MS.toString()
        )
        hint(col, "7500 = the reply closes itself after 7.5 seconds. Tapping the panel restarts the timer, the X closes it right away.")
        addCheck(col, "Speak replies out loud", Prefs.KEY_SPEAK, true)
        addCheck(col, "Upload and transcribe while recording (fastest)", Prefs.KEY_STREAM_UPLOAD, true)
        hint(
            col,
            "Audio is cut into 4 second clips and transcribed while you are still talking, so " +
                "tapping Send only has to finish the last short clip. With this off the whole " +
                "take is sent at the end - slower, but a cancelled take costs nothing."
        )

        section(col, "System prompt")
        addField(col, "Prompt file URL (GitHub file link)", Prefs.KEY_PROMPT_URL, "")
        hint(col, "Polled every 5 seconds, so editing that file updates the coach live. Leave empty to use the bundled prompt.")

        section(col, "Run it")
        addButton(col, "Save settings") { save() }
        addButton(col, "Use free Groq Whisper defaults") { useGroqDefaults() }
        addButton(col, "Start bubble") { startBubble() }
        addButton(col, "Stop bubble") { stopBubble() }

        status = TextView(this).apply {
            setPadding(0, dp(16), 0, 0)
            textSize = if (tablet) 16f else 14f
            text = "Add your keys, save, then tap Start bubble. Grant the microphone and overlay permissions when asked."
        }
        col.addView(status)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun section(col: LinearLayout, label: String) {
        col.addView(TextView(this).apply {
            text = label
            textSize = if (tablet) 18f else 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, 0)
        })
    }

    private fun hint(col: LinearLayout, label: String): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = if (tablet) 14f else 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(tv)
        return tv
    }

    private fun addField(col: LinearLayout, label: String, key: String, def: String, secret: Boolean = false) {
        col.addView(TextView(this).apply {
            text = label
            textSize = if (tablet) 15f else 14f
            setPadding(0, dp(12), 0, 0)
        })
        val et = EditText(this)
        // setSingleLine must come first: it rewrites the input type and would drop the
        // password variation, leaving keys visible on screen in clear text.
        et.setSingleLine(true)
        et.textSize = if (tablet) 16f else 15f
        if (secret) {
            et.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        et.setText(Prefs.get(this, key, def))
        col.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fields[key] = et
    }

    private fun addCheck(col: LinearLayout, label: String, key: String, def: Boolean) {
        val cb = CheckBox(this).apply {
            text = label
            textSize = if (tablet) 16f else 15f
            isChecked = Prefs.getBool(this@MainActivity, key, def)
        }
        col.addView(cb)
        checks[key] = cb
    }

    private fun addButton(col: LinearLayout, label: String, onClick: () -> Unit) {
        col.addView(Button(this).apply {
            text = label
            textSize = if (tablet) 16f else 15f
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
    }

    private fun save() {
        for ((key, et) in fields) {
            Prefs.put(this, key, et.text.toString().trim())
        }
        for ((key, cb) in checks) {
            Prefs.put(this, key, if (cb.isChecked) "true" else "false")
        }
        val ms = Prefs.getInt(this, Prefs.KEY_AUTO_HIDE_MS, Prefs.DEFAULT_AUTO_HIDE_MS)
        status.text = if (ms < 0) {
            "Saved. Auto-close was negative, so the reply will stay open until you close it."
        } else {
            "Saved. Replies will close themselves after ${ms / 1000.0}s."
        }
    }

    private fun useGroqDefaults() {
        fields[Prefs.KEY_STT_URL]?.setText(Prefs.DEFAULT_STT_URL)
        fields[Prefs.KEY_STT_MODEL]?.setText(Prefs.DEFAULT_STT_MODEL)
        fields[Prefs.KEY_STT_PROMPT]?.setText(NamesRepo.hint(this))
        fields[Prefs.KEY_LLM_URL]?.setText(Prefs.DEFAULT_LLM_URL)
        fields[Prefs.KEY_LLM_MODEL]?.setText(Prefs.DEFAULT_LLM_MODEL)
        fields[Prefs.KEY_CLEAN_MODEL]?.setText(Prefs.DEFAULT_CLEAN_MODEL)
        fields[Prefs.KEY_NAMES_URL]?.setText(Prefs.DEFAULT_NAMES_URL)
        fields[Prefs.KEY_AUTO_HIDE_MS]?.setText(Prefs.DEFAULT_AUTO_HIDE_MS.toString())
        fields[Prefs.KEY_SEGMENT_MS]?.setText(Prefs.DEFAULT_SEGMENT_MS.toString())
        save()
        status.text =
            "Loaded Groq free-tier defaults. Paste one Groq API key into both key fields, then save and start the bubble."
    }

    private fun startBubble() {
        save()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            status.text = "Allow 'Display over other apps' for Brawl Draft, then tap Start bubble again."
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        launchService()
    }

    private fun stopBubble() {
        stopService(Intent(this, OverlayService::class.java))
        status.text = "Bubble stopped."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchService()
            } else {
                status.text = "Microphone permission is required."
            }
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, OverlayService::class.java))
        status.text = "Bubble started. Tap it to talk, tap again to send, or Cancel to throw the take away."
        moveTaskToBack(true)
    }
}
