package dev.fonebrew.data.terminal

import dev.fonebrew.domain.remote.ShellSession
import dev.fonebrew.domain.terminal.TerminalEnvironment

/**
 * Launches `/system/bin/sh -i` on a pty in the terminal HOME with the allow-listed environment
 * from [TerminalEnvironment]. [inheritedEnv] is the app process environment the allow-list
 * filters (injectable so tests can prove nothing else gets through).
 */
class PhoneShellLauncher(
    private val files: TerminalFiles,
    private val inheritedEnv: () -> Map<String, String> = { System.getenv() },
) : SessionLauncher {
    override suspend fun launch(size: TermSize, io: SessionIo): SessionBackend {
        val paths = files.prepare()
        val env = TerminalEnvironment.toEnvp(TerminalEnvironment.build(paths, inheritedEnv()))
        return NativePtyBackend.launch(
            PtySpec(TerminalEnvironment.SHELL, listOf(TerminalEnvironment.SHELL, "-i"), env, paths.home),
            size, io,
        )
    }
}

/**
 * Adapts an SSH [ShellSession] (already open) to a [SessionBackend]. No foreground process and
 * no signals (those are Unknown / unsupported over this channel); exit is not reported by the
 * channel, so [SessionIo.onExit] is never called for it - closing is explicit.
 */
class ShellSessionBackend(
    private val shell: ShellSession,
    /** Closes the shell; supplied by the owner of a coroutine scope since close is suspend. */
    private val closeShell: (ShellSession) -> Unit,
) : SessionBackend {
    override suspend fun write(bytes: ByteArray) = shell.send(String(bytes, Charsets.UTF_8))
    override suspend fun resize(rows: Int, cols: Int) = shell.resize(rows, cols)
    override fun close() = closeShell(shell)
}
