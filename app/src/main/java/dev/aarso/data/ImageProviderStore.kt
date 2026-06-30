package dev.aarso.data

import android.content.Context
import dev.aarso.domain.image.ImageProvider
import dev.aarso.domain.image.ImageProviderKind
import dev.aarso.security.KeystoreSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists image-provider configs + encrypted API keys (mirrors ProviderStore). */
class ImageProviderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.image.providers", Context.MODE_PRIVATE)

    private val _providers = MutableStateFlow(load())
    val providers: StateFlow<List<ImageProvider>> = _providers.asStateFlow()

    fun upsert(provider: ImageProvider, apiKey: String?) {
        val list = _providers.value.filter { it.id != provider.id } + provider
        persist(list)
        if (!apiKey.isNullOrBlank()) {
            prefs.edit().putString("key_${provider.id}", KeystoreSecret.encrypt(apiKey)).apply()
        }
        _providers.value = list.sortedBy { it.displayName.lowercase() }
    }

    fun remove(id: String) {
        prefs.edit().remove("key_$id").apply()
        val list = _providers.value.filter { it.id != id }
        persist(list)
        _providers.value = list
    }

    fun apiKey(id: String): String? =
        prefs.getString("key_$id", null)?.let { runCatching { KeystoreSecret.decrypt(it) }.getOrNull() }

    fun hasApiKey(id: String): Boolean = prefs.contains("key_$id")

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(list: List<ImageProvider>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("displayName", p.displayName)
                    .put("kind", p.kind.name).put("baseUrl", p.baseUrl).put("model", p.model),
            )
        }
        prefs.edit().putString("configs", arr.toString()).apply()
    }

    private fun load(): List<ImageProvider> {
        val raw = prefs.getString("configs", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ImageProvider>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += ImageProvider(
                id = o.getString("id"),
                displayName = o.getString("displayName"),
                kind = runCatching { ImageProviderKind.valueOf(o.getString("kind")) }
                    .getOrDefault(ImageProviderKind.OPENAI_IMAGE),
                baseUrl = o.getString("baseUrl"),
                model = o.getString("model"),
            )
        }
        return out.sortedBy { it.displayName.lowercase() }
    }
}
