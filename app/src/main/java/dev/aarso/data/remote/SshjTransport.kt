package dev.aarso.data.remote

import android.util.Base64
import dev.aarso.domain.remote.ExecChunk
import dev.aarso.domain.remote.ExecRequest
import dev.aarso.domain.remote.ExecResult
import dev.aarso.domain.remote.HostKey
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.domain.remote.RemoteTransport
import dev.aarso.domain.remote.SftpEntry
import dev.aarso.domain.remote.SftpOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey

/**
 * The real SSH/SFTP transport (sshj) behind the pure [RemoteTransport] seam — the data-layer
 * half of Sprint 1. **Runtime is owner-verified**: there is no SSH server in the build
 * container, so this is compile-verified only; the pure trust/session/exec logic it serves
 * (`domain/remote/`) is JVM-tested.
 *
 * Trust handshake: the host-key verifier *captures* the presented key and returns true so the
 * SSH transport handshake completes — but **no secret is sent yet**. Our [dev.aarso.domain.remote.RemoteSessionDriver]
 * classifies the captured key against `KnownHosts` and only calls [authenticate] (which sends
 * credentials) after the user accepts. Keys/passwords are resolved by reference via
 * [secretProvider] (Keystore-decrypted), never held in this class.
 */
class SshjTransport(
    private val secretProvider: (id: String) -> String?,
    private val newClient: () -> SSHClient = { SSHClient() },
) : RemoteTransport {

    private var client: SSHClient? = null
    private var presentedKey: HostKey? = null

    override suspend fun connect(host: RemoteHost): HostKey = withContext(Dispatchers.IO) {
        val ssh = newClient()
        ssh.addHostKeyVerifier(CapturingVerifier { presentedKey = it })
        ssh.connect(host.hostname, host.port)
        client = ssh
        currentUsername = host.username
        presentedKey ?: error("host presented no key")
    }

    override suspend fun authenticate(identity: Identity) = withContext(Dispatchers.IO) {
        val ssh = client ?: error("not connected")
        val username = currentUsername ?: error("not connected")
        when (identity) {
            is Identity.Password -> {
                val pw = secretProvider(identity.secretId) ?: error("no password for ${identity.secretId}")
                ssh.authPassword(username, pw)
            }
            is Identity.PublicKey -> {
                val pem = secretProvider(identity.keyId) ?: error("no key for ${identity.keyId}")
                val passphrase = identity.passphraseId?.let { secretProvider(it) }
                val kp = ssh.loadKeys(pem, null, passphrase?.let { net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(it.toCharArray()) })
                ssh.authPublickey(username, kp)
            }
            Identity.Agent -> error("agent auth not supported on device")
        }
    }

    override suspend fun exec(request: ExecRequest, onChunk: (ExecChunk) -> Unit): ExecResult =
        withContext(Dispatchers.IO) {
            val ssh = client ?: error("not connected")
            ssh.startSession().use { session ->
                if (request.pty) session.allocateDefaultPTY()
                val fullCmd = request.cwd?.let { "cd '${it.replace("'", "'\\''")}' && ${request.command}" } ?: request.command
                val cmd = session.exec(fullCmd)
                streamInto(cmd.inputStream, ExecChunk.StdStream.OUT, onChunk)
                streamInto(cmd.errorStream, ExecChunk.StdStream.ERR, onChunk)
                cmd.join()
                ExecResult(cmd.exitStatus ?: -1)
            }
        }

    override suspend fun sftp(op: SftpOp): List<SftpEntry> = withContext(Dispatchers.IO) {
        val ssh = client ?: error("not connected")
        ssh.newSFTPClient().use { sftp ->
            when (op) {
                is SftpOp.List -> sftp.ls(op.remotePath).map {
                    SftpEntry(it.name, it.isDirectory, it.attributes.size, it.attributes.mode.mask)
                }
                is SftpOp.Mkdir -> { sftp.mkdirs(op.remotePath); emptyList() }
                is SftpOp.Remove -> { sftp.rm(op.remotePath); emptyList() }
                is SftpOp.Get -> {
                    // Bytes are delivered to the data-layer caller via a follow-on; stat of the
                    // file is returned so the caller can confirm presence/size.
                    val a = sftp.stat(op.remotePath)
                    val isDir = a.mode.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY
                    listOf(SftpEntry(op.remotePath.substringAfterLast('/'), isDir, a.size, a.mode.mask))
                }
                is SftpOp.Put -> {
                    val bytes = secretProvider(op.bytesId)?.toByteArray() ?: ByteArray(0)
                    sftp.open(op.remotePath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                        rf.write(0, bytes, 0, bytes.size)
                    }
                    emptyList()
                }
            }
        }
    }

    override suspend fun shell(onOutput: (ExecChunk) -> Unit): dev.aarso.domain.remote.ShellSession =
        withContext(Dispatchers.IO) {
            val ssh = client ?: error("not connected")
            val session = ssh.startSession()
            session.allocateDefaultPTY()
            val shell = session.startShell()
            val out = shell.outputStream
            val running = java.util.concurrent.atomic.AtomicBoolean(true)

            // Pump the shell's output on a daemon thread (blocking reads) → onOutput.
            val reader = Thread {
                val buf = ByteArray(8 * 1024)
                try {
                    while (running.get()) {
                        val n = shell.inputStream.read(buf)
                        if (n < 0) break
                        if (n > 0) onOutput(ExecChunk(ExecChunk.StdStream.OUT, buf.copyOf(n)))
                    }
                } catch (_: Exception) { /* channel closed */ }
            }.apply { isDaemon = true; start() }

            object : dev.aarso.domain.remote.ShellSession {
                override suspend fun send(text: String) = withContext(Dispatchers.IO) {
                    out.write(text.toByteArray(Charsets.UTF_8)); out.flush()
                }
                override suspend fun resize(rows: Int, cols: Int) = withContext(Dispatchers.IO) {
                    runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
                    Unit
                }
                override suspend fun close() = withContext(Dispatchers.IO) {
                    running.set(false)
                    runCatching { shell.close() }
                    runCatching { session.close() }
                    reader.interrupt()
                }
            }
        }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { client?.disconnect() }
        client = null
    }

    // The username travels on RemoteHost; stashed at connect time for authenticate().
    @Volatile private var currentUsername: String? = null

    private fun streamInto(input: java.io.InputStream, stream: ExecChunk.StdStream, onChunk: (ExecChunk) -> Unit) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) onChunk(ExecChunk(stream, buf.copyOf(n)))
        }
    }

    /** A verifier that records the presented key (as an OpenSSH SHA-256 fingerprint) and accepts. */
    private class CapturingVerifier(val onKey: (HostKey) -> Unit) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            onKey(HostKey(algorithm = KeyType.fromKey(key).toString(), fingerprint = sha256Fingerprint(key)))
            return true // handshake may complete; trust is decided at our layer before auth
        }
        override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
    }

    companion object {
        /** OpenSSH-style "SHA256:base64(noPadding)" of the SSH wire-format public key blob. */
        fun sha256Fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val sha = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.encodeToString(sha, Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
