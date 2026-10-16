package com.brawldraft.app

import android.content.Context
import java.io.IOException

/**
 * Runs a request down the configured provider list.
 *
 * Every request starts again from the first endpoint. A provider that failed last time is not
 * remembered as failed, so the moment the main one is healthy again it takes over automatically and
 * the fallback is not used. Each failure is logged with the reason, which is what the Logs page
 * shows.
 */
object LlmChain {

    /** Returns the coach's reply. Throws only if every endpoint failed. */
    fun chat(
        ctx: Context,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userText: String
    ): String {
        val chain = ProviderStore.chain(ctx)
        if (chain.isEmpty()) {
            throw IOException(
                "No usable chat provider. Open the Fallback page (top bar) and add a base URL, " +
                    "API key and model."
            )
        }
        var last: Exception? = null
        for (index in chain.indices) {
            val e = chain[index]
            val started = System.currentTimeMillis()
            try {
                val reply = LlmClient.chat(e.baseUrl, e.apiKey, e.chatModel, systemPrompt, history, userText)
                val ms = System.currentTimeMillis() - started
                if (index == 0) {
                    LogStore.log(ctx, "coach answered by ${e.label} [${e.chatModel}] in ${ms}ms")
                } else {
                    LogStore.log(
                        ctx,
                        "coach answered by FALLBACK ${index + 1} of ${chain.size}: ${e.label} " +
                            "[${e.chatModel}] in ${ms}ms"
                    )
                }
                return reply
            } catch (ex: Exception) {
                last = ex
                LogStore.warn(ctx, "provider ${index + 1} of ${chain.size} ${e.label} failed: ${ex.message}")
            }
        }
        throw IOException("Every configured provider failed. Last error: ${last?.message ?: "unknown"}")
    }

    /**
     * Name repair. Returns an empty string if nothing answered, and the caller keeps the raw
     * transcript, so a broken provider can never block a reply.
     */
    fun clean(ctx: Context, namesContext: String, transcript: String): String {
        val chain = ProviderStore.chain(ctx)
        for (index in chain.indices) {
            val e = chain[index]
            val model = e.cleanModel.ifBlank { e.chatModel }
            val started = System.currentTimeMillis()
            try {
                val cleaned = LlmClient.clean(e.baseUrl, e.apiKey, model, namesContext, transcript)
                if (cleaned.isNotEmpty()) {
                    val ms = System.currentTimeMillis() - started
                    val via = if (index == 0) e.label else "FALLBACK ${index + 1}: ${e.label}"
                    LogStore.log(ctx, "name check by $via [$model] in ${ms}ms")
                    return cleaned
                }
                LogStore.warn(ctx, "name check on ${e.label} returned nothing, trying the next provider")
            } catch (ex: Exception) {
                LogStore.warn(ctx, "name check on ${e.label} failed: ${ex.message}")
            }
        }
        return ""
    }
}
