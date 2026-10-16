package com.brawldraft.app

/**
 * Chat providers the app can be pointed at with one tap.
 *
 * Every one of these speaks the OpenAI chat completions shape, which is all this app needs, so
 * switching provider is just a base URL and a model name. Speech to text is deliberately left
 * alone: Whisper on Groq's free tier is generous and the owner is happy with it.
 *
 * An empty [chatModel] means the model IDs move too fast to preset safely: the app clears the model
 * fields and the owner pastes the current one. A wrong preset produces a 404 that looks like a bug
 * in this app, which has already happened once (llama-3.3-70b-versatile was retired by Groq).
 *
 * Verified against the providers' own documentation on 2026-10-16.
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
            "minute and 200K per day. With the knowledge block switched on that is roughly " +
            "one turn a minute, so this is best as the fallback."
    )

    val GEMINI = Provider(
        label = "Google Gemini (free tier)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        chatModel = "gemini-3.8-flash",
        cleanModel = "gemini-3.8-flash",
        note = "Recommended for the knowledge block: by far the highest tokens per minute of the " +
            "free options and a very large context, with no card. Daily request caps vary per " +
            "model and are only shown in AI Studio. Free tier data is used to improve Google " +
            "products. Key: aistudio.google.com/apikey"
    )

    val CEREBRAS = Provider(
        label = "Cerebras (30 day trial)",
        baseUrl = "https://api.cerebras.ai/v1",
        chatModel = "gpt-oss-120b",
        cleanModel = "qwen-3.8-27b",
        note = "Same models as Groq but 30K tokens per minute, 1M per day and about 3000 " +
            "tokens/s. Not permanently free: 5 dollars of credit that expires after 30 days and " +
            "needs a verified payment method."
    )

    val CLAUDE = Provider(
        label = "Anthropic Claude (paid)",
        baseUrl = "https://api.anthropic.com/v1",
        chatModel = "claude-sonnet-4-5",
        cleanModel = "claude-sonnet-4-5",
        note = "Uses Anthropic's OpenAI compatibility layer, so the same request shape works. " +
            "Model IDs move fast: if this one returns 404, copy the current ID from " +
            "platform.claude.com/docs."
    )

    val OPENAI = Provider(
        label = "OpenAI (paid)",
        baseUrl = "https://api.openai.com/v1",
        chatModel = "",
        cleanModel = "",
        note = "Paste the current model ID from platform.openai.com/docs/models into both model " +
            "fields. Left blank on purpose: the names change too often to preset."
    )

    val DEEPSEEK = Provider(
        label = "DeepSeek (very cheap)",
        baseUrl = "https://api.deepseek.com/v1",
        chatModel = "deepseek-flash",
        cleanModel = "deepseek-flash",
        note = "Cheap and OpenAI compatible. Note that the old deepseek-chat and deepseek-reasoner " +
            "names were retired, so do not reuse them."
    )

    val XAI = Provider(
        label = "xAI Grok (paid)",
        baseUrl = "https://api.x.ai/v1",
        chatModel = "",
        cleanModel = "",
        note = "Paste the current Grok model ID from docs.x.ai into both model fields."
    )

    val OPENROUTER = Provider(
        label = "OpenRouter (free and paid)",
        baseUrl = "https://openrouter.ai/api/v1",
        chatModel = "",
        cleanModel = "",
        note = "One key, hundreds of models. Free models are capped at 20 requests per minute and " +
            "50 per day, rising to 1000 per day after a one-time 10 dollars of credit. Free model " +
            "IDs change constantly, so copy one from openrouter.ai/models and avoid anything " +
            "labelled as requiring prompt logging if you care about that."
    )

    val CUSTOM = Provider(
        label = "Custom (any OpenAI-compatible server)",
        baseUrl = "",
        chatModel = "",
        cleanModel = "",
        note = "For anything not listed: Ollama, LM Studio, vLLM, LiteLLM, a company gateway, a " +
            "local server. Type its base URL and a model name in the fields above."
    )

    val ALL: List<Provider> = listOf(
        GEMINI, GROQ, CEREBRAS, CLAUDE, OPENAI, DEEPSEEK, XAI, OPENROUTER, CUSTOM
    )
}
