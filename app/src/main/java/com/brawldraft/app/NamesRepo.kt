package com.brawldraft.app

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Reference names: brawlers, ranked maps, game terms and observed speech to text mistakes.
 *
 * The single source of truth is `app/src/main/assets/brawl_data.json`, which is also published on
 * GitHub so it can be edited without rebuilding the app. The app prefers a copy cached from
 * [Prefs.KEY_NAMES_URL] and silently falls back to the bundled asset, so it still works offline
 * and with an empty or unreachable URL.
 */
object NamesRepo {

    private const val TAG = "BrawlDraft"
    private const val ASSET = "brawl_data.json"

    @Volatile private var cached: JSONObject? = null

    fun data(ctx: Context): JSONObject {
        cached?.let { return it }
        val parsed = parse(Prefs.get(ctx, Prefs.KEY_CACHED_NAMES)) ?: parse(bundled(ctx))
        val result = parsed ?: JSONObject()
        cached = result
        return result
    }

    private fun parse(text: String): JSONObject? {
        if (text.isBlank()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "names parse failed: ${e.message}")
            null
        }
    }

    private fun bundled(ctx: Context): String = try {
        ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e(TAG, "bundled $ASSET missing", e)
        ""
    }

    /** Pulls the published file and caches it. Silent on failure so the bundled copy keeps working. */
    fun refresh(ctx: Context) {
        val url = Prefs.get(ctx, Prefs.KEY_NAMES_URL, Prefs.DEFAULT_NAMES_URL)
        if (url.isBlank()) return
        try {
            val res = Http.send(url, timeoutMs = 20000)
            if (res.code in 200..299 && res.body.contains("\"brawlers\"")) {
                Prefs.put(ctx, Prefs.KEY_CACHED_NAMES, res.body)
                cached = parse(res.body)
                Log.i(TAG, "names refreshed, ${res.body.length} chars")
            } else {
                Log.w(TAG, "names refresh returned ${res.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "names refresh failed: ${e.message}")
        }
    }

    private fun list(ctx: Context, key: String): List<String> {
        val arr = data(ctx).optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            arr.optString(it).takeIf { name -> name.isNotBlank() }
        }
    }

    fun brawlers(ctx: Context): List<String> = list(ctx, "brawlers")

    fun modes(ctx: Context): List<String> {
        val obj = data(ctx).optJSONObject("ranked_modes") ?: return emptyList()
        return obj.keys().asSequence().toList()
    }

    /** Every ranked map, de-duplicated across modes. */
    fun maps(ctx: Context): List<String> {
        val obj = data(ctx).optJSONObject("ranked_modes") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (mode in obj.keys()) {
            val arr = obj.optJSONArray(mode) ?: continue
            for (i in 0 until arr.length()) {
                val name = arr.optString(i)
                if (name.isNotBlank()) out.add(name)
            }
        }
        return out.toList()
    }

    /** Whisper prompt. The provider caps this parameter at 224 tokens, so keep it lean. */
    fun hint(ctx: Context): String =
        "Brawl Stars ranked draft talk. Brawler names: " + brawlers(ctx).joinToString(", ") + "."

    /** Appended to the coach system prompt so it can repair names the cleaner still missed. */
    fun addendum(ctx: Context): String {
        val examples = observed(ctx)
        val base = "KNOWN BRAWLER NAMES: " + brawlers(ctx).joinToString(", ") + ". " +
            "The player's words come from speech to text and brawler and map names are often " +
            "mangled. Silently map anything close to one of those names onto the real name and " +
            "never invent a brawler that is not in the list"
        return if (examples.isEmpty()) "$base." else "$base, for example $examples."
    }

    private fun observed(ctx: Context): String {
        val obj = data(ctx).optJSONObject("observed_asr_mistakes") ?: return ""
        return obj.keys().asSequence().take(12)
            .joinToString(", ") { "\"$it\" means ${obj.optString(it)}" }
    }

    /** Context block for the cleaner pass: every name it may need to repair. */
    fun contextFor(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("BRAWLERS: ").append(brawlers(ctx).joinToString(", ")).append('\n')
        val obj = data(ctx).optJSONObject("ranked_modes")
        if (obj != null) {
            for (mode in obj.keys()) {
                val arr = obj.optJSONArray(mode) ?: continue
                val names = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                if (names.isNotEmpty()) {
                    sb.append(mode.uppercase()).append(" MAPS: ").append(names.joinToString(", ")).append('\n')
                }
            }
        }
        val terms = list(ctx, "game_terms")
        if (terms.isNotEmpty()) sb.append("GAME TERMS: ").append(terms.joinToString(", ")).append('\n')
        val mistakes = data(ctx).optJSONObject("observed_asr_mistakes")
        if (mistakes != null && mistakes.length() > 0) {
            sb.append("OBSERVED SPEECH TO TEXT MISTAKES: ")
            sb.append(mistakes.keys().asSequence().joinToString(", ") { "$it -> ${mistakes.optString(it)}" })
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}
