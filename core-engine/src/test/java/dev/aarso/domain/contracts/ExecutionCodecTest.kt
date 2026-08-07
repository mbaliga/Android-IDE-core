package dev.aarso.domain.contracts

import dev.aarso.contracts.execution.AuthorityGrantRef
import dev.aarso.contracts.execution.CancellationMode
import dev.aarso.contracts.execution.Connectivity
import dev.aarso.contracts.execution.ConnectivityKind
import dev.aarso.contracts.execution.CostTier
import dev.aarso.contracts.execution.ExecutionBudget
import dev.aarso.contracts.execution.ExecutionExitState
import dev.aarso.contracts.execution.ExecutionHandle
import dev.aarso.contracts.execution.ExecutionLogs
import dev.aarso.contracts.execution.ExecutionProvenance
import dev.aarso.contracts.execution.ExecutionReceipt
import dev.aarso.contracts.execution.ExecutionRequest
import dev.aarso.contracts.execution.ExecutionState
import dev.aarso.contracts.execution.ExecutionTarget
import dev.aarso.contracts.execution.ExecutionTargetType
import dev.aarso.contracts.execution.ExternalDuplicateBehavior
import dev.aarso.contracts.execution.FgsType
import dev.aarso.contracts.execution.Heartbeat
import dev.aarso.contracts.execution.OperationClass
import dev.aarso.contracts.execution.ProvenanceStyle
import dev.aarso.contracts.execution.ReceiptVerification
import dev.aarso.contracts.execution.ReconnectAttempt
import dev.aarso.contracts.execution.ReconnectOutcome
import dev.aarso.contracts.execution.ReconnectToken
import dev.aarso.contracts.execution.RequestEnvironment
import dev.aarso.contracts.execution.ResourceSummary
import dev.aarso.contracts.execution.SessionReauthorization
import dev.aarso.contracts.execution.TargetCost
import dev.aarso.contracts.execution.TargetSnapshot
import dev.aarso.contracts.execution.TargetTrust
import dev.aarso.contracts.execution.Timings
import dev.aarso.contracts.execution.TypedOperation
import dev.aarso.contracts.execution.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ExecutionCodecTest {

    private val t0 = Instant.parse("2026-08-07T12:00:00Z")

    @Test
    fun `ExecutionTarget round-trips including a null visibilityContract`() {
        val target = ExecutionTarget(
            id = "target-local", type = ExecutionTargetType.LOCAL_ANDROID, displayName = "This phone",
            trust = TargetTrust.TRUSTED, arch = "arm64-v8a", connectivity = Connectivity(ConnectivityKind.ALWAYS_ON_LOCAL),
            cost = TargetCost(CostTier.FREE), provenanceStyle = ProvenanceStyle.LOCAL_DEVICE_DIRECT, fgsType = FgsType.NONE
        )
        assertEquals(target, ExecutionCodec.decodeExecutionTarget(ExecutionCodec.encodeExecutionTarget(target)))
    }

    @Test
    fun `ExecutionRequest round-trips including external idempotency fields`() {
        val request = ExecutionRequest(
            id = "req1", targetId = "target-local", operation = TypedOperation(OperationClass.CI_DISPATCH, "gh", listOf("workflow", "run")),
            workingRevision = "abc123", environment = RequestEnvironment(mapOf("CI" to "true")), secretHandles = emptyList(),
            budget = ExecutionBudget(SessionReauthorization(300, true)), authorityGrant = AuthorityGrantRef("grant_1", listOf("fb.ci.dispatch")),
            expectedOutputs = emptyList(), idempotencyKey = "idem-1", sideEffectExternal = true,
            fgsType = FgsType.NONE, openEnded = false, externalDuplicateBehavior = ExternalDuplicateBehavior.RETURN_PRIOR_RESULT
        )
        assertEquals(request, ExecutionCodec.decodeExecutionRequest(ExecutionCodec.encodeExecutionRequest(request)))
    }

    @Test
    fun `ExecutionHandle round-trips a TARGET_STATE_UNKNOWN reconnect scenario`() {
        val handle = ExecutionHandle(
            handleId = "h1", requestId = "req1", targetId = "target-local", state = ExecutionState.TARGET_STATE_UNKNOWN,
            enteredStateAtUtc = t0, heartbeat = Heartbeat(heartbeatIntervalSeconds = 5, lastHeartbeatUtc = t0, missedConsecutive = 3),
            cancellationMode = CancellationMode.UNSUPPORTED, stateReason = "reconnect could not establish state",
            reconnectToken = ReconnectToken("tok1", t0), reconnectAttempt = ReconnectAttempt(t0, ReconnectOutcome.STALE_TOKEN_UNKNOWN)
        )
        assertEquals(handle, ExecutionCodec.decodeExecutionHandle(ExecutionCodec.encodeExecutionHandle(handle)))
    }

    @Test
    fun `ExecutionReceipt round-trips a SUCCEEDED receipt with VERIFIED verification`() {
        val receipt = ExecutionReceipt(
            receiptId = "rcpt1", requestId = "req1", handleId = "h1",
            targetSnapshot = TargetSnapshot("target-local", ExecutionTargetType.LOCAL_ANDROID, "This phone", TargetTrust.TRUSTED, "arm64-v8a"),
            revision = "abc123", capsuleDigest = dev.aarso.domain.contracts.Digest.ofUtf8("echo hi"),
            timings = Timings(queuedAtUtc = t0, finishedAtUtc = t0.plusSeconds(2), startedAtUtc = t0.plusSeconds(1), durationMs = 1000),
            exitState = ExecutionExitState.SUCCEEDED,
            logs = ExecutionLogs(redactionApplied = true, excerpt = "hi"),
            resourceSummary = ResourceSummary(cpuSecondsUsed = 0.5, wallClockSecondsUsed = 1.0),
            outputs = emptyList(), sideEffects = emptyList(),
            verification = ReceiptVerification(state = VerificationState.VERIFIED, method = "exit-code-zero", verifiedAtUtc = t0.plusSeconds(2)),
            provenance = ExecutionProvenance("local-process-execution-provider", "abc123", "grant_1")
        )
        assertEquals(receipt, ExecutionCodec.decodeExecutionReceipt(ExecutionCodec.encodeExecutionReceipt(receipt)))
    }

    @Test
    fun `ExecutionReceipt round-trip preserves an unknown field end to end`() {
        val receipt = ExecutionReceipt(
            receiptId = "rcpt2", requestId = "req1", handleId = "h1",
            targetSnapshot = TargetSnapshot("target-local", ExecutionTargetType.LOCAL_ANDROID, "This phone", TargetTrust.TRUSTED, "arm64-v8a"),
            revision = null, capsuleDigest = dev.aarso.domain.contracts.Digest.ofUtf8("cmd"),
            timings = Timings(queuedAtUtc = t0, finishedAtUtc = t0),
            exitState = ExecutionExitState.SUCCEEDED_UNVERIFIED,
            logs = ExecutionLogs(redactionApplied = false),
            resourceSummary = ResourceSummary(), outputs = emptyList(), sideEffects = emptyList(),
            verification = ReceiptVerification(state = VerificationState.NOT_PERFORMED),
            provenance = ExecutionProvenance("provider", "rev", "principal")
        )
        val json = ExecutionCodec.encodeExecutionReceipt(receipt)
        json.put("futureField", "from a newer minor version")
        val decoded = ExecutionCodec.decodeExecutionReceipt(json)
        assertEquals("from a newer minor version", decoded.unknownFields["futureField"])
        assertEquals("from a newer minor version", ExecutionCodec.encodeExecutionReceipt(decoded).getString("futureField"))
    }
}
