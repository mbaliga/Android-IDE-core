package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.remote.KnownHosts
import dev.fonebrew.domain.remote.Trust

/**
 * An SSH private key handed over by the (later) key-management layer. Redacted in [toString]; never
 * logged and never placed in an exception message by this engine.
 */
class SshKeyMaterial(private val pem: String, private val passphraseText: String? = null) {
    fun privateKeyPem(): String = pem
    fun passphrase(): String? = passphraseText
    override fun toString() = "SshKeyMaterial(<redacted>)"
}

/** The SSH identity PORT: the key to use for [user]@[host]:[port], or null when none is stored. */
fun interface SshKeyProvider {
    fun keyFor(host: String, port: Int, user: String): SshKeyMaterial?

    companion object { val NONE = SshKeyProvider { _, _, _ -> null } }
}

/**
 * Decides whether to proceed with the presented host key. Reuses the existing trust vocabulary
 * ([Trust.Vetted] / [Trust.Unknown] / [Trust.Changed]): a policy may ask the user. The default,
 * [vettedOnly], accepts nothing that has not already been pinned.
 */
fun interface HostKeyPolicy {
    fun accept(endpoint: String, trust: Trust): Boolean

    companion object {
        fun vettedOnly(known: () -> KnownHosts) = HostKeyPolicy { _, t -> t is Trust.Vetted }
    }
}
