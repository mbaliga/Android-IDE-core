package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.domain.object3d.Object3dCloudProvider
import dev.fonebrew.security.KeystoreSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One configured 3D cloud-generation provider (docs/design/objects-3d.md §1/§5) — the 3D tab's
 * analogue of [dev.fonebrew.domain.image.ImageProvider]. [kind] selects which of the two landed
 * adapters ([dev.fonebrew.inference.object3d.CloudObject3dEngineFactory]) a job dispatches
 * against; [baseUrl] is §1's "custom base URL escape hatch" (defaults to [kind]'s own API host;
 * see [dev.fonebrew.inference.object3d.CloudObject3dEngine]'s KDoc for what it can and can't
 * override). Every entry here is, by construction, a watched object (binding rule 2) —
 * SettingsRoom always labels this list "Cloud — watched", identically to
 * [dev.fonebrew.domain.cloud.CloudProvider]/[dev.fonebrew.domain.image.ImageProvider].
 */
data class Object3dProviderConfig(
    val id: String,
    val displayName: String,
    val kind: Object3dCloudProvider,
    val baseUrl: String,
)

/** Persists 3D cloud-provider configs + encrypted API keys — same shape as
 *  [dev.fonebrew.data.ImageProviderStore] (rule 5: keys via [KeystoreSecret], never plain). */
class Object3dProviderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.object3d.providers", Context.MODE_PRIVATE)

    private val _providers = MutableStateFlow(load())
    val providers: StateFlow<List<Object3dProviderConfig>> = _providers.asStateFlow()

    fun upsert(provider: Object3dProviderConfig, apiKey: String?) {
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

    private fun persist(list: List<Object3dProviderConfig>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("displayName", p.displayName)
                    .put("kind", p.kind.name).put("baseUrl", p.baseUrl),
            )
        }
        prefs.edit().putString("configs", arr.toString()).apply()
    }

    private fun load(): List<Object3dProviderConfig> {
        val raw = prefs.getString("configs", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Object3dProviderConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kind = runCatching { Object3dCloudProvider.valueOf(o.getString("kind")) }
                .getOrDefault(Object3dCloudProvider.MESHY)
            out += Object3dProviderConfig(
                id = o.getString("id"),
                displayName = o.getString("displayName"),
                kind = kind,
                baseUrl = o.optString("baseUrl").ifBlank { kind.apiBaseUrl },
            )
        }
        return out.sortedBy { it.displayName.lowercase() }
    }
}
