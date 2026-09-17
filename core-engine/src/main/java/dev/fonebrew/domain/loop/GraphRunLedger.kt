package dev.fonebrew.domain.loop

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsageReport
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
 *
 * **Closing the loop-run dollar hole:** a cloud step (`tier == Tier.CLOUD`, i.e.
 * `!step.estimated` — a provider-authoritative count) is priced through the exact same
 * [PricingBook] path a chat turn uses (`ChatViewModel`'s `usage.toAdviceCost(pricingStore.book
 * .value.priceFor(engine.tokenizerId))`) — see [pricingBook] / [resolveTokenizerId]. An
 * on-device step is never priced (real cost is 0 — the sovereignty record), and a cloud step
 * whose model can't be resolved to a tokenizer id still never *invents* a number: falling
 * through to [resolveTokenizerId]'s default (identity) and [PricingBook.priceFor]'s own
 * "unrecognised model id → treated as on-device, i.e. free" behaviour is the same honest
 * degradation [PricingBook] already documents for chat, not a new special case here.
 */
object GraphRunLedger {
    fun toEntries(
        result: GraphRunResult,
        treeNodes: List<MessageNode>,
        loopId: String?,
        runId: String,
        projectId: String?,
        timestampMillis: Long,
        /** The user's priced rates (Cost epic, last mile). Defaults to an empty book — every
         *  cloud model then falls to [PricingBook.fallback]'s
         *  [dev.fonebrew.domain.cost.UsagePricing.CONSERVATIVE_DEFAULT], same as an unpriced
         *  chat turn. */
        pricingBook: PricingBook = PricingBook(),
        /** Maps a [GraphStep.model] (a loop node's chosen `ModelSpec.id`, per
         *  `LoopRoom.kt`'s `bn.ext["model"]`) to the engine tokenizer id
         *  [PricingBook.priceFor] actually keys on (`ModelSpec.tokenizerId`, e.g.
         *  `cloud:claude-…`). Defaults to identity — a caller that doesn't wire model
         *  resolution just prices by the raw step model string, which only matches a
         *  [PricingBook] entry if that string already *is* a `cloud:`-prefixed tokenizer id. */
        resolveTokenizerId: (String?) -> String? = { it },
    ): List<LedgerEntry> {
        val stepNodes = treeNodes.drop(1) // drop the objective root — it has no GraphStep
        return result.steps.mapIndexed { i, step ->
            val tier = if (step.estimated) Tier.ON_DEVICE else Tier.CLOUD
            val estCostMinor = if (tier == Tier.CLOUD) {
                val tokenizerId = resolveTokenizerId(step.model)
                val pricing = tokenizerId?.let(pricingBook::priceFor) ?: dev.fonebrew.domain.cost.UsagePricing.ON_DEVICE
                UsageReport(step.tokensIn ?: 0L, step.tokensOut ?: 0L).toAdviceCost(pricing).moneyMinor
            } else {
                0L
            }
            LedgerCapture.loopStep(
                timestampMillis = timestampMillis,
                runId = runId,
                loopId = loopId,
                nodeId = stepNodes.getOrNull(i)?.id ?: "$runId:${step.index}",
                projectId = projectId,
                model = step.model ?: step.role,
                tier = tier,
                inputTokens = step.tokensIn ?: 0L,
                outputTokens = step.tokensOut ?: 0L,
                latencyMs = step.durationMs,
                estimated = step.estimated,
                estCostMinor = estCostMinor,
            )
        }
    }
}
