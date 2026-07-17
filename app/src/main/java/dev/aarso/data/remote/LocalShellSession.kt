package dev.aarso.data.remote

import dev.aarso.domain.remote.ExecChunk
import dev.aarso.domain.remote.ShellSession
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real, local, on-device shell — no paired machine required. Spawns `/system/bin/sh` inside
 * this app's own sandboxed uid: `ProcessBuilder` never elevates privilege (no root, no `su`),
 * and only whatever ships on Android's minimal toolbox `PATH` is available. That makes this a
 * genuinely limited shell next to a full Termux-style userland (no `apt`/`busybox`/package
 * manager — that needs a bundled userland of its own, out of scope here), but it is a real
 * interactive process with real stdin/stdout, not a stub. It feeds the same transport-agnostic
 * [dev.aarso.domain.remote.term.PtyChannel]/[dev.aarso.domain.remote.term.VtParser] the SSH-
 * backed interactive shell in `RemoteScreen.kt` already uses — that terminal core never cared
 * where the bytes came from. Owner-verified: there is no device in this build environment.
 */
class LocalShellSession private constructor(
    private val process: Process,
    private val reader: Thread,
    private val running: AtomicBoolean,
) : ShellSession {

    override suspend fun send(text: String) = withContext(Dispatchers.IO) {
        process.outputStream.write(text.toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    /** ProcessBuilder allocates no PTY, so there is no real window size to change — an honest no-op. */
    override suspend fun resize(rows: Int, cols: Int) {}

    override suspend fun close() = withContext(Dispatchers.IO) {
        running.set(false)
        reader.interrupt()
        runCatching { process.destroy() }
        Unit
    }

    companion object {
        /** [workingDir] should be a directory this app's uid can actually write to (e.g. filesDir). */
        suspend fun open(workingDir: File, onOutput: (ExecChunk) -> Unit): LocalShellSession =
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder("/system/bin/sh", "-i")
                    .directory(workingDir)
                    .redirectErrorStream(true) // merge stderr into stdout, like a real terminal does
                    .start()
                val running = AtomicBoolean(true)
                // Pump the shell's output on a daemon thread (blocking reads) → onOutput, same
                // shape as SshjTransport.shell()'s reader thread.
                val reader = Thread {
                    val buf = ByteArray(8 * 1024)
                    try {
                        while (running.get()) {
                            val n = process.inputStream.read(buf)
                            if (n < 0) break
                            if (n > 0) onOutput(ExecChunk(ExecChunk.StdStream.OUT, buf.copyOf(n)))
                        }
                    } catch (_: Exception) {
                        // stream closed on our own destroy()/interrupt()
                    }
                }.apply { isDaemon = true; start() }
                LocalShellSession(process, reader, running)
            }
    }
}
