package com.brawldraft.app

import android.content.Context

object Prefs {
    private const val FILE = "brawl_draft_prefs"

    const val KEY_WHISPER_KEY = "whisper_key"
    const val KEY_WHISPER_MODEL = "whisper_model"
    const val KEY_LLM_URL = "llm_url"
    const val KEY_LLM_KEY = "llm_key"
    const val KEY_LLM_MODEL = "llm_model"
    const val KEY_PROMPT_URL = "prompt_url"
    const val KEY_CACHED_PROMPT = "cached_prompt"

    // Free Whisper tiers are tiny/small/medium. "base" and larger are paid.
    const val DEFAULT_WHISPER_MODEL = "small"
    // Groq offers an OpenAI-compatible free tier. Verify the model name in its console.
    const val DEFAULT_LLM_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_LLM_MODEL = "llama-3.3-70b-versatile"

    fun get(ctx: Context, key: String, def: String = ""): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, def) ?: def

    fun put(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }
}
