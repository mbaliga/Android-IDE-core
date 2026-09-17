package dev.fonebrew.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of LOOP_PHONE_AUTHORING_SPEC.md §13's persistence/interruption/recovery table. */
class DraftPersistenceLifecycleTest {

    private fun advanced(state: DraftLifecycleState, event: DraftLifecycleMachine.Event): DraftLifecycleState {
        val result = DraftLifecycleMachine.apply(state, event)
        assertTrue("expected Advanced for $event from $state, got $result", result is DraftLifecycleMachine.Result.Advanced)
        return (result as DraftLifecycleMachine.Result.Advanced).state
    }

    private fun rejected(state: DraftLifecycleState, event: DraftLifecycleMachine.Event) {
        assertTrue(DraftLifecycleMachine.apply(state, event) is DraftLifecycleMachine.Result.Rejected)
    }

    @Test
    fun `an edit journals immediately, and accepting it produces a clean draft again`() {
        val dirty = advanced(DraftLifecycleState.DraftClean, DraftLifecycleMachine.Event.Edit)
        assertEquals(DraftLifecycleState.DirtyJournaled, dirty)
        val clean = advanced(dirty, DraftLifecycleMachine.Event.AcceptEdit)
        assertEquals(DraftLifecycleState.DraftClean, clean)
    }

    @Test
    fun `process death while DIRTY_JOURNALED survives as DIRTY_JOURNALED -- zero lost edits`() {
        val survived = advanced(DraftLifecycleState.DirtyJournaled, DraftLifecycleMachine.Event.ProcessDeath)
        assertEquals(DraftLifecycleState.DirtyJournaled, survived)
    }

    @Test
    fun `process death during any long operation reports the specific interrupted operation, never a guess`() {
        for (op in LongOperationKind.entries) {
            val interrupted = advanced(DraftLifecycleState.InProgress(op), DraftLifecycleMachine.Event.ProcessDeath)
            assertEquals(DraftLifecycleState.Interrupted(op), interrupted)
        }
    }

    @Test
    fun `resuming an interrupted package build is atomic -- either full export or no published package, never partial`() {
        val interrupted = DraftLifecycleState.Interrupted(LongOperationKind.BUILDING_PACKAGE)
        assertEquals(DraftLifecycleState.PackageExportComplete, advanced(interrupted, DraftLifecycleMachine.Event.ResumePackageBuild(succeeded = true)))
        assertEquals(DraftLifecycleState.NoPublishedPackage, advanced(interrupted, DraftLifecycleMachine.Event.ResumePackageBuild(succeeded = false)))
    }

    @Test
    fun `ResumePackageBuild is illegal from an interruption of any other operation`() {
        for (op in listOf(LongOperationKind.VALIDATING, LongOperationKind.SIMULATING, LongOperationKind.RUNNING)) {
            rejected(DraftLifecycleState.Interrupted(op), DraftLifecycleMachine.Event.ResumePackageBuild(succeeded = true))
        }
    }

    @Test
    fun `terminal package-export states accept no further event`() {
        rejected(DraftLifecycleState.PackageExportComplete, DraftLifecycleMachine.Event.Edit)
        rejected(DraftLifecycleState.NoPublishedPackage, DraftLifecycleMachine.Event.Edit)
    }

    @Test
    fun `AcceptEdit and ProcessDeath are the only legal events on DIRTY_JOURNALED -- Edit itself is not`() {
        rejected(DraftLifecycleState.DirtyJournaled, DraftLifecycleMachine.Event.Edit)
    }
}

class DraftEditJournalTest {

    @Test
    fun `appending a fresh idempotency key applies the write and grows the journal`() {
        val journal = DraftEditJournal()
        val applied = journal.append("key-1", "objective", "\"do the thing\"")
        assertTrue(applied)
        assertEquals(1, journal.entriesSoFar().size)
    }

    @Test
    fun `a retried write with an already-seen idempotency key is a no-op, never double-applied`() {
        val journal = DraftEditJournal()
        assertTrue(journal.append("key-1", "objective", "\"first value\""))
        val retried = journal.append("key-1", "objective", "\"first value\"") // same key, simulating a retried journal write
        assertTrue(!retried)
        assertEquals(1, journal.entriesSoFar().size) // not 2 -- FB-RAT-COM-006
    }

    @Test
    fun `materialize replays deterministically, last write per field path wins in append order`() {
        val journal = DraftEditJournal()
        journal.append("key-1", "objective", "\"draft objective\"")
        journal.append("key-2", "objective", "\"final objective\"")
        journal.append("key-3", "nodes[0].explanation", "\"explains itself\"")

        val state = journal.materialize()
        assertEquals("\"final objective\"", state["objective"])
        assertEquals("\"explains itself\"", state["nodes[0].explanation"])
        assertEquals(2, state.size)
    }

