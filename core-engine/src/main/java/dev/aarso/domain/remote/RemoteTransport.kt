package dev.aarso.domain.remote

/**
 * The seam between the pure remote model and real SSH/SFTP I/O. The data layer implements
 * this over a maintained JVM SSH library (sshj or mwiede/jsch — chosen + wired in the data
 * layer, owner-verified on device; deliberately NOT a dependency of this pure module). Tests
 * drive a fake implementation, so the whole session/trust/exec flow is JVM-verifiable without
 * a socket.
 *
 * Contract notes:
 * - [connect] returns the server's presented [HostKey]; the caller classifies it with
 *   [KnownHosts] and only then calls [authenticate]. The transport must NOT auto-trust.
 * - Output is delivered as [ExecChunk]s in order — the remote's raw voice, never reworded.
 */
interface RemoteTransport {

    /** Open the TCP/SSH transport to [host]; return the presented host key for trust classification. */
    suspend fun connect(host: RemoteHost): HostKey

    /** Authenticate the connected session with [identity]. Throws on auth failure. */
    suspend fun authenticate(identity: Identity)

    /**
     * Run [request], delivering output chunks to [onChunk] as they arrive, and return the
     * [ExecResult] (exit code) when the command finishes.
     */
    suspend fun exec(request: ExecRequest, onChunk: (ExecChunk) -> Unit): ExecResult

    /** Perform an [SftpOp]. For [SftpOp.List], returns the entries; otherwise an empty list. */
    suspend fun sftp(op: SftpOp): List<SftpEntry>

    /**
     * Open an interactive PTY shell, delivering output to [onOutput] as it arrives (the remote's
     * raw stream, fed to a [dev.aarso.domain.remote.term.VtParser]). Returns a [ShellSession] for
     * sending keystrokes and resizing. Optional — a transport without interactive support may
     * throw [UnsupportedOperationException].
     */
    suspend fun shell(onOutput: (ExecChunk) -> Unit): ShellSession =
        throw UnsupportedOperationException("interactive shell not supported")

    /** Close the session. */
    suspend fun close()
}

/** A live interactive shell over a [RemoteTransport]. Keystrokes in, [ExecChunk]s out. */
interface ShellSession {
    /** Send raw input (e.g. a command + "\n", or a control byte) to the shell. */
    suspend fun send(text: String)

    /** Tell the remote the terminal window size changed. */
    suspend fun resize(rows: Int, cols: Int)

    /** Close the shell channel. */
    suspend fun close()
}
