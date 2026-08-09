package dev.aarso.domain.loop.authoring

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
