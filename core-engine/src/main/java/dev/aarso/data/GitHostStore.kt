package dev.aarso.data

import android.content.Context
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.security.KeystoreSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists connected Git hosts (configs as JSON in private SharedPreferences) and
 * their access tokens (encrypted at rest via [KeystoreSecret], per host). Mirrors
 * [ProviderStore]. The token is never persisted in plaintext, never logged, and
 * sent only to its own host.
 */
class GitHostStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.git", Context.MODE_PRIVATE)

    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<GitHost>> = _hosts.asStateFlow()

    fun upsert(host: GitHost, token: String?) {
        val list = _hosts.value.filter { it.id != host.id } + host
        persist(list)
        if (!token.isNullOrBlank()) {
            prefs.edit().putString(tokenEntry(host.id), KeystoreSecret.encrypt(token)).apply()
        }
        _hosts.value = list.sortedBy { it.displayName.lowercase() }
    }

    fun remove(id: String) {
        prefs.edit().remove(tokenEntry(id)).apply()
        val list = _hosts.value.filter { it.id != id }
        persist(list)
        _hosts.value = list
    }

    fun token(id: String): String? =
        prefs.getString(tokenEntry(id), null)?.let { runCatching { KeystoreSecret.decrypt(it) }.getOrNull() }

    fun hasToken(id: String): Boolean = prefs.contains(tokenEntry(id))

    fun newId(): String = UUID.randomUUID().toString()

    private fun tokenEntry(id: String) = "token_$id"

    private fun persist(list: List<GitHost>) {
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject()
                    .put("id", h.id).put("displayName", h.displayName).put("kind", h.kind.name)
                    .put("baseUrl", h.baseUrl).put("owner", h.owner).put("repo", h.repo)
                    .put("branch", h.branch).put("authorName", h.authorName).put("authorEmail", h.authorEmail),
            )
        }
        prefs.edit().putString("configs", arr.toString()).apply()
    }

    private fun load(): List<GitHost> {
        val raw = prefs.getString("configs", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<GitHost>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += GitHost(
                id = o.getString("id"),
                displayName = o.getString("displayName"),
                kind = runCatching { GitHostKind.valueOf(o.getString("kind")) }.getOrDefault(GitHostKind.GITHUB),
                baseUrl = o.optString("baseUrl", ""),
                owner = o.getString("owner"),
                repo = o.getString("repo"),
                branch = o.optString("branch", "main"),
                authorName = o.optString("authorName", ""),
                authorEmail = o.optString("authorEmail", ""),
            )
        }
        return out.sortedBy { it.displayName.lowercase() }
    }
}
