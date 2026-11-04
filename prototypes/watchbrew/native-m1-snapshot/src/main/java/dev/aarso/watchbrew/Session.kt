// SPDX-License-Identifier: Apache-2.0
package dev.aarso.watchbrew

import java.util.UUID

enum class ConversationMode(val label: String) { SINGLE("Single"), MODELS("Council · models"), EXPERTS("Council · experts") }
enum class Host(val label: String) { PHONE("Phone nearby"), COMPUTER("Remote computer"), CLOUD("Cloud host · watched") }
enum class TaskStatus(val label: String) { IDLE("Ready"), RUNNING("Working remotely"), STOP_REQUESTED("Stop requested"), STOPPED("Stop confirmed"), COMPLETE("Result ready") }
data class Request(val id: String, val text: String, val host: Host, val mode: ConversationMode, val branch: Int, val replyTo: String?)
data class Session(
    val draft: String = "", val replyTo: String? = null,
    val connected: Boolean = true, val host: Host = Host.PHONE,
    val mode: ConversationMode = ConversationMode.SINGLE, val branch: Int = 1,
    val task: TaskStatus = TaskStatus.IDLE, val active: Request? = null,
    val outbox: List<Request> = emptyList(), val rating: Int = 0,
    val displayEnabled: Boolean = false, val history: List<String> = emptyList(),
    val notice: String = "Local demo. No host is connected.",
) {
    val busy: Boolean get() = task == TaskStatus.RUNNING || task == TaskStatus.STOP_REQUESTED
}

sealed interface Action {
    data class Draft(val text: String) : Action
    data class Connection(val available: Boolean) : Action
    data class SelectHost(val host: Host) : Action
    data class Mode(val mode: ConversationMode) : Action
    data class Rate(val value: Int) : Action
    data class Display(val enabled: Boolean) : Action
    data object Submit : Action
    data object SyncOne : Action
    data object Stop : Action
    data object ConfirmStop : Action
    data object Complete : Action
    data object Reply : Action
    data object Regenerate : Action
}

/** Adapter boundary. This milestone deliberately has no real transport implementation. */
interface SessionHost {
    fun accept(request: Request): String
    fun requestStop(request: Request): String
}
class FakeSessionHost : SessionHost {
    override fun accept(request: Request) = "Simulated host accepted ${request.id}."
    override fun requestStop(request: Request) = "Stop requested for ${request.id}; awaiting simulated acknowledgment."
}

class SessionReducer(private val host: SessionHost = FakeSessionHost(), private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun reduce(s: Session, action: Action): Session = when (action) {
        is Action.Draft -> s.copy(draft = action.text.take(1200))
        is Action.Connection -> s.copy(connected = action.available,
            notice = if (action.available) "Demo link available. Queued requests need explicit sync." else "Demo link unavailable. Requests stay on this watch.")
        is Action.SelectHost -> if (s.busy || s.outbox.isNotEmpty()) s.copy(notice = "Finish active and queued work before changing host.")
            else s.copy(host = action.host, notice = "Selected ${action.host.label} for simulation only.")
        is Action.Mode -> s.copy(mode = action.mode).record("Next request: ${action.mode.label}")
        is Action.Rate -> s.copy(rating = action.value.coerceIn(-2, 2)).record("Sample message value: ${action.value.coerceIn(-2, 2)}")
        is Action.Display -> s.copy(displayEnabled = action.enabled,
            notice = if (action.enabled) "E-ink preference saved. Native display transport is not connected yet." else "Optional e-ink disabled. Watch remains usable.")
        Action.Submit -> when {
            s.draft.isBlank() -> s.copy(notice = "Add a request first.")
            s.busy -> s.copy(notice = "Finish the current task before sending another request.")
            s.outbox.size >= MAX_OUTBOX -> s.copy(notice = "Queue is full. Sync pending requests before adding more.")
            else -> {
                val request = Request(newId(), s.draft.trim(), s.host, s.mode, s.branch, s.replyTo)
                val cleared = s.copy(draft = "", replyTo = null)
                // Preserve FIFO even when connectivity returns before the user explicitly syncs.
                if (!s.connected || s.outbox.isNotEmpty()) cleared.copy(outbox = s.outbox + request).record("Queued ${request.id} on watch. Use Sync next when connected.")
                else cleared.copy(active = request, task = TaskStatus.RUNNING).record(host.accept(request))
            }
        }
        Action.SyncOne -> when {
            !s.connected -> s.copy(notice = "Reconnect before syncing.")
            s.busy -> s.copy(notice = "Wait for the active task before syncing the next request.")
            s.outbox.isEmpty() -> s.copy(notice = "No queued requests.")
            else -> s.copy(outbox = s.outbox.drop(1), active = s.outbox.first(), task = TaskStatus.RUNNING)
                .record(host.accept(s.outbox.first()))
        }
        Action.Stop -> if (s.connected && s.task == TaskStatus.RUNNING && s.active != null)
            s.copy(task = TaskStatus.STOP_REQUESTED).record(host.requestStop(s.active))
            else s.copy(notice = "A stop needs a reachable host and a running task.")
        Action.ConfirmStop -> if (s.connected && s.task == TaskStatus.STOP_REQUESTED)
            s.copy(task = TaskStatus.STOPPED).record("Simulated host confirmed stop.") else s
        Action.Complete -> if (s.connected && s.busy)
            s.copy(task = TaskStatus.COMPLETE).record("Sample result received. No build or model was run.") else s
        Action.Reply -> s.copy(replyTo = SAMPLE_MESSAGE, notice = "Reply linked to the sample message. Review before sending.")
        Action.Regenerate -> if (!s.connected || s.busy || s.outbox.isNotEmpty())
            s.copy(notice = "Reconnect and finish pending work before creating a branch.")
            else {
                val request = Request(newId(), "Regenerate sample result", s.host, s.mode, s.branch + 1, SAMPLE_MESSAGE)
                s.copy(branch = s.branch + 1, active = request, task = TaskStatus.RUNNING)
                    .record("Created branch ${request.branch}; earlier history preserved. ${host.accept(request)}")
            }
    }
    private fun Session.record(event: String) = copy(history = (history + event).takeLast(100), notice = event)
    companion object { const val MAX_OUTBOX = 10; const val SAMPLE_MESSAGE = "sample-result-1" }
}
