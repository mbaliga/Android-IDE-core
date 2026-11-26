package dev.fonebrew.domain.gitcore.transport

/** Why a network operation failed, as a closed set the facade maps to plain-language failures. */
enum class TransportErrorKind {
    /** The server asked for credentials and we sent none (none available, or the user declined). */
    AUTH_REQUIRED,
    /** Credentials were sent and refused. */
    AUTH_FAILED,
    /** 404, or the server said the repository does not exist / is not exported. */
    NOT_FOUND,
    /** The bytes on the wire do not follow the protocol (or exceed a ceiling). */
    PROTOCOL,
    /** The server reported an error itself (ERR packet, side-band channel 3, HTTP 5xx text). */
    REMOTE_ERROR,
    /** Socket, DNS, TLS, timeout. */
    NETWORK,
    /** A redirect we refuse: too many, https -> http, or to an URL that is not an info/refs one. */
    REDIRECT_REFUSED,
    /** SSH host key unknown/changed and the policy did not accept it. */
    HOST_KEY_REJECTED,
    /** The caller cancelled. */
    CANCELLED,
    /** Received data is incomplete or corrupt (bad pack, missing objects): refs were NOT moved. */
    INTEGRITY,
    /** The server (or this build) lacks something the operation needs. */
    UNSUPPORTED,
}

/**
 * The one exception transports and protocol codecs throw. Messages are built from host/path and
 * server text only: NEVER a credential, a key, an Authorization header or a URL userinfo.
 */
class TransportException(
    val kind: TransportErrorKind,
    message: String,
    /** The `host` the failure concerns, for credential retry and for display; never a secret. */
    val host: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun protocol(msg: String): Nothing = throw TransportException(TransportErrorKind.PROTOCOL, msg)

/** Cooperative cancellation: checked by loops, and closeables registered here are closed on cancel. */
class CancelToken {
    @Volatile var cancelled = false
        private set
    private val closeables = java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()

    fun cancel() {
        cancelled = true
        for (c in closeables) try { c.close() } catch (_: Exception) {}
    }

    fun register(c: AutoCloseable): AutoCloseable {
        closeables.add(c)
        if (cancelled) try { c.close() } catch (_: Exception) {}
        return c
    }

    fun unregister(c: AutoCloseable) { closeables.remove(c) }

    fun check() {
        if (cancelled) throw TransportException(TransportErrorKind.CANCELLED, "cancelled")
    }

    companion object { val NONE = CancelToken() }
}
