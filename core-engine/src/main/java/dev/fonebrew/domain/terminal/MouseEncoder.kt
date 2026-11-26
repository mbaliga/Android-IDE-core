package dev.fonebrew.domain.terminal

import dev.fonebrew.domain.remote.term.MouseTracking
import dev.fonebrew.domain.remote.term.TerminalModes

enum class MouseButton(internal val code: Int) {
    LEFT(0), MIDDLE(1), RIGHT(2),
    /** No button held (pure motion). */
    NONE(3),
    WHEEL_UP(64), WHEEL_DOWN(65), WHEEL_LEFT(66), WHEEL_RIGHT(67),
}

enum class MouseAction { PRESS, RELEASE, MOVE }

/** [col] and [row] are 0-based cell coordinates; [modifiers] are [Mod] flags. */
data class MouseEvent(
    val action: MouseAction,
    val button: MouseButton,
    val col: Int,
    val row: Int,
    val modifiers: Int = 0,
)

/**
 * Encodes mouse events for the mode the application asked for (DECSET 1000 / 1002 / 1003) in
 * either SGR (1006) or the legacy X10 byte encoding. Returns null when the event is not to be
 * reported (tracking off, motion not requested, wheel release, or - X10 only - a coordinate
 * beyond 223). Pure. Reference: "XTerm Control Sequences", Mouse Tracking.
 *
 * Not modelled: UTF-8 (1005) and urxvt (1015) encodings, and alternate-scroll (1007), where the
 * wheel becomes arrow keys on the alt screen - that is a UI decision.
 */
object MouseEncoder {
    fun encode(e: MouseEvent, modes: TerminalModes): ByteArray? {
        val wheel = e.button.code >= 64
        if (wheel && e.action != MouseAction.PRESS) return null
        when (modes.mouseTracking) {
            MouseTracking.NONE -> return null
            MouseTracking.CLICK -> if (e.action == MouseAction.MOVE) return null
            MouseTracking.DRAG -> if (e.action == MouseAction.MOVE && e.button == MouseButton.NONE) return null
            MouseTracking.ANY -> {}
        }
        if (e.col < 0 || e.row < 0) return null
        var cb = e.button.code
        if (e.action == MouseAction.MOVE) cb += 32
        if (e.modifiers and Mod.SHIFT != 0) cb += 4
        if (e.modifiers and (Mod.ALT or Mod.META) != 0) cb += 8
        if (e.modifiers and Mod.CTRL != 0) cb += 16
        val x = e.col + 1
        val y = e.row + 1
        if (modes.mouseSgr) {
            val final = if (e.action == MouseAction.RELEASE) 'm' else 'M'
            return "\u001B[<$cb;$x;$y$final".toByteArray(Charsets.US_ASCII)
        }
        // X10 / normal: one byte each for button, column, row, offset by 32; a release has no
        // button identity (code 3).
        if (e.action == MouseAction.RELEASE) cb = (cb and 3.inv()) or 3
        if (x > 223 || y > 223) return null
        return byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (cb + 32).toByte(), (x + 32).toByte(), (y + 32).toByte())
    }

    /** Focus in/out report (`CSI I` / `CSI O`), only when the application enabled DECSET 1004. */
    fun encodeFocus(focused: Boolean, modes: TerminalModes): ByteArray? =
        if (!modes.focusReporting) null else (if (focused) "\u001B[I" else "\u001B[O").toByteArray(Charsets.US_ASCII)
}
