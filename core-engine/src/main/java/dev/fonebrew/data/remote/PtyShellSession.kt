package dev.fonebrew.data.remote

import android.os.ParcelFileDescriptor
import dev.fonebrew.domain.remote.ExecChunk
import dev.fonebrew.domain.remote.ShellSession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real, local, on-device shell — no paired machine required, and a genuine controlling
 * terminal: real line discipline (ONLCR translation, echo, signal-driven job control), the same
 * class of session `/system/bin/sh` gets from any real terminal app. Replaces the earlier
 * `LocalShellSession`, which spawned the shell via plain `ProcessBuilder` pipes — no pty at all,
 * hence its "can't find tty fd" warning and the garbled line-wrapping a bare LF produces without
 * a tty's ONLCR translation. This session forks onto a real pty slave via [NativePty]
 * (`core-engine/src/main/cpp/pty_exec.cpp`), the same approach Termux's terminal-emulator uses.
 * Still runs at this app's own sandboxed uid — no root, no privilege escalation. Feeds the same
 * transport-agnostic [dev.fonebrew.domain.remote.term.PtyChannel]/[dev.fonebrew.domain.remote.term.VtParser]
 * the SSH-backed interactive shell in `RemoteScreen.kt` already uses. Owner-verified: there is
 * no device in this build environment — fork()/exec()/ioctl() behavior on a real device is
 * unverifiable here.
 */
class PtyShellSession private constructor(
    private val pfd: ParcelFileDescriptor,
    private val masterOut: FileOutputStream,
    private val masterFdRaw: Int,
    private val pid: Int,
    private val reader: Thread,
    private val running: AtomicBoolean,
) : ShellSession {

    override suspend fun send(text: String) = withContext(Dispatchers.IO) {
        runCatching {
            masterOut.write(text.toByteArray(Charsets.UTF_8))
            masterOut.flush()
        }
        Unit
    }

    override suspend fun resize(rows: Int, cols: Int) = withContext(Dispatchers.IO) {
        runCatching { NativePty.resize(masterFdRaw, rows, cols) }
        Unit
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        running.set(false)
        reader.interrupt()
        runCatching { pfd.close() } // closes the pty master fd -> the reader thread's read() unblocks with EOF/error
        runCatching { NativePty.waitFor(pid) } // reap the child -- avoid a zombie across open/close cycles
        Unit
    }

    companion object {
        /** [workingDir] should be a directory this app's uid can actually write to (e.g. filesDir). */
        suspend fun open(
            workingDir: File,
            rows: Int = 24,
            cols: Int = 80,
            onOutput: (ExecChunk) -> Unit,
        ): PtyShellSession = withContext(Dispatchers.IO) {
            val result = NativePty.spawn(
                "/system/bin/sh",
                arrayOf("/system/bin/sh", "-i"),
                workingDir.absolutePath,
                rows, cols,
            )
            check(result.size == 2 && result[0] >= 0 && result[1] > 0) {
                "pty spawn failed (see logcat tag aarso-pty for errno)"
            }
            val masterFdRaw = result[0]
            val pid = result[1]
            val pfd = ParcelFileDescriptor.adoptFd(masterFdRaw)
            val masterIn = FileInputStream(pfd.fileDescriptor)
            val masterOut = FileOutputStream(pfd.fileDescriptor)
            val running = AtomicBoolean(true)
            // Pump the pty's output on a daemon thread (blocking reads) -> onOutput, same shape
            // as SshjTransport.shell()'s reader thread.
            val reader = Thread {
                val buf = ByteArray(8 * 1024)
                try {
                    while (running.get()) {
                        val n = masterIn.read(buf)
                        if (n < 0) break
                        if (n > 0) onOutput(ExecChunk(ExecChunk.StdStream.OUT, buf.copyOf(n)))
                    }
                } catch (_: Exception) {
                    // fd closed on our own close()
                }
            }.apply { isDaemon = true; start() }
            PtyShellSession(pfd, masterOut, masterFdRaw, pid, reader, running)
        }
    }
}
