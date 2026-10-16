package com.brawldraft.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 30000
    ): Pair<Int, String> {
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
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

object WhisperClient {
    private const val BASE = "https://api.whisper-api.com"

    /** Uploads the audio, then polls /status/{task_id} until the transcript is ready. */
    fun transcribe(apiKey: String, modelSize: String, audio: File): String {
        val boundary = "----BrawlDraft" + System.currentTimeMillis()
        val out = ByteArrayOutputStream()

        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("language", "en")
        field("format", "text")
        field("model_size", modelSize)
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.name}\"\r\n" +
                "Content-Type: audio/mp4\r\n\r\n").toByteArray()
        )
        out.write(audio.readBytes())
        out.write("\r\n--$boundary--\r\n".toByteArray())

        val (code, body) = Http.request(
            "$BASE/transcribe",
            method = "POST",
            headers = mapOf("X-API-Key" to apiKey),
            body = out.toByteArray(),
            contentType = "multipart/form-data; boundary=$boundary",
            timeoutMs = 60000
        )
        if (code !in 200..299) throw IOException("Whisper upload failed ($code): ${body.take(200)}")
        val taskId = JSONObject(body).getString("task_id")

        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val (sc, sb) = Http.request("$BASE/status/$taskId", headers = mapOf("X-API-Key" to apiKey))
            if (sc !in 200..299) throw IOException("Whisper status failed ($sc): ${sb.take(200)}")
            val json = JSONObject(sb)
            when (json.optString("status")) {
                "completed" -> return json.optString("result", "").trim()
                "failed", "error" -> throw IOException("Whisper failed: ${sb.take(200)}")
            }
        }
        throw IOException("Whisper timed out")
    }
}

object LlmClient {
    /** OpenAI-compatible chat completion. Works with Groq, OpenRouter, Gemini's OpenAI endpoint, etc. */
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

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("max_tokens", 300)

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val (code, resp) = Http.request(
            url,
            method = "POST",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            timeoutMs = 60000
        )
        if (code !in 200..299) throw IOException("LLM failed ($code): ${resp.take(300)}")
        return JSONObject(resp).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim()
    }
}