    @Test
    fun `a hundred simulated forced kills at random points -- a fresh journal instance over the surviving entries recovers exactly what was durably appended`() {
        // Same discipline as WP-3's RoomWorkspaceJournalTest, adapted to this journal's simpler
        // in-memory shape: since every append() call IS the durability point (there is no
        // separate "flush" step to lose), the only thing a forced kill can lose is whatever
        // hadn't been appended yet -- which this test proves by construction, not by chance.
        for (iteration in 0 until 100) {
            val random = kotlin.random.Random(iteration)
            val totalEdits = 1 + random.nextInt(20)
            val allEdits = (0 until totalEdits).map { i -> Triple("key-$i", "field-${i % 5}", "\"value-$i\"") }
            val killAtIndex = random.nextInt(totalEdits + 1) // 0..totalEdits: how many edits "landed" before the kill

            val journal = DraftEditJournal()
            for (i in 0 until killAtIndex) {
                val (key, field, value) = allEdits[i]
                journal.append(key, field, value)
            }
            // "process restarted": a fresh journal instance rebuilt from exactly the entries that
            // durably landed (append() is itself the durability boundary in this in-memory model).
            val recovered = DraftEditJournal()
            for (entry in journal.entriesSoFar()) recovered.append(entry.idempotencyKey, entry.fieldPath, entry.newValueJson)

            assertEquals("iteration $iteration", journal.entriesSoFar().size, recovered.entriesSoFar().size)
            assertEquals("iteration $iteration", journal.materialize(), recovered.materialize())
        }
    }
}

/** Regression coverage for the LoopRoom mount finding: a random-UUID idempotency key defeats
 *  FB-RAT-COM-006 (every write "looks new"); [contentIdempotencyKey] must be deterministic, and
 *  wiring it through [DraftEditJournal] must actually exercise the dedup no-op path for unchanged
 *  content while still applying genuinely new content. */
class ContentIdempotencyKeyTest {

    @Test
    fun `the same field and value always produce the same key`() {
        val a = contentIdempotencyKey("objective", "\"refine the widget\"")
        val b = contentIdempotencyKey("objective", "\"refine the widget\"")
        assertEquals(a, b)
    }

    @Test
    fun `a different value for the same field produces a different key`() {
        val a = contentIdempotencyKey("objective", "\"refine the widget\"")
        val b = contentIdempotencyKey("objective", "\"refine the gadget\"")
        assertTrue(a != b)
    }

    @Test
    fun `a different field for the same value produces a different key`() {
        val a = contentIdempotencyKey("objective", "\"same text\"")
        val b = contentIdempotencyKey("nodes[0].systemPrompt", "\"same text\"")
        assertTrue(a != b)
    }

    @Test
    fun `repeated debounces over an unchanged draft are a real dedup no-op, not fresh entries`() {
        // Mirrors LoopRoom's debounced LaunchedEffect firing several times (e.g. the graph's
        // nodes/edges moved) while the journaled field (objective) itself never actually changed.
        val journal = DraftEditJournal()
        val objective = "\"make it airtight\""
        repeat(5) {
            journal.append(contentIdempotencyKey("objective", objective), "objective", objective)
        }
        assertEquals(1, journal.entriesSoFar().size) // 5 debounces, exactly 1 real edit
    }

    @Test
    fun `a genuinely changed value still journals as a new entry`() {
        val journal = DraftEditJournal()
        journal.append(contentIdempotencyKey("objective", "\"draft one\""), "objective", "\"draft one\"")
        journal.append(contentIdempotencyKey("objective", "\"draft two\""), "objective", "\"draft two\"")
        assertEquals(2, journal.entriesSoFar().size)
        assertEquals("\"draft two\"", journal.materialize()["objective"])
    }
}

/** The recovery banner must show only when there is real content to restore/discard **and** the
 *  machine agrees the draft is genuinely dirty-unresolved — never `hasPendingRecovery` alone
 *  standing in for that state decision, and never true just because the live typing/autosave
 *  cycle happens to be passing through DirtyJournaled too. */
class ShouldShowRecoveryBannerTest {

    @Test
    fun `shows only when a recovered draft is pending and the machine is DirtyJournaled`() {
        assertTrue(shouldShowRecoveryBanner(hasPendingRecovery = true, lifecycle = DraftLifecycleState.DirtyJournaled))
    }

    @Test
    fun `never shows without a recovered draft, even if the machine is DirtyJournaled`() {
        // The live typing/autosave cycle visits DirtyJournaled on every debounce -- this is
        // exactly the case that must NOT spuriously show the recovery banner mid-edit.
        assertTrue(!shouldShowRecoveryBanner(hasPendingRecovery = false, lifecycle = DraftLifecycleState.DirtyJournaled))
    }

    @Test
    fun `never shows once the machine is back to DraftClean, even if a stale flag says pending`() {
        assertTrue(!shouldShowRecoveryBanner(hasPendingRecovery = true, lifecycle = DraftLifecycleState.DraftClean))
    }

    @Test
    fun `never shows for any other lifecycle state`() {
        val others = listOf(
            DraftLifecycleState.InProgress(LongOperationKind.RUNNING),
            DraftLifecycleState.Interrupted(LongOperationKind.BUILDING_PACKAGE),
            DraftLifecycleState.PackageExportComplete,
            DraftLifecycleState.NoPublishedPackage,
        )
        for (state in others) {
            assertTrue("expected false for $state", !shouldShowRecoveryBanner(hasPendingRecovery = true, lifecycle = state))
        }
    }
}
