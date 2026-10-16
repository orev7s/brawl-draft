package com.brawldraft.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(val code: Int, val body: String, val headers: Map<String, String>)

object Http {

    fun send(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 30000
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = when {
                code in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                code == 304 -> ""
                else -> conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            val hdrs = HashMap<String, String>()
            for ((k, values) in conn.headerFields) {
                if (k != null && values != null && values.isNotEmpty()) hdrs[k.lowercase()] = values[0]
            }
            return HttpResponse(code, text, hdrs)
        } finally {
            conn.disconnect()
        }
    }

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 30000
    ): Pair<Int, String> {
        val res = send(url, method, headers, body, contentType, timeoutMs)
        return Pair(res.code, res.body)
    }

    /** Best-effort human readable message out of an OpenAI-style error body. */
    fun errorText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "no response body"
        return try {
            val json = JSONObject(trimmed)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: trimmed.take(300)
        } catch (e: Exception) {
            trimmed.take(300)
        }
    }
}

/**
 * Speech to text against any OpenAI-compatible transcription endpoint
 * (Groq, OpenAI, Deepgram's compatible layer, a local Whisper server, ...).
 */
object TranscribeClient {

    private const val MAX_UPLOAD_BYTES = 24L * 1024 * 1024

    fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        hint: String,
        audio: File
    ): String {
        if (baseUrl.isBlank()) throw IOException("No transcription base URL is set.")
        if (apiKey.isBlank()) throw IOException("No transcription API key is set. Add one in Brawl Draft.")
        if (!audio.exists()) throw IOException("The recording file is gone.")
        if (audio.length() > MAX_UPLOAD_BYTES) throw IOException("Recording is over the 25 MB upload limit.")

        val boundary = "----BrawlDraft" + System.currentTimeMillis()
        val out = ByteArrayOutputStream()

        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }

        field("model", model)
        field("language", "en")
        field("response_format", "text")
        field("temperature", "0")
        if (hint.isNotBlank()) field("prompt", hint)
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.name}\"\r\n" +
                "Content-Type: audio/mp4\r\n\r\n").toByteArray()
        )
        out.write(audio.readBytes())
        out.write("\r\n--$boundary--\r\n".toByteArray())

        val payload = out.toByteArray()
        val url = baseUrl.trimEnd('/') + "/audio/transcriptions"

        fun post(): HttpResponse = Http.send(
            url,
            method = "POST",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = payload,
            contentType = "multipart/form-data; boundary=$boundary",
            timeoutMs = 120000
        )

        var res = post()
        if (res.code == 429) {
            // Free tiers are rate limited. One short retry, then report it.
            val wait = (res.headers["retry-after"]?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 5)
            Thread.sleep(wait * 1000L)
            res = post()
        }
        if (res.code !in 200..299) {
            val hint429 = if (res.code == 429) " Rate limit hit - wait a few seconds." else ""
            throw IOException("Transcription failed (${res.code}): ${Http.errorText(res.body)}.$hint429")
        }

        val body = res.body.trim()
        if (body.startsWith("{")) {
            val json = JSONObject(body)
            json.optString("text").takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return body
    }
}

object LlmClient {

    /**
     * Generous on purpose: reasoning models spend hundreds of tokens thinking before writing a
     * three sentence answer, and 300 tokens produced a completely empty reply.
     */
    const val MAX_REPLY_TOKENS = 2048

    /** The cleaner pass only rewrites a short transcript, so it needs far less room. */
    const val MAX_CLEAN_TOKENS = 1024

    const val CLEAN_SYSTEM_PROMPT =
        "You fix speech-to-text mistakes in Brawl Stars voice chat transcripts. You are given a " +
            "list of known brawlers, map names and game terms, and a raw transcript. Rewrite the " +
            "transcript so that any word which is clearly a mangled version of a known name " +
            "becomes that exact name, including every entry of the observed mistakes list. " +
            "Output ONLY the corrected transcript: no commentary, no quotes, no markdown, no " +
            "explanation. Keep every other word, the word order and the tense exactly as they " +
            "are. Never add information, never answer the question and never change the meaning. " +
            "If nothing is clearly wrong, repeat the transcript unchanged."

    private class Reply(val content: String, val finish: String, val completion: Int, val reasoning: Int)

    private fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        maxTokens: Int,
        temperature: Double
    ): Reply {
        if (baseUrl.isBlank()) throw IOException("No LLM base URL is set.")
        if (apiKey.isBlank()) throw IOException("No LLM API key is set. Add one in Brawl Draft.")

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)

        val url = baseUrl.trimEnd('/') + "/chat/completions"

        fun post(): HttpResponse = Http.send(
            url,
            method = "POST",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            timeoutMs = 60000
        )

        var res = post()
        if (res.code == 429) {
            val wait = (res.headers["retry-after"]?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 5)
            Thread.sleep(wait * 1000L)
            res = post()
        }
        if (res.code !in 200..299) {
            throw IOException("LLM call failed (${res.code}, model $model): ${Http.errorText(res.body)}")
        }

        val root = JSONObject(res.body)
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val usage = root.optJSONObject("usage")
        return Reply(
            content = choice.getJSONObject("message").optString("content").trim(),
            finish = choice.optString("finish_reason"),
            completion = usage?.optInt("completion_tokens") ?: 0,
            reasoning = usage?.optJSONObject("completion_tokens_details")
                ?.optInt("reasoning_tokens") ?: 0
        )
    }

    /** OpenAI-compatible chat completion. Works with Groq, OpenRouter, OpenAI, etc. */
    fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userText: String
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val reply = complete(baseUrl, apiKey, model, messages, MAX_REPLY_TOKENS, 0.3)
        if (reply.content.isNotEmpty()) return reply.content

        // Reasoning models think inside the same token budget as the answer. With a tight budget
        // the whole allowance goes on reasoning and "content" comes back empty, which used to show
        // up in the app as a reply that never arrived. Say what actually happened instead.
        throw IOException(
            if (reply.finish == "length") {
                "The model spent all $MAX_REPLY_TOKENS tokens on thinking and never wrote an " +
                    "answer (${reply.reasoning} of them on reasoning). Use a smaller reasoning " +
                    "model or raise the reply budget."
            } else {
                "The model returned no text (finish_reason=${reply.finish}, tokens=${reply.completion})."
            }
        )
    }

    /**
     * Second pass: repairs brawler and map names the transcriber mangled, so the coach reasons
     * about real names. Returns the cleaned transcript, or an empty string if the model produced
     * nothing usable - the caller then keeps the raw transcript.
     */
    fun clean(
        baseUrl: String,
        apiKey: String,
        model: String,
        namesContext: String,
        transcript: String
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", CLEAN_SYSTEM_PROMPT))
        messages.put(
            JSONObject().put("role", "user")
                .put("content", "KNOWN NAMES\n$namesContext\n\nRAW TRANSCRIPT\n$transcript")
        )
        val reply = complete(baseUrl, apiKey, model, messages, MAX_CLEAN_TOKENS, 0.0)
        return reply.content.replace("\n", " ").trim()
    }
}
