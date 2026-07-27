package dev.aarso.data.remote

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot, non-interactive command execution — backs the `!`-sigil shell escape in Chat
 * (the Jupyter/IPython `!cmd` convention: run a single command to completion, no live session).
 * Deliberately separate from [PtyShellSession]: that's a persistent interactive pty for the
 * Terminal facet; this just runs one command and hands back its output for a chat turn.
 */
object LocalExec {
    private const val MAX_OUTPUT_CHARS = 20_000

    /**
     * Runs [command] to completion (or until [timeoutSeconds] elapses) and returns its output.
     * The read happens on its own [Dispatchers.IO] job so a command that never closes stdout
     * (`!tail -f`, `!ping host`, …) can't block this call past the timeout: cancellation can't
     * preempt a plain blocking [java.io.InputStream.read], so timing out instead forcibly kills
     * the process — which closes its end of the pipe and unblocks the read with EOF — then waits
     * a short grace period to collect whatever output arrived before the kill.
     */
    suspend fun run(command: String, workingDir: File, timeoutSeconds: Long = 30): String = coroutineScope {
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
        }
        val readJob = async(Dispatchers.IO) {
            runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        }
        val finished = withTimeoutOrNull(timeoutSeconds * 1000) { readJob.join() } != null
        if (finished) {
            process.waitFor()
            cap(readJob.await())
        } else {
            process.destroyForcibly()
            val partial = withTimeoutOrNull(2_000) { readJob.await() } ?: ""
            "${cap(partial)}\n[timed out after ${timeoutSeconds}s — command killed]"
        }
    }

    private fun cap(output: String): String =
        if (output.length > MAX_OUTPUT_CHARS) "${output.take(MAX_OUTPUT_CHARS)}\n[truncated, ${output.length} chars total]" else output
}
