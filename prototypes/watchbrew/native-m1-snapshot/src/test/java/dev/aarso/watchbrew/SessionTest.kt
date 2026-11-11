// SPDX-License-Identifier: Apache-2.0
package dev.aarso.watchbrew

import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private var sequence = 0
    private val reducer = SessionReducer(newId = { "request-${++sequence}" })
    private fun apply(s: Session = Session(), vararg actions: Action) = actions.fold(s, reducer::reduce)
    private fun running() = apply(Session(), Action.Draft("Check the build"), Action.Submit)

    @Test fun watchOnlyTaskCompletesWithoutDisplay() {
        val s = reducer.reduce(running(), Action.Complete)
        assertEquals(TaskStatus.COMPLETE, s.task)
        assertFalse(s.displayEnabled)
    }
    @Test fun queuedRequestsSurviveRestartWithIdentityAndExplicitSync() {
        val queued = apply(Session(), Action.Connection(false), Action.Draft("Inspect target"), Action.Submit)
        val restored = SessionCodec.decode(SessionCodec.encode(queued))
        assertEquals(queued, restored)
        val connected = reducer.reduce(restored, Action.Connection(true))
        assertNull(connected.active)
        val accepted = reducer.reduce(connected, Action.SyncOne)
        assertEquals(queued.outbox.single().id, accepted.active!!.id)
        assertTrue(accepted.outbox.isEmpty())
    }
    @Test fun reconnectDoesNotAllowNewRequestsToOvertakeOutbox() {
        val s = apply(Session(), Action.Connection(false), Action.Draft("First"), Action.Submit,
            Action.Connection(true), Action.Draft("Second"), Action.Submit, Action.SyncOne)
        assertEquals("First", s.active!!.text)
        assertEquals("Second", s.outbox.single().text)
    }
    @Test fun queuedModeAndHostRemainCapturedWhenNextTurnModeChanges() {
        val s = apply(Session(), Action.Connection(false), Action.Draft("First"), Action.Submit,
            Action.Mode(ConversationMode.EXPERTS), Action.SelectHost(Host.CLOUD))
        assertEquals(Host.PHONE, s.host)
        assertEquals(ConversationMode.SINGLE, s.outbox.single().mode)
        assertEquals(ConversationMode.EXPERTS, s.mode)
    }
    @Test fun stopNeedsAcknowledgmentAndCanLoseRaceToCompletion() {
        val pending = reducer.reduce(running(), Action.Stop)
        assertEquals(TaskStatus.STOP_REQUESTED, pending.task)
        assertEquals(pending, SessionCodec.decode(SessionCodec.encode(pending)))
        assertEquals(TaskStatus.STOPPED, reducer.reduce(pending, Action.ConfirmStop).task)
        val complete = reducer.reduce(pending, Action.Complete)
        assertEquals(TaskStatus.COMPLETE, reducer.reduce(complete, Action.ConfirmStop).task)
    }
    @Test fun stopCannotBeClaimedWhileDisconnected() {
        val s = apply(running(), Action.Connection(false), Action.Stop)
        assertEquals(TaskStatus.RUNNING, s.task)
    }
    @Test fun fullQueuePreservesUnsentDraft() {
        var s = Session(connected = false)
        repeat(SessionReducer.MAX_OUTBOX) { s = apply(s, Action.Draft("Queued $it"), Action.Submit) }
        s = apply(s, Action.Draft("Keep this draft"), Action.Submit)
        assertEquals(SessionReducer.MAX_OUTBOX, s.outbox.size)
        assertEquals("Keep this draft", s.draft)
        assertEquals(s, SessionCodec.decode(SessionCodec.encode(s)))
    }
    @Test fun replyAndDraftSurviveRestartBeforeSending() {
        val s = apply(Session(), Action.Reply, Action.Draft("Explain the second change"))
        val restored = SessionCodec.decode(SessionCodec.encode(s))
        assertEquals(s, restored)
        assertEquals(SessionReducer.SAMPLE_MESSAGE, reducer.reduce(restored, Action.Submit).active!!.replyTo)
    }
    @Test fun regenerationAddsBranchWithoutDiscardingPriorEvents() {
        val s = apply(Session(), Action.Rate(2), Action.Mode(ConversationMode.MODELS))
        val branch = reducer.reduce(s, Action.Regenerate)
        assertEquals(2, branch.branch)
        assertEquals(s.history, branch.history.take(s.history.size))
        assertEquals(ConversationMode.MODELS, branch.active!!.mode)
    }
    @Test fun activeRequestCannotBeReplacedByAnotherSend() {
        val first = running()
        val second = apply(first, Action.Draft("Another task"), Action.Submit, Action.SyncOne)
        assertEquals(first.active, second.active)
        assertEquals("Another task", second.draft)
    }
    @Test fun displayPreferenceDoesNotAffectExecution() {
        val s = apply(running(), Action.Display(true), Action.Display(false), Action.Complete)
        assertEquals(TaskStatus.COMPLETE, s.task)
        assertFalse(s.displayEnabled)
    }
    @Test fun unsupportedSnapshotIsRejectedInsteadOfDiscardingHistory() {
        try {
            SessionCodec.decode(SessionCodec.encode(Session()).replace("\"version\":1", "\"version\":999"))
            fail("Future versions must not load as a new empty session")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun ratingIsBoundedAndEmptyDraftIsNotSubmitted() {
        assertEquals(-2, reducer.reduce(Session(), Action.Rate(-50)).rating)
        assertEquals(2, reducer.reduce(Session(), Action.Rate(50)).rating)
        assertEquals(TaskStatus.IDLE, reducer.reduce(Session(), Action.Submit).task)
    }
}
