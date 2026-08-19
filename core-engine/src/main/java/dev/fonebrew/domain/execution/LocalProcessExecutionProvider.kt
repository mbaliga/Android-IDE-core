package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.common.CapabilityManifest
import dev.fonebrew.contracts.common.CapabilitySubjectKind
import dev.fonebrew.contracts.common.CapabilityVersions
import dev.fonebrew.contracts.execution.CancelMode
import dev.fonebrew.contracts.execution.CancelResult
import dev.fonebrew.contracts.execution.CancellationMode
import dev.fonebrew.contracts.execution.ExecutionEvent
import dev.fonebrew.contracts.execution.ExecutionExitState
import dev.fonebrew.contracts.execution.ExecutionHandle
import dev.fonebrew.contracts.execution.ExecutionLogs
import dev.fonebrew.contracts.execution.ExecutionProvider
import dev.fonebrew.contracts.execution.ExecutionProviderDescriptor
import dev.fonebrew.contracts.execution.ExecutionProvenance
import dev.fonebrew.contracts.execution.ExecutionReceipt
import dev.fonebrew.contracts.execution.ExecutionRequest
import dev.fonebrew.contracts.execution.ExecutionState
import dev.fonebrew.contracts.execution.ExecutionTargetType
import dev.fonebrew.contracts.execution.Heartbeat
import dev.fonebrew.contracts.execution.PreparedExecution
import dev.fonebrew.contracts.execution.ReceiptVerification
import dev.fonebrew.contracts.execution.ReconnectOutcome
import dev.fonebrew.contracts.execution.ReconnectToken
import dev.fonebrew.contracts.execution.ReconnectTokenHandle
import dev.fonebrew.contracts.execution.TargetSnapshot
import dev.fonebrew.contracts.execution.TargetTrust
import dev.fonebrew.contracts.execution.TerminationCause
import dev.fonebrew.contracts.execution.Timings
import dev.fonebrew.contracts.execution.VerificationState
import dev.fonebrew.domain.contracts.Digest
import dev.fonebrew.domain.contracts.IdGenerator
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The `LOCAL_ANDROID` [ExecutionProvider] (WP-4). "Process supervisor semantics JVM-mocked where
 * Android-bound" per the brief: real Android local execution is bounded by the W^X SELinux
 * restriction (`docs/ratified/EXECUTION_CONTRACT.md` §3.1 -- no download-and-exec-a-binary path
 * exists), so a real on-device implementation could only ever invoke an already-installed,
 * package-signed executable/interpreter, never an arbitrary `ProcessBuilder` command. This class
 * is that provider's real supervisor LOGIC (lifecycle, heartbeat, cancellation, reconnect,
 * receipt emission), exercised in this JVM gate against `/bin/sh -c` (a real process, safe to
 * spawn in a plain JVM sandbox even though it would not be a legal `LOCAL_ANDROID` command on a
 * real device) -- the same "test the real mechanism against a real JVM-native equivalent" pattern
 * `LocalWorkspaceProvider` already established for the workspace domain (WP-3).
 */
