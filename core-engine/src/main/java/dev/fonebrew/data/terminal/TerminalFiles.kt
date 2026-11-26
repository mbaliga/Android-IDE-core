package dev.fonebrew.data.terminal

import dev.fonebrew.domain.terminal.SessionPaths
import dev.fonebrew.domain.terminal.TerminalEnvironment
import java.io.File

/**
 * The terminal's app-private directory tree under `<filesDir>/terminal/`:
 * `home/` (HOME, the shell's start directory), `tmp/` (TMPDIR), `bin/` (reserved for a later
 * package's command bridges), `rc/fonebrew.rc` (the generated `ENV` file) and `history/`
 * (saved scrollback snapshots, see [TerminalHistoryStore]). Nothing here is world-readable
 * storage; everything is created with owner-only permissions where the platform allows.
 */
class TerminalFiles(private val root: File) {
    val historyDir: File get() = File(root, "history")

    fun paths() = SessionPaths(
        home = File(root, "home").absolutePath,
        tmp = File(root, "tmp").absolutePath,
        bin = File(root, "bin").absolutePath,
        rcFile = File(root, "rc/fonebrew.rc").absolutePath,
    )

    /** Creates the directories and (re)writes the generated rc if it differs. Idempotent. */
    fun prepare(): SessionPaths {
        val p = paths()
        for (d in listOf(p.home, p.tmp, p.bin, File(p.rcFile).parent!!)) {
            File(d).mkdirs()
            ownerOnly(File(d))
        }
        val rc = File(p.rcFile)
        val wanted = TerminalEnvironment.rcFileContent()
        if (!rc.exists() || rc.readText() != wanted) rc.writeText(wanted)
        ownerOnly(rc)
        return p
    }

    private fun ownerOnly(f: File) {
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
            if (f.isDirectory) { f.setExecutable(false, false); f.setExecutable(true, true) }
        }
    }
}
