package dev.fonebrew.domain.loop

/**
 * A run's own token/step/wall-clock ceiling (CORE_PHASES.md P3) — **user intent**, checked
 * between steps so the step that would exceed it is never started. Distinct from
 * [GraphRunner]'s `hardCap`, which is an **engine safety net** that always applies (it bounds
 * a pathological loop-back even with no budget set at all).
 *
 * Deliberately narrow: plain token/step/wall counting only, no dollar-cost matrix. Rule 5
 * (CORE_PHASES.md invariants) fences `domain/council/CostEstimator.kt` to Council escalation —
 * this type does not touch it, and the per-loop **dollar**-cost boundary is separate, not-yet-
 * built work (docs/HANDOFF-CURRENT.md §11).
 */
data class LoopBudget(
    val maxTokensTotal: Long? = null,
    val maxSteps: Int? = null,
    val maxWallMs: Long? = null,
)
