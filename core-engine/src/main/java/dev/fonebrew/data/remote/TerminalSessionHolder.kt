package dev.fonebrew.data.remote

import dev.fonebrew.data.RemoteHostStore
import dev.fonebrew.domain.remote.Identity
import dev.fonebrew.domain.remote.RemoteHost
import dev.fonebrew.domain.remote.RemoteSessionDriver
import dev.fonebrew.domain.remote.RemoteTransport
import dev.fonebrew.domain.remote.ShellSession
import dev.fonebrew.domain.remote.Trust
import dev.fonebrew.domain.remote.term.PtyChannel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * THE terminal session — exactly one per app process, owned by
 * [dev.fonebrew.di.AppContainer], never by a composable.
 *
 * The terminal is reachable from two doors (Chat's Terminal tab and Develop's Terminal tab), and
 * it used to die walking through either of them: every piece of session state — the pty screen,
 * the live [ShellSession], which machine you were on, even the half-typed input line — lived in
 * per-mount `remember {}` inside the facet, with a `DisposableEffect` closing the shell on
 * dispose. Net effect on the phone (owner report, 2026-08-21): "two terminals, idk how to use
 * either of them" — two doors that each spawned their *own* shell, and switching tabs killed the
 * transcript. Hoisting the whole session here means both doors now open onto the SAME live
 * shell and the SAME scrollback, and leaving the tab detaches the *view*, not the session — the
 * same relationship every desktop terminal has with its window.
 *
 * The composable keeps only view concerns (colors, layout, which hint to show); every state
 * transition — open/close/switch/reconnect/trust — lives here on the holder's own scope, so an
 * in-flight SSH connect also survives the user wandering off mid-handshake.
 *
 * Runtime behaviour (fork/exec pty, SSH) is owner-verified — no device or SSH host in the build
 * environment.
 */
class TerminalSessionHolder(
    private val filesDir: File,
    private val hostStore: RemoteHostStore,
    private val newTransport: () -> RemoteTransport,
) {
    companion object {
        const val PHONE = "phone"
        const val REMOTE = "remote"
    }

    // Main.immediate to match the rememberCoroutineScope() the facet used to run these on —
    // shell callbacks already hop threads internally; state writes stay on main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The screen grid + VT parser. One per process, like the shell it renders. */
    val pty = PtyChannel(rows = 24, cols = 80)

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

    /** Set only by the explicit Close button (not by [switchTo]'s close-before-reopen) so a
     *  deliberate Close reads as "Session closed." + Reconnect, while switching target still
     *  just reconnects immediately with no interstitial. */
    private val _closedByUser = MutableStateFlow(false)
    val closedByUser: StateFlow<Boolean> = _closedByUser

    private val _pendingTrust = MutableStateFlow<Trust?>(null)
    val pendingTrust: StateFlow<Trust?> = _pendingTrust
    private var trustGate: CompletableDeferred<Boolean>? = null

    /** The input line draft — session state, not view state: a half-typed command must survive
     *  a tab switch (same bug class as the composer-draft-text fix). */
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input
    fun setInput(value: String) { _input.value = value }

    private fun onOutput(bytes: ByteArray) {
        pty.onOutput(String(bytes, Charsets.UTF_8))
        _screenVersion.update { it + 1 }
    }

    private fun closeShell() {
        val s = _shell.value
        _shell.value = null
        scope.launch { runCatching { s?.close() } }
    }

    /** The Close button's action: close, and say so — see [closedByUser]. */
    fun userCloseShell() {
        closeShell()
        _closedByUser.value = true
    }

    // Same identity resolution as DevicesFacet: a pinned key/password secret if present, else
    // the ssh-agent — the host's trust decision belongs to the Remote screen, not this action.
    private fun identityFor(h: RemoteHost): Identity {
        val ref = hostStore.hostSecret(h.alias)
        return when {
            ref == null -> Identity.Agent
            ref.isKey -> Identity.PublicKey(ref.id)
            else -> Identity.Password(ref.id)
        }
    }

    private fun openPhone() {
        _connecting.value = true; _connectError.value = null
        scope.launch {
            runCatching {
                PtyShellSession.open(filesDir, rows = pty.screen.rows, cols = pty.screen.cols) { chunk -> onOutput(chunk.bytes) }
            }.onSuccess { _shell.value = it }.onFailure { _connectError.value = it.message ?: "couldn't open a shell" }
            _connecting.value = false
        }
    }

    private fun openRemote(alias: String) {
        val host = hostStore.hosts.value.firstOrNull { it.alias == alias }
        if (host == null) {
            _connectError.value = "no saved host named \"$alias\""
            return
        }
        _connecting.value = true; _connectError.value = null
        scope.launch {
            val driver = RemoteSessionDriver(newTransport(), hostStore.knownHosts.value)
            runCatching {
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
                driver.shell { chunk -> onOutput(chunk.bytes) }
            }.onSuccess { _shell.value = it }.onFailure { _connectError.value = it.message ?: "couldn't connect — is it trusted yet in Settings → Global?" }
            _connecting.value = false
        }
    }

    /** The trust dialog's verdict lands here. */
    fun resolveTrust(accepted: Boolean) { trustGate?.complete(accepted) }

    /** Reconnect to whatever's currently active (phone, or the same remote alias) — the
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
        // Switching target reconnects immediately (ensureOpen) — never show the "closed"
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

    /** Auto-connect on entering the terminal and whenever the target changes — no "Open shell"
     *  gate button. Deliberately a no-op after an explicit Close (that's Reconnect's job) and
     *  while a connect is already in flight (two doors may both call this on mount). */
    fun ensureOpen() {
        if (_shell.value != null || _connecting.value || _closedByUser.value) return
        when {
            _mode.value == PHONE -> openPhone()
            _mode.value == REMOTE && _remoteAlias.value != null -> openRemote(_remoteAlias.value!!)
        }
    }

    /** Send a line (Enter semantics — appends the newline itself). */
    fun sendLine(line: String) {
        scope.launch { runCatching { _shell.value?.send(line + "\n") } }
    }

    /** Send raw text/control bytes exactly as given (Ctrl-C = 0x03 etc.). */
    fun sendRaw(text: String) {
        scope.launch { runCatching { _shell.value?.send(text) } }
    }

    /** /clear — wipe the grid without touching the shell. */
    fun clearScreen() {
        pty.screen.clear()
        _screenVersion.update { it + 1 }
    }
}
