package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.ExecutionExitState
import dev.fonebrew.contracts.execution.ExecutionReceipt
import dev.fonebrew.domain.provenance.ProvenanceState
import java.time.Duration

/**
 * The Run panel's structured result -- exit state, duration, an honest output tail, and the
 * durable receipt id it was recorded under. Deliberately NOT a JUnit summary: no JUnit-XML (or
 * any other test-report) parser exists anywhere in `domain/` today, and the task brief that
 * asked for this panel is explicit that inventing one is out of scope -- "record raw exit + tail
 * honestly" is the fallback it names, and every provider WP-5 shipped only ever reports a raw
 * process/CI exit, never a parsed test count. When a real parser lands for some provider's
 * output shape, it plugs in here as an additional, optional field -- this type does not need to
 * change shape to gain one.
 */
data class RunOutcome(
    val targetLabel: String,
    val command: String,
    val exitState: ExecutionExitState,
    val durationMs: Long?,
    val outputTail: String,
    val receiptId: String,
    val provenance: ProvenanceState,
) {
    /** The three exit families a receipt can land in that plainly mean "the run finished and told us how" -- see [ExecutionExitState]. */
    val succeeded: Boolean get() = exitState == ExecutionExitState.SUCCEEDED || exitState == ExecutionExitState.SUCCEEDED_UNVERIFIED
}

object RunOutcomes {
    /** [receipt.timings.durationMs] when the provider filled it in; otherwise derived from the
     *  start/finish instants every receipt already carries -- never left silently null when the
     *  data to compute it is right there. */
    fun from(receipt: ExecutionReceipt, target: RunTarget, command: String): RunOutcome {
        val duration = receipt.timings.durationMs
            ?: receipt.timings.startedAtUtc?.let { started -> Duration.between(started, receipt.timings.finishedAtUtc).toMillis() }
        return RunOutcome(
            targetLabel = target.label,
            command = command,
            exitState = receipt.exitState,
            durationMs = duration,
            outputTail = receipt.logs.excerpt.orEmpty(),
            receiptId = receipt.receiptId,
            provenance = target.provenance,
        )
    }
}
