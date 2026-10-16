package com.brawldraft.app

import android.content.Context
import android.content.SharedPreferences

/**
 * All persisted settings.
 *
 * Writes use [SharedPreferences.Editor.commit] rather than `apply`, because `apply` is asynchronous
 * and a value can be lost if the process dies right after it is set. Settings, API keys and the
 * conversation history go through here, so they survive the app being killed, swiped away, or the
 * tablet being restarted. Only the log buffer uses the async path, since losing the last line of a
 * log is not important.
 */
object Prefs {
    private const val FILE = "brawl_draft_prefs"

    // Speech to text (any OpenAI-compatible /audio/transcriptions endpoint).
    const val KEY_STT_URL = "stt_url"
    const val KEY_STT_KEY = "stt_key"
    const val KEY_STT_MODEL = "stt_model"
    const val KEY_STT_PROMPT = "stt_prompt"

    // Legacy single chat provider. Kept so an existing install can be migrated into the provider
    // list; the list is the source of truth once it exists.
    const val KEY_LLM_URL = "llm_url"
    const val KEY_LLM_KEY = "llm_key"
    const val KEY_LLM_MODEL = "llm_model"

    /** Ordered list of chat endpoints, newest schema. Read and written by ProviderStore. */
    const val KEY_PROVIDERS = "providers_json"

    /** Conversation history, so a restart does not lose the draft in progress. */
    const val KEY_HISTORY = "history_json"

    /** Persisted log buffer. */
    const val KEY_LOG = "log_lines"

    // Second pass that repairs mis-heard names before the coach sees the transcript.
    const val KEY_CLEAN_ENABLED = "clean_enabled"
    const val KEY_CLEAN_MODEL = "clean_model"

    // Published reference names (brawlers, ranked maps, observed mistakes).
    const val KEY_NAMES_URL = "names_url"
    const val KEY_CACHED_NAMES = "cached_names"

    // Coach game knowledge: roles, counters, modes, current ranked statistics.
    const val KEY_KNOWLEDGE_URL = "knowledge_url"
    const val KEY_CACHED_KNOWLEDGE = "cached_knowledge"
    const val KEY_KNOWLEDGE_ENABLED = "knowledge_enabled"

    const val KEY_PROMPT_URL = "prompt_url"
    const val KEY_CACHED_PROMPT = "cached_prompt"

    // Overlay behaviour.
    const val KEY_AUTO_HIDE_MS = "auto_hide_ms"
    const val KEY_SPEAK = "speak_replies"
    const val KEY_STREAM_UPLOAD = "stream_upload"
    const val KEY_SEGMENT_MS = "segment_ms"

    /** How long the press on the bubble has to be held to start a fresh conversation. */
    const val KEY_RESET_HOLD_MS = "reset_hold_ms"

    /** Bubble diameter in dp. Phones default smaller than tablets. */
    const val KEY_BUBBLE_DP = "bubble_dp"
    const val MIN_BUBBLE_DP = 36
    const val MAX_BUBBLE_DP = 120

    /**
     * 10 seconds. Measured against a real 26 second draft recording with a known transcript:
     * 4 second clips wrecked accuracy ("Gus" -> "Gull", "Emz is banned" -> "Ems is back", plus
     * invented words like "Gryffindor"), while 10 second clips matched single-request accuracy.
     */
    const val DEFAULT_SEGMENT_MS = 10000
    const val MIN_SEGMENT_MS = 2000
    const val MAX_SEGMENT_MS = 30000

    /**
     * Groq serves OpenAI's open-source Whisper models on a rate-limited free tier:
     * whisper-large-v3-turbo is capped at 7,200 audio seconds per hour and
     * 28,800 audio seconds per day, which is about 8 hours of audio every day at no cost.
     * Anything OpenAI-compatible works here too (OpenAI, Deepgram, a local Whisper server, ...).
     */
    const val DEFAULT_STT_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_STT_MODEL = "whisper-large-v3"
    const val DEFAULT_LLM_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_LLM_MODEL = "openai/gpt-oss-120b"
    const val DEFAULT_CLEAN_MODEL = "qwen/qwen3.8-27b"

    const val DEFAULT_NAMES_URL =
        "https://raw.githubusercontent.com/orev7s/brawl-draft/main/app/src/main/assets/brawl_data.json"
    const val DEFAULT_KNOWLEDGE_URL =
        "https://raw.githubusercontent.com/orev7s/brawl-draft/main/app/src/main/assets/coach_knowledge.md"

    /** How long a finished reply stays on screen before it closes itself. */
    const val DEFAULT_AUTO_HIDE_MS = 7500
    const val MIN_AUTO_HIDE_MS = 500
    const val MAX_AUTO_HIDE_MS = 300000

    /** Exactly two seconds of holding the bubble wipes the conversation. */
    const val DEFAULT_RESET_HOLD_MS = 2000
    const val MIN_RESET_HOLD_MS = 500
    const val MAX_RESET_HOLD_MS = 10000

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String, def: String = ""): String =
        prefs(ctx).getString(key, def) ?: def

    /** Synchronous write. Use for anything the owner would be annoyed to lose. */
    fun put(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).commit()
    }

    /** Asynchronous write, for high frequency data such as the log buffer. */
    fun putAsync(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    fun getInt(ctx: Context, key: String, def: Int): Int =
        get(ctx, key, def.toString()).trim().toIntOrNull() ?: def

    fun getBool(ctx: Context, key: String, def: Boolean): Boolean =
        get(ctx, key, def.toString()).trim().equals("true", ignoreCase = true)

    /** Auto-close delay in milliseconds, clamped to something sane. 0 means "stay open". */
    fun autoHideMs(ctx: Context): Int {
        val raw = getInt(ctx, KEY_AUTO_HIDE_MS, DEFAULT_AUTO_HIDE_MS)
        if (raw <= 0) return 0
        return raw.coerceIn(MIN_AUTO_HIDE_MS, MAX_AUTO_HIDE_MS)
    }

    /** Live upload clip length, clamped to a range that keeps accuracy and latency sane. */
    fun segmentMs(ctx: Context): Int =
        getInt(ctx, KEY_SEGMENT_MS, DEFAULT_SEGMENT_MS).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)

    /** Hold time that resets the conversation, clamped to something usable. */
    fun resetHoldMs(ctx: Context): Int =
        getInt(ctx, KEY_RESET_HOLD_MS, DEFAULT_RESET_HOLD_MS)
            .coerceIn(MIN_RESET_HOLD_MS, MAX_RESET_HOLD_MS)

    /** Bubble diameter in dp. 0 means "use the size that suits this screen". */
    fun bubbleDp(ctx: Context): Int {
        val raw = getInt(ctx, KEY_BUBBLE_DP, 0)
        return if (raw <= 0) 0 else raw.coerceIn(MIN_BUBBLE_DP, MAX_BUBBLE_DP)
    }
}
