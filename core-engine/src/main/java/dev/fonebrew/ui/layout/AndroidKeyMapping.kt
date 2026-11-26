package dev.fonebrew.ui.layout

import android.view.KeyEvent
import dev.fonebrew.domain.layout.KeyChord

/**
 * Maps Android `(keyCode, metaState)` ints to a pure [KeyChord]. Ints rather than a `KeyEvent`
 * so it runs in plain JVM tests. Keys we do not name (media, volume, modifiers themselves) map to null.
 */
object AndroidKeyMapping {
    fun keyName(keyCode: Int): String? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_COMMA -> "Comma"
        KeyEvent.KEYCODE_PERIOD -> "Period"
        KeyEvent.KEYCODE_SLASH -> "Slash"
        KeyEvent.KEYCODE_BACKSLASH -> "Backslash"
        KeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
        KeyEvent.KEYCODE_APOSTROPHE -> "Quote"
        KeyEvent.KEYCODE_MINUS -> "Minus"
        KeyEvent.KEYCODE_EQUALS -> "Equals"
        KeyEvent.KEYCODE_GRAVE -> "Backquote"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "LeftBracket"
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "RightBracket"
        else -> null
    }

    fun toChord(keyCode: Int, metaState: Int): KeyChord? {
        val key = keyName(keyCode) ?: return null
        return KeyChord(
            ctrl = metaState and KeyEvent.META_CTRL_ON != 0,
            alt = metaState and KeyEvent.META_ALT_ON != 0,
            shift = metaState and KeyEvent.META_SHIFT_ON != 0,
            meta = metaState and KeyEvent.META_META_ON != 0,
            key = key,
        )
    }
}
