package com.brawldraft.app

import android.content.Context

object Prefs {
    private const val FILE = "brawl_draft_prefs"

    // Speech to text (any OpenAI-compatible /audio/transcriptions endpoint).
    const val KEY_STT_URL = "stt_url"
    const val KEY_STT_KEY = "stt_key"
    const val KEY_STT_MODEL = "stt_model"
    const val KEY_STT_PROMPT = "stt_prompt"

    // Coach LLM (any OpenAI-compatible /chat/completions endpoint).
    const val KEY_LLM_URL = "llm_url"
    const val KEY_LLM_KEY = "llm_key"
    const val KEY_LLM_MODEL = "llm_model"

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

    /** Upload and transcribe while recording instead of after stopping. */
    const val KEY_STREAM_UPLOAD = "stream_upload"

    /** Length of each live upload clip while recording. */
    const val KEY_SEGMENT_MS = "segment_ms"

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

    /**
     * Was llama-3.3-70b-versatile, which Groq retired - it now answers 404 model_not_found.
     * 120b rather than 20b because it measurably follows the draft rules better: on a transcript
     * where speech to text dropped the word "not", 20b repeated a stale ban while 120b reasoned
     * about the contradiction and moved on. Costs roughly a second more per reply.
     */
    const val DEFAULT_LLM_MODEL = "openai/gpt-oss-120b"

    /**
     * The cleaner pass. qwen3.8-27b answered in 0.76 s and repaired every name in the test
     * transcript ("Ems"->Emz, "Chug"->Chuck, "Gull"->Gus, "Artie"->R-T, "Rings of Fire"->Ring of
     * Fire). gpt-oss-20b was equally accurate but took 2.3 s; allam-2-7b missed most of them.
     */
    const val DEFAULT_CLEAN_MODEL = "qwen/qwen3.8-27b"

    /**
     * Published copy of assets/brawl_data.json. Editing that file on GitHub updates every install
     * without a rebuild. The repository must stay public for the app to fetch it unauthenticated.
     */
    const val DEFAULT_NAMES_URL =
        "https://raw.githubusercontent.com/orev7s/brawl-draft/main/app/src/main/assets/brawl_data.json"

    /** Published copy of assets/coach_knowledge.md, same edit-on-GitHub workflow. */
    const val DEFAULT_KNOWLEDGE_URL =
        "https://raw.githubusercontent.com/orev7s/brawl-draft/main/app/src/main/assets/coach_knowledge.md"

    /** How long a finished reply stays on screen before it closes itself. */
    const val DEFAULT_AUTO_HIDE_MS = 7500
    const val MIN_AUTO_HIDE_MS = 500
    const val MAX_AUTO_HIDE_MS = 300000

    fun get(ctx: Context, key: String, def: String = ""): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, def) ?: def

    fun put(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
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
}
