package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.ExecutionExitState
import dev.fonebrew.contracts.execution.ExecutionLogs
import dev.fonebrew.contracts.execution.ExecutionProvenance
import dev.fonebrew.contracts.execution.ExecutionReceipt
import dev.fonebrew.contracts.execution.ExecutionTargetType
import dev.fonebrew.contracts.execution.ReceiptVerification
import dev.fonebrew.contracts.execution.ResourceSummary
import dev.fonebrew.contracts.execution.TargetSnapshot
import dev.fonebrew.contracts.execution.TargetTrust
import dev.fonebrew.contracts.execution.Timings
import dev.fonebrew.contracts.execution.VerificationState
import dev.fonebrew.domain.contracts.Digest
import dev.fonebrew.domain.provenance.ProvenanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunOutcomeTest {

    private val started = Instant.parse("2026-08-28T00:00:00Z")
    private val finished = started.plusMillis(4200)

    private fun receipt(exitState: ExecutionExitState, durationMs: Long? = null, excerpt: String? = "ok") = ExecutionReceipt(
        receiptId = "rcpt_1", requestId = "req_1", handleId = "handle_1",
        targetSnapshot = TargetSnapshot(id = "t1", type = ExecutionTargetType.LOCAL_ANDROID, displayName = "local", trust = TargetTrust.TRUSTED, arch = "arm64"),
        revision = null, capsuleDigest = Digest.ofUtf8("cmd"),
        timings = Timings(queuedAtUtc = started, finishedAtUtc = finished, startedAtUtc = started, durationMs = durationMs),
        exitState = exitState, logs = ExecutionLogs(redactionApplied = false, excerpt = excerpt),
        resourceSummary = ResourceSummary(), outputs = emptyList(), sideEffects = emptyList(),
        verification = ReceiptVerification(
            state = if (exitState == ExecutionExitState.SUCCEEDED) VerificationState.VERIFIED else VerificationState.NOT_PERFORMED,
        ),
        provenance = ExecutionProvenance(sourceLocation = "test", projectRevision = "main", initiatingPrincipal = "grant_1"),
    )

    @Test
    fun `from -- carries exit state, receipt id, output tail, and target label`() {
        val outcome = RunOutcomes.from(receipt(ExecutionExitState.SUCCEEDED_UNVERIFIED), RunTarget.Local, "./gradlew test")
        assertEquals(ExecutionExitState.SUCCEEDED_UNVERIFIED, outcome.exitState)
        assertEquals("rcpt_1", outcome.receiptId)
        assertEquals("ok", outcome.outputTail)
        assertEquals("This phone", outcome.targetLabel)
        assertEquals(ProvenanceState.LOCAL, outcome.provenance)
        assertEquals("./gradlew test", outcome.command)
    }

    @Test
    fun `from -- durationMs derives from the timings when the receipt didn't set it`() {
        val outcome = RunOutcomes.from(receipt(ExecutionExitState.SUCCEEDED_UNVERIFIED, durationMs = null), RunTarget.Local, "cmd")
        assertEquals(4200L, outcome.durationMs)
    }

    @Test
    fun `from -- an explicit receipt durationMs is used as-is`() {
        val outcome = RunOutcomes.from(receipt(ExecutionExitState.SUCCEEDED_UNVERIFIED, durationMs = 999L), RunTarget.Local, "cmd")
        assertEquals(999L, outcome.durationMs)
    }

    @Test
    fun `succeeded -- true for SUCCEEDED and SUCCEEDED_UNVERIFIED, false otherwise`() {
        assertTrue(RunOutcomes.from(receipt(ExecutionExitState.SUCCEEDED_UNVERIFIED), RunTarget.Local, "cmd").succeeded)
        assertFalse(RunOutcomes.from(receipt(ExecutionExitState.FAILED_SAFE), RunTarget.Local, "cmd").succeeded)
        assertFalse(RunOutcomes.from(receipt(ExecutionExitState.CANCELLED), RunTarget.Local, "cmd").succeeded)
    }

    @Test
    fun `from -- a missing excerpt records an honest blank tail, never a placeholder`() {
        val outcome = RunOutcomes.from(receipt(ExecutionExitState.FAILED_SAFE, excerpt = null), RunTarget.Local, "cmd")
        assertEquals("", outcome.outputTail)
    }
}
