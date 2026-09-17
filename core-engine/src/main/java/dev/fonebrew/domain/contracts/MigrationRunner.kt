// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.contracts

import dev.fonebrew.contracts.common.MigrationCompatibility
import dev.fonebrew.contracts.common.MigrationPlan

/**
 * Minimal, real migration-execution scaffolding (WP-2 — "migration-plan scaffolding," the last
 * of the six things this work package must implement, not just declare). [MigrationPlan] itself
 * (`CommonContracts.kt`) is a pure declarative record; this file is the one piece of actual
 * runtime behavior around it: applying a plan's ordered [MigrationPlan.dataMigration] steps to a
 * value of type [T], and reporting a structured outcome rather than throwing past a partially
 * applied step.
 *
 * Deliberately narrow: this is NOT a general schema-migration framework (no step registry, no
 * auto-discovery, no reflection). A concrete [MigrationStep] is just a named function; a domain
 * that needs real migrations (e.g. a future `AppDatabase` bump wanting something better than
 * `fallbackToDestructiveMigration()` — see `docs/WP0_SURVEY.md` §3, no real `Room.Migration`
 * exists anywhere in this codebase yet) wires its own ordered [MigrationStep] list and calls
 * [MigrationRunner.apply]. This keeps the "scaffolding" honest: a real, testable execution path
 * for whichever [MigrationPlan] a later work package's domain actually needs to run, not a
 * speculative framework for migrations nobody has written yet.
 */
object MigrationRunner {

    /** One named, ordered step a [MigrationPlan.dataMigration] step-name corresponds to. */
    fun interface MigrationStep<T> {
        /** Applies this step to [input], returning the transformed value. Throws on failure — [apply] catches it. */
        fun apply(input: T): T
    }

    /** What actually happened when [apply] ran a [MigrationPlan] against a value. */
    sealed interface MigrationOutcome<out T> {
        data class Succeeded<T>(val result: T, val stepsApplied: List<String>) : MigrationOutcome<T>
        data class Failed(
            val failedStep: String,
            val stepsAppliedBeforeFailure: List<String>,
            val cause: Throwable
        ) : MigrationOutcome<Nothing>
    }

    /**
     * Applies [plan]'s [MigrationPlan.dataMigration] steps to [input] in order, looking each step
     * name up in [steps]. Fails closed: the first step that throws, or the first plan step name
     * with no matching entry in [steps], stops the migration immediately and returns
     * [MigrationOutcome.Failed] — a migration NEVER applies a partial prefix of its steps and
     * calls that success (mirrors `LOOP_PACKAGE_SPEC.md`'s "the build fails closed" invariant,
     * applied here to data migrations instead of package builds).
     *
     * @param steps Step name -> executable step. A plan whose [MigrationPlan.dataMigration] names
     *   a step not present here fails immediately at that step, before applying it (an
     *   unrecognized migration step is exactly as unsafe as a failed one).
     */
    fun <T> apply(plan: MigrationPlan, input: T, steps: Map<String, MigrationStep<T>>): MigrationOutcome<T> {
        var current = input
        val applied = mutableListOf<String>()
        for (stepName in plan.dataMigration.steps) {
            val step = steps[stepName]
            if (step == null) {
                return MigrationOutcome.Failed(
                    failedStep = stepName,
                    stepsAppliedBeforeFailure = applied.toList(),
                    cause = IllegalArgumentException(
                        "MigrationRunner: plan '${plan.migrationId}' names step '$stepName', " +
                            "which has no registered MigrationStep implementation."
                    )
                )
            }
            try {
                current = step.apply(current)
                applied.add(stepName)
            } catch (t: Throwable) {
                return MigrationOutcome.Failed(
                    failedStep = stepName,
                    stepsAppliedBeforeFailure = applied.toList(),
                    cause = t
                )
            }
        }
        return MigrationOutcome.Succeeded(current, applied.toList())
    }

    /**
     * True if [plan] crosses a MAJOR boundary (`MigrationCompatibility.MAJOR`) — the caller-side
     * signal for "old readers MUST reject the new payload until this plan runs" (FB-RAT-COM-003).
     * `MigrationRunner` itself does not enforce this — a reader/store decides what "reject" means
     * for its own data (refuse to open, refuse to decode a single record, etc.).
     */
    fun requiresMajorMigration(plan: MigrationPlan): Boolean = plan.compatibility == MigrationCompatibility.MAJOR
}
