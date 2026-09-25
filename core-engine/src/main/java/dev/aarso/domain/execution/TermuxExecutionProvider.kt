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
import dev.aarso.contracts.execution.ExecutionProvider
import dev.aarso.contracts.execution.ExecutionProviderDescriptor
import dev.aarso.contracts.execution.ExecutionProvenance
import dev.aarso.contracts.execution.ExecutionReceipt
import dev.aarso.contracts.execution.ExecutionRequest
import dev.aarso.contracts.execution.ExecutionState
import dev.aarso.contracts.execution.ExecutionTargetType
import dev.aarso.contracts.execution.Heartbeat
import dev.aarso.contracts.execution.PreparedExecution
import dev.aarso.contracts.execution.ReceiptVerification
import dev.aarso.contracts.execution.ReconnectToken
import dev.aarso.contracts.execution.ReconnectTokenHandle
import dev.aarso.contracts.execution.TargetSnapshot
import dev.aarso.contracts.execution.TargetTrust
import dev.aarso.contracts.execution.TerminationCause
import dev.aarso.contracts.execution.Timings
import dev.aarso.contracts.execution.VerificationState
import dev.aarso.data.runtime.TermuxRunCommandBridge
import dev.aarso.domain.contracts.Digest
import dev.aarso.domain.contracts.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class TermuxExecutionProvider(
    private val bridge: TermuxRunCommandBridge,
    private val now: () -> Instant = Instant::now,
) : ExecutionProvider {

    override val descriptor = ExecutionProviderDescriptor(
        providerId = "termux-linux",
        supportedTargetTypes = listOf(ExecutionTargetType.LOCAL_ANDROID),
        capabilityManifest = CapabilityManifest(
            manifestId = "cap_termux-linux-provider",
            subjectKind = CapabilitySubjectKind.EXECUTION,
            supportedOperations = listOf("ONE_SHOT_COMMAND", "BUILD"),
            limits = mapOf("resultTransport" to "termux-file-channel-with-bounded-receipt"),
            versions = CapabilityVersions(subjectVersion = "1.0.0"),
        ),
    )

    private data class Run(
        val request: ExecutionRequest,
        val handle: ExecutionHandle,
        val queuedAt: Instant,
        val startedAt: Instant,
        val events: MutableSharedFlow<ExecutionEvent>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparedRequests = ConcurrentHashMap<String, ExecutionRequest>()
    private val runs = ConcurrentHashMap<String, Run>()
    private val reconnect = ConcurrentHashMap<String, String>()

    override suspend fun prepare(request: ExecutionRequest): PreparedExecution {
        require(request.operation.command.isNotBlank()) { "Termux command must be non-blank." }
        check(bridge.installationState() == dev.aarso.domain.runtime.RuntimeAvailability.READY) {
            "Termux runtime is not ready."
        }
        preparedRequests[request.id] = request
        return PreparedExecution(
            requestId = request.id,
            targetId = request.targetId,
            preparedAtUtc = now(),
        )
    }

    override suspend fun start(prepared: PreparedExecution): ExecutionHandle {
        val request = preparedRequests[prepared.requestId]
            ?: error("TermuxExecutionProvider.start called before prepare for ${prepared.requestId}")

        val handleId = "exec_" + IdGenerator.generate()
        val reconnectToken = "rt_" + IdGenerator.generate()
        val startedAt = now()
        val handle = ExecutionHandle(
            handleId = handleId,
            requestId = request.id,
            targetId = request.targetId,
            state = ExecutionState.RUNNING,
            enteredStateAtUtc = startedAt,
            heartbeat = Heartbeat(
                heartbeatIntervalSeconds = null,
                lastHeartbeatUtc = startedAt,
                missedConsecutive = null,
            ),
            cancellationMode = CancellationMode.UNSUPPORTED,
            reconnectToken = ReconnectToken(token = reconnectToken, issuedAtUtc = startedAt),
        )
        val run = Run(
            request = request,
            handle = handle,
            queuedAt = prepared.preparedAtUtc,
            startedAt = startedAt,
            events = MutableSharedFlow(replay = 8, extraBufferCapacity = 16),
        )
        runs[handleId] = run
        reconnect[reconnectToken] = handleId
        scope.launch { execute(run) }
        return handle
    }

    override fun observe(handle: ExecutionHandle): Flow<ExecutionEvent> =
        runs[handle.handleId]?.events?.asSharedFlow()
            ?: kotlinx.coroutines.flow.emptyFlow()

    override suspend fun cancel(handle: ExecutionHandle, mode: CancelMode): CancelResult =
        CancelResult(
            accepted = false,
            knownStoppedDescription = "Termux RUN_COMMAND does not expose a safe process handle for cancellation.",
            resultingState = ExecutionState.TARGET_STATE_UNKNOWN,
        )

    override suspend fun reconnect(token: ReconnectTokenHandle): ExecutionHandle? {
        val handleId = reconnect[token.token] ?: return null
        return runs[handleId]?.handle
    }

    private suspend fun execute(run: Run) {
        val request = run.request
        val result = runCatching {
            bridge.recoverFileBacked(request.id) ?: bridge.runFileBacked(
                executable = request.operation.command,
                args = request.operation.args,
                workDir = request.operation.workingDirectory ?: "~/",
                timeoutMs = request.budget.wallClockSeconds?.times(1000) ?: 120_000,
                requestId = request.id,
            )
        }
        val finishedAt = now()

        if (result.isFailure) {
            val detail = result.exceptionOrNull()?.message ?: "Termux execution failed"
            run.events.emit(ExecutionEvent.StateChanged(run.handle.handleId, finishedAt, ExecutionState.FAILED_SAFE, detail))
            run.events.emit(
                ExecutionEvent.ReceiptReady(
                    run.handle.handleId,
                    finishedAt,
                    receipt(run, ExecutionExitState.FAILED_SAFE, detail, finishedAt),
                )
            )
            return
        }

        val commandResult = result.getOrThrow()
        val success = commandResult.internalErrorCode == android.app.Activity.RESULT_OK && commandResult.exitCode == 0
        val state = if (success) ExecutionState.SUCCEEDED_UNVERIFIED else ExecutionState.FAILED_SAFE
        val exit = if (success) ExecutionExitState.SUCCEEDED_UNVERIFIED else ExecutionExitState.FAILED_SAFE
        val combined = buildString {
            if (commandResult.outputTruncated) {
                append(
                    "[output truncated by Termux result transport; original stdout=" +
                        commandResult.stdoutOriginalLength + " chars, stderr=" +
                        commandResult.stderrOriginalLength + " chars]\n"
                )
            }
            if (commandResult.stdout.isNotBlank()) append(commandResult.stdout)
            if (commandResult.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(commandResult.stderr)
            }
            if (commandResult.internalErrorMessage.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(commandResult.internalErrorMessage)
            }
        }

        if (commandResult.stdout.isNotBlank()) {
            run.events.emit(ExecutionEvent.OutputChunk(run.handle.handleId, finishedAt, commandResult.stdout))
        }
        if (commandResult.stderr.isNotBlank()) {
            run.events.emit(ExecutionEvent.OutputChunk(run.handle.handleId, finishedAt, commandResult.stderr))
        }
        run.events.emit(
            ExecutionEvent.StateChanged(
                run.handle.handleId,
                finishedAt,
                state,
                if (success) null else "Termux command exited ${commandResult.exitCode}",
            )
        )
        run.events.emit(
            ExecutionEvent.ReceiptReady(
                run.handle.handleId,
                finishedAt,
                receipt(run, exit, combined, finishedAt),
            )
        )
    }

    private fun receipt(
        run: Run,
        exitState: ExecutionExitState,
        output: String,
        finishedAt: Instant,
    ): ExecutionReceipt {
        val request = run.request
        return ExecutionReceipt(
            receiptId = "rcpt_" + IdGenerator.generate(),
            requestId = request.id,
            handleId = run.handle.handleId,
            targetSnapshot = TargetSnapshot(
                id = request.targetId,
                type = ExecutionTargetType.LOCAL_ANDROID,
                displayName = "Termux Linux userspace",
                trust = TargetTrust.TRUSTED,
                arch = "aarch64",
            ),
            revision = null,
            capsuleDigest = Digest.ofUtf8(request.operation.command + "|" + request.operation.args.joinToString(",")),
            timings = Timings(
                queuedAtUtc = run.queuedAt,
                startedAtUtc = run.startedAt,
                finishedAtUtc = finishedAt,
            ),
            exitState = exitState,
            logs = ExecutionLogs(redactionApplied = false, excerpt = output.take(4096)),
            resourceSummary = dev.aarso.contracts.execution.ResourceSummary(
                wallClockSecondsUsed = java.time.Duration.between(run.startedAt, finishedAt).toMillis() / 1000.0,
            ),
            outputs = emptyList(),
            sideEffects = emptyList(),
            verification = ReceiptVerification(state = VerificationState.NOT_PERFORMED),
            provenance = ExecutionProvenance(
                sourceLocation = "termux-run-command",
                projectRevision = request.workingRevision ?: "unknown",
                initiatingPrincipal = request.authorityGrant.grantId,
            ),
            terminationCause = TerminationCause.NORMAL,
        )
    }
}
