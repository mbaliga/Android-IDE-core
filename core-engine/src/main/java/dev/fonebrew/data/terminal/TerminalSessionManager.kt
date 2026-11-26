package dev.fonebrew.data.terminal

import dev.fonebrew.domain.remote.term.ClipboardWriteRequest
import dev.fonebrew.domain.remote.term.PtyChannel
import dev.fonebrew.domain.remote.term.ScreenBuffer
import dev.fonebrew.domain.remote.term.TerminalEvents
import dev.fonebrew.domain.remote.term.TerminalReplies
import dev.fonebrew.domain.terminal.CloseDecision
import dev.fonebrew.domain.terminal.FgSignal
import dev.fonebrew.domain.terminal.ForegroundProcess
import dev.fonebrew.domain.terminal.KeyEncoder
import dev.fonebrew.domain.terminal.KeyEvent
import dev.fonebrew.domain.terminal.MouseEncoder
import dev.fonebrew.domain.terminal.MouseEvent
import dev.fonebrew.domain.terminal.PasteEncoder
import dev.fonebrew.domain.terminal.SessionInfo
import dev.fonebrew.domain.terminal.SessionRegistry
import dev.fonebrew.domain.terminal.SessionState
import dev.fonebrew.domain.terminal.SnapshotMeta
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Result of asking to close a session. */
sealed interface CloseResult {
    data object Closed : CloseResult
    data object NotFound : CloseResult
    /** Not closed: the user must confirm (something is running, or we cannot tell). */
    data class NeedsConfirm(val decision: CloseDecision) : CloseResult
}

/**
 * Many terminal sessions over [SessionBackend]s: the Android-free adapter around the pure
 * [SessionRegistry]. It owns each session's [PtyChannel] (screen + modes), wires the stream's
 * title / cwd / bell / OSC 52 / query replies, serialises writes per session, tracks exit, and
 * optionally banks "previous session history" ([history]). JVM-testable with a fake
 * [SessionBackend]; the Android pieces (pty, service, lifecycle) are injected around it.
 *
 * Honesty rules: a session whose shell is gone is [SessionState.Exited] (and its transcript says
 * so), never shown as running; the foreground indicator is Unknown unless the backend can say;
 * OSC 52 clipboard writes wait in [TerminalSession.pendingClipboard] for the host to ask the user.
 */
