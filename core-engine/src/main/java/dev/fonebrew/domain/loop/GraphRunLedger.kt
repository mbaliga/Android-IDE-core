package dev.fonebrew.domain.loop

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.ledger.LedgerCapture
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Tier

/**
 * Maps a [GraphRunResult] to one [LedgerEntry] per completed [GraphStep] — the ledger-side
 * counterpart to [GraphRunLog.toNodes] (CORE_PHASES.md P3: "each step writes a LedgerCapture
 * row"). Takes the **same** [treeNodes] [GraphRunLog.toNodes] produced for this run so a ledger
 * row correlates 1:1 with its tree node by [MessageNode.id] — no second id scheme invented.
 * [treeNodes]'s first element is the run's objective root (no matching step); the rest line up
 * with [GraphRunResult.steps] in order.
 *
 * Every entry is tagged `surface = "loop"` (see [LedgerCapture.loopStep]) so it never gets
 * silently folded into Chat's usage views. Pure; JVM-tested.
 */
object GraphRunLedger {
    fun toEntries(
        result: GraphRunResult,
        treeNodes: List<MessageNode>,
        loopId: String?,
        runId: String,
        projectId: String?,
        timestampMillis: Long,
    ): List<LedgerEntry> {
        val stepNodes = treeNodes.drop(1) // drop the objective root — it has no GraphStep
        return result.steps.mapIndexed { i, step ->
            LedgerCapture.loopStep(
                timestampMillis = timestampMillis,
                runId = runId,
                loopId = loopId,
                nodeId = stepNodes.getOrNull(i)?.id ?: "$runId:${step.index}",
                projectId = projectId,
                model = step.model ?: step.role,
                tier = if (step.estimated) Tier.ON_DEVICE else Tier.CLOUD,
                inputTokens = step.tokensIn ?: 0L,
                outputTokens = step.tokensOut ?: 0L,
                latencyMs = step.durationMs,
                estimated = step.estimated,
            )
        }
    }
}
