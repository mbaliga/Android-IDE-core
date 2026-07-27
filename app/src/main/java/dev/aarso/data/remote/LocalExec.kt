package dev.aarso.data.remote

import java.io.File
import kotlinx.coroutines.Dispatchers
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

    suspend fun run(command: String, workingDir: File, timeoutSeconds: Long = 30): String =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            val output = withTimeoutOrNull(timeoutSeconds * 1000) {
                process.inputStream.bufferedReader().readText()
            }
            if (output == null) {
                process.destroyForcibly()
                "[timed out after ${timeoutSeconds}s — command killed]"
            } else {
                process.waitFor()
                output.take(MAX_OUTPUT_CHARS).let {
                    if (output.length > MAX_OUTPUT_CHARS) "$it\n[truncated, ${output.length} chars total]" else it
                }
            }
        }
}
