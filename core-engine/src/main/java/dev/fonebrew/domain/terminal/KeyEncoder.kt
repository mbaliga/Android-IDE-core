package dev.fonebrew.domain.terminal

import dev.fonebrew.domain.remote.term.TerminalModes

/** Modifier bit flags on a [KeyEvent]. */
object Mod {
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
    const val META = 8
}

/** Keys that are not just text. Keypad keys are separate because application-keypad mode changes them. */
enum class NamedKey {
    ENTER, TAB, BACKSPACE, ESCAPE,
    UP, DOWN, RIGHT, LEFT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    KP_0, KP_1, KP_2, KP_3, KP_4, KP_5, KP_6, KP_7, KP_8, KP_9,
    KP_ENTER, KP_PLUS, KP_MINUS, KP_MULTIPLY, KP_DIVIDE, KP_DECIMAL, KP_COMMA,
}

/**
 * An abstract key press, independent of any UI toolkit: either a [key] or the [text] it produced
 * (already shifted/composed by the platform), plus [modifiers] ([Mod] flags).
 */
data class KeyEvent(val key: NamedKey? = null, val text: String? = null, val modifiers: Int = 0)

/**
 * Turns [KeyEvent]s into the byte sequences an xterm sends, for the *current* [TerminalModes]
 * (application cursor keys, application keypad). Pure. Reference: the public "XTerm Control
 * Sequences" document (PC-style function keys, cursor keys, keypad, modifier parameter
 * `1 + Shift(1) + Alt(2) + Ctrl(4) + Meta(8)`).
 *
 * Choices, stated:
 *  - Modified cursor/Home/End/F-keys/editing keys always use the CSI form `CSI 1;m X` /
 *    `CSI n;m ~`, even in application cursor mode (that is what xterm does).
 *  - Alt on a text key or on Enter/Tab/Backspace/Esc sends an ESC prefix. Meta alone on a text
 *    key is ignored (it is the platform's Super key more often than a terminal Meta).
 *  - Backspace sends DEL (0x7F); Ctrl+Backspace sends BS (0x08).
 *  - Ctrl+letter and the classic Ctrl punctuation set (`@ [ \ ] ^ _ ? space 2-8 /`) send the
 *    control code; Ctrl with any other text sends the text unchanged.
 *  - Application-keypad keys with *any* modifier fall back to their plain character (xterm's
 *    modified keypad forms are rarely wired and not modelled).
 * Not modelled: modifyOtherKeys / kitty keyboard protocol (an app has to opt in; we never answer
 * the query, so none will).
 */
object KeyEncoder {
    private const val ESC = "\u001B"

    /** The bytes to send, or null if the event produces nothing (empty text). */
    fun encode(event: KeyEvent, modes: TerminalModes): ByteArray? {
        val s = (event.key?.let { named(it, event.modifiers, modes) }
            ?: event.text?.let { text(it, event.modifiers) }) ?: return null
        return s.toByteArray(Charsets.UTF_8)
    }

    /** `1 + mask` as xterm numbers modifiers; the mask here is exactly [Mod]'s bit layout. */
    private fun param(mods: Int) = 1 + (mods and 15)

