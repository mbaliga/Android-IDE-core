package dev.aarso.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of LOOP_PHONE_AUTHORING_SPEC.md §14's proposed undo/redo table (FB-RAT-PHN-011, PROPOSED). */
class LoopDraftUndoStackTest {

    @Test
    fun `a new action clears the redo stack -- linear history, not a tree`() {
        val stack = LoopDraftUndoStack()
        stack.record(UndoableAction(StageVerb.ADD, "add a verifier node"))
        stack.record(UndoableAction(StageVerb.CONNECT, "connect node A to node B"))
        stack.undo()
        assertTrue(stack.canRedo())

        stack.record(UndoableAction(StageVerb.DELETE, "delete node C")) // a fresh action while a redo was pending
        assertTrue(!stack.canRedo())
        assertEquals(2, stack.undoStackSize())
    }

    @Test
    fun `undo then redo restores the exact same action, symmetric round trip`() {
        val stack = LoopDraftUndoStack()
        val action = UndoableAction(StageVerb.BRANCH, "split into two outcomes")
        stack.record(action)

        val undone = stack.undo()
        assertEquals(action, undone)
        assertEquals(0, stack.undoStackSize())
        assertEquals(1, stack.redoStackSize())

        val redone = stack.redo()
        assertEquals(action, redone)
        assertEquals(1, stack.undoStackSize())
        assertEquals(0, stack.redoStackSize())
    }

    @Test
    fun `undo and redo on an empty stack are no-ops, not errors`() {
        val stack = LoopDraftUndoStack()
        assertNull(stack.undo())
        assertNull(stack.redo())
        assertTrue(!stack.canUndo())
        assertTrue(!stack.canRedo())
    }

    @Test
    fun `applying an AI proposal is exactly one undoable transaction, however many operations it contained`() {
        val stack = LoopDraftUndoStack()
        stack.recordAiProposalApplied("proposal: add a retry policy and a verifier to three nodes")
        assertEquals(1, stack.undoStackSize())
        val undone = stack.undo()
        assertEquals(StageVerb.APPLIED_AI_PROPOSAL, undone?.verb)
    }

    @Test
    fun `activation or export boundary clears both stacks and permanently retires this instance`() {
        val stack = LoopDraftUndoStack()
        stack.record(UndoableAction(StageVerb.ADD, "add a node"))
        stack.undo()
        assertTrue(stack.canRedo())

        stack.markActivationOrExportBoundary()
        assertTrue(!stack.canUndo())
        assertTrue(!stack.canRedo())
        assertEquals(0, stack.undoStackSize())
        assertEquals(0, stack.redoStackSize())

        // Undo cannot reach back across this event afterward -- new recordings are refused too,
        // since this instance is retired; a forked draft gets a brand-new LoopDraftUndoStack.
        val recorded = stack.record(UndoableAction(StageVerb.DELETE, "delete after activation"))
        assertTrue(!recorded)
        assertNull(stack.undo())
    }

    @Test
    fun `description must be non-blank`() {
        try {
            UndoableAction(StageVerb.ADD, "")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
