package dev.fonebrew.data.terminal

import android.os.ParcelFileDescriptor
import dev.fonebrew.data.remote.NativePty
import dev.fonebrew.domain.terminal.FgSignal
import dev.fonebrew.domain.terminal.ForegroundProcess
import dev.fonebrew.domain.terminal.SessionPaths
import dev.fonebrew.domain.terminal.TerminalEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A local pty shell as a [SessionBackend]: `/system/bin/sh -i` with the explicit environment
 * from [TerminalEnvironment], HOME under app storage, and the generated rc file. Output is read
 * on a daemon thread into [SessionIo]; exit status comes from a non-blocking reap once the
 * master reaches EOF. Owner-verified only (no device here).
 */
class NativePtyBackend private constructor(
    private val pfd: ParcelFileDescriptor,
    private val out: FileOutputStream,
    private val masterFd: Int,
    private val pid: Int,
    private val inspector: ProcInspector = ProcInspector(),
) : SessionBackend {
    private val closed = AtomicBoolean(false)
    @Volatile private var reaped = false

    override suspend fun write(bytes: ByteArray) {
        if (closed.get()) return
        runCatching { out.write(bytes); out.flush() }
    }

    override suspend fun resize(rows: Int, cols: Int) {
        if (!closed.get()) runCatching { NativePty.resize(masterFd, rows, cols) }
    }

    override fun foreground(): ForegroundProcess {
        if (closed.get() || reaped) return ForegroundProcess.Unknown
        val pg = runCatching { NativePty.foregroundPgid(masterFd) }.getOrDefault(-1)
        if (pg <= 0) return ForegroundProcess.Unknown
        // The shell is its own session/group leader (setsid), so its pgid equals its pid.
        if (pg == pid) return ForegroundProcess.Shell
        val name = inspector.comm(pg)
        return ForegroundProcess.Busy(pg, name)
    }

    override fun signalForeground(signal: FgSignal): Boolean {
        if (closed.get() || reaped) return false
        return runCatching { NativePty.signalForeground(masterFd, pid, signal.number) == 0 }.getOrDefault(false)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { NativePty.signalChild(pid, 1) } // SIGHUP, as a closing terminal does
        runCatching { pfd.close() }
        Thread { runCatching { NativePty.waitFor(pid) } }.apply { isDaemon = true; start() } // reap
    }

    companion object {
        /** Creates the directories/rc file the environment names. Idempotent. */
        fun preparePaths(filesDir: File): SessionPaths {
            val home = File(filesDir, "terminal/home").apply { mkdirs() }
            val tmp = File(filesDir, "terminal/tmp").apply { mkdirs() }
            val bin = File(filesDir, "terminal/bin").apply { mkdirs() }
            File(home, ".fonebrew/rc.d").mkdirs()
            val rc = File(filesDir, "terminal/fonebrew.rc")
            runCatching { rc.writeText(TerminalEnvironment.rcFileContent()) }
            return SessionPaths(home.absolutePath, tmp.absolutePath, bin.absolutePath, rc.absolutePath)
        }

        fun launcher(filesDir: File): SessionLauncher = SessionLauncher { size, io ->
            val paths = preparePaths(filesDir)
            val env = TerminalEnvironment.toEnvp(TerminalEnvironment.build(paths, System.getenv()))
            launch(PtySpec(TerminalEnvironment.SHELL, listOf(TerminalEnvironment.SHELL, "-i"), env, paths.home), size, io)
        }

        /** Spawns [spec] on a pty and starts the reader thread feeding [io]. Throws if the spawn fails. */
        fun launch(spec: PtySpec, size: TermSize, io: SessionIo, inspector: ProcInspector = ProcInspector()): NativePtyBackend {
            val r = NativePty.spawnEx(spec.cmd, spec.argv.toTypedArray(), spec.env, spec.cwd, size.rows, size.cols)
            check(r.size == 2 && r[0] >= 0 && r[1] > 0) { "pty spawn failed (see logcat tag aarso-pty)" }
            val pfd = ParcelFileDescriptor.adoptFd(r[0])
            val backend = NativePtyBackend(pfd, FileOutputStream(pfd.fileDescriptor), r[0], r[1], inspector)
            val input = FileInputStream(pfd.fileDescriptor)
            Thread {
                val buf = ByteArray(8 * 1024)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) io.onOutput(buf, n)
                    }
                } catch (_: Exception) { /* fd closed by us or the child's side hung up */ }
                if (!backend.closed.get()) {
                    var st = NativePty.tryWait(r[1])
                    if (st == NativePty.STILL_RUNNING) st = runCatching { NativePty.waitFor(r[1]) }.getOrDefault(-1)
                    backend.reaped = true
                    io.onExit(if (st == -1) null else st)
                }
            }.apply { isDaemon = true; start() }
            return backend
        }
    }
}

/** What to exec on the pty: [env] is the complete `KEY=VALUE` environment. */
class PtySpec(val cmd: String, val argv: List<String>, val env: Array<String>, val cwd: String)
