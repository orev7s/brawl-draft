package com.brawldraft.app

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

/**
 * Everything the coach does, in one place, and it survives a restart.
 *
 * A ring buffer kept in memory and mirrored into prefs asynchronously. Writes are async on purpose:
 * a log line is not worth a synchronous disk write on every event, and losing the last line to a
 * hard kill is acceptable. Settings, keys and history use the synchronous path in [Prefs].
 */
object LogStore {

    private const val TAG = "BrawlDraft"
    private const val MAX_LINES = 300

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = ArrayList<String>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val saved = Prefs.get(ctx, Prefs.KEY_LOG)
        if (saved.isNotBlank()) buffer.addAll(saved.split("\n"))
    }

    /** Log to logcat and to the in-app log page. */
    fun log(ctx: Context, message: String) {
        Log.i(TAG, message)
        append(ctx, "  " + message)
    }

    /** A section heading, so the log page reads as turns rather than a wall of text. */
    fun section(ctx: Context, title: String) {
        Log.i(TAG, "== $title ==")
        append(ctx, "  == $title ==")
    }

    fun warn(ctx: Context, message: String) {
        Log.w(TAG, message)
        append(ctx, "! " + message)
    }

    private fun append(ctx: Context, body: String) {
        val line = stamp.format(Date()) + body
        synchronized(this) {
            ensureLoaded(ctx)
            buffer.add(line)
            while (buffer.size > MAX_LINES) buffer.removeAt(0)
            Prefs.putAsync(ctx, Prefs.KEY_LOG, buffer.joinToString("\n"))
        }
    }

    fun lines(ctx: Context): List<String> = synchronized(this) {
        ensureLoaded(ctx)
        buffer.toList()
    }

    fun clear(ctx: Context) {
        synchronized(this) {
            buffer.clear()
            loaded = true
            Prefs.put(ctx, Prefs.KEY_LOG, "")
        }
    }
}
