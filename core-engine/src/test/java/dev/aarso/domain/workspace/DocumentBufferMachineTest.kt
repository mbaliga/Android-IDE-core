package dev.aarso.domain.workspace

import dev.aarso.contracts.common.ErrorEnvelope
import dev.aarso.contracts.common.ErrorSeverity
import dev.aarso.contracts.common.SideEffectState
import dev.aarso.contracts.workspace.BufferConflict
import dev.aarso.contracts.workspace.DocumentBufferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Exhaustive coverage of WORKSPACE_KERNEL_SPEC.md §3.1's from-state/event/to-state table. */
class DocumentBufferMachineTest {

    private fun conflict() = BufferConflict(remoteRevision = "rev-2", detectedAtUtc = Instant.parse("2026-08-07T00:00:00Z"))
    private fun error() = ErrorEnvelope(
        code = "SAVE_FAILED", severity = ErrorSeverity.ERROR, retryable = true,
        sideEffectState = SideEffectState.NONE, detail = "disk full",
        recoveryAction = "retry", userMessage = "Could not save your changes."
    )

    private fun allowed(current: DocumentBufferState, event: DocumentBufferMachine.Event): DocumentBufferState {
        val result = DocumentBufferMachine.transition(current, event)
        assertTrue("expected Allowed for $event from $current, got $result", result is DocumentBufferMachine.Result.Allowed)
        return (result as DocumentBufferMachine.Result.Allowed).next
    }

    @Test
    fun `Closed to OpenClean via Open`() {
        assertEquals(DocumentBufferState.OpenClean, allowed(DocumentBufferState.Closed, DocumentBufferMachine.Event.Open))
    }

    @Test
    fun `OpenClean to OpenDirty via Edit, and to Closed via Close`() {
        assertEquals(DocumentBufferState.OpenDirty, allowed(DocumentBufferState.OpenClean, DocumentBufferMachine.Event.Edit))
        assertEquals(DocumentBufferState.Closed, allowed(DocumentBufferState.OpenClean, DocumentBufferMachine.Event.Close))
    }

    @Test
    fun `OpenDirty to Saving, Conflicted, or Closed`() {
        assertEquals(DocumentBufferState.Saving, allowed(DocumentBufferState.OpenDirty, DocumentBufferMachine.Event.SaveRequested))
        val c = conflict()
        assertEquals(DocumentBufferState.Conflicted(c), allowed(DocumentBufferState.OpenDirty, DocumentBufferMachine.Event.RemoteRevisionChanged(c)))
        assertEquals(DocumentBufferState.Closed, allowed(DocumentBufferState.OpenDirty, DocumentBufferMachine.Event.Discard))
    }

    @Test
    fun `Saving to OpenClean on success, SaveFailed on failure`() {
        assertEquals(DocumentBufferState.OpenClean, allowed(DocumentBufferState.Saving, DocumentBufferMachine.Event.SaveSucceeded))
        val e = error()
        assertEquals(DocumentBufferState.SaveFailed(e), allowed(DocumentBufferState.Saving, DocumentBufferMachine.Event.SaveFailed(e)))
    }

    @Test
    fun `SaveFailed to Saving, OpenDirty, or Closed`() {
        val failed = DocumentBufferState.SaveFailed(error())
        assertEquals(DocumentBufferState.Saving, allowed(failed, DocumentBufferMachine.Event.RetrySave))
        assertEquals(DocumentBufferState.OpenDirty, allowed(failed, DocumentBufferMachine.Event.Edit))
        assertEquals(DocumentBufferState.Closed, allowed(failed, DocumentBufferMachine.Event.Discard))
    }

    @Test
    fun `Conflicted to Merging preserving its own conflict, or to Closed`() {
        val c = conflict()
        val conflicted = DocumentBufferState.Conflicted(c)
        assertEquals(DocumentBufferState.Merging(c), allowed(conflicted, DocumentBufferMachine.Event.BeginMerge))
        assertEquals(DocumentBufferState.Closed, allowed(conflicted, DocumentBufferMachine.Event.Discard))
    }

    @Test
    fun `Merging to OpenDirty via MergeResolved`() {
        val merging = DocumentBufferState.Merging(conflict())
        assertEquals(DocumentBufferState.OpenDirty, allowed(merging, DocumentBufferMachine.Event.MergeResolved))
    }

    @Test
    fun `illegal transitions are rejected, not silently accepted`() {
        val illegal = listOf(
            DocumentBufferState.Closed to DocumentBufferMachine.Event.Edit,
            DocumentBufferState.OpenClean to DocumentBufferMachine.Event.SaveRequested,
            DocumentBufferState.OpenDirty to DocumentBufferMachine.Event.Open,
            DocumentBufferState.Saving to DocumentBufferMachine.Event.Edit,
            DocumentBufferState.SaveFailed(error()) to DocumentBufferMachine.Event.SaveSucceeded,
            DocumentBufferState.Conflicted(conflict()) to DocumentBufferMachine.Event.MergeResolved,
            DocumentBufferState.Merging(conflict()) to DocumentBufferMachine.Event.Discard,
        )
        for ((state, event) in illegal) {
            val result = DocumentBufferMachine.transition(state, event)
            assertTrue("expected Rejected for $event from $state, got $result", result is DocumentBufferMachine.Result.Rejected)
        }
    }

    @Test
    fun `dirty is false only for Closed and OpenClean, matching the wire schema's pin`() {
        val notDirty = listOf(DocumentBufferState.Closed, DocumentBufferState.OpenClean)
        val dirty = listOf(
            DocumentBufferState.OpenDirty, DocumentBufferState.Saving,
            DocumentBufferState.SaveFailed(error()), DocumentBufferState.Conflicted(conflict()),
            DocumentBufferState.Merging(conflict())
        )
        for (s in notDirty) assertTrue("$s should read as not-dirty", s is DocumentBufferState.Closed || s is DocumentBufferState.OpenClean)
        for (s in dirty) assertTrue("$s should read as dirty", s !is DocumentBufferState.Closed && s !is DocumentBufferState.OpenClean)
    }
}
