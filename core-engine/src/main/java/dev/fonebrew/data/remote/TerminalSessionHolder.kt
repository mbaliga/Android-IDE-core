package dev.fonebrew.data.remote

import dev.fonebrew.data.RemoteHostStore
import dev.fonebrew.data.terminal.PhoneShellLauncher
import dev.fonebrew.data.terminal.SessionLauncher
import dev.fonebrew.data.terminal.ShellSessionBackend
import dev.fonebrew.data.terminal.TermSize
import dev.fonebrew.data.terminal.TerminalFiles
import dev.fonebrew.data.terminal.TerminalHistoryStore
import dev.fonebrew.data.terminal.TerminalPlatform
import dev.fonebrew.data.terminal.TerminalSession
import dev.fonebrew.data.terminal.TerminalSessionManager
import dev.fonebrew.domain.remote.ExecChunk
import dev.fonebrew.domain.remote.Identity
import dev.fonebrew.domain.remote.RemoteHost
import dev.fonebrew.domain.remote.RemoteSessionDriver
import dev.fonebrew.domain.remote.RemoteTransport
import dev.fonebrew.domain.remote.ShellSession
import dev.fonebrew.domain.remote.Trust
import dev.fonebrew.domain.remote.term.PtyChannel
import dev.fonebrew.domain.remote.term.ScreenBuffer
import dev.fonebrew.domain.remote.term.TerminalRenderer
import dev.fonebrew.domain.terminal.KeyEvent
import dev.fonebrew.domain.terminal.SessionState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * THE terminal session as the current screens see it - exactly one per app process, owned by
 * [dev.fonebrew.di.AppContainer], never by a composable. Since the terminal-engine work it is a
 * **thin facade over [TerminalSessionManager]**: the pty/SSH shell, the screen, the key/paste
 * encoders, history, the keep-alive service and the saved scrollback all live there, and this
 * class keeps the public surface the Chat and Develop terminal doors already compile against
 * (`pty`, `shell`, `mode`, `sendLine`, `sendRaw`, `resize`, `switchTo`, ...). More sessions, and
 * real keystroke input, are available on [manager] / [sendKey] / [paste] for the terminal UI that
 * is designed separately.
 *
 * The terminal is reachable from two doors (Chat's Terminal tab and Develop's Terminal tab), and
 * it used to die walking through either of them: every piece of session state lived in per-mount
 * `remember {}` inside the facet, with a `DisposableEffect` closing the shell on dispose.
 * Hoisting the whole session here means both doors open onto the SAME live shell and the SAME
 * scrollback, and leaving the tab detaches the *view*, not the session. "Both doors" includes
 * `ui/remote/RemoteScreen`, which used to build a third terminal of its own.
 *
 * Behaviour that differs from before, deliberately: a local shell now starts in the terminal HOME
 * (`<filesDir>/terminal/home`) with an explicit, allow-listed environment instead of the app's
 * inherited one and `filesDir`; output is decoded as a UTF-8 *stream*; if the shell exits on its
 * own the facade reports it as closed (so the existing "Session closed - Reconnect" screen shows)
 * rather than leaving a dead shell looking live.
 *
 * Runtime behaviour (fork/exec pty, SSH, TIOCSWINSZ) is owner-verified - no device or SSH host in
 * the build environment.
 */
