package dev.fonebrew.domain.loop

import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsageReport

/**
 * A run's own token/step/wall-clock/money ceiling (CORE_PHASES.md P3) — **user intent**, checked
 * between steps so the step that would exceed it is never started. Distinct from
 * [GraphRunner]'s `hardCap`, which is an **engine safety net** that always applies.
 *
 * [maxSpendMinor] is in the minor currency unit the usage ledger already uses
 * (`LedgerEntry.estCostMinor`, `UsagePricing.centsPer1k*`); the currency label is the user's
 * `CurrencyPref`, with no exchange rate ever. Money is priced through the existing
 * [UsageReport.toAdviceCost]/[PricingBook] path ([StepPricing]) — **not** `domain/council/
 * CostEstimator`, which stays fenced to council escalation (CORE_PHASES.md rule 5).
 *
 * Semantics, stated plainly (docs/design/loop-tools.md, "Money budget"): the check runs BEFORE the
 * next step starts, so **a single step can overshoot the cap**; an on-device step costs zero; a
 * cloud step whose price cannot be known stops the run rather than letting the cap go unenforced.
 */
data class LoopBudget(
    val maxTokensTotal: Long? = null,
    val maxSteps: Int? = null,
    val maxWallMs: Long? = null,
    val maxSpendMinor: Long? = null,
) {
    val isEmpty: Boolean get() = maxTokensTotal == null && maxSteps == null && maxWallMs == null && maxSpendMinor == null
}

/** The money cost of one step. Never a guess: unknown stays unknown. */
sealed interface StepCost {
    /** An on-device engine or a tool: exactly zero money. */
    data object Free : StepCost

    /** Provider-reported usage × the user's explicit rate for this model. */
    data class Priced(val minor: Long) : StepCost

    data class Unknown(val reason: String) : StepCost

    /** No cost resolver was wired for this run: money was not measured. */
    data object NotMeasured : StepCost
}

fun interface StepCostResolver {
    /** Called right after a model step; [tokens] is what the token counter reported (maybe null). */
    suspend fun costOf(node: dev.fonebrew.domain.bpmn.BpmnNode, tokens: StepTokens?): StepCost
}

object StepPricing {
    const val UNKNOWN_STOP_REASON = "cost unknown - cannot enforce the money budget"

    /**
     * [isCloud] is the executing engine's own tier (never inferred from token estimates — a cloud
     * engine that reported no usage would otherwise look on-device). A cloud step is priced only
     * when provider usage exists AND the user set an explicit price for [tokenizerId]:
     * [PricingBook]'s fallback is a labelled placeholder, not a quote.
     */
    fun price(isCloud: Boolean, usage: UsageReport?, tokenizerId: String?, book: PricingBook): StepCost {
        if (!isCloud) return StepCost.Free
        if (usage == null || usage.totalTokens <= 0) return StepCost.Unknown("the provider reported no usage")
        if (tokenizerId == null || !book.isExplicit(tokenizerId)) return StepCost.Unknown("no price is set for this model")
        return StepCost.Priced(usage.toAdviceCost(book.priceFor(tokenizerId)).moneyMinor)
    }
}
