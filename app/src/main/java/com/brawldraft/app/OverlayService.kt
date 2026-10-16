package com.brawldraft.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Floating bubble over other apps. Tap once to start recording, tap again to stop.
 * The audio goes to Whisper, the transcript goes to the LLM with the live system prompt,
 * and the reply is shown in a panel and spoken in English.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.brawldraft.app.STOP"
        private const val CHANNEL_ID = "brawl_draft"
        private const val NOTIF_ID = 1
        private const val COLOR_IDLE = 0xFF3F51B5.toInt()
        private const val COLOR_REC = 0xFFE53935.toInt()
        private const val COLOR_BUSY = 0xFFFB8C00.toInt()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val history = ArrayList<Pair<String, String>>()

    private lateinit var wm: WindowManager
    private lateinit var promptSync: PromptSync

    private var bubbleView: TextView? = null
    private var panelView: TextView? = null
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null

    @Volatile private var recording = false
    @Volatile private var busy = false

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()

        promptSync = PromptSync(this)
        promptSync.start()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        recorder = null
        promptSync.stop()
        tts?.shutdown()
        worker.shutdownNow()
        removeView(bubbleView)
        removeView(panelView)
        bubbleView = null
        panelView = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Brawl Draft", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Brawl Draft is running")
            .setContentText("Tap the bubble to talk")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    private fun removeView(v: View?) {
        if (v != null) {
            try { wm.removeView(v) } catch (e: Exception) { }
        }
    }

    private fun showBubble() {
        val size = dp(64)
        val tv = TextView(this).apply {
            text = "TALK"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            background = circle(COLOR_IDLE)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTapped()
                }
            }
            true
        }

        wm.addView(tv, lp)
        bubbleView = tv
    }

    private fun setBubble(label: String, color: Int) {
        val b = bubbleView ?: return
        b.text = label
        b.background = circle(color)
    }

    private fun showPanel(text: String) {
        var tv = panelView
        if (tv == null) {
            tv = TextView(this)
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.textSize = 15f
            tv.background = rounded(0xE6000000.toInt())
            tv.setPadding(dp(14), dp(12), dp(14), dp(12))
            tv.setOnClickListener { hidePanel() }
            val lp = WindowManager.LayoutParams(
                dp(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(40)
            }
            wm.addView(tv, lp)
            panelView = tv
        }
        tv.text = text
    }

    private fun hidePanel() {
        removeView(panelView)
        panelView = null
    }

    private fun onBubbleTapped() {
        if (busy) return
        if (recording) stopAndProcess() else startRecording()
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun startRecording() {
        try {
            val file = File(cacheDir, "utterance.m4a")
            file.delete()
            audioFile = file

            val rec = newRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(64000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()

            recorder = rec
            recording = true
            setBubble("REC", COLOR_REC)
            showPanel("Listening... tap the bubble again when you're done.")
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            setBubble("TALK", COLOR_IDLE)
            showPanel("Could not start recording: ${e.message}")
        }
    }

    private fun stopAndProcess() {
        recording = false
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (e: Exception) { /* too short */ }
        rec?.release()

        val file = audioFile
        if (file == null || !file.exists() || file.length() < 2000) {
            setBubble("TALK", COLOR_IDLE)
            showPanel("Recording was too short. Try again.")
            return
        }

        busy = true
        setBubble("...", COLOR_BUSY)
        showPanel("Transcribing...")
        worker.execute { process(file) }
    }

    /** Runs on the worker thread. */
    private fun process(file: File) {
        try {
            val heard = WhisperClient.transcribe(
                Prefs.get(this, Prefs.KEY_WHISPER_KEY),
                Prefs.get(this, Prefs.KEY_WHISPER_MODEL, Prefs.DEFAULT_WHISPER_MODEL),
                file
            )
            if (heard.isBlank()) {
                finish("Did not catch any speech. Try again.")
                return
            }
            main.post { showPanel("You: $heard\n\nThinking...") }

            val reply = LlmClient.chat(
                Prefs.get(this, Prefs.KEY_LLM_URL, Prefs.DEFAULT_LLM_URL),
                Prefs.get(this, Prefs.KEY_LLM_KEY),
                Prefs.get(this, Prefs.KEY_LLM_MODEL, Prefs.DEFAULT_LLM_MODEL),
                promptSync.current,
                history.toList(),
                heard
            )

            history.add(Pair("user", heard))
            history.add(Pair("assistant", reply))
            while (history.size > 20) history.removeAt(0)

            finish("You: $heard\n\n$reply", reply)
        } catch (e: Exception) {
            finish("Error: ${e.message}")
        }
    }

    private fun finish(text: String, speakText: String? = null) {
        main.post {
            showPanel(text)
            if (speakText != null) speak(speakText)
            setBubble("TALK", COLOR_IDLE)
            busy = false
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }
}
