package dev.aarso.domain.remote

/**
 * Drives one remote session through its lifecycle over a [RemoteTransport], enforcing the
 * trust gate. This is the orchestration the DoD calls for: connect → classify the presented
 * host key → (suspend for the user's decision on Unknown/Changed) → authenticate → exec →
 * close, advancing [SessionMachine] at every step so the held [state] is always legible.
 *
 * The trust decision is the caller's: [approveTrust] is invoked only for non-vetted keys and
 * returns true to proceed (and pin) or false to abort. A vetted key never prompts. Pure
 * orchestration over the transport seam — no I/O of its own; JVM-tested with a fake transport.
 */
class RemoteSessionDriver(
    private val transport: RemoteTransport,
    knownHosts: KnownHosts,
) {
    var knownHosts: KnownHosts = knownHosts
        private set

    var state: SessionState = SessionState.Disconnected
        private set

    private fun move(to: SessionState) { state = SessionMachine.advance(state, to) }

    /**
     * Open and authenticate a session to [host] with [identity]. For an Unknown or Changed
     * host key, [approveTrust] is consulted; returning false aborts to [SessionState.Closed].
     * On success the session is left [SessionState.Ready].
     */
    suspend fun open(
        host: RemoteHost,
        identity: Identity,
        approveTrust: suspend (Trust) -> Boolean = { false },
    ) {
        move(SessionState.Connecting)
        val presented = try {
            transport.connect(host)
        } catch (e: Exception) {
            move(SessionState.Failed(e.message ?: "connect failed")); throw e
        }

        val verdict = knownHosts.classify(host, presented)
        move(SessionState.TrustCheck(presented, verdict))

        val proceed = when (verdict) {
            is Trust.Vetted -> true
            is Trust.Unknown, is Trust.Changed -> approveTrust(verdict)
        }
        if (!proceed) {
            move(SessionState.Closed)
            transport.close()
            return
        }
        // Pin (or re-pin after an accepted change) so next time it's vetted.
        if (verdict !is Trust.Vetted) knownHosts = knownHosts.pin(host.endpoint, presented)

        move(SessionState.Authenticating)
        try {
            transport.authenticate(identity)
        } catch (e: Exception) {
            move(SessionState.Failed(e.message ?: "auth failed")); throw e
        }
        move(SessionState.Ready)
    }

    /** Run [request] on a Ready session, streaming chunks to [onChunk]; returns the exit code. */
    suspend fun exec(request: ExecRequest, onChunk: (ExecChunk) -> Unit): ExecResult {
        check(state is SessionState.Ready) { "exec requires Ready, was $state" }
        move(SessionState.Running)
        val result = try {
            transport.exec(request, onChunk)
        } catch (e: Exception) {
            move(SessionState.Failed(e.message ?: "exec failed")); throw e
        }
        move(SessionState.Ready)
        return result
    }

    /** Open an interactive shell on a Ready session (delegates to the transport). */
    suspend fun shell(onOutput: (ExecChunk) -> Unit): ShellSession {
        check(state is SessionState.Ready) { "shell requires Ready, was $state" }
        return transport.shell(onOutput)
    }

    /** Close the session if still live. */
    suspend fun close() {
        if (!SessionMachine.isTerminal(state)) {
            move(SessionState.Closed)
            transport.close()
        }
    }
}
