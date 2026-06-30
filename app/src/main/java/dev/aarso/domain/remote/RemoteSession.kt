package dev.aarso.domain.remote

/**
 * The session lifecycle as an explicit state machine, plus the exec request/stream framing.
 * Modelling the lifecycle purely (rather than letting a transport's callbacks imply it) is
 * what lets the UI render *where we are* materially — connecting vs. authenticating vs. a
 * live stream — and what lets a trust prompt suspend the machine at exactly one place.
 *
 * No sockets here; [RemoteTransport] drives real I/O and feeds these transitions. Pure; JVM-tested.
 */

/** Where a session is. Trust is a first-class stop: we pause to let the user decide. */
sealed interface SessionState {
    data object Disconnected : SessionState
    data object Connecting : SessionState

    /** Transport connected; presented [key] needs a [Trust] verdict before auth. */
    data class TrustCheck(val key: HostKey, val verdict: Trust) : SessionState

    data object Authenticating : SessionState

    /** Authenticated and idle — ready to exec or open a channel. */
    data object Ready : SessionState

    /** A command is streaming. */
    data object Running : SessionState

    data object Closed : SessionState

    /** Terminal failure with a legible [reason] (the machine's own words where possible). */
    data class Failed(val reason: String) : SessionState
}

/** A command to run on the remote, with optional env and a pty (for interactive/terminal use). */
data class ExecRequest(
    val command: String,
    val env: Map<String, String> = emptyMap(),
    val pty: Boolean = false,
    /** Working directory, if the transport should `cd` first. */
    val cwd: String? = null,
) {
    init { require(command.isNotBlank()) { "empty command" } }
}

/** One framed chunk of the remote's output stream — its raw voice, tagged by stream. */
data class ExecChunk(val stream: StdStream, val bytes: ByteArray) {
    enum class StdStream { OUT, ERR }

    // ByteArray needs value-equality for tests.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ExecChunk && stream == other.stream && bytes.contentEquals(other.bytes))
    override fun hashCode(): Int = 31 * stream.hashCode() + bytes.contentHashCode()
}

/** The terminal result of an exec: the process exit code (the honest success/fail signal). */
data class ExecResult(val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * The legal-transition table for [SessionState]. Centralising it means an illegal jump
 * (e.g. Ready→TrustCheck) is a caught bug, not a silent UI glitch, and the machine reads
 * top-to-bottom. The transport calls [advance]; an illegal move throws.
 */
object SessionMachine {

    /** Whether [from] → [to] is a legal transition. */
    fun canTransition(from: SessionState, to: SessionState): Boolean = when (from) {
        is SessionState.Disconnected -> to is SessionState.Connecting
        is SessionState.Connecting -> to is SessionState.TrustCheck || to is SessionState.Failed || to is SessionState.Closed
        is SessionState.TrustCheck ->
            // Vetted/accepted → authenticate; rejected/aborted → closed; error → failed.
            to is SessionState.Authenticating || to is SessionState.Closed || to is SessionState.Failed
        is SessionState.Authenticating -> to is SessionState.Ready || to is SessionState.Failed || to is SessionState.Closed
        is SessionState.Ready -> to is SessionState.Running || to is SessionState.Closed || to is SessionState.Failed
        is SessionState.Running -> to is SessionState.Ready || to is SessionState.Closed || to is SessionState.Failed
        is SessionState.Closed -> false
        is SessionState.Failed -> false
    }

    /** Advance the machine or throw on an illegal transition. */
    fun advance(from: SessionState, to: SessionState): SessionState {
        require(canTransition(from, to)) { "illegal session transition: $from -> $to" }
        return to
    }

    /** A state is terminal when no further transition is possible. */
    fun isTerminal(state: SessionState): Boolean =
        state is SessionState.Closed || state is SessionState.Failed
}