    private fun named(key: NamedKey, mods: Int, modes: TerminalModes): String? {
        val m = mods and 15
        val alt = m and Mod.ALT != 0
        val altPrefix = if (alt) ESC else ""
        return when (key) {
            NamedKey.UP -> cursorKey('A', m, modes)
            NamedKey.DOWN -> cursorKey('B', m, modes)
            NamedKey.RIGHT -> cursorKey('C', m, modes)
            NamedKey.LEFT -> cursorKey('D', m, modes)
            NamedKey.HOME -> cursorKey('H', m, modes)
            NamedKey.END -> cursorKey('F', m, modes)
            NamedKey.INSERT -> tilde(2, m)
            NamedKey.DELETE -> tilde(3, m)
            NamedKey.PAGE_UP -> tilde(5, m)
            NamedKey.PAGE_DOWN -> tilde(6, m)
            NamedKey.F1 -> ss3Fn('P', m)
            NamedKey.F2 -> ss3Fn('Q', m)
            NamedKey.F3 -> ss3Fn('R', m)
            NamedKey.F4 -> ss3Fn('S', m)
            NamedKey.F5 -> tilde(15, m)
            NamedKey.F6 -> tilde(17, m)
            NamedKey.F7 -> tilde(18, m)
            NamedKey.F8 -> tilde(19, m)
            NamedKey.F9 -> tilde(20, m)
            NamedKey.F10 -> tilde(21, m)
            NamedKey.F11 -> tilde(23, m)
            NamedKey.F12 -> tilde(24, m)
            NamedKey.ENTER -> altPrefix + "\r"
            NamedKey.TAB -> altPrefix + (if (m and Mod.SHIFT != 0) "$ESC[Z" else "\t")
            NamedKey.BACKSPACE -> altPrefix + (if (m and Mod.CTRL != 0) "\b" else "\u007F")
            NamedKey.ESCAPE -> altPrefix + ESC
            else -> keypad(key, m, modes)
        }
    }

    private fun cursorKey(letter: Char, m: Int, modes: TerminalModes): String = when {
        m != 0 -> "$ESC[1;${param(m)}$letter"
        modes.applicationCursorKeys -> "${ESC}O$letter"
        else -> "$ESC[$letter"
    }

    private fun tilde(n: Int, m: Int) = if (m == 0) "$ESC[$n~" else "$ESC[$n;${param(m)}~"

    private fun ss3Fn(letter: Char, m: Int) = if (m == 0) "${ESC}O$letter" else "$ESC[1;${param(m)}$letter"

    private fun keypad(key: NamedKey, m: Int, modes: TerminalModes): String {
        val (plain, appLetter) = when (key) {
            NamedKey.KP_0 -> "0" to 'p'
            NamedKey.KP_1 -> "1" to 'q'
            NamedKey.KP_2 -> "2" to 'r'
            NamedKey.KP_3 -> "3" to 's'
            NamedKey.KP_4 -> "4" to 't'
            NamedKey.KP_5 -> "5" to 'u'
            NamedKey.KP_6 -> "6" to 'v'
            NamedKey.KP_7 -> "7" to 'w'
            NamedKey.KP_8 -> "8" to 'x'
            NamedKey.KP_9 -> "9" to 'y'
            NamedKey.KP_ENTER -> "\r" to 'M'
            NamedKey.KP_PLUS -> "+" to 'k'
            NamedKey.KP_MINUS -> "-" to 'm'
            NamedKey.KP_MULTIPLY -> "*" to 'j'
            NamedKey.KP_DIVIDE -> "/" to 'o'
            NamedKey.KP_DECIMAL -> "." to 'n'
            NamedKey.KP_COMMA -> "," to 'l'
            else -> error("not a keypad key: $key")
        }
        if (modes.applicationKeypad && m == 0) return "${ESC}O$appLetter"
        return (if (m and Mod.ALT != 0) ESC else "") + plain
    }

    private fun text(t: String, mods: Int): String? {
        if (t.isEmpty()) return null
        val m = mods and 15
        var out = t
        if (m and Mod.CTRL != 0 && t.codePointCount(0, t.length) == 1) {
            ctrlCode(t.codePointAt(0))?.let { out = it.toChar().toString() }
        }
        return if (m and Mod.ALT != 0) ESC + out else out
    }

    /** The control code Ctrl+[cp] produces on a US-style layout, or null if it has none. */
    private fun ctrlCode(cp: Int): Int? = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        '@'.code, ' '.code, '2'.code -> 0x00
        '['.code, '3'.code -> 0x1B
        '\\'.code, '4'.code -> 0x1C
        ']'.code, '5'.code -> 0x1D
        '^'.code, '6'.code, '~'.code -> 0x1E
        '_'.code, '7'.code, '/'.code -> 0x1F
        '?'.code, '8'.code -> 0x7F
        else -> null
    }
}
