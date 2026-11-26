package dev.fonebrew.domain.gitcore.transport

import java.io.InputStream
import java.io.OutputStream

enum class GitService(val wire: String) {
    UPLOAD_PACK("git-upload-pack"),
    RECEIVE_PACK("git-receive-pack"),
}

/**
 * A request body. [write] may be called more than once (a 401 retry or a redirect re-sends it), so it
 * must be repeatable. [small] bodies (pkt-line command blocks) may be buffered and gzip-compressed
 * by the transport; large ones (a push pack) are streamed as they are generated.
 */
class Payload(val small: Boolean, val write: (OutputStream) -> Unit)

/**
 * One conversation with a git service. Smart HTTP is stateless (each [send] is a POST) while SSH is
 * one duplex channel (each [send] writes to it and the response continues on the same stream); the
 * protocol code above does not care. Not thread-safe. [close] must release the connection.
 */
interface GitSession : AutoCloseable {
    /** The server's opening message (ref advertisement or v2 capability list). Read it exactly once. */
    val advertisement: InputStream

    /** Sends [payload] and returns the response stream. [protocolV2] says the request is a v2 command. */
    fun send(payload: Payload, protocolV2: Boolean): InputStream
}

/** Opens sessions. [requestV2] asks for protocol v2 (silently answered with v0 by older servers). */
interface GitTransport {
    fun open(service: GitService, requestV2: Boolean, cancel: CancelToken): GitSession

    /** The URL as it may be shown to a user and put in logs: no userinfo, no query. */
    val displayUrl: String
}

/**
 * One credential. [toString] is redacted and the secret is only readable through [password]; the
 * class is deliberately not a data class so `copy`/`equals` cannot leak or compare secrets by accident.
 */
class Credential(val username: String, private val secret: String) {
    fun password(): String = secret
    override fun toString() = "Credential(user=<redacted>, secret=<redacted>)"
}

/** What a transport knows when it asks for credentials. Scoped to ONE origin. */
data class CredentialRequest(val scheme: String, val host: String, val port: Int, val usernameHint: String?)

/**
 * The credential PORT. Called per origin, only after the server answered 401 with a Basic
 * challenge. Return null for "none for this host". The implementation must scope what it returns to
 * [CredentialRequest.host]; the engine additionally never reuses a credential for another origin.
 */
fun interface CredentialProvider {
    fun credentialFor(request: CredentialRequest): Credential?

    companion object { val NONE = CredentialProvider { null } }
}
