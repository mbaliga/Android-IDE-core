package dev.aarso.domain.cost

import dev.aarso.domain.council.Cost
import dev.aarso.domain.pm.BoardCard

/**
 * Bridges the three pillars: a board **card** becomes a **loop** objective, and the
 * loop's projected **[Cost]** becomes the advice cost of a **[Decision]** you can forecast.
 * So "run a loop against this issue" carries a legible, risk-adjustable price — the same
 * cost language across chat, loops, and the world (`docs/design/cost.md`).
 *
 * Pure; JVM-tested. Actually running the loop needs an engine (runtime, owner-verified);
 * this is the headless wiring that turns its result into a cost forecast.
 */
object CardDecision {

    /** A loop objective derived from the card (title + body). */
    fun objective(card: BoardCard): String {
        val head = "Resolve #${card.number}: ${card.title}".trim()
        return if (card.body.isBlank()) head else "$head\n\n${card.body.trim()}"
    }

    /**
     * Build a [Decision] for working this card via a loop whose projected cost is
     * [loopCost]. [risks] lets the caller price the chance the loop's output is wrong
     * (the cost-of-being-wrong), consistent with the Cost epic.
     */
    fun fromLoopCost(
        card: BoardCard,
        loopCost: Cost,
        risks: List<RiskedOutcome> = emptyList(),
    ): Decision = Decision(
        label = "#${card.number} ${card.title}",
        adviceCost = loopCost.toCostVector(),
        risks = risks,
    )
}
