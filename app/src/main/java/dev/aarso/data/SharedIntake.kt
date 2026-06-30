package dev.aarso.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Content captured from elsewhere on the device and routed *into* Aarso (handoff
 * §7): text shared in (ACTION_SEND), a text selection (ACTION_PROCESS_TEXT), or
 * the assist gesture's on-screen text. A new front door onto the existing model
 * registry + fan-out — not a new backend.
 *
 * Frame honestly as "shared-in or captured," not "magically lifted": a 3rd-party
 * app cannot reach into other apps the way an OEM can (§7).
 */
data class Intake(val text: String? = null, val imageUri: String? = null, val source: String = "share")

class SharedIntake {
    private val _pending = MutableStateFlow<Intake?>(null)
    val pending: StateFlow<Intake?> = _pending.asStateFlow()

    fun offer(intake: Intake) { _pending.value = intake }
    fun consume(): Intake? = _pending.value.also { _pending.value = null }
    fun clear() { _pending.value = null }
}
