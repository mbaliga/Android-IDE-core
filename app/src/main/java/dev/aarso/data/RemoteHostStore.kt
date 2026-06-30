package dev.aarso.data

import android.content.Context
import dev.aarso.domain.remote.HostKey
import dev.aarso.domain.remote.KnownHosts
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.security.KeystoreSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists SSH remotes (the remote-exec spine, Sprint 1 data layer): the [RemoteHost] configs,
 * the pinned host keys ([KnownHosts] — the trust-on-first-use record), and the secrets (SSH
 * private keys / passwords) **encrypted at rest** via [KeystoreSecret], keyed by a secret id.
 * Mirrors [GitHostStore]. A secret is never persisted in plaintext, never logged, and handed
 * only to [dev.aarso.data.remote.SshjTransport] for the host it belongs to.
 */
class RemoteHostStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.remote", Context.MODE_PRIVATE)

    private val _hosts = MutableStateFlow(loadHosts())
    val hosts: StateFlow<List<RemoteHost>> = _hosts.asStateFlow()

    private val _known = MutableStateFlow(loadKnownHosts())
    /** The current trust record; [dev.aarso.domain.remote.RemoteSessionDriver] classifies against it. */
    val knownHosts: StateFlow<KnownHosts> = _known.asStateFlow()

    // ---- hosts ----

    fun upsert(host: RemoteHost) {
        val list = _hosts.value.filter { it.alias != host.alias } + host
        persistHosts(list)
        _hosts.value = list.sortedBy { it.alias.lowercase() }
    }

    fun remove(alias: String) {
        hostSecret(alias)?.let { removeSecret(it.id) }
        prefs.edit().remove(hostSecKey(alias)).apply()
        val list = _hosts.value.filter { it.alias != alias }
        persistHosts(list)
        _hosts.value = list
    }

    /** A host's auth reference: the encrypted-secret id + whether it's a key (vs password). */
    data class SecretRef(val id: String, val isKey: Boolean)

    /** Save a host's secret (PEM key or password), encrypted, and index it by alias. */
    fun setHostSecret(alias: String, plaintext: String, isKey: Boolean) {
        val id = putSecret(plaintext)
        prefs.edit().putString(hostSecKey(alias), "${if (isKey) "key" else "pwd"}:$id").apply()
    }

    /** The host's saved auth reference, or null (→ agent/none). */
    fun hostSecret(alias: String): SecretRef? {
        val raw = prefs.getString(hostSecKey(alias), null) ?: return null
        val kind = raw.substringBefore(":")
        val id = raw.substringAfter(":", "")
        if (id.isEmpty()) return null
        return SecretRef(id, kind == "key")
    }

    private fun hostSecKey(alias: String) = "hostsec_$alias"

    // ---- trust (known hosts) ----

    fun pin(endpoint: String, key: HostKey) {
        val next = _known.value.pin(endpoint, key)
        _known.value = next
        persistKnown(next)
    }

    fun unpin(endpoint: String) {
        val next = _known.value.unpin(endpoint)
        _known.value = next
        persistKnown(next)
    }

    // ---- secrets (SSH keys / passwords) ----

    /** Store a secret (PEM private key or password) encrypted; returns its generated id. */
    fun putSecret(plaintext: String): String {
        val id = "sec_" + UUID.randomUUID().toString()
        prefs.edit().putString(id, KeystoreSecret.encrypt(plaintext)).apply()
        return id
    }

    /** Resolve a secret by id (decrypted), or null. This is the provider [SshjTransport] uses. */
    fun secret(id: String): String? =
        prefs.getString(id, null)?.let { runCatching { KeystoreSecret.decrypt(it) }.getOrNull() }

    fun removeSecret(id: String) { prefs.edit().remove(id).apply() }

    fun newAlias(base: String = "host"): String =
        if (_hosts.value.none { it.alias == base }) base else "$base-${_hosts.value.size + 1}"

    // ---- persistence ----

    private fun persistHosts(list: List<RemoteHost>) {
        val arr = JSONArray()
        for (h in list) arr.put(
            JSONObject().put("alias", h.alias).put("hostname", h.hostname)
                .put("port", h.port).put("username", h.username),
        )
        prefs.edit().putString(KEY_HOSTS, arr.toString()).apply()
    }

    private fun loadHosts(): List<RemoteHost> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                RemoteHost(
                    alias = o.getString("alias"),
                    hostname = o.getString("hostname"),
                    port = o.optInt("port", 22),
                    username = o.getString("username"),
                )
            }.getOrNull()
        }
    }

    private fun persistKnown(known: KnownHosts) {
        val arr = JSONArray()
        for (p in known.all()) arr.put(
            JSONObject().put("endpoint", p.endpoint)
                .put("alg", p.key.algorithm).put("fp", p.key.fingerprint),
        )
        prefs.edit().putString(KEY_KNOWN, arr.toString()).apply()
    }

    private fun loadKnownHosts(): KnownHosts {
        val raw = prefs.getString(KEY_KNOWN, null) ?: return KnownHosts()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return KnownHosts()
        var kh = KnownHosts()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            kh = kh.pin(o.getString("endpoint"), HostKey(o.getString("alg"), o.getString("fp")))
        }
        return kh
    }

    private companion object {
        const val KEY_HOSTS = "hosts"
        const val KEY_KNOWN = "known_hosts"
    }
}
