package com.brawldraft.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One chat endpoint. The app tries them in order, every request, and returns to the first one as
 * soon as it answers again - so a fallback is a per-request decision, not a sticky switch.
 */
data class Endpoint(
    var name: String = "",
    var baseUrl: String = "",
    var apiKey: String = "",
    var chatModel: String = "",
    var cleanModel: String = "",
    var enabled: Boolean = true
) {
    val label: String get() = if (name.isBlank()) baseUrl else name
    fun isValid(): Boolean = baseUrl.isNotBlank() && apiKey.isNotBlank() && chatModel.isNotBlank()
}

/**
 * The ordered provider list, persisted as JSON.
 *
 * Also the only place that knows how to read the old single-provider settings: on first run the
 * legacy llm_url / llm_key / llm_model / clean_model values are migrated into a one entry list so an
 * existing install keeps working and nobody has to retype a key.
 */
object ProviderStore {

    private const val KEY = "providers_json"
    private const val F_NAME = "name"
    private const val F_URL = "baseUrl"
    private const val F_KEY = "apiKey"
    private const val F_MODEL = "chatModel"
    private const val F_CLEAN = "cleanModel"
    private const val F_ENABLED = "enabled"

    fun load(ctx: Context): MutableList<Endpoint> {
        val raw = Prefs.get(ctx, Prefs.KEY_PROVIDERS)
        if (raw.isBlank()) {
            val migrated = mutableListOf(legacy(ctx))
            save(ctx, migrated)
            return migrated
        }
        val list = mutableListOf<Endpoint>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    Endpoint(
                        name = o.optString(F_NAME),
                        baseUrl = o.optString(F_URL),
                        apiKey = o.optString(F_KEY),
                        chatModel = o.optString(F_MODEL),
                        cleanModel = o.optString(F_CLEAN),
                        enabled = o.optBoolean(F_ENABLED, true)
                    )
                )
            }
        } catch (e: Exception) {
            LogStore.warn(ctx, "provider list unreadable, starting from the legacy settings")
        }
        if (list.isEmpty()) list.add(legacy(ctx))
        return list
    }

    fun save(ctx: Context, list: List<Endpoint>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put(F_NAME, e.name)
                    .put(F_URL, e.baseUrl)
                    .put(F_KEY, e.apiKey)
                    .put(F_MODEL, e.chatModel)
                    .put(F_CLEAN, e.cleanModel)
                    .put(F_ENABLED, e.enabled)
            )
        }
        Prefs.put(ctx, Prefs.KEY_PROVIDERS, arr.toString())
    }

    /** Enabled endpoints in order, which is the fallback chain. */
    fun chain(ctx: Context): List<Endpoint> =
        load(ctx).filter { it.enabled && it.isValid() }

    fun primaryLabel(ctx: Context): String {
        val first = load(ctx).firstOrNull() ?: return "none"
        return first.name.ifBlank { first.chatModel.ifBlank { first.baseUrl } }
    }

    private fun legacy(ctx: Context): Endpoint = Endpoint(
        name = hostOf(Prefs.get(ctx, Prefs.KEY_LLM_URL, Prefs.DEFAULT_LLM_URL)),
        baseUrl = Prefs.get(ctx, Prefs.KEY_LLM_URL, Prefs.DEFAULT_LLM_URL),
        apiKey = Prefs.get(ctx, Prefs.KEY_LLM_KEY),
        chatModel = Prefs.get(ctx, Prefs.KEY_LLM_MODEL, Prefs.DEFAULT_LLM_MODEL),
        cleanModel = Prefs.get(ctx, Prefs.KEY_CLEAN_MODEL, Prefs.DEFAULT_CLEAN_MODEL),
        enabled = true
    )

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/')
}