class TerminalSessionManager(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val history: TerminalHistoryStore? = null,
    private val maxScrollback: Int = ScreenBuffer.DEFAULT_SCROLLBACK,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called with the live-session count whenever it changes (drives the keep-alive service). */
    private val onLiveCount: (Int) -> Unit = {},
) {
    private val lock = Any()
    private val registry = SessionRegistry()
    private val byId = LinkedHashMap<Int, TerminalSession>()
    private val runStamp = clock()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    /** Snapshot of every session, in creation order. */
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    private val _activeId = MutableStateFlow<Int?>(null)
    val activeId: StateFlow<Int?> = _activeId

    private val _liveCount = MutableStateFlow(0)
    val liveCount: StateFlow<Int> = _liveCount

    fun session(id: Int): TerminalSession? = synchronized(lock) { byId[id] }
    fun active(): TerminalSession? = synchronized(lock) { registry.activeId?.let { byId[it] } }
    fun info(id: Int): SessionInfo? = synchronized(lock) { registry.get(id) }

    /** Creates a session and starts it with [launcher] at [size]. */
    fun create(
        defaultTitle: String,
        launcher: SessionLauncher,
        size: TermSize = TermSize(24, 80),
        /** Reuse an existing screen (the facade keeps one stable [PtyChannel] across shells). */
        channel: PtyChannel? = null,
    ): TerminalSession {
        val session = synchronized(lock) {
            val info = registry.create(defaultTitle)
            val ch = channel ?: PtyChannel(size.rows, size.cols, maxScrollback = maxScrollback)
            TerminalSession(info.id, ch, launcher, this).also {
                byId[info.id] = it
                publishLocked()
            }
        }
        session.wire()
        session.launch()
        return session
    }

    fun select(id: Int) { synchronized(lock) { registry.select(id); publishLocked() } }

    /** Restart an exited session in place: same id, title and screen, a fresh shell. */
    fun restart(id: Int): Boolean {
        val s = synchronized(lock) {
            val session = byId[id] ?: return false
            if (!registry.beginRestart(id)) return false
            publishLocked()
            session
        }
        s.channel.onOutput("\r\n[session restarted]\r\n")
        s.launch()
        return true
    }

    /**
     * Close [id]. If something is running in its foreground (or that cannot be determined) and
     * [confirmed] is false, nothing is closed and [CloseResult.NeedsConfirm] says why; the caller
     * asks the user and calls again with `confirmed = true`.
     */
    fun close(id: Int, confirmed: Boolean = false): CloseResult {
        val s = session(id) ?: return CloseResult.NotFound
        s.refreshForeground()
        val decision = synchronized(lock) { registry.closeDecision(id) }
        if (decision is CloseDecision.NotFound) return CloseResult.NotFound
        if (!confirmed && (decision is CloseDecision.ConfirmBusy || decision is CloseDecision.ConfirmUnknown)) {
            return CloseResult.NeedsConfirm(decision)
        }
        persist(s, "closed")
        s.teardown()
        synchronized(lock) {
            registry.remove(id)
            byId.remove(id)
            publishLocked()
        }
        return CloseResult.Closed
    }

    /** Explicit user action (the notification's Close-all): no per-session confirmation. */
    fun closeAll() {
        for (info in sessions.value) close(info.id, confirmed = true)
    }

    /** Bank a bounded text snapshot of every session that has a transcript (call on background). */
    fun persistSnapshots(reason: String) {
        val all = synchronized(lock) { byId.values.toList() }
        for (s in all) persist(s, reason)
    }

    internal fun persist(s: TerminalSession, reason: String) {
        val store = history ?: return
        val text = s.channel.screen.transcriptText()
        if (text.isBlank()) return
        val info = info(s.id)
        val meta = SnapshotMeta(
            title = info?.title ?: "terminal",
            cwd = info?.cwd,
            savedAtMillis = clock(),
            reason = reason,
        )
        // Text is taken now (the screen may be reset right after); the file write is off-thread.
        scope.launch(io) { store.save("run$runStamp-s${s.id}", meta, text) }
    }

    // --- called by TerminalSession ---------------------------------------------------------

    internal fun <T> withRegistry(f: SessionRegistry.() -> T): T = synchronized(lock) {
        val r = registry.f()
        publishLocked()
        r
    }

    private fun publishLocked() {
        _sessions.value = registry.sessions
        _activeId.value = registry.activeId
        val live = registry.liveCount
        if (_liveCount.value != live) {
            _liveCount.value = live
            onLiveCount(live)
        }
    }

    internal val ioDispatcher get() = io
    internal val managerScope get() = scope
}

/**
 * One terminal session: its screen/modes ([channel]), its input path, and its current backend.
 * Created by [TerminalSessionManager.create]; a UI holds a reference and reads [channel] while
 * calling `sendKey` / `paste` / `resize`.
 */
