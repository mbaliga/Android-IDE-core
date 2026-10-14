package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.TaskToggleResult
import dev.fonebrew.watchbridge.WatchTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksTest {
    private fun reduce(s: TasksState, vararg events: TaskEvent) = events.fold(s) { acc, e -> TasksReducer.reduce(acc, e) }

    private val three = Fx.snapshot(
        Fx.task("a", "Buy milk", WatchTaskState.TODO),
        Fx.task("b", "Write spec", WatchTaskState.DOING),
        Fx.task("c", "Ship it", WatchTaskState.DONE),
    )
    private val loaded = reduce(TasksState(), TaskEvent.SnapshotReceived(three, 1_000))

    // ---- the single-writer rule: optimistic-free ----

    @Test
    fun requestingAToggle_recordsPending_butChangesNoRow() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("a", done = true, atMs = 2_000))
        assertEquals(PendingToggle(true, 2_000), s.pending["a"])
        assertSame("the confirmed snapshot must be untouched", loaded.snapshot, s.snapshot)
        assertEquals(WatchTaskState.TODO, s.snapshot!!.tasks.first { it.id == "a" }.state)
    }

    @Test
    fun theRowStillShowsItsConfirmedState_whileAToggleIsPending() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000))
        val row = (TasksPresenter.present(s, Connection.Reachable(Fx.phone, null), 3_000) as TasksView.Data).rows.first { it.id == "a" }
        assertEquals(Glyph.TASK_TODO, row.glyph)
        assertEquals("to do", row.stateLabel)
        assertEquals("Asking phone to mark done…", row.pendingLabel)
        assertFalse("no second tap while one is outstanding", row.tappable)
    }

    @Test
    fun aDuplicateRequestForTheSameTask_orForAnUnknownTask_isIgnored() {
        val once = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000))
        assertSame(once, reduce(once, TaskEvent.ToggleRequested("a", true, 2_500)))
        assertSame(loaded, reduce(loaded, TaskEvent.ToggleRequested("nope", true, 2_000)))
        assertEquals(TasksState(), reduce(TasksState(), TaskEvent.ToggleRequested("a", true, 2_000)))
    }

    @Test
    fun appliedDone_isExact_rowBecomesDone_andPendingCountFallsByOne() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000), TaskEvent.ToggleResultReceived(TaskToggleResult.Applied("a", true)))
        assertEquals(WatchTaskState.DONE, s.snapshot!!.tasks.first { it.id == "a" }.state)
        assertEquals(1, s.snapshot!!.pendingCount)
        assertTrue(s.pending.isEmpty())
        assertFalse("done=true is exact; nothing to re-fetch", s.needsResync)
    }

    @Test
    fun appliedNotDone_onADoneRow_showsFreeFloorTodo_butAsksForTheTrueState() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("c", false, 2_000), TaskEvent.ToggleResultReceived(TaskToggleResult.Applied("c", false)))
        assertEquals(WatchTaskState.TODO, s.snapshot!!.tasks.first { it.id == "c" }.state)
        assertEquals(3, s.snapshot!!.pendingCount)
        assertTrue("the watch chose TODO; it must not pretend to know the real not-done state", s.needsResync)
    }

    @Test
    fun appliedNotDone_onAnAlreadyOpenRow_changesNothing() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("b", false, 2_000), TaskEvent.ToggleResultReceived(TaskToggleResult.Applied("b", false)))
        assertEquals(WatchTaskState.DOING, s.snapshot!!.tasks.first { it.id == "b" }.state)
        assertFalse(s.needsResync)
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun pendingCount_neverDropsBelowTheNotDoneRowsActuallyListed_evenWhenTheListIsTruncated() {
        val truncated = reduce(TasksState(), TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("a"), Fx.task("b"), pending = 40), 1_000))
        val s = reduce(truncated, TaskEvent.ToggleRequested("a", true, 2_000), TaskEvent.ToggleResultReceived(TaskToggleResult.Applied("a", true)))
        assertEquals("the phone's true 40 minus the one just completed", 39, s.snapshot!!.pendingCount)
    }

    @Test
    fun rejection_clearsPending_showsThePhonesWords_changesNoRow_andAsksForAFreshList() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000), TaskEvent.ToggleResultReceived(TaskToggleResult.Rejected("a", "the task no longer exists")))
        assertTrue(s.pending.isEmpty())
        assertEquals("Phone: the task no longer exists", s.notices["a"])
        assertEquals(loaded.snapshot, s.snapshot)
        assertTrue(s.needsResync)
    }

    @Test
    fun sendFailure_clearsPending_andSaysNothingWasChanged() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000), TaskEvent.ToggleSendFailed("a", LinkFailure.NO_NODE))
        assertTrue(s.pending.isEmpty())
        assertTrue(s.notices["a"]!!.contains("Nothing was changed"))
        assertEquals(loaded.snapshot, s.snapshot)
    }

    @Test
    fun timeout_doesNotRollBackAnything_becauseNothingWasChanged_andIgnoresAStaleTimer() {
        val requested = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000))
        assertSame(requested, reduce(requested, TaskEvent.ToggleTimedOut("a", sentAtMs = 1_999)))
        val s = reduce(requested, TaskEvent.ToggleTimedOut("a", 2_000))
        assertTrue(s.pending.isEmpty())
        assertTrue(s.notices["a"]!!.contains("No confirmation"))
        assertTrue(s.needsResync)
        assertEquals(loaded.snapshot, s.snapshot)
    }

    // ---- snapshots ----

    @Test
    fun freshSnapshot_replacesTheList_clearsNotices_andRecordsTheReceiptTime() {
        val noisy = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000), TaskEvent.ToggleSendFailed("a", LinkFailure.OTHER))
        val s = reduce(noisy, TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("a"), Fx.task("z")), 5_000))
        assertEquals(setOf("a", "z"), s.snapshot!!.tasks.map { it.id }.toSet())
        assertEquals(5_000L, s.receivedAtMs)
        assertTrue(s.notices.isEmpty())
        assertFalse(s.needsResync)
    }

    @Test
    fun aSnapshotThatNowShowsTheRequestedState_clearsPending_oneThatDoesNotKeepsIt() {
        val requested = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000))
        val tookEffect = reduce(requested, TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("a", state = WatchTaskState.DONE)), 3_000))
        assertTrue(tookEffect.pending.isEmpty())
        val notYet = reduce(requested, TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("a")), 3_000))
        assertTrue(notYet.pending.containsKey("a"))
        val vanished = reduce(requested, TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("other")), 3_000))
        assertTrue(vanished.pending.isEmpty())
    }

    @Test
    fun resyncHandled_andNoticeDismissed() {
        val s = reduce(loaded, TaskEvent.ToggleRequested("a", true, 2_000), TaskEvent.ToggleTimedOut("a", 2_000))
        assertTrue(s.needsResync)
        assertFalse(reduce(s, TaskEvent.ResyncHandled).needsResync)
        assertNull(reduce(s, TaskEvent.NoticeDismissed("a")).notices["a"])
    }

    // ---- presenter ----

    @Test
    fun neverReceived_isNoData_withTheLinksReasonWhenUnreachable() {
        val unreachable = TasksPresenter.present(TasksState(), Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), 0) as TasksView.NoData
        assertTrue(unreachable.reason.contains("Phone not reachable"))
        val linked = TasksPresenter.present(TasksState(), Connection.Reachable(Fx.phone, null), 0) as TasksView.NoData
        assertEquals("No tasks received from the phone yet.", linked.reason)
    }

    @Test
    fun rows_areOrderedByAttention_stableWithinAState_andCarryDistinctShapesAndWords() {
        val snap = Fx.snapshot(
            Fx.task("done1", state = WatchTaskState.DONE),
            Fx.task("todo1"),
            Fx.task("blocked1", state = WatchTaskState.BLOCKED),
            Fx.task("todo2"),
            Fx.task("doing1", state = WatchTaskState.DOING),
        )
        val state = reduce(TasksState(), TaskEvent.SnapshotReceived(snap, 1_000))
        val data = TasksPresenter.present(state, Connection.Reachable(Fx.phone, null), 1_000) as TasksView.Data
        assertEquals(listOf("doing1", "blocked1", "todo1", "todo2", "done1"), data.rows.map { it.id })
        val perState = data.rows.associateBy { it.stateLabel }
        assertEquals(4, perState.size)
        assertEquals(4, perState.values.map { it.glyph }.toSet().size)
    }

    @Test
    fun tapTarget_isTheOppositeOfTheConfirmedState() {
        val data = TasksPresenter.present(loaded, Connection.Reachable(Fx.phone, null), 2_000) as TasksView.Data
        assertTrue(data.rows.first { it.id == "a" }.nextDone)
        assertFalse(data.rows.first { it.id == "c" }.nextDone)
    }

    @Test
    fun anUntitledTask_isFlagged_notRenamed() {
        val state = reduce(TasksState(), TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("a", title = "")), 1_000))
        val row = (TasksPresenter.present(state, Connection.Checking, 1_000) as TasksView.Data).rows.single()
        assertTrue(row.isUntitled)
        assertEquals("", row.title)
    }

    @Test
    fun truncatedList_saysHowManyPendingTasksAreNotShown() {
        val state = reduce(TasksState(), TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("a"), Fx.task("b"), pending = 9), 1_000))
        val data = TasksPresenter.present(state, Connection.Checking, 1_000) as TasksView.Data
        assertEquals(9, data.pendingCount)
        assertEquals(7, data.hiddenPending)
        assertEquals(0, (TasksPresenter.present(loaded, Connection.Checking, 1_000) as TasksView.Data).hiddenPending)
    }

    @Test
    fun whenThePhoneIsUnreachable_theLastConfirmedListStays_butTogglesAreNotOffered_andAgeIsShown() {
        val data = TasksPresenter.present(loaded, Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), 1_000 + 5 * 60_000) as TasksView.Data
        assertFalse(data.toggleEnabled)
        assertTrue(data.rows.none { it.tappable })
        assertEquals("updated 5 min ago", data.freshness)
        assertTrue(data.offlineReason!!.contains("Phone not reachable"))
        val online = TasksPresenter.present(loaded, Connection.Reachable(Fx.phone, null), 1_000) as TasksView.Data
        assertTrue(online.toggleEnabled)
        assertNull(online.offlineReason)
    }
}
