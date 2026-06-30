package dev.aarso.domain.remote.term

/**
 * A small VT100/ANSI parser that folds a terminal stream into a [ScreenBuffer]. It is a
 * three-state machine — GROUND → ESC → CSI — handling the control codes and CSI sequences a
 * real shell session actually emits: cursor movement, absolute positioning, erase, and SGR
 * colour/attributes. Unknown sequences are swallowed rather than printed as garbage (a real
 * terminal does the same), so the screen stays the remote's legible voice.
 *
 * Deliberately a subset (no scroll regions, no DEC private modes, no charset switching) — it
 * covers the common case and has clear seams to extend. Feed decoded text; the transport
 * handles byte→UTF-8 decoding (as the JNI bridge already does elsewhere). Pure; JVM-tested.
 */
class VtParser(private val screen: ScreenBuffer) {

    private enum class State { GROUND, ESC, CSI, CHARSET }
    private var state = State.GROUND
    private val params = StringBuilder()

    /** Feed a chunk of decoded terminal output. */
    fun feed(text: String) { for (ch in text) feed(ch) }

    private fun feed(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESC -> esc(ch)
            State.CSI -> csi(ch)
            State.CHARSET -> state = State.GROUND // swallow the charset designator byte
        }
    }

    private fun ground(ch: Char) {
        when (ch.code) {
            0x1B -> state = State.ESC          // ESC
            0x0D -> screen.carriageReturn()    // CR
            0x0A -> screen.lineFeed()          // LF
            0x08 -> screen.backspace()         // BS
            0x09 -> screen.tab()               // HT
            0x07 -> {}                         // BEL — no visual effect in this model
            else -> if (ch >= ' ') screen.put(ch)
        }
    }

    private fun esc(ch: Char) {
        when (ch) {
            '[' -> { params.setLength(0); state = State.CSI }
            '(', ')', '*', '+' -> state = State.CHARSET // charset designation: swallow next byte
            else -> state = State.GROUND // other unsupported escapes — ignore the intro
        }
    }

    private fun csi(ch: Char) {
        // Parameter / intermediate bytes accumulate; a final byte (0x40–0x7E) dispatches.
        if (ch in '0'..'9' || ch == ';' || ch == '?') { params.append(ch); return }
        dispatch(ch, parseParams())
        state = State.GROUND
    }

    private fun parseParams(): List<Int> =
        params.toString().removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }

    private fun dispatch(final: Char, args: List<Int>) {
        fun arg(i: Int, default: Int) = args.getOrNull(i)?.takeIf { it != 0 } ?: default
        when (final) {
            'A' -> screen.moveCursor(-arg(0, 1), 0)                         // cursor up
            'B' -> screen.moveCursor(arg(0, 1), 0)                          // cursor down
            'C' -> screen.moveCursor(0, arg(0, 1))                          // cursor forward
            'D' -> screen.moveCursor(0, -arg(0, 1))                         // cursor back
            'H', 'f' -> screen.setCursor(arg(0, 1) - 1, arg(1, 1) - 1)      // position (1-based)
            'J' -> when (args.firstOrNull() ?: 0) {                         // erase display
                0 -> screen.eraseToScreenEnd()
                2, 3 -> screen.clear()
            }
            'K' -> when (args.firstOrNull() ?: 0) {                         // erase line
                0 -> screen.eraseToLineEnd()
                2 -> screen.eraseLine()
            }
            'm' -> screen.pen = applySgr(screen.pen, if (args.isEmpty()) listOf(0) else args)
            else -> {} // unsupported CSI — swallow
        }
    }

    /** Fold SGR codes into the current pen. Supports reset, bold/underline/inverse, 30–37/40–47. */
    private fun applySgr(start: Sgr, codes: List<Int>): Sgr {
        var s = start
        for (code in codes) s = when (code) {
            0 -> Sgr.DEFAULT
            1 -> s.copy(bold = true)
            4 -> s.copy(underline = true)
            7 -> s.copy(inverse = true)
            22 -> s.copy(bold = false)
            24 -> s.copy(underline = false)
            27 -> s.copy(inverse = false)
            in 30..37 -> s.copy(fg = code - 30)
            39 -> s.copy(fg = null)
            in 40..47 -> s.copy(bg = code - 40)
            49 -> s.copy(bg = null)
            else -> s
        }
        return s
    }
}
