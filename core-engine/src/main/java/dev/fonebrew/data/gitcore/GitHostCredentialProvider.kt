package dev.fonebrew.data.gitcore

import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.gitcore.transport.Credential
import dev.fonebrew.domain.gitcore.transport.CredentialProvider
import dev.fonebrew.domain.gitcore.transport.CredentialRequest
import dev.fonebrew.domain.gitcore.transport.GitHostFlavor

/**
 * Adapts the existing Keystore-backed `GitHostStore` (tokens encrypted at rest, per host) to the
 * engine's credential port. Takes lambdas rather than the store so it is JVM-testable and the
 * store stays the only owner of token bytes. A token is returned ONLY for the host its
 * [GitHost] entry belongs to: the lookup key is the request host, never a fallback.
 */
class GitHostCredentialProvider(
    private val hosts: () -> List<GitHost>,
    private val tokenOf: (hostId: String) -> String?,
) : CredentialProvider {

    override fun credentialFor(request: CredentialRequest): Credential? {
        val h = hosts().firstOrNull { hostOf(it).equals(request.host, ignoreCase = true) } ?: return null
        val token = tokenOf(h.id)?.takeIf { it.isNotBlank() } ?: return null
        val flavor = when (h.kind) {
            GitHostKind.GITHUB -> GitHostFlavor.GITHUB
            GitHostKind.GITEA -> GitHostFlavor.fromHost(request.host).takeIf { it != GitHostFlavor.OTHER } ?: GitHostFlavor.GITEA
        }
        return Credential(flavor.tokenUsername(request.usernameHint ?: h.owner.takeIf { it.isNotBlank() }), token)
    }

    private fun hostOf(h: GitHost): String? = when (h.kind) {
        GitHostKind.GITHUB -> "github.com"
        GitHostKind.GITEA -> try { java.net.URI(h.baseUrl).host } catch (_: Exception) { null }
    }
}