class TerminalSession internal constructor(
    val id: Int,
    val channel: PtyChannel,
    private var launcher: SessionLauncher,
    private val manager: TerminalSessionManager,
) {
    @Volatile private var backend: SessionBackend? = null
    private val generation = AtomicInteger(0)
    private var writes = Channel<ByteArray>(Channel.UNLIMITED)

    private val _pendingClipboard = MutableStateFlow<ClipboardWriteRequest?>(null)
    /** An OSC 52 write the shell asked for, waiting for the user's consent. Never applied by the engine. */
    val pendingClipboard: StateFlow<ClipboardWriteRequest?> = _pendingClipboard

    private val _bellCount = MutableStateFlow(0)
    /** Incremented on every BEL, for the active session too. */
    val bellCount: StateFlow<Int> = _bellCount

    val info: SessionInfo? get() = manager.info(id)

    /** Replace what a restart launches (the holder swaps phone/remote targets). */
    fun setLauncher(l: SessionLauncher) { launcher = l }

    internal fun wire() {
        channel.events = object : TerminalEvents {
            override fun onTitle(title: String) { manager.withRegistry { setTitle(id, title) } }
            override fun onWorkingDirectory(path: String, host: String?) { manager.withRegistry { setCwd(id, path, host) } }
            override fun onBell() {
                _bellCount.value += 1
                manager.withRegistry { noteBell(id) }
            }
            override fun onClipboardWriteRequested(request: ClipboardWriteRequest) { _pendingClipboard.value = request }
        }
        // Query replies go straight back into this session's own pty input, nowhere else.
        channel.replies = TerminalReplies { seq -> enqueue(seq.toByteArray(Charsets.UTF_8)) }
    }

    /** Start (or restart) the shell. Output/exit callbacks from an older launch are ignored. */
    internal fun launch() {
        val gen = generation.incrementAndGet()
        // Fresh writer queue per launch, so keystrokes typed for a dead shell are never replayed
        // into its replacement.
        writes.close()
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        writes = queue
        val size = TermSize(channel.screen.rows, channel.screen.cols)
        val io = object : SessionIo {
            override fun onOutput(bytes: ByteArray, length: Int) {
                if (generation.get() != gen) return
                channel.onOutputBytes(bytes, length)
                manager.withRegistry { noteOutput(id) }
            }

            override fun onExit(status: Int?) {
                if (generation.get() != gen) return
                handleExit(status)
            }
        }
        manager.managerScope.launch(manager.ioDispatcher) {
            val b = try {
                launcher.launch(size, io)
            } catch (e: Exception) {
                if (generation.get() == gen) {
                    channel.onOutput("\r\n[could not start a shell: ${e.message ?: e.javaClass.simpleName}]\r\n")
                    handleExit(null)
                }
                return@launch
            }
            if (generation.get() != gen) { b.close(); return@launch }
            backend = b
            manager.withRegistry { markRunning(id) }
            for (bytes in queue) {
                if (generation.get() != gen) break
                runCatching { backend?.write(bytes) }
            }
        }
    }

    private fun handleExit(status: Int?) {
        backend = null
        channel.flushDecoder()
        val why = when {
            status == null -> "ended, status unknown"
            status < 0 -> "killed by signal ${-status}"
            else -> "exited with status $status"
        }
        channel.onOutput("\r\n[session $why]\r\n")
        manager.withRegistry { markExited(id, status) }
        manager.persist(this, "ended")
    }

    internal fun teardown() {
        generation.incrementAndGet() // ignore any further callbacks
        writes.close()
        backend?.close()
        backend = null
    }

    private fun enqueue(bytes: ByteArray) { writes.trySend(bytes) }

    // --- input ---------------------------------------------------------------------------------

    /** Raw bytes to the shell (dropped if it is not running). */
    fun sendBytes(bytes: ByteArray) { if (info?.state == SessionState.Running || info?.state == SessionState.Starting) enqueue(bytes) }
    fun sendText(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    /** A key press encoded for the application's current modes. Returns false if it produced nothing. */
    fun sendKey(event: KeyEvent): Boolean {
        val bytes = KeyEncoder.encode(event, channel.modes) ?: return false
        sendBytes(bytes); return true
    }

    /** Pasted text, sanitised and (if the application asked) bracketed. See [PasteEncoder]. */
    fun paste(text: String) = sendBytes(PasteEncoder.encode(text, channel.modes))

    /** A mouse event, if the application asked to hear about it. Returns whether anything was sent. */
    fun sendMouse(event: MouseEvent): Boolean {
        val bytes = MouseEncoder.encode(event, channel.modes) ?: return false
        sendBytes(bytes); return true
    }

    fun sendFocus(focused: Boolean): Boolean {
        val bytes = MouseEncoder.encodeFocus(focused, channel.modes) ?: return false
        sendBytes(bytes); return true
    }

    /** Resize the screen model and tell the shell (TIOCSWINSZ / SSH window-change). */
    fun resize(rows: Int, cols: Int) {
        if (rows < 1 || cols < 1) return
        channel.resize(rows, cols)
        val b = backend ?: return
        manager.managerScope.launch(manager.ioDispatcher) { runCatching { b.resize(rows, cols) } }
    }

    /**
     * Deliver [signal] to the pty's foreground process group (the explicit path, for a program
     * that disabled ISIG). The primary path stays Ctrl-C through the line discipline: send 0x03
     * and the kernel signals the group. Returns false if unsupported, refused or nothing running.
     */
    fun signalForeground(signal: FgSignal): Boolean = backend?.signalForeground(signal) ?: false

    /** Re-read the foreground indicator from the backend (cheap; call before showing it or closing). */
    fun refreshForeground() {
        val b = backend
        val fg = if (b == null) ForegroundProcess.Unknown else runCatching { b.foreground() }.getOrDefault(ForegroundProcess.Unknown)
        manager.withRegistry { setForeground(id, fg) }
    }

    /** The user's answer to the OSC 52 consent prompt. Returns the text to put on the clipboard
     *  ONLY when accepted; the caller (UI) does the clipboard write. Declining discards it. */
    fun resolveClipboard(accept: Boolean): String? {
        val req = _pendingClipboard.value ?: return null
        _pendingClipboard.value = null
        return if (accept) req.text else null
    }

    /** Restart if exited (see [TerminalSessionManager.restart]). */
    fun restart(): Boolean = manager.restart(id)
}
