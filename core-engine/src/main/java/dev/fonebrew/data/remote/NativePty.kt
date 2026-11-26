package dev.fonebrew.data.remote

/**
 * JNI bridge to `pty_exec.cpp` (`libaarso_pty.so`): forks + execs a command with a real pty
 * attached, instead of the bare stdin/stdout pipes `ProcessBuilder` gives a subprocess. Backs
 * the on-device "This phone" terminal ([dev.fonebrew.data.terminal.NativePtyBackend], and the
 * [PtyShellSession] compatibility wrapper). Owner-verified only: fork()/exec()/ioctl() behavior
 * on a real device is unverifiable in this build environment (no device/emulator here).
 */
internal object NativePty {
    init { System.loadLibrary("aarso_pty") }

    /**
     * Forks + execs [cmd] (argv0 in [args]\[0\]) with a real pty attached, in [cwd], sized
     * [rows]x[cols]. Returns `[masterFd, pid]` on success, or `[-1, -1]` on failure (see logcat
     * tag `aarso-pty` for the errno). The child inherits this process's environment (+TERM).
     */
    external fun spawn(cmd: String, args: Array<String>, cwd: String, rows: Int, cols: Int): IntArray

    /**
     * As [spawn], but the child gets **exactly** [env] (`KEY=VALUE` strings) as its environment,
     * has signal dispositions/mask reset, and has every inherited descriptor above stderr closed.
     */
    external fun spawnEx(cmd: String, args: Array<String>, env: Array<String>, cwd: String, rows: Int, cols: Int): IntArray

    /** `ioctl(TIOCSWINSZ)` on the pty - tells the shell (and anything it runs) the new size. */
    external fun resize(masterFd: Int, rows: Int, cols: Int)

    /** Blocks until [pid] exits - reaps it (avoids a zombie). Call off the main thread. */
    external fun waitFor(pid: Int): Int

    /** The pty's foreground process group per the kernel, or -1 if it will not say. */
    external fun foregroundPgid(masterFd: Int): Int

    /**
     * Sends [sig] (SIGHUP 1, SIGINT 2, SIGQUIT 3, SIGTSTP 20 only) to the pty's foreground
     * process group. 0 on success; -1 if refused (see pty_exec.cpp) or failed.
     */
    external fun signalForeground(masterFd: Int, shellPid: Int, sig: Int): Int

    /** Non-blocking reap: [STILL_RUNNING] while alive, else the exit status (or `-signal`); -1 if nothing to wait for. */
    external fun tryWait(pid: Int): Int

    /** kill(pid, sig) for our own unreaped child; only SIGHUP 1 / SIGTERM 15 / SIGKILL 9. */
    external fun signalChild(pid: Int, sig: Int): Int

    const val STILL_RUNNING = Int.MIN_VALUE
    const val SIGHUP = 1
    const val SIGKILL = 9
    const val SIGTERM = 15
}
