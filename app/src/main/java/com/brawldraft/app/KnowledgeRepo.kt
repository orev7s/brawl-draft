package com.brawldraft.app

import android.content.Context
import android.util.Log

/**
 * The coach's game knowledge block.
 *
 * Same pattern as [NamesRepo]: the bundled asset is the offline default, and a copy cached from
 * [Prefs.KEY_KNOWLEDGE_URL] wins once it has been fetched, so the file can be edited on GitHub
 * without rebuilding the app.
 *
 * Around 2,100 tokens, which matters: the free Groq tier allows only 8K tokens per minute, so this
 * roughly triples the cost of every coach turn. On Gemini's free tier it is comfortable. That is
 * why it has its own on/off switch.
 */
object KnowledgeRepo {

    private const val TAG = "BrawlDraft"
    private const val ASSET = "coach_knowledge.md"

    /** Marker that proves a fetched body is really the knowledge file and not an error page. */
    private const val SENTINEL = "BRAWL STARS RANKED"

    @Volatile private var cached: String? = null

    fun text(ctx: Context): String {
        cached?.let { return it }
        val remote = Prefs.get(ctx, Prefs.KEY_CACHED_KNOWLEDGE).trim()
        val result = if (remote.isNotEmpty()) remote else bundled(ctx).trim()
        cached = result
        return result
    }

    private fun bundled(ctx: Context): String = try {
        ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e(TAG, "bundled $ASSET missing", e)
        ""
    }

    fun refresh(ctx: Context) {
        val url = Prefs.get(ctx, Prefs.KEY_KNOWLEDGE_URL, Prefs.DEFAULT_KNOWLEDGE_URL)
        if (url.isBlank()) return
        try {
            val res = Http.send(url, timeoutMs = 20000)
            if (res.code in 200..299 && res.body.contains(SENTINEL)) {
                Prefs.put(ctx, Prefs.KEY_CACHED_KNOWLEDGE, res.body)
                cached = res.body.trim()
                Log.i(TAG, "knowledge refreshed, ${res.body.length} chars")
            } else {
                Log.w(TAG, "knowledge refresh returned ${res.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "knowledge refresh failed: ${e.message}")
        }
    }

    /** Rough size estimate for the settings screen. English prose is about 4 characters per token. */
    fun approxTokens(ctx: Context): Int = text(ctx).length / 4
}
