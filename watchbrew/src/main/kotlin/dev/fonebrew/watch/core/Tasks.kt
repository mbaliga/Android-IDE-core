package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.TaskSnapshot
import dev.fonebrew.watchbridge.TaskToggleResult
import dev.fonebrew.watchbridge.WatchTaskState

/** A toggle the watch has asked the phone for and not yet heard back about. NOT a state change — see [TasksReducer]. */
data class PendingToggle(val requestedDone: Boolean, val sentAtMs: Long)

/**
 * The Tasks surface's state: the **last confirmed** snapshot from the phone plus the bookkeeping of
 * outstanding requests. The phone's `TaskStore` is the single writer; this state never holds a task
 * state the phone did not say. A toggle in flight lives in [pending] *beside* the confirmed rows, it
 * does not edit them.
 */
data class TasksState(
    val snapshot: TaskSnapshot? = null,
    val receivedAtMs: Long? = null,
    val pending: Map<String, PendingToggle> = emptyMap(),
    /** Per-task one-line outcomes the user should see ("phone rejected…", "no confirmation…"). */
    val notices: Map<String, String> = emptyMap(),
    /** The reducer could not be sure of a row after an authoritative reply; ask the phone for a fresh snapshot. */
    val needsResync: Boolean = false,
)

sealed interface TaskEvent : WatchEvent {
    data class SnapshotReceived(val snapshot: TaskSnapshot, val atMs: Long) : TaskEvent
    data class ToggleRequested(val taskId: String, val done: Boolean, val atMs: Long) : TaskEvent
    data class ToggleSendFailed(val taskId: String, val failure: LinkFailure) : TaskEvent
    data class ToggleResultReceived(val result: TaskToggleResult) : TaskEvent
    data class ToggleTimedOut(val taskId: String, val sentAtMs: Long) : TaskEvent
    data class NoticeDismissed(val taskId: String) : TaskEvent
    data object ResyncHandled : TaskEvent
}

/**
 * Task-list reduction. **Optimistic-free by construction** (the owner's rule: render the confirmed
 * state only; the phone is the single writer):
 *
 *  - [TaskEvent.ToggleRequested] records the request in `pending` and changes **no** row.
 *  - A row changes state only through an authoritative phone message: a fresh [TaskSnapshot], or a
 *    [TaskToggleResult.Applied] (which says what the phone's store now holds).
 *  - An `Applied(done = true)` is exact (the row is DONE). An `Applied(done = false)` on a row we
 *    showed as DONE means "no longer done" but not *which* not-done state; the reducer shows the free
 *    floor's `TODO` and sets [TasksState.needsResync] so the true state is fetched — it does not
 *    pretend to know more.
 *  - A timeout does **not** roll anything back (nothing was changed) — it clears the pending marker
 *    and leaves a notice that no confirmation came, plus a resync.
 */
object TasksReducer {
    fun reduce(state: TasksState, event: TaskEvent): TasksState = when (event) {
        is TaskEvent.SnapshotReceived -> {
            val byId = event.snapshot.tasks.associateBy { it.id }
            state.copy(
                snapshot = event.snapshot,
                receivedAtMs = event.atMs,
                // A pending request survives a snapshot only if that snapshot does not yet show it taking effect.
                pending = state.pending.filter { (id, p) -> byId[id]?.let { (it.state == WatchTaskState.DONE) != p.requestedDone } == true },
                notices = emptyMap(),
                needsResync = false,
            )
        }

        is TaskEvent.ToggleRequested -> {
            val known = state.snapshot?.tasks?.any { it.id == event.taskId } == true
            if (!known || state.pending.containsKey(event.taskId)) state
            else state.copy(
                pending = state.pending + (event.taskId to PendingToggle(event.done, event.atMs)),
                notices = state.notices - event.taskId,
            )
        }

        is TaskEvent.ToggleSendFailed -> state.copy(
            pending = state.pending - event.taskId,
            notices = state.notices + (event.taskId to sendFailureNotice(event.failure)),
        )

        is TaskEvent.ToggleResultReceived -> when (val result = event.result) {
            is TaskToggleResult.Applied -> applyConfirmed(state, result.taskId, result.done)
            is TaskToggleResult.Rejected -> state.copy(
                pending = state.pending - result.taskId,
                notices = state.notices + (result.taskId to "Phone: ${result.reason}"),
                needsResync = true,
            )
        }

        is TaskEvent.ToggleTimedOut ->
            if (state.pending[event.taskId]?.sentAtMs == event.sentAtMs) {
                state.copy(
                    pending = state.pending - event.taskId,
                    notices = state.notices + (event.taskId to "No confirmation from phone. Showing the last confirmed state."),
                    needsResync = true,
                )
            } else {
                state
            }

        is TaskEvent.NoticeDismissed -> state.copy(notices = state.notices - event.taskId)
        TaskEvent.ResyncHandled -> state.copy(needsResync = false)
    }

    private fun sendFailureNotice(failure: LinkFailure): String = when (failure) {
        LinkFailure.NO_NODE -> "Phone not reachable. Nothing was changed."
        LinkFailure.API_UNAVAILABLE -> "Phone link unavailable. Nothing was changed."
        LinkFailure.OTHER -> "Couldn't reach the phone. Nothing was changed."
    }

