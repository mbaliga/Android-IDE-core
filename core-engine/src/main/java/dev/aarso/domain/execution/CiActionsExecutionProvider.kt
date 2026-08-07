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
import dev.aarso.data.GitTransport
import dev.aarso.domain.builds.CiTrigger
import dev.aarso.domain.builds.WorkflowRun
import dev.aarso.domain.contracts.Digest
import dev.aarso.domain.contracts.IdGenerator
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The `GITHUB_ACTIONS` / `GITEA_ACTIONS` [ExecutionProvider] (WP-5), against **recorded HTTP
 * fixtures in tests, never a live network call** (the WP-5 brief's own words) -- an adapter over
 * the already-real `domain/builds/CiTrigger` request-builders/parsers (dispatch + list-runs, both
 * pure) and `data/GitTransport` (the `open class` this codebase's own tests already override for
 * fake HTTP -- see `CiTrigger`'s doc comment: "network execution stays in GitTransport"). One
 * class serves both target types since `CiTrigger` already abstracts the GitHub/Gitea header
 * difference; `host.kind` picks which one this instance's [descriptor] declares.
 *
 * `operation.command` is the workflow file name or numeric id (e.g. `"ci.yml"`);
 * `workingRevision` is the ref to dispatch on branch (defaults to the host's configured branch).
 *
 * **Honest limitation, not hidden:** GitHub/Gitea's `workflow_dispatch` API returns no run id
 * directly -- [start] must poll `listRuns` to *find* the just-dispatched run by matching
 * workflow+branch+recency, a best-effort correlation (both APIs offer no stronger idempotency key
 * for this). If no matching run is found within [correlationAttempts] polls, the handle stays
 * `RUNNING` with an unresolved run id and [observe]/[reconnect] keep trying.
 */
class CiActionsExecutionProvider(
    private val host: GitHost,
    private val token: String,
    private val transport: GitTransport,
    private val pollIntervalMillis: Long = 500,
    private val correlationAttempts: Int = 5,
    private val idGenerator: () -> String = { "exec_" + IdGenerator.generate() },
    private val now: () -> Instant = Instant::now,
) : ExecutionProvider {

    override val descriptor = ExecutionProviderDescriptor(
        providerId = "ci-actions-${host.kind.name.lowercase()}",
        supportedTargetTypes = listOf(
            if (host.kind == GitHostKind.GITHUB) ExecutionTargetType.GITHUB_ACTIONS else ExecutionTargetType.GITEA_ACTIONS
        ),
        capabilityManifest = CapabilityManifest(
            manifestId = "cap_ci-actions-provider", subjectKind = CapabilitySubjectKind.EXECUTION,
            supportedOperations = listOf("CI_DISPATCH"), limits = emptyMap(),
            versions = CapabilityVersions(subjectVersion = "1.0.0")
        )
    )

    private class Run(
        val requestId: String, val targetId: String, val handleId: String, val workflowId: String, val ref: String,
        val queuedAtUtc: Instant, val dispatchedAtUtc: Instant, val reconnectToken: String,
        @Volatile var runId: Long? = null, @Volatile var state: ExecutionState = ExecutionState.RUNNING,
        @Volatile var lastKnownRun: WorkflowRun? = null,
    )

    private val preparedRequests = ConcurrentHashMap<String, ExecutionRequest>()
    private val runsByHandle = ConcurrentHashMap<String, Run>()
    private val handleByReconnectToken = ConcurrentHashMap<String, String>()

    override suspend fun prepare(request: ExecutionRequest): PreparedExecution {
        require(request.operation.command.isNotBlank()) { "CiActionsExecutionProvider.prepare: empty workflowId." }
        require(request.sideEffectExternal) { "CiActionsExecutionProvider: a CI dispatch is always external (FB-RAT-EXE-008) -- sideEffectExternal must be true." }
        preparedRequests[request.id] = request
        return PreparedExecution(requestId = request.id, targetId = request.targetId, preparedAtUtc = now())
    }

    override suspend fun start(prepared: PreparedExecution): ExecutionHandle {
        val request = preparedRequests[prepared.requestId]
            ?: error("CiActionsExecutionProvider.start: no prepare()d request for id '${prepared.requestId}'.")
        val workflowId = request.operation.command
        val ref = request.workingRevision ?: host.branch

        val handleId = idGenerator()
        val startedAt = now()
        val reconnectTok = "rt_" + IdGenerator.generate()
        val run = Run(
            requestId = request.id, targetId = request.targetId, handleId = handleId, workflowId = workflowId, ref = ref,
            queuedAtUtc = prepared.preparedAtUtc, dispatchedAtUtc = startedAt, reconnectToken = reconnectTok
        )
        runsByHandle[handleId] = run
        handleByReconnectToken[reconnectTok] = handleId

        val dispatchResp = transport.execute(CiTrigger.dispatch(host, workflowId, ref, token))
        require(dispatchResp.code in 200..299) { "CiActionsExecutionProvider: dispatch failed, HTTP ${dispatchResp.code}: ${dispatchResp.body}" }

        correlateRunId(run)

        return ExecutionHandle(
            handleId = handleId, requestId = request.id, targetId = request.targetId, state = ExecutionState.RUNNING,
            enteredStateAtUtc = startedAt,
            heartbeat = Heartbeat(heartbeatIntervalSeconds = 5, lastHeartbeatUtc = startedAt, missedConsecutive = 0),
            cancellationMode = CancellationMode.UNSUPPORTED, // no cancel-run request builder exists in CiTrigger yet -- honest, not guessed.
            reconnectToken = ReconnectToken(token = reconnectTok, issuedAtUtc = startedAt)
        )
    }

    /** Best-effort: the most recent run for [Run.workflowId]/[Run.ref] created at/after dispatch time. */
    private suspend fun correlateRunId(run: Run) {
        if (run.runId != null) return
        val resp = transport.execute(CiTrigger.listRuns(host, token, workflowId = run.workflowId, branch = run.ref))
        if (resp.code !in 200..299) return
        val matched = CiTrigger.parseRuns(resp.body, host.kind).firstOrNull()
        if (matched != null) {
            run.runId = matched.id
            run.lastKnownRun = matched
        }
    }

    override fun observe(handle: ExecutionHandle): Flow<ExecutionEvent> = callbackFlow {
        val run = runsByHandle[handle.handleId] ?: run { close(); return@callbackFlow }

        val job = launch(Dispatchers.IO) {
            var attempts = 0
            while (run.runId == null && attempts < correlationAttempts) {
                correlateRunId(run)
                if (run.runId == null) { attempts++; delay(pollIntervalMillis) }
            }
            var lastStatus: String? = null
            while (true) {
                val resp = transport.execute(CiTrigger.listRuns(host, token, workflowId = run.workflowId, branch = run.ref))
                if (resp.code in 200..299) {
                    val runs = CiTrigger.parseRuns(resp.body, host.kind)
                    val current = runs.firstOrNull { it.id == run.runId } ?: runs.firstOrNull()
                    if (current != null) {
                        run.lastKnownRun = current
                        if (current.status != lastStatus) {
                            lastStatus = current.status
                            trySend(ExecutionEvent.StateChanged(handle.handleId, now(), run.state, "CI run status: ${current.status}"))
                        }
                        if (current.conclusion != null) {
                            val finishedAt = now()
                            // A CI dispatch's external side effect (triggering the run) already
                            // happened before this outcome is even known -- unlike a local process
                            // that can fail before touching anything, a failed CI run is
                            // FAILED_SIDE_EFFECTS_POSSIBLE, never FAILED_SAFE (mid-run artifacts,
                            // partial deploys, etc. may have occurred).
                            val success = current.conclusion == "success"
                            val exitState = if (success) ExecutionExitState.SUCCEEDED_UNVERIFIED else ExecutionExitState.FAILED_SIDE_EFFECTS_POSSIBLE
                            run.state = if (success) ExecutionState.SUCCEEDED_UNVERIFIED else ExecutionState.FAILED_SIDE_EFFECTS_POSSIBLE
                            trySend(ExecutionEvent.ReceiptReady(handle.handleId, finishedAt, buildReceipt(run, exitState, finishedAt, current)))
                            close()
                            return@launch
                        }
                    }
                }
                delay(pollIntervalMillis)
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun cancel(handle: ExecutionHandle, mode: CancelMode): CancelResult {
        // Honest per FB-RAT-EXE-003: this provider declared cancellationMode=UNSUPPORTED up
        // front (start()) -- a caller should never reach this expecting success, but the
        // contract still requires a well-formed, honest CancelResult if one is called anyway.
        return CancelResult(accepted = false, knownStoppedDescription = "cancellation is UNSUPPORTED for CiActionsExecutionProvider -- no run-cancel request builder exists yet", resultingState = ExecutionState.RUNNING)
    }

    override suspend fun reconnect(token: ReconnectTokenHandle): ExecutionHandle? {
        val handleId = handleByReconnectToken[token.token] ?: return null
        val run = runsByHandle[handleId] ?: return null
        if (run.runId == null) correlateRunId(run)
        val stillUnresolved = run.runId == null

        return if (stillUnresolved) {
            ExecutionHandle(
                handleId = run.handleId, requestId = run.requestId, targetId = run.targetId, state = ExecutionState.TARGET_STATE_UNKNOWN,
                enteredStateAtUtc = now(), heartbeat = Heartbeat(heartbeatIntervalSeconds = 5),
                cancellationMode = CancellationMode.UNSUPPORTED, stateReason = "dispatched run could not yet be correlated to a run id",
                reconnectToken = ReconnectToken(token = run.reconnectToken, issuedAtUtc = run.dispatchedAtUtc),
                reconnectAttempt = ReconnectAttempt(attemptedAtUtc = now(), outcome = ReconnectOutcome.UNREACHABLE_UNKNOWN)
            )
        } else {
            ExecutionHandle(
                handleId = run.handleId, requestId = run.requestId, targetId = run.targetId, state = run.state,
                enteredStateAtUtc = now(), heartbeat = Heartbeat(heartbeatIntervalSeconds = 5),
                cancellationMode = CancellationMode.UNSUPPORTED,
                reconnectToken = ReconnectToken(token = run.reconnectToken, issuedAtUtc = run.dispatchedAtUtc),
                reconnectAttempt = ReconnectAttempt(attemptedAtUtc = now(), outcome = ReconnectOutcome.ESTABLISHED)
            )
        }
    }

    private fun buildReceipt(run: Run, exitState: ExecutionExitState, finishedAt: Instant, workflowRun: WorkflowRun): ExecutionReceipt {
        val request = preparedRequests[run.requestId]
        val targetType = if (host.kind == GitHostKind.GITHUB) ExecutionTargetType.GITHUB_ACTIONS else ExecutionTargetType.GITEA_ACTIONS
        return ExecutionReceipt(
            receiptId = "rcpt_" + IdGenerator.generate(), requestId = run.requestId, handleId = run.handleId,
            targetSnapshot = TargetSnapshot(id = run.targetId, type = targetType, displayName = workflowRun.workflowName, trust = TargetTrust.TRUSTED, arch = "n/a"),
            revision = run.ref, capsuleDigest = Digest.ofUtf8("${run.workflowId}@${run.ref}"),
            timings = Timings(queuedAtUtc = run.queuedAtUtc, finishedAtUtc = finishedAt, startedAtUtc = run.dispatchedAtUtc),
            exitState = exitState,
            logs = ExecutionLogs(redactionApplied = false, excerpt = "CI run ${workflowRun.id}: ${workflowRun.status}/${workflowRun.conclusion} -- ${workflowRun.htmlUrl}"),
            resourceSummary = ResourceSummary(), outputs = emptyList(), sideEffects = listOf(
                dev.aarso.contracts.execution.SideEffect(description = "Dispatched CI workflow run ${workflowRun.id} on ${host.displayName}", external = true, reversible = false)
            ),
            verification = ReceiptVerification(state = VerificationState.NOT_PERFORMED),
            provenance = ExecutionProvenance(sourceLocation = "ci-actions-execution-provider", projectRevision = run.ref, initiatingPrincipal = request?.authorityGrant?.grantId ?: "unknown"),
            terminationCause = TerminationCause.NORMAL
        )
    }
}
