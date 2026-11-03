package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.TaskSnapshot

/** Marker for everything [WatchReducer] accepts. Families are sealed in their own files. */
sealed interface WatchEvent

/** The whole watch-side picture. Every field is either a fact the phone sent or bookkeeping about a request. */
data class WatchState(
    val connection: Connection = Connection.Checking,
    val ask: AskState = AskState.Idle,
    val tasks: TasksState = TasksState(),
    val loops: LoopsState = LoopsState(),
    /** The last finished reply (survives restarts via the cache) — feeds the Tile. */
    val lastReply: ReplyRecord? = null,
)

/** Cached facts loaded at start-up; the age shown for them is the original receipt time, never "now". */
data class Hydrated(
    val tasks: Pair<TaskSnapshot, Long>?,
    val loops: List<LoopEntry>,
    val lastReply: ReplyRecord?,
) : WatchEvent

object WatchReducer {
    fun reduce(state: WatchState, event: WatchEvent): WatchState = when (event) {
        is LinkEvent -> state.copy(connection = ConnectionReducer.reduce(state.connection, event))
        is AskEvent -> {
            val ask = AskReducer.reduce(state.ask, event)
            state.copy(ask = ask, lastReply = (ask as? AskState.Done)?.reply ?: state.lastReply)
        }
        is TaskEvent -> state.copy(tasks = TasksReducer.reduce(state.tasks, event))
        is LoopEvent -> state.copy(loops = LoopsReducer.reduce(state.loops, event))
        // Cached facts only ever FILL gaps: live data that already arrived is newer than the cache.
        is Hydrated -> state.copy(
            tasks = if (state.tasks.snapshot == null) {
                event.tasks?.let { (snapshot, at) -> state.tasks.copy(snapshot = snapshot, receivedAtMs = at) } ?: state.tasks
            } else {
                state.tasks
            },
            loops = if (state.loops.runs.isEmpty() && event.loops.isNotEmpty()) LoopsState(event.loops.take(LoopsState.MAX_RUNS)) else state.loops,
            lastReply = state.lastReply ?: event.lastReply,
        )
    }
}
