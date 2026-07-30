package dev.aarso.domain.remote

/**
 * Remote-exec / device-IO spine (docs/build-plan.md, Sprint 1). Pure model for talking
 * to other machines (a Raspberry Pi, a Dell box, an SSH server) the way a real computer
 * does — *legibly*. The remote machine's output is a **watched object**: its raw,
 * unmediated voice, shown verbatim and marked as not-our-voice (THE LAW).
 *
 * This file is the address book + identity model. No I/O, no crypto, no sockets — those
 * live behind [RemoteTransport] in the data layer and are owner-verified on device.
 * Pure Kotlin; JVM-tested.
 */

/**
 * A machine we can reach over SSH. [alias] is the human label; [hostname]/[port] address
 * it; [username] is the login. The matching private key / password is held by [Identity]
 * (and, in the data layer, encrypted in the Android Keystore — never stored here in clear).
 */
data class RemoteHost(
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
) {
    init {
        require(alias.isNotBlank()) { "alias is blank" }
        require(hostname.isNotBlank()) { "hostname is blank" }
        require(port in 1..65535) { "port out of range: $port" }
        require(username.isNotBlank()) { "username is blank" }
    }

    /** Stable identity for trust lookups: a host key is bound to host:port (RFC 4251 style). */
    val endpoint: String get() = "$hostname:$port"
}

/**
 * How we authenticate to a [RemoteHost]. We never hold secret material in this model — only
 * a *reference*: a [keyId] resolved by the data layer to a Keystore-decrypted private key,
 * or [Password] resolved likewise. Public-key auth is the default; password is a fallback.
 */
sealed interface Identity {
    /** Public-key auth. [keyId] names a private key the data layer decrypts from the Keystore. */
    data class PublicKey(val keyId: String, val passphraseId: String? = null) : Identity

    /** Password auth. [secretId] names a Keystore-encrypted password. Discouraged; offered for parity. */
    data class Password(val secretId: String) : Identity

    /** Agent / no stored secret — the transport supplies auth out of band. */
    data object Agent : Identity
}
