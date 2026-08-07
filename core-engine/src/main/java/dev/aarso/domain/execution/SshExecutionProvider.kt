package dev.aarso.domain.execution

import dev.aarso.contracts.common.CapabilityManifest
import dev.aarso.contracts.common.CapabilitySubjectKind
import dev.aarso.contracts.common.CapabilityVersions
import dev.aarso.contracts.execution.CancelMode
import dev.aarso.contracts.execution.CancelResult
import dev.aarso.contracts.execution.CancellationMode
import dev.aarso.contracts.execution.ExecutionEvent
import dev.aarso.contracts.execution.ExecutionExitState
import dev.aarso.contracts.execution.ExecutionHandle
import dev.aarso.contracts.execution.ExecutionLogs
import dev.aarso.contracts.execution.ExecutionProvenance
import dev.aarso.contracts.execution.ExecutionProvider
import dev.aarso.contracts.execution.ExecutionProviderDescriptor
import dev.aarso.contracts.execution.ExecutionReceipt
import dev.aarso.contracts.execution.ExecutionRequest
import dev.aarso.contracts.execution.ExecutionState
import dev.aarso.contracts.execution.ExecutionTargetType
import dev.aarso.contracts.execution.Heartbeat
import dev.aarso.contracts.execution.PreparedExecution
import dev.aarso.contracts.execution.ReceiptVerification
import dev.aarso.contracts.execution.ReconnectAttempt
import dev.aarso.contracts.execution.ReconnectOutcome
import dev.aarso.contracts.execution.ReconnectToken
import dev.aarso.contracts.execution.ReconnectTokenHandle
import dev.aarso.contracts.execution.ResourceSummary
import dev.aarso.contracts.execution.TargetSnapshot
import dev.aarso.contracts.execution.TargetTrust
import dev.aarso.contracts.execution.TerminationCause
import dev.aarso.contracts.execution.Timings
import dev.aarso.contracts.execution.VerificationState
import dev.aarso.domain.contracts.Digest
import dev.aarso.domain.contracts.IdGenerator
import dev.aarso.domain.remote.ExecChunk
import dev.aarso.domain.remote.ExecRequest
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.KnownHosts
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.domain.remote.RemoteSessionDriver
import dev.aarso.domain.remote.RemoteTransport
import dev.aarso.domain.remote.Trust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The `SSH_HOST` / `RASPBERRY_PI` [ExecutionProvider] (WP-5) -- an **adapter over the already-real
 * SSH spine** (`domain/remote/RemoteSessionDriver`/`RemoteTransport`/`KnownHosts`, WP0_SURVEY.md
 * §1(h)), not a reimplementation. `RASPBERRY_PI` shares this provider with `SSH_HOST` rather than
 * getting its own class: per this codebase's existing "Arduino-via-Pi" precedent (CLAUDE.md's
 * Devices surface), a Pi target IS an SSH-reachable host -- the distinction is which
 * [ExecutionTargetType] a caller declares, not a different transport.
 *
 * Every connection goes through [RemoteSessionDriver.open]'s real trust gate
 * (`KnownHosts.classify` -> [approveTrust] for Unknown/Changed keys, silent proceed only for
 * Vetted) -- this class adds no shortcut around it. [transportFactory] produces one
 * [RemoteTransport] per execution (a fresh SSH connection per request, not a shared pool this
 * pass) via [dev.aarso.data.remote.SshjTransport] in production; JVM tests inject a fake
 * transport, the same pattern `domain/remote`'s own doc comments already establish
 * ("Tests drive a fake implementation, so the whole session/trust/exec flow is JVM-verifiable
 * without a socket") -- this pass does NOT have a real loopback sshd available in this sandbox
 * (checked: no `sshd` binary), so "over a loopback sshd" from the WP-5 brief is honestly not what
 * is achieved here; see the gate report.
 */
class SshExecutionProvider(
    private val transportFactory: () -> RemoteTransport,
    private val hostResolver: (targetId: String) -> RemoteHost,
    private val identityResolver: (targetId: String) -> Identity,
    initialKnownHosts: KnownHosts = KnownHosts(),
    private val approveTrust: suspend (Trust) -> Boolean = { false },
    private val idGenerator: () -> String = { "exec_" + IdGenerator.generate() },
    private val now: () -> Instant = Instant::now,
) : ExecutionProvider {

    @Volatile var knownHosts: KnownHosts = initialKnownHosts
        private set

    override val descriptor = ExecutionProviderDescriptor(
        providerId = "ssh-remote",
        supportedTargetTypes = listOf(ExecutionTargetType.SSH_HOST, ExecutionTargetType.RASPBERRY_PI),
        capabilityManifest = CapabilityManifest(
            manifestId = "cap_ssh-execution-provider", subjectKind = CapabilitySubjectKind.EXECUTION,
            supportedOperations = listOf("ONE_SHOT_COMMAND"), limits = emptyMap(),
            versions = CapabilityVersions(subjectVersion = "1.0.0")
        )
    )

    private class Run(
        val requestId: String, val targetId: String, val handleId: String,
        val driver: RemoteSessionDriver, val queuedAtUtc: Instant, val startedAtUtc: Instant,
        val reconnectToken: String, val stdout: StringBuilder = StringBuilder(),
        @Volatile var state: ExecutionState = ExecutionState.RUNNING,
        @Volatile var lastHeartbeatUtc: Instant = Instant.EPOCH,
    )

    private val preparedRequests = ConcurrentHashMap<String, ExecutionRequest>()
    private val runsByHandle = ConcurrentHashMap<String, Run>()
    private val handleByReconnectToken = ConcurrentHashMap<String, String>()

    override suspend fun prepare(request: ExecutionRequest): PreparedExecution {
        require(request.operation.command.isNotBlank()) { "SshExecutionProvider.prepare: empty command." }
        preparedRequests[request.id] = request
        return PreparedExecution(requestId = request.id, targetId = request.targetId, preparedAtUtc = now())
    }

    override suspend fun start(prepared: PreparedExecution): ExecutionHandle {
        val request = preparedRequests[prepared.requestId]
            ?: error("SshExecutionProvider.start: no prepare()d request for id '${prepared.requestId}'.")

        val handleId = idGenerator()
        val startedAt = now()
        val driver = RemoteSessionDriver(transportFactory(), knownHosts)
        val reconnectTok = "rt_" + IdGenerator.generate()
        val run = Run(
            requestId = request.id, targetId = request.targetId, handleId = handleId, driver = driver,
            queuedAtUtc = prepared.preparedAtUtc, startedAtUtc = startedAt, reconnectToken = reconnectTok, lastHeartbeatUtc = startedAt
        )
        runsByHandle[handleId] = run
        handleByReconnectToken[reconnectTok] = handleId

        val host = hostResolver(request.targetId)
        val identity = identityResolver(request.targetId)
        driver.open(host, identity, approveTrust)
        knownHosts = driver.knownHosts // pick up any newly-pinned/re-pinned key from this open()

        // RemoteSessionDriver.open() throws on a real connect/auth failure (propagates naturally),
        // but a REJECTED trust decision is the one path that returns normally without reaching
        // Ready -- it just leaves the driver Closed. Must not report RUNNING for that case.
        if (driver.state !is dev.aarso.domain.remote.SessionState.Ready) {
            runsByHandle.remove(handleId)
            handleByReconnectToken.remove(reconnectTok)
            error("SshExecutionProvider.start: session did not reach Ready (state=${driver.state}) -- " +
                "the user's trust decision rejected this host, or the session otherwise failed to authenticate.")
        }

        return ExecutionHandle(
            handleId = handleId, requestId = request.id, targetId = request.targetId, state = ExecutionState.RUNNING,
            enteredStateAtUtc = startedAt,
            heartbeat = Heartbeat(heartbeatIntervalSeconds = 2, lastHeartbeatUtc = startedAt, missedConsecutive = 0),
            cancellationMode = CancellationMode.COOPERATIVE,
            reconnectToken = ReconnectToken(token = reconnectTok, issuedAtUtc = startedAt)
        )
    }

    /**
     * Drives the actual `driver.exec(...)` call (which streams chunks and returns the exit code
     * on completion) on a background coroutine, emitting [ExecutionEvent.OutputChunk] as chunks
     * arrive and [ExecutionEvent.ReceiptReady] once the command finishes -- mirroring
     * [LocalProcessExecutionProvider.observe]'s shape for the same reason: a real background
     * worker driving a blocking-until-done call, not a busy-poll.
     */
    override fun observe(handle: ExecutionHandle): Flow<ExecutionEvent> = callbackFlow {
        val run = runsByHandle[handle.handleId] ?: run { close(); return@callbackFlow }
        val request = preparedRequests[run.requestId]

        val job = launch(Dispatchers.IO) {
            try {
                val execResult = run.driver.exec(
                    ExecRequest(command = request?.operation?.command ?: "", env = request?.environment?.envVars ?: emptyMap())
                ) { chunk: ExecChunk ->
                    val text = String(chunk.bytes)
                    run.stdout.append(text)
                    trySend(ExecutionEvent.OutputChunk(handle.handleId, now(), text))
                }
                val finishedAt = now()
                val exitState = if (execResult.ok) ExecutionExitState.SUCCEEDED_UNVERIFIED else ExecutionExitState.FAILED_SAFE
                run.state = if (execResult.ok) ExecutionState.SUCCEEDED_UNVERIFIED else ExecutionState.FAILED_SAFE
                trySend(ExecutionEvent.StateChanged(handle.handleId, finishedAt, run.state, "exec exited with code ${execResult.exitCode}"))
                trySend(ExecutionEvent.ReceiptReady(handle.handleId, finishedAt, buildReceipt(run, exitState, finishedAt)))
                run.driver.close()
                close()
            } catch (t: Throwable) {
                val finishedAt = now()
                run.state = ExecutionState.FAILED_SAFE
                trySend(ExecutionEvent.StateChanged(handle.handleId, finishedAt, run.state, t.message ?: "ssh exec failed"))
                trySend(ExecutionEvent.ReceiptReady(handle.handleId, finishedAt, buildReceipt(run, ExecutionExitState.FAILED_SAFE, finishedAt)))
                close()
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun cancel(handle: ExecutionHandle, mode: CancelMode): CancelResult {
        val run = runsByHandle[handle.handleId]
            ?: return CancelResult(accepted = false, knownStoppedDescription = "no such handle", resultingState = ExecutionState.TARGET_STATE_UNKNOWN)
        run.driver.close()
        run.state = ExecutionState.CANCELLED
        return CancelResult(accepted = true, knownStoppedDescription = "SSH session closed (mode=$mode)", resultingState = ExecutionState.CANCELLED)
    }

    /**
     * FB-RAT-EXE-004: a remote SSH command's true state after a lost connection genuinely CAN be
     * unknown (unlike [LocalProcessExecutionProvider], which always has a truthful local
     * `Process.isAlive()`) -- an unrecognized token still returns `null` (no handle ever issued),
     * but a recognized token for a run whose driver session is no longer `Ready`/`Running`
     * reports `TARGET_STATE_UNKNOWN` rather than guessing success or failure.
     */
    override suspend fun reconnect(token: ReconnectTokenHandle): ExecutionHandle? {
        val handleId = handleByReconnectToken[token.token] ?: return null
        val run = runsByHandle[handleId] ?: return null
        val sessionLost = run.driver.state.let {
            it is dev.aarso.domain.remote.SessionState.Failed || it is dev.aarso.domain.remote.SessionState.Closed
        } && run.state !in setOf(ExecutionState.SUCCEEDED_UNVERIFIED, ExecutionState.FAILED_SAFE, ExecutionState.CANCELLED)

        return if (sessionLost) {
            ExecutionHandle(
                handleId = run.handleId, requestId = run.requestId, targetId = run.targetId, state = ExecutionState.TARGET_STATE_UNKNOWN,
                enteredStateAtUtc = now(), heartbeat = Heartbeat(heartbeatIntervalSeconds = 2, lastHeartbeatUtc = run.lastHeartbeatUtc),
                cancellationMode = CancellationMode.COOPERATIVE, stateReason = "SSH session lost before a terminal receipt was produced",
                reconnectToken = ReconnectToken(token = run.reconnectToken, issuedAtUtc = run.startedAtUtc),
                reconnectAttempt = ReconnectAttempt(attemptedAtUtc = now(), outcome = ReconnectOutcome.UNREACHABLE_UNKNOWN)
            )
        } else {
            ExecutionHandle(
                handleId = run.handleId, requestId = run.requestId, targetId = run.targetId, state = run.state,
                enteredStateAtUtc = run.lastHeartbeatUtc, heartbeat = Heartbeat(heartbeatIntervalSeconds = 2, lastHeartbeatUtc = run.lastHeartbeatUtc),
                cancellationMode = CancellationMode.COOPERATIVE,
                reconnectToken = ReconnectToken(token = run.reconnectToken, issuedAtUtc = run.startedAtUtc),
                reconnectAttempt = ReconnectAttempt(attemptedAtUtc = now(), outcome = ReconnectOutcome.ESTABLISHED)
            )
        }
    }

    private fun buildReceipt(run: Run, exitState: ExecutionExitState, finishedAt: Instant): ExecutionReceipt {
        val request = preparedRequests[run.requestId]
        return ExecutionReceipt(
            receiptId = "rcpt_" + IdGenerator.generate(), requestId = run.requestId, handleId = run.handleId,
            targetSnapshot = TargetSnapshot(id = run.targetId, type = ExecutionTargetType.SSH_HOST, displayName = "ssh-remote", trust = TargetTrust.TRUSTED, arch = "unknown"),
            revision = null, capsuleDigest = Digest.ofUtf8(request?.operation?.command ?: ""),
            timings = Timings(queuedAtUtc = run.queuedAtUtc, finishedAtUtc = finishedAt, startedAtUtc = run.startedAtUtc),
            exitState = exitState, logs = ExecutionLogs(redactionApplied = false, excerpt = run.stdout.toString().take(4096)),
            resourceSummary = ResourceSummary(), outputs = emptyList(), sideEffects = emptyList(),
            verification = ReceiptVerification(state = VerificationState.NOT_PERFORMED),
            provenance = ExecutionProvenance(sourceLocation = "ssh-execution-provider", projectRevision = request?.workingRevision ?: "unknown", initiatingPrincipal = request?.authorityGrant?.grantId ?: "unknown"),
            terminationCause = TerminationCause.NORMAL
        )
    }
}
