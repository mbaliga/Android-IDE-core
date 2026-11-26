package dev.fonebrew.data.terminal

import dev.fonebrew.domain.terminal.FgSignal
import dev.fonebrew.domain.terminal.ForegroundProcess

data class TermSize(val rows: Int, val cols: Int)

/** Callbacks a backend invokes from its reader thread. */
interface SessionIo {
    /** Raw bytes from the shell (decoded statefully by the session). */
    fun onOutput(bytes: ByteArray, length: Int)
    /** The shell ended. [status] = exit code, negative signal number, or null if unknown. */
    fun onExit(status: Int?)
}

/**
 * What a terminal session talks to: a local pty, an SSH shell channel, or a fake in tests. The
 * manager serialises [write] calls per session, so implementations need no extra ordering.
 */
interface SessionBackend {
    suspend fun write(bytes: ByteArray)
    suspend fun resize(rows: Int, cols: Int)
    /** Tear the shell down. Idempotent. */
    fun close()
    /** Cheap, non-blocking look at the pty's foreground; [ForegroundProcess.Unknown] if not available. */
    fun foreground(): ForegroundProcess = ForegroundProcess.Unknown
    /** Signal the foreground process group; false if unsupported or refused. */
    fun signalForeground(signal: FgSignal): Boolean = false
}

/** Starts a shell and wires its output/exit to [io]. Re-invoked on restart. */
fun interface SessionLauncher {
    suspend fun launch(size: TermSize, io: SessionIo): SessionBackend
}
