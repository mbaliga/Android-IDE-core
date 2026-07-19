package dev.aarso.data.remote

/**
 * JNI bridge to `pty_exec.cpp` (`libaarso_pty.so`): forks + execs a command with a real pty
 * attached, the same way Termux's terminal-emulator does it, instead of the bare stdin/stdout
 * pipes `ProcessBuilder` gives a subprocess. Backs [PtyShellSession] — the on-device "This
 * phone" Terminal facet. Owner-verified only: fork()/exec()/ioctl() behavior on a real device
 * is unverifiable in this build environment (no device/emulator here).
 */
internal object NativePty {
    init { System.loadLibrary("aarso_pty") }

    /**
     * Forks + execs [cmd] (argv0 in [args]\[0\]) with a real pty attached, in [cwd], sized
     * [rows]x[cols]. Returns `[masterFd, pid]` on success, or `[-1, -1]` on failure (see logcat
     * tag `aarso-pty` for the errno).
     */
    external fun spawn(cmd: String, args: Array<String>, cwd: String, rows: Int, cols: Int): IntArray

    /** `ioctl(TIOCSWINSZ)` on the pty — tells the shell (and anything it runs) the new size. */
    external fun resize(masterFd: Int, rows: Int, cols: Int)

    /** Blocks until [pid] exits — reaps it (avoids a zombie). Call off the main thread. */
    external fun waitFor(pid: Int): Int
}
