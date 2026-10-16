package com.brawldraft.app

/**
 * Chat providers the app can be pointed at with one tap.
 *
 * Every one of these speaks the OpenAI chat completions shape, which is all this app needs, so
 * switching provider is just a base URL and a model name. Speech to text is deliberately left
 * alone: Whisper on Groq's free tier is generous and the owner is happy with it.
 *
 * Verified against the providers' own documentation on 2026-10-16. Model names do get retired
 * (Groq dropped llama-3.3-70b-versatile, which answered 404), so re-check before trusting a
 * preset that has not been used in a while.
 */
data class Provider(
    val label: String,
    val baseUrl: String,
    val chatModel: String,
    val cleanModel: String,
    val note: String
)

object Providers {

    val GROQ = Provider(
        label = "Groq (permanently free)",
        baseUrl = "https://api.groq.com/openai/v1",
        chatModel = "openai/gpt-oss-120b",
        cleanModel = "qwen/qwen3.8-27b",
        note = "Renews every day and needs no card, but the free tier allows only 8K tokens per " +
            "minute and 200K per day, which is what makes a large system prompt impossible. " +
            "Best fallback to keep working."
    )

    val GEMINI = Provider(
        label = "Google Gemini (free tier)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        chatModel = "gemini-3.8-flash",
        cleanModel = "gemini-3.8-flash",
        note = "Best fit for a big system prompt: by far the highest tokens per minute of the free " +
            "options and a very large context, with no card. Daily request caps vary per model and " +
            "are only shown in AI Studio. Free tier data is used to improve Google products. " +
            "Get a key at aistudio.google.com/apikey."
    )

    val CEREBRAS = Provider(
        label = "Cerebras (30 day trial)",
        baseUrl = "https://api.cerebras.ai/v1",
        chatModel = "gpt-oss-120b",
        cleanModel = "qwen-3.8-27b",
        note = "Same model family as Groq but 30K tokens per minute, 1M per day and about 3000 " +
            "tokens/s. Not permanently free: 5 dollars of credit that expires after 30 days and " +
            "needs a verified payment method."
    )

    val ALL: List<Provider> = listOf(GROQ, GEMINI, CEREBRAS)
}