    private fun applyConfirmed(state: TasksState, taskId: String, done: Boolean): TasksState {
        val cleared = state.copy(pending = state.pending - taskId, notices = state.notices - taskId)
        val snapshot = state.snapshot ?: return cleared
        val index = snapshot.tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return cleared
        val task = snapshot.tasks[index]
        val newState = when {
            done -> WatchTaskState.DONE
            task.state == WatchTaskState.DONE -> WatchTaskState.TODO
            else -> task.state
        }
        if (newState == task.state) return cleared
        val tasks = snapshot.tasks.toMutableList().also { it[index] = task.copy(state = newState) }
        val delta = (if (newState != WatchTaskState.DONE) 1 else 0) - (if (task.state != WatchTaskState.DONE) 1 else 0)
        val listedPending = tasks.count { it.state != WatchTaskState.DONE }
        return cleared.copy(
            snapshot = TaskSnapshot(tasks, maxOf(listedPending, snapshot.pendingCount + delta)),
            // We chose TODO for a task the phone merely said is no longer done.
            needsResync = state.needsResync || (!done && task.state == WatchTaskState.DONE),
        )
    }
}

/** A task row as words + a shape. The row shows the CONFIRMED state; [pending] is a separate, worded marker. */
data class TaskRow(
    val id: String,
    val title: String,
    val isUntitled: Boolean,
    val glyph: Glyph,
    val stateLabel: String,
    /** What tapping the row asks the phone for: `done = true` for an open task, `false` for a done one. */
    val nextDone: Boolean,
    /** "Asking phone to mark done…" while a request is outstanding; the row's glyph does NOT change meanwhile. */
    val pendingLabel: String?,
    val notice: String?,
    val tappable: Boolean,
    val spoken: String,
)

sealed interface TasksView {
    /** Nothing has ever arrived from the phone. [reason] says why in the link's own words. */
    data class NoData(val reason: String) : TasksView

    data class Data(
        val rows: List<TaskRow>,
        /** The phone's true not-done count (may exceed the listed rows when the phone truncated the list). */
        val pendingCount: Int,
        /** Not-done tasks the phone has but did not send ("N more on phone"); 0 when the list is complete. */
        val hiddenPending: Int,
        val freshness: String,
        /** `false` while the phone is unreachable/incompatible: toggles would only fail, so they are not offered. */
        val toggleEnabled: Boolean,
        /** A one-line worded reason shown when [toggleEnabled] is false. */
        val offlineReason: String?,
    ) : TasksView
}

object TasksPresenter {
    fun present(state: TasksState, connection: Connection, nowMs: Long): TasksView {
        val snapshot = state.snapshot
        val link = ConnectionPresenter.present(connection)
        if (snapshot == null) {
            return TasksView.NoData(
                if (link.blocking) "${link.headline}. ${link.detail}" else "No tasks received from the phone yet.",
            )
        }
        val enabled = !link.blocking
        val listedPending = snapshot.tasks.count { it.state != WatchTaskState.DONE }
        val rows = snapshot.tasks
            .sortedBy { order(it.state) } // stable: the phone's own order is kept within a state
            .map { task ->
                val pending = state.pending[task.id]
                val untitled = task.title.isBlank()
                val label = stateLabel(task.state)
                val pendingLabel = pending?.let { if (it.requestedDone) "Asking phone to mark done…" else "Asking phone to reopen…" }
                TaskRow(
                    id = task.id,
                    title = task.title,
                    isUntitled = untitled,
                    glyph = glyph(task.state),
                    stateLabel = label,
                    nextDone = task.state != WatchTaskState.DONE,
                    pendingLabel = pendingLabel,
                    notice = state.notices[task.id],
                    tappable = enabled && pending == null,
                    spoken = "${if (untitled) "untitled task" else task.title}, $label" + (pendingLabel?.let { ", $it" } ?: ""),
                )
            }
        return TasksView.Data(
            rows = rows,
            pendingCount = snapshot.pendingCount,
            hiddenPending = snapshot.pendingCount - listedPending,
            freshness = "updated " + RelativeTime.ago(state.receivedAtMs ?: nowMs, nowMs),
            toggleEnabled = enabled,
            offlineReason = if (enabled) null else "${link.headline}. Showing the last confirmed list.",
        )
    }

    fun glyph(state: WatchTaskState): Glyph = when (state) {
        WatchTaskState.TODO -> Glyph.TASK_TODO
        WatchTaskState.DOING -> Glyph.TASK_DOING
        WatchTaskState.BLOCKED -> Glyph.TASK_BLOCKED
        WatchTaskState.DONE -> Glyph.TASK_DONE
    }

    fun stateLabel(state: WatchTaskState): String = when (state) {
        WatchTaskState.TODO -> "to do"
        WatchTaskState.DOING -> "doing"
        WatchTaskState.BLOCKED -> "blocked"
        WatchTaskState.DONE -> "done"
    }

    /** Attention order: what is moving first, what is stuck next, then the open ones, done last. */
    private fun order(state: WatchTaskState): Int = when (state) {
        WatchTaskState.DOING -> 0
        WatchTaskState.BLOCKED -> 1
        WatchTaskState.TODO -> 2
        WatchTaskState.DONE -> 3
    }
}
