package dev.fonebrew.data.remote

import dev.fonebrew.data.terminal.NativePtyBackend
import dev.fonebrew.data.terminal.ProcInspector
import dev.fonebrew.data.terminal.PtySpec
import dev.fonebrew.data.terminal.SessionIo
import dev.fonebrew.data.terminal.TermSize
import dev.fonebrew.domain.remote.ExecChunk
import dev.fonebrew.domain.remote.ShellSession
import dev.fonebrew.domain.terminal.SessionPaths
import dev.fonebrew.domain.terminal.TerminalEnvironment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real, local, on-device shell on a genuine controlling terminal, as a plain [ShellSession].
 * Kept for callers that want one shell without the session manager; the terminal screens now go
 * through [TerminalSessionHolder] -> [dev.fonebrew.data.terminal.TerminalSessionManager], which
 * uses the same [NativePtyBackend]. Raw bytes are handed to [onOutput] undecoded (decode them with
 * a [dev.fonebrew.domain.terminal.Utf8StreamDecoder], not `String(bytes)` per chunk). The shell
 * gets the allow-listed [TerminalEnvironment], with HOME/TMPDIR under [workingDir]. Owner-verified:
 * there is no device in this build environment.
 */
class PtyShellSession private constructor(private val backend: NativePtyBackend) : ShellSession {

    override suspend fun send(text: String) = backend.write(text.toByteArray(Charsets.UTF_8))

    override suspend fun resize(rows: Int, cols: Int) = backend.resize(rows, cols)

    override suspend fun close() = withContext(Dispatchers.IO) { backend.close() }

    companion object {
        /** [workingDir] should be a directory this app's uid can actually write to (e.g. filesDir). */
        suspend fun open(
            workingDir: File,
            rows: Int = 24,
            cols: Int = 80,
            onOutput: (ExecChunk) -> Unit,
        ): PtyShellSession = withContext(Dispatchers.IO) {
            val paths = SessionPaths(
                home = workingDir.absolutePath,
                tmp = File(workingDir, "tmp").also { it.mkdirs() }.absolutePath,
                bin = File(workingDir, "bin").absolutePath,
                rcFile = File(workingDir, "fonebrew.rc").absolutePath,
            )
            val env = TerminalEnvironment.toEnvp(TerminalEnvironment.build(paths, System.getenv()))
            val emit = onOutput
            val io = object : SessionIo {
                override fun onOutput(bytes: ByteArray, length: Int) {
                    emit(ExecChunk(ExecChunk.StdStream.OUT, bytes.copyOf(length)))
                }
                override fun onExit(status: Int?) {}
            }
            PtyShellSession(
                NativePtyBackend.launch(
                    PtySpec(TerminalEnvironment.SHELL, listOf(TerminalEnvironment.SHELL, "-i"), env, paths.home),
                    TermSize(rows, cols), io, ProcInspector(),
                ),
            )
        }
    }
}