class LocalProcessExecutionProvider(
    private val idGenerator: () -> String = { "exec_" + IdGenerator.generate() },
    private val now: () -> Instant = Instant::now,
) : ExecutionProvider {

    override val descriptor = ExecutionProviderDescriptor(
        providerId = "local-process",
        supportedTargetTypes = listOf(ExecutionTargetType.LOCAL_ANDROID),
        capabilityManifest = CapabilityManifest(
            manifestId = "cap_local-process-provider",
            subjectKind = CapabilitySubjectKind.EXECUTION,
            supportedOperations = listOf("ONE_SHOT_COMMAND"),
            limits = emptyMap(),
            versions = CapabilityVersions(subjectVersion = "1.0.0")
        )
    )

    private class Run(
        val requestId: String,
        val targetId: String,
        val handleId: String,
        val process: Process,
        val queuedAtUtc: Instant,
        val startedAtUtc: Instant,
        val reconnectToken: String,
        @Volatile var state: ExecutionState = ExecutionState.RUNNING,
        @Volatile var lastHeartbeatUtc: Instant = Instant.EPOCH,
    )

    private val preparedRequests = ConcurrentHashMap<String, ExecutionRequest>()
    private val runsByHandle = ConcurrentHashMap<String, Run>()
    private val handleByReconnectToken = ConcurrentHashMap<String, String>()

    override suspend fun prepare(request: ExecutionRequest): PreparedExecution {
        require(request.operation.command.isNotBlank()) { "LocalProcessExecutionProvider.prepare: empty command." }
        preparedRequests[request.id] = request
        return PreparedExecution(requestId = request.id, targetId = request.targetId, preparedAtUtc = now())
    }

    override suspend fun start(prepared: PreparedExecution): ExecutionHandle {
        val request = preparedRequests[prepared.requestId]
            ?: error("LocalProcessExecutionProvider.start: no prepare()d request for id '${prepared.requestId}' -- prepare() must run first.")

        val handleId = idGenerator()
        val queuedAt = prepared.preparedAtUtc
        val op = request.operation
        val fullCommand = if (op.args.isEmpty()) op.command else op.command + " " + op.args.joinToString(" ")
        val builder = ProcessBuilder(listOf("/bin/sh", "-c", fullCommand)).redirectErrorStream(true)
        op.workingDirectory?.let { builder.directory(File(it)) }
        builder.environment().putAll(request.environment.envVars)

        val process = builder.start()
        val reconnectTok = "rt_" + IdGenerator.generate()
        val startedAt = now()
        val run = Run(
            requestId = request.id, targetId = request.targetId, handleId = handleId, process = process,
            queuedAtUtc = queuedAt, startedAtUtc = startedAt, reconnectToken = reconnectTok, lastHeartbeatUtc = startedAt
        )
        runsByHandle[handleId] = run
        handleByReconnectToken[reconnectTok] = handleId

        return ExecutionHandle(
            handleId = handleId, requestId = request.id, targetId = request.targetId, state = ExecutionState.RUNNING,
            enteredStateAtUtc = startedAt,
            heartbeat = Heartbeat(heartbeatIntervalSeconds = 2, lastHeartbeatUtc = startedAt, missedConsecutive = 0),
            cancellationMode = CancellationMode.COOPERATIVE,
            reconnectToken = ReconnectToken(token = reconnectTok, issuedAtUtc = startedAt)
        )
    }

    /**
     * Polls the process every 100ms on a daemon thread (real background work, matching
     * [dev.fonebrew.domain.workspace.LocalWorkspaceProvider.watch]'s pattern) and emits a
     * [ExecutionEvent.StateChanged] the moment it exits, followed by
     * [ExecutionEvent.ReceiptReady] once the terminal [ExecutionReceipt] is built.
     */
    override fun observe(handle: ExecutionHandle): Flow<ExecutionEvent> = callbackFlow {
        val run = runsByHandle[handle.handleId]
            ?: run { close(); return@callbackFlow }

        val thread = Thread {
            try {
                while (run.process.isAlive) {
                    run.lastHeartbeatUtc = now()
                    trySend(ExecutionEvent.HeartbeatReceived(handle.handleId, run.lastHeartbeatUtc))
                    Thread.sleep(100)
                }
                val exitCode = run.process.waitFor()
                val finishedAt = now()
                val exitState = if (exitCode == 0) ExecutionExitState.SUCCEEDED_UNVERIFIED else ExecutionExitState.FAILED_SAFE
                run.state = if (exitCode == 0) ExecutionState.SUCCEEDED_UNVERIFIED else ExecutionState.FAILED_SAFE
                trySend(ExecutionEvent.StateChanged(handle.handleId, finishedAt, run.state, "process exited with code $exitCode"))

                val output = run.process.inputStream.bufferedReader().readText()
                val receipt = buildReceipt(run, exitState, exitCode, output, finishedAt)
                trySend(ExecutionEvent.ReceiptReady(handle.handleId, finishedAt, receipt))
                close()
            } catch (_: InterruptedException) {
                close()
            }
        }
        thread.isDaemon = true
        thread.start()
        awaitClose { thread.interrupt() }
    }

    override suspend fun cancel(handle: ExecutionHandle, mode: CancelMode): CancelResult {
        val run = runsByHandle[handle.handleId]
            ?: return CancelResult(accepted = false, knownStoppedDescription = "no such handle", resultingState = ExecutionState.TARGET_STATE_UNKNOWN)

        when (mode) {
            CancelMode.COOPERATIVE -> run.process.destroy()
            CancelMode.FORCEFUL -> run.process.destroyForcibly()
        }
        val stopped = run.process.waitFor(2, TimeUnit.SECONDS)
        run.state = ExecutionState.CANCELLED
        return CancelResult(
            accepted = true,
            knownStoppedDescription = if (stopped) "process confirmed stopped (mode=$mode)" else "signal sent, exit not yet confirmed within 2s (mode=$mode)",
            resultingState = ExecutionState.CANCELLED
        )
    }

    /**
     * FB-RAT-EXE-004: a handleId this provider never issued (or already forgot) resolves to
     * `null` -- distinct from "the handle exists but its state is unknown," which is never this
     * provider's outcome for a still-tracked run (a local `Process` always answers `isAlive()`
     * truthfully within this JVM; the *_UNKNOWN family exists for providers that can genuinely
     * lose contact with a remote target, which this one, by construction, cannot).
     */
    override suspend fun reconnect(token: ReconnectTokenHandle): ExecutionHandle? {
        val handleId = handleByReconnectToken[token.token] ?: return null
        val run = runsByHandle[handleId] ?: return null
        return ExecutionHandle(
            handleId = run.handleId, requestId = run.requestId, targetId = run.targetId, state = run.state,
            enteredStateAtUtc = run.lastHeartbeatUtc,
            heartbeat = Heartbeat(heartbeatIntervalSeconds = 2, lastHeartbeatUtc = run.lastHeartbeatUtc, missedConsecutive = 0),
            cancellationMode = CancellationMode.COOPERATIVE,
            reconnectToken = ReconnectToken(token = run.reconnectToken, issuedAtUtc = run.startedAtUtc),
            reconnectAttempt = dev.fonebrew.contracts.execution.ReconnectAttempt(attemptedAtUtc = now(), outcome = ReconnectOutcome.ESTABLISHED),
            stateReason = if (run.state in setOf(ExecutionState.CANCELLED, ExecutionState.FAILED_SAFE)) "resumed handle after reconnect" else null
        )
    }

    private fun buildReceipt(run: Run, exitState: ExecutionExitState, exitCode: Int, output: String, finishedAt: Instant): ExecutionReceipt {
        val request = preparedRequests[run.requestId]
        val capsuleDigest = Digest.ofUtf8("${request?.operation?.command}|${request?.operation?.args?.joinToString(",")}")
        val excerpt = output.take(4096)
        return ExecutionReceipt(
            receiptId = "rcpt_" + IdGenerator.generate(),
            requestId = run.requestId,
            handleId = run.handleId,
            targetSnapshot = TargetSnapshot(
                id = run.targetId, type = ExecutionTargetType.LOCAL_ANDROID, displayName = "local-process",
                trust = TargetTrust.TRUSTED, arch = System.getProperty("os.arch") ?: "unknown"
            ),
            revision = null,
            capsuleDigest = capsuleDigest,
            timings = Timings(queuedAtUtc = run.queuedAtUtc, finishedAtUtc = finishedAt, startedAtUtc = run.startedAtUtc),
            exitState = exitState,
            logs = ExecutionLogs(redactionApplied = false, excerpt = excerpt),
            resourceSummary = dev.fonebrew.contracts.execution.ResourceSummary(),
            outputs = emptyList(),
            sideEffects = emptyList(),
            verification = ReceiptVerification(state = VerificationState.NOT_PERFORMED),
            provenance = ExecutionProvenance(
                sourceLocation = "local-process-execution-provider",
                projectRevision = request?.workingRevision ?: "unknown",
                initiatingPrincipal = request?.authorityGrant?.grantId ?: "unknown"
            ),
            // A clean process exit (any exit code) is NORMAL termination -- SIGKILL_SUSPECTED/
            // PROCESS_DEATH_UNSPECIFIED are reserved for the TARGET_STATE_UNKNOWN path this
            // provider takes via reconnect(), never via a normal observe()-driven exit.
            terminationCause = TerminationCause.NORMAL
        )
    }
}