class TerminalSessionHolder(
    private val filesDir: File,
    private val hostStore: RemoteHostStore,
    private val newTransport: () -> RemoteTransport,
    private val platform: TerminalPlatform = TerminalPlatform.Headless,
    /** Live-session count changes (drives the keep-alive foreground service, wired in AppContainer). */
    onLiveCount: (Int) -> Unit = {},
    // Main.immediate to match the rememberCoroutineScope() the facet used to run these on -
    // shell callbacks already hop threads internally; state writes stay on main.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val PHONE = "phone"
        const val REMOTE = "remote"

        /** How many past command lines up/down can walk back through. Bounded because this is held
         *  for the life of the process and nothing prunes it otherwise. */
        const val MAX_HISTORY = 200
    }

    /** Alias of [manager] (the keep-alive service reads `holder.sessions`). */
    val sessions: TerminalSessionManager get() = manager

    /** The terminal's app-private tree (home, tmp, bin, rc, history). */
    val files = TerminalFiles(File(filesDir, "terminal"))

    /** Saved "previous session history" - text only, never a live shell. */
    val historyStore = TerminalHistoryStore(files.historyDir)

    /** All sessions. The facade's own shell is one of them; the new terminal UI may add more. */
    val manager = TerminalSessionManager(
        scope = scope,
        io = ioDispatcher,
        history = historyStore,
        onLiveCount = onLiveCount,
    )

    /** The screen grid + VT parser. One per process, like the shell it renders; it outlives
     *  individual shells (a reconnect keeps the transcript, a target switch resets it). */
    val pty = PtyChannel(rows = 24, cols = 80, maxScrollback = ScreenBuffer.DEFAULT_SCROLLBACK)

    private val _mode = MutableStateFlow(PHONE)
    val mode: StateFlow<String> = _mode

    private val _remoteAlias = MutableStateFlow<String?>(null)
    val remoteAlias: StateFlow<String?> = _remoteAlias

    private val _shell = MutableStateFlow<ShellSession?>(null)
    val shell: StateFlow<ShellSession?> = _shell

    /** Bumped on every output chunk so the view can re-key the grid. */
    private val _screenVersion = MutableStateFlow(0)
    val screenVersion: StateFlow<Int> = _screenVersion

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError

    /** Set by the explicit Close button, and by a shell that ended on its own (not by
     *  [switchTo]'s close-before-reopen) so a Close reads as "Session closed." + Reconnect, while
     *  switching target still just reconnects immediately with no interstitial. */
    private val _closedByUser = MutableStateFlow(false)
    val closedByUser: StateFlow<Boolean> = _closedByUser

    private val _pendingTrust = MutableStateFlow<Trust?>(null)
    val pendingTrust: StateFlow<Trust?> = _pendingTrust
    private var trustGate: CompletableDeferred<Boolean>? = null

    /** The input line draft - session state, not view state: a half-typed command must survive
     *  a tab switch (same bug class as the composer-draft-text fix). */
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input
    fun setInput(value: String) { _input.value = value }

    /** Command history, oldest first. It lives here rather than in the composable for the same
     *  reason the draft does: navigating away must not erase it. */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    /** Where up/down currently sit in [history]; `size` means "not recalling - showing the draft". */
    private var historyCursor = 0
    private var draftBeforeRecall: String? = null

    /** The session the facade currently drives (null = no shell started / closed). */
    private var current: TerminalSession? = null
    private var launchFailed = false

    init {
        // Version bumps for the view, from the screen model itself (reader thread or main).
        pty.attach(object : TerminalRenderer {
            override fun onScreenChanged(screen: ScreenBuffer) { _screenVersion.update { it + 1 } }
        })
        // Mirror the manager's state into the flows the screens already observe.
        scope.launch { manager.sessions.collect { syncFromManager() } }
        // Bank bounded scrollback snapshots whenever the app leaves the foreground.
        platform.registerBackgroundHook { manager.persistSnapshots("background") }
    }

    private fun syncFromManager() {
        val cur = current ?: return
        when (manager.info(cur.id)?.state) {
            SessionState.Running -> {
                _connecting.value = false
                if (_shell.value == null) _shell.value = SessionShell(cur)
            }
            is SessionState.Exited -> {
                _connecting.value = false
                _shell.value = null
                // A shell that ended on its own is "closed" (honest: not live). A failed launch is
                // not - it keeps the retry-on-next-open behaviour with its error shown.
                if (!launchFailed) _closedByUser.value = true
            }
            else -> {}
        }
    }

    /** A [ShellSession] view of the current session, for callers that hold one. */
    private inner class SessionShell(private val session: TerminalSession) : ShellSession {
        override suspend fun send(text: String) = session.sendText(text)
        override suspend fun resize(rows: Int, cols: Int) = session.resize(rows, cols)
        override suspend fun close() { if (current === session) userCloseShell() }
    }

    /** Up arrow - step back to an older command, stashing whatever was being typed so down can return it. */
    fun historyBack() {
        val h = _history.value
        if (h.isEmpty()) return
        if (historyCursor >= h.size) draftBeforeRecall = _input.value
        historyCursor = (historyCursor - 1).coerceAtLeast(0)
        _input.value = h[historyCursor]
    }

    /** Down arrow - step forward, past the newest entry back to the stashed draft. */
    fun historyForward() {
        val h = _history.value
        if (historyCursor >= h.size) return
        historyCursor += 1
        _input.value = if (historyCursor >= h.size) draftBeforeRecall.orEmpty() else h[historyCursor]
    }

    private fun recordHistory(line: String) {
        val entry = line.trim()
        // Consecutive repeats are noise to walk back through, and a blank line isn't a command.
        if (entry.isNotEmpty() && _history.value.lastOrNull() != entry) {
            _history.update { (it + entry).takeLast(MAX_HISTORY) }
        }
        historyCursor = _history.value.size
        draftBeforeRecall = null
    }

    /** Tear down the current shell (if any) and forget it; the screen keeps its transcript. */
    private fun dropCurrent() {
        val s = current
        current = null
        _shell.value = null
        if (s != null) manager.close(s.id, confirmed = true)
    }

    private fun closeShell() { dropCurrent() }

    /** The Close button's action: close, and say so - see [closedByUser]. */
    fun userCloseShell() {
        closeShell()
        _closedByUser.value = true
    }

    // Same identity resolution as DevicesFacet: a pinned key/password secret if present, else
    // the ssh-agent - the host's trust decision belongs to the Remote screen, not this action.
    private fun identityFor(h: RemoteHost): Identity {
        val ref = hostStore.hostSecret(h.alias)
        return when {
            ref == null -> Identity.Agent
            ref.isKey -> Identity.PublicKey(ref.id)
            else -> Identity.Password(ref.id)
        }
    }

    /** Records a launch failure for the screens, then lets the manager report it in the transcript. */
    private fun reporting(launcher: SessionLauncher, fallback: String) = SessionLauncher { size, io ->
        try {
            launcher.launch(size, io)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            launchFailed = true
            _connectError.value = e.message ?: fallback
            throw e
        }
    }

    private fun start(title: String, launcher: SessionLauncher, fallback: String) {
        dropCurrent()
        _connecting.value = true
        _connectError.value = null
        launchFailed = false
        current = manager.create(
            defaultTitle = title,
            launcher = reporting(launcher, fallback),
            size = TermSize(pty.screen.rows, pty.screen.cols),
            channel = pty,
        )
    }

    private fun openPhone() =
        start("This phone", PhoneShellLauncher(files) { platform.processEnvironment() }, "couldn't open a shell")

    private fun openRemote(alias: String) {
        val host = hostStore.hosts.value.firstOrNull { it.alias == alias }
        if (host == null) {
            _connectError.value = "no saved host named \"$alias\""
            return
        }
        start(alias, remoteLauncher(host), "couldn't connect - is it trusted yet in Settings → Global?")
    }

    private fun remoteLauncher(host: RemoteHost) = SessionLauncher { size, io ->
        val driver = RemoteSessionDriver(newTransport(), hostStore.knownHosts.value)
        driver.open(host, identityFor(host)) { verdict ->
            val gate = CompletableDeferred<Boolean>()
            _pendingTrust.value = verdict; trustGate = gate
            val ok = gate.await()
            _pendingTrust.value = null; trustGate = null
            if (ok && verdict !is Trust.Vetted) {
                val key = (verdict as? Trust.Unknown)?.presented ?: (verdict as Trust.Changed).presented
                hostStore.pin(host.endpoint, key)
            }
            ok
        }
        val shell = driver.shell { chunk: ExecChunk -> io.onOutput(chunk.bytes, chunk.bytes.size) }
        // sshj's allocateDefaultPTY() is a fixed 80x24; tell the channel the size the phone is
        // actually rendering so the first prompt does not paint against the wrong width.
        runCatching { shell.resize(size.rows, size.cols) }
        ShellSessionBackend(shell) { s -> scope.launch { runCatching { s.close() } } }
    }

    /** The trust dialog's verdict lands here. */
    fun resolveTrust(accepted: Boolean) { trustGate?.complete(accepted) }

    /** Reconnect to whatever's currently active (phone, or the same remote alias) - the
     *  Reconnect affordance after a Close, and Retry after a failed connect. */
    fun reconnect() {
        _closedByUser.value = false
        when {
            _mode.value == PHONE -> openPhone()
            _mode.value == REMOTE && _remoteAlias.value != null -> openRemote(_remoteAlias.value!!)
        }
    }

    fun switchTo(newMode: String, alias: String? = null) {
        closeShell()
        pty.reset()
        _mode.value = newMode
        _remoteAlias.value = alias
        _connectError.value = null
        // Switching target reconnects immediately (ensureOpen) - never show the "closed"
        // interstitial for that path, only for an explicit Close.
        _closedByUser.value = false
    }

    /** Entering Remote with no alias picked and exactly one saved host: use it, no extra pick. */
    fun autoPickSingleHost() {
        val hosts = hostStore.hosts.value
        if (_mode.value == REMOTE && _remoteAlias.value == null && hosts.size == 1) {
            _remoteAlias.value = hosts.first().alias
        }
    }

    /** Auto-connect on entering the terminal and whenever the target changes - no "Open shell"
     *  gate button. Deliberately a no-op after an explicit Close (that's Reconnect's job) and
     *  while a connect is already in flight (two doors may both call this on mount). */
    fun ensureOpen() {
        if (_shell.value != null || _connecting.value || _closedByUser.value) return
        when {
            _mode.value == PHONE -> openPhone()
            _mode.value == REMOTE && _remoteAlias.value != null -> openRemote(_remoteAlias.value!!)
        }
    }

    /** Send a line (Enter semantics - appends the newline itself), recording it for up-arrow recall. */
    fun sendLine(line: String) {
        recordHistory(line)
        current?.sendText(line + "\n")
    }

    /**
     * The rendered grid reported its real size - drive it into the screen model *and* the live
     * shell (TIOCSWINSZ locally, SSH window-change remotely).
     *
     * Idempotent, because the view re-reports on every layout pass and a redundant SIGWINCH makes
     * a full-screen app repaint for nothing. Whether the shell honours the new size on a real
     * phone is owner-verified - there is no device here.
     */
    fun resize(rows: Int, cols: Int) {
        if (rows < 1 || cols < 1) return
        if (rows == pty.screen.rows && cols == pty.screen.cols) return
        val s = current
        if (s != null) s.resize(rows, cols) else pty.resize(rows, cols)
        _screenVersion.update { it + 1 }
    }

    /** Send raw text/control bytes exactly as given (Ctrl-C = 0x03 etc.). */
    fun sendRaw(text: String) { current?.sendText(text) }

    /** A key press, encoded for the application's current modes (cursor keys, keypad, ...). */
    fun sendKey(event: KeyEvent): Boolean = current?.sendKey(event) ?: false

    /** Pasted text: sanitised, and bracketed when the application asked for it. */
    fun paste(text: String) { current?.paste(text) }

    /** /clear - wipe the grid without touching the shell. */
    fun clearScreen() {
        pty.screen.clear()
        _screenVersion.update { it + 1 }
    }
}
