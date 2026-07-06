package dev.aarso.data

import android.content.Context
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.ProviderKind
import dev.aarso.security.KeystoreSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists the user's cloud provider configurations and their API keys. Configs
 * live as JSON in private SharedPreferences; keys are encrypted at rest via
 * [KeystoreSecret] and stored under a per-provider entry. Nothing leaves the
 * device except, later, a request to the provider the user explicitly invokes.
 */
class ProviderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.providers", Context.MODE_PRIVATE)

    private val _providers = MutableStateFlow(load())
    val providers: StateFlow<List<CloudProvider>> = _providers.asStateFlow()

    fun upsert(provider: CloudProvider, apiKey: String?) {
        val list = _providers.value.filter { it.id != provider.id } + provider
        persist(list)
        if (apiKey != null && apiKey.isNotBlank()) {
            prefs.edit().putString(keyEntry(provider.id), KeystoreSecret.encrypt(apiKey)).apply()
        }
        _providers.value = list.sortedBy { it.displayName.lowercase() }
    }

    fun remove(id: String) {
        prefs.edit().remove(keyEntry(id)).apply()
        val list = _providers.value.filter { it.id != id }
        persist(list)
        _providers.value = list
    }

    fun apiKey(id: String): String? =
        prefs.getString(keyEntry(id), null)?.let {
            runCatching { KeystoreSecret.decrypt(it) }.getOrNull()
        }

    fun hasApiKey(id: String): Boolean = prefs.contains(keyEntry(id))

    fun byId(id: String): CloudProvider? = _providers.value.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    private fun keyEntry(id: String) = "key_$id"

    private fun persist(list: List<CloudProvider>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("displayName", p.displayName)
                    .put("kind", p.kind.name)
                    .put("baseUrl", p.baseUrl)
                    .put("model", p.model)
                    .put("contextWindow", p.contextWindow),
            )
        }
        prefs.edit().putString("configs", arr.toString()).apply()
    }

    private fun load(): List<CloudProvider> {
        val raw = prefs.getString("configs", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CloudProvider>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += CloudProvider(
                id = o.getString("id"),
                displayName = o.getString("displayName"),
                kind = runCatching { ProviderKind.valueOf(o.getString("kind")) }
                    .getOrDefault(ProviderKind.OPENAI_COMPATIBLE),
                baseUrl = o.getString("baseUrl"),
                model = o.getString("model"),
                contextWindow = o.optInt("contextWindow", 8192),
            )
        }
        return out.sortedBy { it.displayName.lowercase() }
    }
}
