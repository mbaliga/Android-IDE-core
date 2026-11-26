package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException
import dev.fonebrew.domain.gitcore.transport.TransportErrorKind
import dev.fonebrew.domain.gitcore.transport.TransportException
import java.io.IOException

/**
 * Every way a network git operation can fail, as data a UI can branch on. [explanation] is the
 * plain-language sentence to show; it never contains a credential, a stack trace or a URL userinfo.
 * The server's own words (when it gave a reason) are quoted in [remoteReason], not paraphrased.
 */
sealed class GitFailure {
    abstract val explanation: String
    open val remoteReason: String? get() = null

    data class AuthRequired(val host: String) : GitFailure() {
        override val explanation get() = "$host asked for a sign-in and none is saved. Add an access token for $host and try again."
    }
    data class AuthFailed(val host: String) : GitFailure() {
        override val explanation get() = "$host refused the saved sign-in. The token may be wrong, expired, or lack permission for this repository."
    }
    data class NotFound(val where: String) : GitFailure() {
        override val explanation get() = "The repository was not found at $where. Check the address, and that your account can see it (private repositories answer 'not found' to the wrong sign-in)."
    }
    /** Refused locally or by the server because the update would discard remote commits. */
    data class NonFastForward(val ref: String, override val remoteReason: String? = null) : GitFailure() {
        override val explanation get() = "The remote $ref has commits you do not have, so the push was refused. Fetch and integrate them first." +
            (remoteReason?.let { " The server said: \"$it\"." } ?: "")
    }
    /** `force-with-lease`: the remote ref is not what the caller expected. */
    data class LeaseStale(val ref: String) : GitFailure() {
        override val explanation get() = "The remote $ref changed since you last looked at it, so the forced push was refused. Fetch, review, and try again."
    }
    data class Rejected(val ref: String, override val remoteReason: String) : GitFailure() {
        override val explanation get() = "The server refused to update $ref: \"$remoteReason\"."
    }
    data class ProtocolError(val detail: String) : GitFailure() {
        override val explanation get() = "The server did not follow the git protocol: $detail."
    }
    /** Hostile or damaged data: nothing was changed locally (no ref moved). */
    data class Integrity(val detail: String) : GitFailure() {
        override val explanation get() = "The data the server sent was incomplete or corrupt ($detail). Nothing in your repository was changed."
    }
    data class RemoteError(override val remoteReason: String) : GitFailure() {
        override val explanation get() = "The server reported a problem: \"$remoteReason\"."
    }
    data class Network(val detail: String) : GitFailure() {
        override val explanation get() = "The connection failed ($detail). Check the network and try again."
    }
    data class RedirectRefused(val detail: String) : GitFailure() {
        override val explanation get() = "The server tried to send the request somewhere unsafe: $detail. No sign-in was sent."
    }
    data class HostKeyRejected(val host: String) : GitFailure() {
        override val explanation get() = "The SSH identity of $host is not trusted (unknown or changed). Verify its fingerprint before connecting."
    }
    data class Unsupported(val detail: String) : GitFailure() {
        override val explanation get() = "Not supported: $detail."
    }
    /** Uncommitted work would be overwritten; nothing was changed. */
    data class LocalChanges(val paths: List<String>) : GitFailure() {
        override val explanation get() = "Local changes would be overwritten: ${paths.take(5).joinToString(", ")}${if (paths.size > 5) " and ${paths.size - 5} more" else ""}. Commit or discard them first."
    }
    data class InvalidRequest(val detail: String) : GitFailure() {
        override val explanation get() = detail.replaceFirstChar { it.uppercase() } + "."
    }
    data class LocalRepository(val kind: GitErrorKind, val detail: String) : GitFailure() {
        override val explanation get() = "The local repository could not complete the operation: $detail."
    }
    object Cancelled : GitFailure() {
        override val explanation get() = "Cancelled."
    }
}

/** What every [GitClient] operation throws. The cause is kept for diagnostics but is never needed for display. */
class GitClientException(val failure: GitFailure, cause: Throwable? = null) : RuntimeException(failure.explanation, cause)

internal fun Throwable.toGitFailure(): GitFailure = when (this) {
    is GitClientException -> failure
    is TransportException -> when (kind) {
        TransportErrorKind.AUTH_REQUIRED -> GitFailure.AuthRequired(host ?: "the server")
        TransportErrorKind.AUTH_FAILED -> GitFailure.AuthFailed(host ?: "the server")
        TransportErrorKind.NOT_FOUND -> GitFailure.NotFound(host ?: "that address")
        TransportErrorKind.PROTOCOL -> GitFailure.ProtocolError(message ?: "malformed answer")
        TransportErrorKind.INTEGRITY -> GitFailure.Integrity(message ?: "damaged data")
        TransportErrorKind.REMOTE_ERROR -> GitFailure.RemoteError((message ?: "").removePrefix("the server reported: "))
        TransportErrorKind.NETWORK -> GitFailure.Network(message ?: "network error")
        TransportErrorKind.REDIRECT_REFUSED -> GitFailure.RedirectRefused(message ?: "redirect refused")
        TransportErrorKind.HOST_KEY_REJECTED -> GitFailure.HostKeyRejected(host ?: "the server")
        TransportErrorKind.CANCELLED -> GitFailure.Cancelled
        TransportErrorKind.UNSUPPORTED -> GitFailure.Unsupported(message ?: "unsupported")
    }
    is GitException -> when (kind) {
        GitErrorKind.DIRTY_TREE -> GitFailure.LocalChanges(paths)
        GitErrorKind.INVALID_ARGUMENT -> GitFailure.InvalidRequest(message ?: "invalid request")
        else -> GitFailure.LocalRepository(kind, message ?: "failed")
    }
    is kotlinx.coroutines.CancellationException -> GitFailure.Cancelled
    is IOException -> GitFailure.Network(javaClass.simpleName)
    else -> GitFailure.LocalRepository(GitErrorKind.IO, "unexpected ${javaClass.simpleName}")
}
