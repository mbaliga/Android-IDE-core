package dev.fonebrew.data.gitcore

import dev.fonebrew.domain.gitcore.transport.CancelToken
import dev.fonebrew.domain.gitcore.transport.GitService
import dev.fonebrew.domain.gitcore.transport.GitSession
import dev.fonebrew.domain.gitcore.transport.GitTransport
import dev.fonebrew.domain.gitcore.transport.HostKeyPolicy
import dev.fonebrew.domain.gitcore.transport.Payload
import dev.fonebrew.domain.gitcore.transport.RemoteUrl
import dev.fonebrew.domain.gitcore.transport.SshKeyProvider
import dev.fonebrew.domain.gitcore.transport.TransportErrorKind
import dev.fonebrew.domain.gitcore.transport.TransportException
import dev.fonebrew.domain.remote.HostKey
import dev.fonebrew.domain.remote.KnownHosts
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * git over SSH on sshj: an exec channel running `git-upload-pack '<path>'` / `git-receive-pack`.
 * Trust reuses the existing known-hosts flow (domain/remote): the handshake may complete, but NO
 * secret is sent until [HostKeyPolicy] accepts the presented key. The key comes from an
 * [SshKeyProvider] port. **There is no sshd in the build container: this class is compile-verified
 * only; its pure helpers are unit-tested and the runtime is owner-verified.**
 */
class SshGitTransport(
    private val url: RemoteUrl.Ssh,
    private val keys: SshKeyProvider,
    private val hostKeys: HostKeyPolicy,
    private val knownHosts: () -> KnownHosts,
    private val newClient: () -> SSHClient = { SSHClient() },
) : GitTransport {

    override val displayUrl: String get() = url.display

    override fun open(service: GitService, requestV2: Boolean, cancel: CancelToken): GitSession {
        val user = url.user ?: "git"
        val ssh = newClient()
        var presented: HostKey? = null
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                presented = HostKey(KeyType.fromKey(key).toString(), sha256Fingerprint(key))
                return true // handshake may finish; trust is decided below, BEFORE any credential is sent
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
        })
        try {
            cancel.register(AutoCloseable { try { ssh.disconnect() } catch (_: Exception) {} })
            try { ssh.connect(url.host, url.port) } catch (e: IOException) { throw net(e, cancel) }
            val key = presented ?: throw TransportException(TransportErrorKind.HOST_KEY_REJECTED, "the server presented no host key", host = url.host)
            val endpoint = "${url.host}:${url.port}"
            val trust = knownHosts().classify(endpoint, key)
            if (!hostKeys.accept(endpoint, trust)) throw TransportException(TransportErrorKind.HOST_KEY_REJECTED, "host key not trusted", host = url.host)
            val material = keys.keyFor(url.host, url.port, user) ?: throw TransportException(TransportErrorKind.AUTH_REQUIRED, "no SSH key is saved for ${url.host}", host = url.host)
            try {
                val finder = material.passphrase()?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                val kp = ssh.loadKeys(material.privateKeyPem(), null, finder)
                ssh.authPublickey(user, kp)
            } catch (e: IOException) {
                if (cancel.cancelled) throw net(e, cancel)
                throw TransportException(TransportErrorKind.AUTH_FAILED, "the SSH key was refused", host = url.host)
            }
            val session = ssh.startSession()
            if (requestV2 && service == GitService.UPLOAD_PACK) {
                // Servers may refuse to take the variable (AcceptEnv); then they simply answer in v0.
                try { session.setEnvVar("GIT_PROTOCOL", "version=2") } catch (_: Exception) {}
            }
            val cmd = session.exec("${service.wire} ${quote(url.path)}")
            val err = StderrTail(cmd.errorStream)
            return object : GitSession {
                override val advertisement: InputStream = wrapStream(cmd.inputStream, cancel, err)
                override fun send(payload: Payload, protocolV2: Boolean): InputStream {
                    try { payload.write(cmd.outputStream); cmd.outputStream.flush() } catch (e: IOException) { throw net(e, cancel) }
                    return advertisement
                }
                override fun close() {
                    try { cmd.close() } catch (_: Exception) {}
                    try { session.close() } catch (_: Exception) {}
                    try { ssh.disconnect() } catch (_: Exception) {}
                }
            }
        } catch (e: Throwable) {
            try { ssh.disconnect() } catch (_: Exception) {}
            throw e
        }
    }

    private fun wrapStream(raw: InputStream, cancel: CancelToken, err: StderrTail): InputStream = object : java.io.FilterInputStream(raw) {
        override fun read(): Int = g { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = g { super.read(b, off, len) }
        private fun <T> g(f: () -> T): T = try { f() } catch (e: IOException) { throw net(e, cancel, err.text()) }
    }

    private fun net(e: IOException, cancel: CancelToken, remote: String? = null): TransportException =
        if (cancel.cancelled) TransportException(TransportErrorKind.CANCELLED, "cancelled")
        else TransportException(TransportErrorKind.NETWORK, "SSH connection failed (${e.javaClass.simpleName})" + (remote?.takeIf { it.isNotBlank() }?.let { "; the server said: $it" } ?: ""), host = url.host, cause = e)

    /** Drains stderr (so the channel window never stalls) and keeps a short sanitised tail for messages. */
    private class StderrTail(input: InputStream) {
        private val buf = StringBuilder()
        init {
            Thread {
                try {
                    val b = ByteArray(1024)
                    while (true) { val n = input.read(b); if (n < 0) break; synchronized(buf) { buf.append(String(b, 0, n, Charsets.UTF_8)); if (buf.length > 2048) buf.delete(0, buf.length - 2048) } }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
        }
        fun text(): String = synchronized(buf) { buf.toString().replace(Regex("\\p{Cntrl}"), " ").trim().takeLast(300) }
    }

    companion object {
        /** POSIX single-quote quoting, as git does for the remote path. */
        fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

        /** OpenSSH-style `SHA256:<base64 no padding>` of the wire-format key blob (JVM-safe; no android.*). */
        fun sha256Fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        }
    }
}
