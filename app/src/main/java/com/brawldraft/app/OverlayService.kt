package com.brawldraft.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Floating bubble over other apps.
 *
 * Audio is captured as raw 16 kHz PCM and cut into short segments while the player is still
 * talking. Each finished segment is uploaded and transcribed immediately on a background
 * pool, so by the time Send is tapped most of the sentence is already text and only the last
 * short tail is still in flight. That is what makes the reply come back quickly.
 *
 * A finished reply closes itself after [Prefs.KEY_AUTO_HIDE_MS] milliseconds (7.5 seconds by
 * default), tapping the panel restarts that timer and the X closes it right away. Cancel
 * throws the take away without asking the coach.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.brawldraft.app.STOP"
        private const val TAG = "BrawlDraft"

        private const val CHANNEL_ID = "brawl_draft"
        private const val NOTIF_ID = 1

        private const val COLOR_IDLE = 0xFF3F51B5.toInt()
        private const val COLOR_REC = 0xFFD32F2F.toInt()
        private const val COLOR_BUSY = 0xFFF57C00.toInt()

        private const val COLOR_PANEL = 0xF2181818.toInt()
        private const val COLOR_TITLE = 0xFFBDBDBD.toInt()
        private const val COLOR_BODY = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFF9E9E9E.toInt()
        private const val COLOR_BTN = 0xFF2F5BEA.toInt()
        private const val COLOR_BTN_DANGER = 0xFF9E2B2B.toInt()
        private const val COLOR_CLOSE_BG = 0x26FFFFFF.toInt()

        private const val BUBBLE_IDLE = "TALK"
        private const val BUBBLE_REC = "REC"
        private const val BUBBLE_BUSY = "..."

        /** 16 kHz mono 16-bit PCM - exactly what Whisper wants, no resampling needed. */
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2

        /** Shortest chunk worth uploading (250 ms). */
        private const val MIN_SEGMENT_BYTES = SAMPLE_RATE / 4 * BYTES_PER_SAMPLE

        /** Shortest tail worth a request once recording stops (800 ms). */
        private const val MIN_FINAL_BYTES = (SAMPLE_RATE * 4 / 5) * BYTES_PER_SAMPLE

        /** Hard stop at 5 minutes so a forgotten recording cannot eat memory or quota. */
        private const val MAX_TOTAL_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 300

        /** Below this RMS the clip is silence; uploading it only risks a hallucinated transcript. */
        private const val SILENCE_RMS = 180.0

        /** How often to re-pull the published name lists. */
        private const val NAMES_REFRESH_MS = 5 * 60 * 1000L

        /** Conversation turns kept in context. Older ones are dropped from the front. */
        private const val MAX_HISTORY = 20
    }

    private enum class State { IDLE, RECORDING, WORKING, RESULT }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Segment transcriptions run in parallel, results are joined in order. */
    private val segmentPool = Executors.newFixedThreadPool(3)

    /**
     * The conversation lives in prefs, not in a field, so it survives the service being killed,
     * the app being swiped away and the tablet restarting. Only a deliberate hold on the bubble
     * clears it.
     */
    private fun loadHistory(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val raw = Prefs.get(this, Prefs.KEY_HISTORY)
        if (raw.isBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role")
                val content = o.optString("content")
                if (role.isNotBlank() && content.isNotBlank()) out.add(Pair(role, content))
            }
        } catch (e: Exception) {
            LogStore.warn(this, "stored conversation unreadable, starting a fresh one")
        }
        return out
    }

    private fun saveHistory(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((role, content) in list) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        Prefs.put(this, Prefs.KEY_HISTORY, arr.toString())
    }

    private fun appendHistory(heard: String, reply: String) {
        val list = loadHistory()
        list.add(Pair("user", heard))
        list.add(Pair("assistant", reply))
        while (list.size > MAX_HISTORY) list.removeAt(0)
        saveHistory(list)
    }

    /** Wipes the conversation. Triggered only by holding the bubble for [Prefs.resetHoldMs]. */
    private fun resetConversation(reason: String) {
        saveHistory(emptyList())
        LogStore.section(this, "new conversation ($reason)")
        state = State.RESULT
        showResult("New conversation", "Conversation cleared. The next thing you say starts fresh.")
    }

    private lateinit var wm: WindowManager
    private lateinit var promptSync: PromptSync

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelTitle: TextView? = null
    private var panelClose: TextView? = null
    private var panelBodyScroll: ScrollView? = null
    private var panelBody: TextView? = null
    private var panelFooter: TextView? = null
    private var actionRow: LinearLayout? = null

    // ------------------------------------------------------------------ capture

    private class Segment(val index: Int) {
        var file: File? = null
        var future: Future<*>? = null

        @Volatile var text: String = ""
        @Volatile var error: String? = null
        @Volatile var skippedSilence = false
        @Volatile var done = false
    }

    private val segments = CopyOnWriteArrayList<Segment>()

    @Volatile private var capturing = false
    @Volatile private var discardCapture = false
    private var captureThread: Thread? = null

    @Volatile private var state = State.IDLE

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsSpeaking = false

    private var autoHideDeadline = 0L
    private var lastLatencyMs = 0L

    private val autoHideTask = Runnable { hidePanel() }

    private val tickTask = object : Runnable {
        override fun run() {
            updateFooterCountdown()
            if (autoHideDeadline > System.currentTimeMillis()) main.postDelayed(this, 400)
        }
    }

    private val namesRefreshTask = object : Runnable {
        override fun run() {
            // this@OverlayService: inside this anonymous Runnable, "this" is the Runnable itself.
            Thread({
                NamesRepo.refresh(this@OverlayService)
                KnowledgeRepo.refresh(this@OverlayService)
            }, "bd-names").start()
            main.postDelayed(this, NAMES_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()

        promptSync = PromptSync(this)
        promptSync.start()

        // Pull the published name lists and the knowledge file without blocking startup, then
        // refresh occasionally so an edit on GitHub reaches the tablet without a rebuild.
        Thread({
            NamesRepo.refresh(this)
            KnowledgeRepo.refresh(this)
        }, "bd-names").start()
        main.postDelayed(namesRefreshTask, NAMES_REFRESH_MS)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        onSpeechEnded()
                    }

                    override fun onError(utteranceId: String?) {
                        onSpeechEnded()
                    }
                })
                ttsReady = true
            }
        }

        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: rebuild both overlays so nothing lands off screen or ends up too narrow.
        applyBubbleGeometry()
        applyPanelGeometry()
        fitBody()
    }

    override fun onDestroy() {
        cancelAutoHide()
        stopSpeech()
        capturing = false
        discardCapture = true
        try { captureThread?.join(1200) } catch (e: InterruptedException) { }
        for (seg in segments) {
            seg.future?.cancel(true)
            seg.file?.delete()
        }
        segments.clear()
        promptSync.stop()
        main.removeCallbacks(namesRefreshTask)
        tts?.shutdown()
        segmentPool.shutdownNow()
        worker.shutdownNow()
        removeView(panel)
        removeView(bubble)
        panel = null
        bubble = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Brawl Draft", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = buildNotification("Tap the bubble to talk")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Brawl Draft")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            // A failed notification refresh must never break the overlay.
        }
    }

    // ---------------------------------------------------------------- geometry

    private fun isTablet(): Boolean = resources.configuration.screenWidthDp >= 600

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun screenW(): Int = resources.displayMetrics.widthPixels

    private fun screenH(): Int = resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun panelWidthPx(): Int {
        val cap = if (isTablet()) dp(560) else dp(380)
        return min(cap, screenW() - dp(24))
    }

    private fun bodyMaxHeightPx(): Int = (screenH() * 0.42f).toInt()

    /** Owner setting wins; 0 means "pick the size that suits this screen". */
    private fun bubbleSizePx(): Int {
        val pref = Prefs.bubbleDp(this)
        return if (pref > 0) dp(pref) else if (isTablet()) dp(78) else dp(64)
    }

    private fun bodyTextSize(): Float = if (isTablet()) 18f else 15f

    private fun titleTextSize(): Float = if (isTablet()) 15f else 13f

    private fun buttonTextSize(): Float = if (isTablet()) 17f else 15f

    private fun removeView(v: View?) {
        if (v != null) {
            try { wm.removeView(v) } catch (e: Exception) { }
        }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    // ------------------------------------------------------------------ bubble

    private fun showBubble() {
        val size = bubbleSizePx()
        val tv = TextView(this).apply {
            text = BUBBLE_IDLE
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (isTablet()) 14f else 12f
            typeface = Typeface.DEFAULT_BOLD
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
        var held = false

        // Holding the bubble wipes the conversation. The tap has to be suppressed afterwards,
        // otherwise letting go would also start a recording.
        val holdMs = Prefs.resetHoldMs(this).toLong()
        val holdTask = Runnable {
            held = true
            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            resetConversation("held for ${holdMs}ms")
        }

        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    held = false
                    main.postDelayed(holdTask, holdMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        if (!moved) main.removeCallbacks(holdTask)
                        moved = true
                    }
                    if (moved) {
                        val size = bubbleSizePx()
                        lp.x = (startX + dx).coerceIn(0, max(0, screenW() - size))
                        lp.y = (startY + dy).coerceIn(0, max(0, screenH() - size))
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdTask)
                    if (!moved && !held) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onBubbleTapped()
                    }
                }
            }
            true
        }

        wm.addView(tv, lp)
        bubble = tv
        bubbleParams = lp
    }

    private fun applyBubbleGeometry() {
        val b = bubble ?: return
        val lp = bubbleParams ?: return
        val size = bubbleSizePx()
        lp.width = size
        lp.height = size
        lp.x = lp.x.coerceIn(0, max(0, screenW() - size))
        lp.y = lp.y.coerceIn(0, max(0, screenH() - size))
        try { wm.updateViewLayout(b, lp) } catch (e: Exception) { }
    }

    private fun setBubble(label: String, color: Int) {
        val b = bubble ?: return
        b.text = label
        b.background = circle(color)
    }

    // ------------------------------------------------------------------- panel

    private fun ensurePanel(): LinearLayout {
        panel?.let { return it }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_PANEL, 18)
            setPadding(dp(16), dp(14), dp(16), dp(12))
            isClickable = true
            // Tapping the panel restarts the auto-close timer instead of killing the reply.
            setOnClickListener {
                if (state == State.RESULT) scheduleAutoHide()
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            setTextColor(COLOR_TITLE)
            textSize = titleTextSize()
            typeface = Typeface.DEFAULT_BOLD
        }
        panelTitle = titleView
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeSize = dp(if (isTablet()) 44 else 40)
        val closeView = TextView(this).apply {
            text = "\u2715"
            setTextColor(COLOR_BODY)
            textSize = if (isTablet()) 18f else 16f
            gravity = Gravity.CENTER
            background = circle(COLOR_CLOSE_BG)
            isClickable = true
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hidePanel()
            }
        }
        panelClose = closeView
        header.addView(closeView, LinearLayout.LayoutParams(closeSize, closeSize))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bodyView = TextView(this).apply {
            setTextColor(COLOR_BODY)
            textSize = bodyTextSize()
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }
        panelBody = bodyView
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(bodyView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panelBodyScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val actionsView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        actionRow = actionsView
        actionsView.addView(pill("Send", COLOR_BTN) { stopAndSend() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionsView.addView(
            pill("Cancel", COLOR_BTN_DANGER) { cancelRecording() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) }
        )
        root.addView(actionsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val footerView = TextView(this).apply {
            setTextColor(COLOR_MUTED)
            textSize = if (isTablet()) 13f else 11f
            setPadding(0, dp(10), 0, 0)
        }
        panelFooter = footerView
        root.addView(footerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = WindowManager.LayoutParams(
            panelWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(16)
        }
        wm.addView(root, lp)
        panel = root
        panelParams = lp
        return root
    }

    private fun applyPanelGeometry() {
        val p = panel ?: return
        val lp = panelParams ?: return
        lp.width = panelWidthPx()
        lp.y = statusBarHeight() + dp(16)
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) { }
    }

    private fun pill(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(COLOR_BODY)
            textSize = buttonTextSize()
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(color, 12)
            val padH = dp(18)
            val padV = dp(if (isTablet()) 14 else 12)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    private fun renderPanel(title: String, body: String, footer: String, showActions: Boolean) {
        cancelAutoHide()
        val root = ensurePanel()
        panelTitle?.text = title
        panelBody?.text = body
        panelFooter?.text = footer
        panelFooter?.visibility = if (footer.isEmpty()) View.GONE else View.VISIBLE
        actionRow?.visibility = if (showActions) View.VISIBLE else View.GONE
        root.visibility = View.VISIBLE
        applyPanelGeometry()
        fitBody()
    }

    private fun setPanelBody(body: String) {
        panelBody?.text = body
        fitBody()
    }

    /** Keeps long replies inside a scrollable box instead of covering the whole screen. */
    private fun fitBody() {
        val sv = panelBodyScroll ?: return
        val child = panelBody ?: return
        sv.post {
            val width = sv.width - sv.paddingLeft - sv.paddingRight
            if (width <= 0) return@post
            child.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val lp = sv.layoutParams as? LinearLayout.LayoutParams ?: return@post
            val maxH = bodyMaxHeightPx()
            val target = if (child.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            if (lp.height != target) {
                lp.height = target
                sv.layoutParams = lp
            }
        }
    }

    private fun hidePanel() {
        cancelAutoHide()
        panel?.visibility = View.GONE
        if (state == State.RESULT) state = State.IDLE
        updateNotification("Ready - tap the bubble to talk")
    }

    // --------------------------------------------------------------- auto hide

    private fun scheduleAutoHide() {
        cancelAutoHide()
        if (state != State.RESULT) return
        if (ttsSpeaking) return // the speech callback reschedules once the reply is read out
        val ms = Prefs.autoHideMs(this)
        if (ms <= 0) {
            panelFooter?.text = "Stays open until you tap \u2715."
            return
        }
        autoHideDeadline = System.currentTimeMillis() + ms
        main.postDelayed(autoHideTask, ms.toLong())
        main.post(tickTask)
        updateFooterCountdown()
    }

    private fun cancelAutoHide() {
        autoHideDeadline = 0L
        main.removeCallbacks(autoHideTask)
        main.removeCallbacks(tickTask)
    }

    private fun updateFooterCountdown() {
        if (autoHideDeadline <= 0L) return
        val left = autoHideDeadline - System.currentTimeMillis()
        val secs = if (left <= 0) 0 else ((left + 999) / 1000).toInt()
        val timing = if (lastLatencyMs > 0) " - answered in ${lastLatencyMs / 1000.0}s" else ""
        panelFooter?.text = "Closing in ${secs}s - tap this panel to keep it, \u2715 to close$timing."
    }

    private fun onSpeechEnded() {
        main.post {
            ttsSpeaking = false
            if (state == State.RESULT) scheduleAutoHide()
        }
    }

    // ------------------------------------------------------------------- flow

    private fun onBubbleTapped() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndSend()
            State.WORKING -> Unit // a request is already in flight
            State.RESULT -> startRecording()
        }
    }

    private fun startRecording() {
        if (state == State.RECORDING || state == State.WORKING) return
        stopSpeech()
        discardCapture = false
        capturing = true
        lastLatencyMs = 0L
        segments.clear()
        LogStore.section(this, "recording")

        state = State.RECORDING
        setBubble(BUBBLE_REC, COLOR_REC)
        updateNotification("Recording - tap the bubble to send")
        renderPanel(
            "Recording",
            "Talk now. Tap the bubble or Send when you are done.",
            recordingFooter(),
            showActions = true
        )

        val thread = Thread({ captureLoop() }, "bd-capture")
        captureThread = thread
        thread.start()
    }

    /**
     * Throws the take away. No coach request is made, and anything still in flight is dropped.
     */
    private fun cancelRecording() {
        if (state != State.RECORDING) return
        state = State.IDLE
        discardCapture = true
        capturing = false
        LogStore.log(this, "recording cancelled, nothing was sent")

        val thread = captureThread
        setBubble(BUBBLE_IDLE, COLOR_IDLE)
        updateNotification("Ready - tap the bubble to talk")
        showResult(
            "Cancelled",
            "Recording discarded. The coach was not asked anything. " +
                "Clips that had already finished uploading while you talked still count against your free quota."
        )

        worker.execute {
            try { thread?.join(1500) } catch (e: Exception) { }
            for (seg in segments) {
                seg.future?.cancel(true)
                seg.file?.delete()
            }
            segments.clear()
        }
    }

    private fun stopAndSend() {
        if (state != State.RECORDING) return
        state = State.WORKING
        LogStore.log(this, "recording stopped, finishing the last clip")
        setBubble(BUBBLE_BUSY, COLOR_BUSY)
        updateNotification("Wrapping up the last clip...")
        renderPanel("Working", "Finishing the last clip...", "", showActions = false)
        worker.execute { finishCaptureAndAsk() }
    }

    // ------------------------------------------------------------ capture loop

    private fun captureLoop() {
        var rec: AudioRecord? = null
        val pcm = ByteArrayOutputStream()
        try {
            rec = openAudioRecord()
            if (rec == null) {
                main.post { captureFailed("Could not open the microphone.") }
                return
            }
            rec.startRecording()
            val streaming = Prefs.getBool(this, Prefs.KEY_STREAM_UPLOAD, true)
            val segmentBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * Prefs.segmentMs(this) / 1000
            val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms
            var total = 0

            while (capturing && !discardCapture && total < MAX_TOTAL_BYTES) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                appendPcm(pcm, buffer, read)
                total += read * BYTES_PER_SAMPLE
                if (streaming && pcm.size() >= segmentBytes) flushSegment(pcm, final = false)
            }
            if (!discardCapture) flushSegment(pcm, final = true)
        } catch (e: Exception) {
            Log.e(TAG, "capture loop failed", e)
            main.post { captureFailed(e.message ?: "Recording failed.") }
        } finally {
            try { rec?.stop() } catch (e: Exception) { }
            try { rec?.release() } catch (e: Exception) { }
        }
    }

    private fun openAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, SAMPLE_RATE * BYTES_PER_SAMPLE * 2)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord source $source refused", e)
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec
            try { rec?.release() } catch (e: Exception) { }
        }
        return null
    }

    private fun appendPcm(out: ByteArrayOutputStream, src: ShortArray, count: Int) {
        val bytes = ByteArray(count * BYTES_PER_SAMPLE)
        for (i in 0 until count) {
            val v = src[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
    }

    /**
     * Cuts the current buffer off as its own clip and starts transcribing it right away,
     * so the upload overlaps with the player still talking.
     */
    private fun flushSegment(pcm: ByteArrayOutputStream, final: Boolean) {
        val bytes = pcm.toByteArray()
        pcm.reset()
        if (bytes.isEmpty()) return

        // A tiny tail is not worth a request unless it is the only thing we have.
        val floor = if (final) MIN_FINAL_BYTES else MIN_SEGMENT_BYTES
        val isOnlySegment = segments.isEmpty()
        if (bytes.size < floor && !(final && isOnlySegment)) return

        val segment = Segment(segments.size)
        segments.add(segment)

        if (rms(bytes) < SILENCE_RMS) {
            segment.skippedSilence = true
            segment.done = true
            LogStore.log(this, "clip ${segment.index + 1}: silence, not uploaded")
            main.post { onSegmentDone() }
            return
        }

        val file = File(cacheDir, "seg_${segment.index}.wav")
        try {
            writeWav(file, bytes)
        } catch (e: Exception) {
            LogStore.warn(this, "could not write clip ${segment.index + 1}: ${e.message}")
            segment.error = "Could not write the audio clip: ${e.message}"
            segment.done = true
            main.post { onSegmentDone() }
            return
        }

        segment.file = file
        segment.future = segmentPool.submit(Callable {
            try {
                segment.text = TranscribeClient.transcribe(
                    Prefs.get(this, Prefs.KEY_STT_URL, Prefs.DEFAULT_STT_URL),
                    Prefs.get(this, Prefs.KEY_STT_KEY),
                    Prefs.get(this, Prefs.KEY_STT_MODEL, Prefs.DEFAULT_STT_MODEL),
                    Prefs.get(this, Prefs.KEY_STT_PROMPT).ifBlank { NamesRepo.hint(this) },
                    file
                )
                LogStore.log(this, "clip ${segment.index + 1} heard: ${segment.text}")
            } catch (e: Exception) {
                LogStore.warn(this, "clip ${segment.index + 1} transcription failed: ${e.message}")
                segment.error = e.message ?: e.toString()
            } finally {
                segment.done = true
                file.delete()
                main.post { onSegmentDone() }
            }
        })
    }

    private fun onSegmentDone() {
        if (state == State.RECORDING) {
            val live = liveTranscript()
            if (live.isNotEmpty()) {
                setPanelBody("You: $live")
                panelTitle?.text = "Recording"
            }
            panelFooter?.text = recordingFooter()
        }
    }

    private fun recordingFooter(): String {
        val uploaded = segments.count { it.future != null }
        val done = segments.count { it.done && it.future != null }
        return if (Prefs.getBool(this, Prefs.KEY_STREAM_UPLOAD, true)) {
            "Uploading while you talk: $done of $uploaded clips transcribed. Send stops and asks the coach."
        } else {
            "Uploading is off: the clip is sent when you tap Send."
        }
    }

    private fun liveTranscript(): String =
        segments.joinToString(" ") { it.text }.trim().replace(Regex("\\s+"), " ")

    /**
     * Shows what the coach actually read, plus the raw transcript whenever the cleaner pass
     * changed something, so a wrong repair is visible instead of silent.
     */
    /**
     * The prompt the coach actually receives: the editable base prompt, then the game knowledge
     * when the owner has it switched on, then the brawler-name addendum.
     */
    private fun systemPrompt(): String {
        val prompt = buildString {
            append(promptSync.current)
            if (Prefs.getBool(this@OverlayService, Prefs.KEY_KNOWLEDGE_ENABLED, true)) {
                val knowledge = KnowledgeRepo.text(this@OverlayService)
                if (knowledge.isNotEmpty()) {
                    append("\n\n")
                    append(knowledge)
                }
            }
            append("\n\n")
            append(NamesRepo.addendum(this@OverlayService))
        }
        LogStore.log(
            this,
            "system prompt " + prompt.length + " chars (~" + (prompt.length / 4) + " tokens), " +
                "knowledge " + (Prefs.getBool(this, Prefs.KEY_KNOWLEDGE_ENABLED, true))
        )
        return prompt
    }

    private fun transcriptBlock(raw: String, cleaned: String): String =
        if (cleaned == raw) {
            "You: $raw"
        } else {
            "You: $cleaned\n(heard: $raw)"
        }

    private fun captureFailed(message: String) {
        capturing = false
        state = State.IDLE
        setBubble(BUBBLE_IDLE, COLOR_IDLE)
        showResult("Mic problem", message)
    }

    // ------------------------------------------------------- stop, join, ask

    private fun finishCaptureAndAsk() {
        val started = System.currentTimeMillis()
        capturing = false
        try { captureThread?.join(4000) } catch (e: InterruptedException) { }

        // Wait for every clip in order. Earlier clips were already in flight, so this only
        // really waits for the short tail recorded after the last upload.
        var firstError: String? = null
        for (seg in segments) {
            try { seg.future?.get() } catch (e: Exception) { Log.e(TAG, "join failed", e) }
            if (firstError == null && seg.error != null) firstError = seg.error
        }

        val raw = liveTranscript()

        if (raw.isEmpty()) {
            finish(
                "Heard nothing",
                firstError ?: "No speech was picked up. Check the mic, then tap TALK and try again.",
                null,
                System.currentTimeMillis() - started
            )
            return
        }

        // Second pass: repair names the transcriber mangled before the coach reads the transcript.
        // Measured at 0.76 s on Groq qwen3.8-27b. Any failure keeps the raw transcript.
        var heard = raw
        if (Prefs.getBool(this, Prefs.KEY_CLEAN_ENABLED, true)) {
            main.post {
                updateNotification("Checking the names...")
                if (state == State.WORKING) {
                    setPanelBody("You: $raw\n\nFixing names...")
                    panelTitle?.text = "Checking names"
                }
            }
            heard = LlmChain.clean(this, NamesRepo.contextFor(this), raw).ifBlank { raw }
            LogStore.log(this, "heard: $raw")
            if (heard != raw) LogStore.log(this, "after name check: $heard")
        }

        val history = loadHistory()
        LogStore.log(this, "asking the coach with ${history.size / 2} earlier turn(s) of context")

        try {
            main.post {
                updateNotification("Asking the coach...")
                if (state == State.WORKING) {
                    setPanelBody(transcriptBlock(raw, heard) + "\n\nThinking...")
                    panelTitle?.text = "Thinking"
                }
            }

            val reply = LlmChain.chat(this, systemPrompt(), history, heard)
            LogStore.log(this, "reply: $reply")
            appendHistory(heard, reply)

            finish("Coach", transcriptBlock(raw, heard) + "\n\n" + reply, reply, System.currentTimeMillis() - started)
        } catch (e: IOException) {
            finish("Request failed", e.message ?: "Unknown network error.", null, System.currentTimeMillis() - started)
        } catch (e: Exception) {
            finish("Error", e.message ?: e.toString(), null, System.currentTimeMillis() - started)
        } finally {
            segments.clear()
        }
    }

    private fun finish(title: String, body: String, speakText: String?, latencyMs: Long) {
        main.post {
            lastLatencyMs = latencyMs
            state = State.RESULT
            setBubble(BUBBLE_IDLE, COLOR_IDLE)
            updateNotification("Reply ready")
            showResult(title, body)
            if (speakText != null) speak(speakText)
            scheduleAutoHide()
        }
    }

    private fun showResult(title: String, body: String) {
        state = State.RESULT
        val ms = Prefs.autoHideMs(this)
        val timing = if (lastLatencyMs > 0) " - answered in ${lastLatencyMs / 1000.0}s" else ""
        val footer =
            if (ms <= 0) "Stays open until you tap \u2715$timing."
            else "Closing in ${ms / 1000}s - tap this panel to keep it, \u2715 to close$timing."
        renderPanel(title, body, footer, showActions = false)
    }

    // -------------------------------------------------------------------- wav

    /** Wraps raw PCM in a canonical 16-bit mono WAV header, which every provider accepts. */
    private fun writeWav(file: File, pcm: ByteArray) {
        val dataLen = pcm.size
        val header = ByteArray(44)

        fun putAscii(offset: Int, text: String) {
            for (i in text.indices) header[offset + i] = text[i].code.toByte()
        }

        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putInt(4, 36 + dataLen)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)                                   // PCM chunk size
        putInt(20, 1)                                    // format = PCM
        putInt(22, 1)                                    // mono
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * BYTES_PER_SAMPLE)       // byte rate
        putInt(32, BYTES_PER_SAMPLE)                     // block align
        putInt(34, 16)                                   // bits per sample
        putAscii(36, "data")
        putInt(40, dataLen)

        FileOutputStream(file).use {
            it.write(header)
            it.write(pcm)
        }
    }

    private fun rms(pcm: ByteArray): Double {
        val samples = pcm.size / BYTES_PER_SAMPLE
        if (samples == 0) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val raw = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val value = if (raw > 32767) raw - 65536 else raw
            sum += value.toDouble() * value
            i += BYTES_PER_SAMPLE
        }
        return sqrt(sum / samples)
    }

    // -------------------------------------------------------------------- tts

    private fun speak(text: String) {
        if (!ttsReady || !Prefs.getBool(this, Prefs.KEY_SPEAK, true)) {
            ttsSpeaking = false
            return
        }
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bd-reply")
        ttsSpeaking = (rc == TextToSpeech.SUCCESS)
    }

    private fun stopSpeech() {
        ttsSpeaking = false
        try { tts?.stop() } catch (e: Exception) { }
    }
}
