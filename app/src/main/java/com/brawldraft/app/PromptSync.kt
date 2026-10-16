package com.brawldraft.app

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Polls the system-prompt file on GitHub every 5 seconds.
 * When the content changes, [current] is replaced and the new prompt is used on the next message.
 * The last good prompt is cached in prefs, so the app still works offline.
 */
class PromptSync(private val ctx: Context) {

    private val exec = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    var current: String = ""
        private set

    fun start() {
        current = Prefs.get(ctx, Prefs.KEY_CACHED_PROMPT).ifBlank { defaultPrompt() }
        exec.scheduleWithFixedDelay({ poll() }, 0, 5, TimeUnit.SECONDS)
    }

    fun stop() {
        exec.shutdownNow()
    }

    private fun poll() {
        val url = toRawUrl(Prefs.get(ctx, Prefs.KEY_PROMPT_URL).trim())
        if (url.isEmpty()) return
        try {
            // Cache-buster: raw.githubusercontent.com caches responses, so each poll uses a unique query string.
            val sep = if (url.contains('?')) '&' else '?'
            val (code, body) = Http.request(
                "$url${sep}t=${System.currentTimeMillis()}",
                headers = mapOf("Cache-Control" to "no-cache"),
                timeoutMs = 8000
            )
            if (code in 200..299 && body.isNotBlank() && body != current) {
                current = body
                Prefs.put(ctx, Prefs.KEY_CACHED_PROMPT, body)
            }
        } catch (e: Exception) {
            // Keep the last good prompt; the next poll runs in 5 seconds.
        }
    }

    private fun defaultPrompt(): String =
        ctx.assets.open("default_prompt.txt").bufferedReader().use { it.readText() }

    companion object {
        /** Converts a github.com/.../blob/... link into its raw.githubusercontent.com equivalent. */
        fun toRawUrl(url: String): String {
            val m = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url) ?: return url
            return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
        }
    }
}
