package dev.aarso.domain.contracts

import dev.aarso.contracts.common.DataMigrationSteps
import dev.aarso.contracts.common.MigrationCompatibility
import dev.aarso.contracts.common.MigrationPlan
import dev.aarso.contracts.common.RollbackPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationRunnerTest {

    private fun plan(steps: List<String>, compatibility: MigrationCompatibility = MigrationCompatibility.MINOR) = MigrationPlan(
        migrationId = "mig_" + IdGenerator.generate(),
        fromSchemaVersion = "1.0.0",
        toSchemaVersion = "1.1.0",
        compatibility = compatibility,
        dataMigration = DataMigrationSteps(steps = steps),
        rollback = RollbackPlan(possible = false, steps = emptyList())
    )

    @Test
    fun `apply runs every step in order and returns Succeeded`() {
        val addOne = MigrationRunner.MigrationStep<Int> { it + 1 }
        val double = MigrationRunner.MigrationStep<Int> { it * 2 }
        val outcome = MigrationRunner.apply(
            plan(listOf("addOne", "double")),
            input = 5,
            steps = mapOf("addOne" to addOne, "double" to double)
        )
        assertTrue(outcome is MigrationRunner.MigrationOutcome.Succeeded)
        val succeeded = outcome as MigrationRunner.MigrationOutcome.Succeeded
        assertEquals(12, succeeded.result) // (5 + 1) * 2
        assertEquals(listOf("addOne", "double"), succeeded.stepsApplied)
    }

    @Test
    fun `apply with a single passthrough step succeeds and returns the input unchanged`() {
        // DataMigrationSteps.steps must be non-empty (FB-RAT contract, enforced by its own init
        // block), so a MigrationPlan can never legally declare zero steps -- a passthrough step
        // is the closest reachable analogue to "nothing actually changes."
        val passthrough = MigrationRunner.MigrationStep<String> { it }
        val outcome = MigrationRunner.apply(
            plan(listOf("passthrough")),
            input = "unchanged",
            steps = mapOf("passthrough" to passthrough)
        )
        assertTrue(outcome is MigrationRunner.MigrationOutcome.Succeeded)
        assertEquals("unchanged", (outcome as MigrationRunner.MigrationOutcome.Succeeded).result)
    }

    @Test
    fun `apply fails closed when a named step has no registered implementation`() {
        val known = MigrationRunner.MigrationStep<Int> { it }
        val outcome = MigrationRunner.apply(
            plan(listOf("known", "missing")),
            input = 1,
            steps = mapOf("known" to known)
        )
        assertTrue(outcome is MigrationRunner.MigrationOutcome.Failed)
        val failed = outcome as MigrationRunner.MigrationOutcome.Failed
        assertEquals("missing", failed.failedStep)
        assertEquals(listOf("known"), failed.stepsAppliedBeforeFailure)
    }

    @Test
    fun `apply fails closed and reports which steps ran before a step throws`() {
        val ok = MigrationRunner.MigrationStep<Int> { it }
        val boom = MigrationRunner.MigrationStep<Int> { throw IllegalStateException("boom") }
        val neverReached = MigrationRunner.MigrationStep<Int> { it * 100 }
        val outcome = MigrationRunner.apply(
            plan(listOf("ok", "boom", "neverReached")),
            input = 1,
            steps = mapOf("ok" to ok, "boom" to boom, "neverReached" to neverReached)
        )
        assertTrue(outcome is MigrationRunner.MigrationOutcome.Failed)
        val failed = outcome as MigrationRunner.MigrationOutcome.Failed
        assertEquals("boom", failed.failedStep)
        assertEquals(listOf("ok"), failed.stepsAppliedBeforeFailure)
        assertEquals("boom", failed.cause.message)
    }

    @Test
    fun `requiresMajorMigration reflects the plan's compatibility field`() {
        assertTrue(MigrationRunner.requiresMajorMigration(plan(listOf("noop"), MigrationCompatibility.MAJOR)))
        assertEquals(false, MigrationRunner.requiresMajorMigration(plan(listOf("noop"), MigrationCompatibility.MINOR)))
    }
}
