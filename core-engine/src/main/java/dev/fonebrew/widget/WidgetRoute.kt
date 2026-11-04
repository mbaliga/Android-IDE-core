package dev.fonebrew.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The places a home-screen widget tap may open. Each is navigation only, side-effect free apart from
 * [NEW_CHAT], which does exactly what the chat's own "new chat" control does (the current conversation
 * stays in the tree, untouched).
 */
enum class WidgetDestination { NEW_CHAT, CHAT, CHATS, TASKS, AGENT_REVIEW, LOOPS, MODELS }

data class WidgetRoute(val destination: WidgetDestination, val taskId: String? = null)

/**
 * The extras a widget's PendingIntent carries to `MainActivity` (explicit component, no URL scheme),
 * and their validation. MainActivity is exported (it is the launcher activity), so any app can start
 * it with these extras: an unknown destination is ignored and a malformed task id is dropped.
 */
object WidgetRoutes {
    /** The explicit intent's action, so a widget tap is never mistaken for a launcher start or a share. */
    const val ACTION_OPEN: String = "dev.fonebrew.widget.action.OPEN"
    const val EXTRA_DESTINATION: String = "dev.fonebrew.widget.extra.DESTINATION"
    const val EXTRA_TASK_ID: String = "dev.fonebrew.widget.extra.TASK_ID"

    /** Task ids are UUIDs (`TaskStore`); this accepts any short id of that alphabet, nothing else. */
    private val TASK_ID = Regex("[A-Za-z0-9_-]{1,64}")

    // PendingIntent request-code slots. PendingIntents that differ only in extras are the same
    // PendingIntent, so every tap target of one widget that can carry different extras needs its own slot.
    /** The whole widget (medium and large layouts, and small layouts with no item of their own). */
    const val SLOT_MAIN: Int = 0

    /** A small layout's one shown item (Next up opens the To-do list on that task). */
    const val SLOT_SMALL: Int = 1

    /** A card's explicit action button. */
    const val SLOT_ACTION: Int = 2

    private const val SLOT_PAD_BASE: Int = 3
    const val SLOTS_PER_WIDGET: Int = 16

    fun padSlot(pad: PadAction): Int = SLOT_PAD_BASE + pad.ordinal

    /** Unique per (widget id, slot): app widget ids are small positive ints handed out by the system. */
    fun requestCode(widgetId: Int, slot: Int): Int {
        require(slot in 0 until SLOTS_PER_WIDGET) { "slot $slot out of range" }
        return widgetId * SLOTS_PER_WIDGET + slot
    }

    fun parse(destination: String?, taskId: String?): WidgetRoute? {
        val dest = WidgetDestination.entries.firstOrNull { it.name == destination } ?: return null
        val id = taskId?.takeIf { dest == WidgetDestination.TASKS && TASK_ID.matches(it) }
        return WidgetRoute(dest, id)
    }
}

/**
 * Holds a widget route between `MainActivity` receiving it and the shell (spatial or Regular) acting on
 * it, the same shape as [dev.fonebrew.data.SharedIntake]. The shell [consume]s it once it has navigated.
 */
class WidgetRouteInbox {
    private val _pending = MutableStateFlow<WidgetRoute?>(null)
    val pending: StateFlow<WidgetRoute?> = _pending.asStateFlow()

    fun offer(route: WidgetRoute) {
        _pending.value = route
    }

    fun consume(): WidgetRoute? = _pending.value.also { _pending.value = null }
}
