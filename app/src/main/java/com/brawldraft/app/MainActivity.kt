package com.brawldraft.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 42
    }

    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val root = ScrollView(this)
        root.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(TextView(this).apply {
            text = "Brawl Draft"
            textSize = 22f
        })

        addField(col, "Whisper API key", Prefs.KEY_WHISPER_KEY, "", secret = true)
        addField(col, "Whisper model_size (free: tiny / small / medium)", Prefs.KEY_WHISPER_MODEL, Prefs.DEFAULT_WHISPER_MODEL)
        addField(col, "LLM base URL (OpenAI-compatible)", Prefs.KEY_LLM_URL, Prefs.DEFAULT_LLM_URL)
        addField(col, "LLM API key", Prefs.KEY_LLM_KEY, "", secret = true)
        addField(col, "LLM model name", Prefs.KEY_LLM_MODEL, Prefs.DEFAULT_LLM_MODEL)
        addField(col, "System prompt URL (GitHub file link)", Prefs.KEY_PROMPT_URL, "")

        addButton(col, "Save settings") { save() }
        addButton(col, "Start bubble") { startBubble() }
        addButton(col, "Stop bubble") { stopService(Intent(this, OverlayService::class.java)) }

        status = TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
            text = "Fill in the settings, then tap Start bubble."
        }
        col.addView(status)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addField(col: LinearLayout, label: String, key: String, def: String, secret: Boolean = false) {
        col.addView(TextView(this).apply {
            text = label
            setPadding(0, dp(10), 0, 0)
        })
        val et = EditText(this)
        if (secret) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.setSingleLine(true)
        et.setText(Prefs.get(this, key, def))
        col.addView(et)
        fields[key] = et
    }

    private fun addButton(col: LinearLayout, label: String, onClick: () -> Unit) {
        col.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun save() {
        for ((key, et) in fields) {
            Prefs.put(this, key, et.text.toString().trim())
        }
        status.text = "Saved."
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
        status.text = "Bubble started. Open Brawl Stars, tap the bubble to talk, tap again to send."
        moveTaskToBack(true)
    }
}
