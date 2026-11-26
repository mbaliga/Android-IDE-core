package dev.fonebrew.domain.terminal

/** Lifecycle of one terminal session. A dead shell is [Exited], never "running". */
sealed interface SessionState {
    /** Launch requested, no shell yet. */
    data object Starting : SessionState
    data object Running : SessionState
    /** The shell is gone. [status] is the exit code, a negative signal number if it was killed,
     *  or null when it could not be determined (unknown stays unknown). Exited sessions are restartable. */
    data class Exited(val status: Int?) : SessionState
}

/** What is in the pty's foreground, when the kernel will tell us cheaply. Never a guess. */
sealed interface ForegroundProcess {
    /** Could not be determined (not a local pty, tcgetpgrp failed, ...). */
    data object Unknown : ForegroundProcess
    /** The shell itself owns the terminal: nothing else is running in the foreground. */
    data object Shell : ForegroundProcess
    /** Another process group is in the foreground. [name] is from /proc when readable, else null. */
    data class Busy(val pgid: Int, val name: String?) : ForegroundProcess
}

/** Signals deliverable to the foreground process group. Linux/Android numbering. */
enum class FgSignal(val number: Int) { HUP(1), INT(2), QUIT(3), TSTP(20) }

data class SessionInfo(
    val id: Int,
    /** Title shown to the user: the OSC title if the stream set one, else [defaultTitle]. */
    val title: String,
    val defaultTitle: String,
    val cwd: String? = null,
    val cwdHost: String? = null,
    val state: SessionState = SessionState.Starting,
    /** New output arrived while this session was not the active one. */
    val activity: Boolean = false,
    /** BEL arrived while this session was not the active one. */
    val bell: Boolean = false,
    val foreground: ForegroundProcess = ForegroundProcess.Unknown,
    val restartCount: Int = 0,
) {
    val isLive: Boolean get() = state is SessionState.Starting || state is SessionState.Running
    val isRestartable: Boolean get() = state is SessionState.Exited
}

/** What closing a session needs. */
sealed interface CloseDecision {
    data object NotFound : CloseDecision
    /** Nothing is running (or the session is already dead): close without asking. */
    data object CloseNow : CloseDecision
    /** Something is running in the foreground: ask first. */
    data class ConfirmBusy(val processName: String?) : CloseDecision
    /** The foreground could not be determined: ask first rather than guess it is idle. */
    data object ConfirmUnknown : CloseDecision
}

/**
 * The pure state machine behind multiple terminal sessions: stable ids (never reused within a
 * run), titles, cwd, running / exited / restartable states, activity and bell flags, the
 * foreground indicator and the close-confirmation rule. No I/O, no threads (the manager
 * serialises access); JVM-tested.
 */
class SessionRegistry {
    private val list = ArrayList<SessionInfo>()
    private var nextId = 1

    /** The session whose output counts as seen (its activity/bell flags stay clear). */
    var activeId: Int? = null
        private set

    val sessions: List<SessionInfo> get() = list.toList()
    val liveCount: Int get() = list.count { it.isLive }

    fun get(id: Int): SessionInfo? = list.firstOrNull { it.id == id }

    /** Registers a new session in [SessionState.Starting]; the first one becomes active. */
    fun create(defaultTitle: String): SessionInfo {
        val info = SessionInfo(id = nextId++, title = defaultTitle, defaultTitle = defaultTitle)
        list.add(info)
        if (activeId == null) activeId = info.id
        return info
    }

    fun select(id: Int): Boolean {
        if (get(id) == null) return false
        activeId = id
        update(id) { it.copy(activity = false, bell = false) }
        return true
    }

    fun markRunning(id: Int) = update(id) { if (it.state is SessionState.Exited) it else it.copy(state = SessionState.Running) }

    fun markExited(id: Int, status: Int?) =
        update(id) { it.copy(state = SessionState.Exited(status), foreground = ForegroundProcess.Unknown) }

    /** Exited -> Starting, keeping id, title and history. False if the session is not restartable. */
    fun beginRestart(id: Int): Boolean {
        val s = get(id) ?: return false
        if (!s.isRestartable) return false
        update(id) { it.copy(state = SessionState.Starting, restartCount = it.restartCount + 1, activity = false, bell = false) }
        return true
    }

    fun setTitle(id: Int, title: String) = update(id) { it.copy(title = title.ifBlank { it.defaultTitle }) }
    fun setCwd(id: Int, cwd: String?, host: String?) = update(id) { it.copy(cwd = cwd, cwdHost = host) }
    fun setForeground(id: Int, fg: ForegroundProcess) =
        update(id) { if (it.isLive) it.copy(foreground = fg) else it }

    fun noteOutput(id: Int) { if (id != activeId) update(id) { it.copy(activity = true) } }
    fun noteBell(id: Int) { if (id != activeId) update(id) { it.copy(bell = true) } }

    /** Whether closing [id] needs the user's confirmation. */
    fun closeDecision(id: Int): CloseDecision {
        val s = get(id) ?: return CloseDecision.NotFound
        // Exited: nothing to lose. Starting: no shell exists yet.
        if (!s.isLive || s.state is SessionState.Starting) return CloseDecision.CloseNow
        return when (val fg = s.foreground) {
            ForegroundProcess.Shell -> CloseDecision.CloseNow
            is ForegroundProcess.Busy -> CloseDecision.ConfirmBusy(fg.name)
            ForegroundProcess.Unknown -> CloseDecision.ConfirmUnknown
        }
    }

    /** Removes the session; the active one moves to a neighbour. */
    fun remove(id: Int): Boolean {
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list.removeAt(idx)
        if (activeId == id) activeId = list.getOrNull(idx)?.id ?: list.getOrNull(idx - 1)?.id
        return true
    }

    private fun update(id: Int, f: (SessionInfo) -> SessionInfo) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = f(list[i])
    }
}
