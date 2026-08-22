package dev.fonebrew.domain.remote.term

/**
 * A VT100/xterm parser that folds a terminal stream into a [ScreenBuffer]. A small state
 * machine — GROUND → ESC → CSI / OSC / STRING / CHARSET — handling what a real shell session
 * actually emits: cursor movement (relative, absolute, column-absolute), erase, insert/delete,
 * scroll regions, SGR colour (including xterm-256 and 24-bit truecolour), the alternate screen,
 * and the string sequences (OSC/DCS/APC) a prompt or a curses app wraps its titles in.
 *
 * **Unknown sequences are swallowed rather than printed as garbage** — a real terminal does the
 * same, so the screen stays the remote's legible voice. That promise was previously only
 * half-kept: `ESC ]` (OSC) fell straight through to GROUND, so every title-setting prompt, vim
 * or tmux dumped its payload onto the screen as literal text. Anything we deliberately do not
 * model (mouse reporting, bracketed paste, device-attribute *queries* we have no channel to
 * answer) is swallowed here on purpose, not forgotten.
 *
 * Feed decoded text; the transport handles byte→UTF-8 decoding (as the JNI bridge already does
 * elsewhere). Pure; JVM-tested.
 */
class VtParser(private val screen: ScreenBuffer) {

    private enum class State { GROUND, ESC, CSI, CHARSET, ESC_HASH, OSC, OSC_ESC, STRING, STRING_ESC }

    private var state = State.GROUND
    private val params = StringBuilder()
    private val intermediates = StringBuilder()

    /** Feed a chunk of decoded terminal output. */
    fun feed(text: String) { for (ch in text) feed(ch) }

    private fun feed(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESC -> esc(ch)
            State.CSI -> csi(ch)
            State.CHARSET -> state = State.GROUND   // swallow the charset designator byte
            State.ESC_HASH -> state = State.GROUND  // swallow the DEC line-size selector
            State.OSC -> osc(ch)
            State.STRING -> string(ch)
            State.OSC_ESC, State.STRING_ESC -> stringEsc(ch)
        }
    }

    private fun ground(ch: Char) {
        when (ch.code) {
            0x1B -> state = State.ESC          // ESC
            0x0D -> screen.carriageReturn()    // CR
            0x0A, 0x0B, 0x0C -> screen.lineFeed() // LF / VT / FF all feed a line
            0x08 -> screen.backspace()         // BS
            0x09 -> screen.tab()               // HT
            0x07 -> {}                         // BEL — no visual effect in this model
            // 0x7F is DEL, not a glyph: printing it puts a tofu box on the screen.
            0x7F -> {}
            else -> if (ch >= ' ') screen.put(ch)
        }
    }

    private fun esc(ch: Char) {
        state = State.GROUND
        when (ch) {
            '[' -> { params.setLength(0); intermediates.setLength(0); state = State.CSI }
            // OSC — an operating-system command (window/icon title, colour queries, hyperlinks).
            // Payload is swallowed to its BEL or ST terminator; see the class doc.
            ']' -> state = State.OSC
            // DCS / SOS / PM / APC: other string sequences, likewise swallowed through ST.
            'P', 'X', '^', '_' -> state = State.STRING
            // Charset designation (and ESC % for UTF-8 selection): swallow the next byte.
            '(', ')', '*', '+', '-', '.', '/', '%' -> state = State.CHARSET
            '#' -> state = State.ESC_HASH      // DECDHL/DECDWL/DECALN — swallow the selector
            '7' -> screen.saveCursor()         // DECSC
            '8' -> screen.restoreCursor()      // DECRC
            'D' -> screen.lineFeed()           // IND
            'E' -> { screen.carriageReturn(); screen.lineFeed() } // NEL
            'M' -> screen.reverseIndex()       // RI
            'c' -> screen.resetAll()           // RIS
            else -> {}                         // ESC =, ESC >, and the rest — swallow the intro
        }
    }

    private fun csi(ch: Char) {
        // A C0 control inside a CSI is executed in place and the sequence continues — which is
        // also how ESC cancels it, since ground() moves us back to State.ESC.
        if (ch.code < 0x20) { ground(ch); return }
        when (ch.code) {
            // Parameter bytes 0–9 : ; < = > ? — capped so a malformed stream cannot grow this
            // without bound.
            in 0x30..0x3F -> { if (params.length < MAX_PARAM_CHARS) params.append(ch); return }
            // Intermediate bytes (space ! " # $ % & ' ( ) * + , - . /) — collected, then ignored;
            // they only ever select a variant of a sequence we already swallow (e.g. CSI SP q).
            in 0x20..0x2F -> { if (intermediates.length < MAX_PARAM_CHARS) intermediates.append(ch); return }
        }
        if (ch.code in 0x40..0x7E) dispatch(ch)
        state = State.GROUND
    }

    /** OSC payload: terminated by BEL, or by ST (`ESC \`). Nothing is ever printed. */
    private fun osc(ch: Char) {
        when (ch.code) {
            0x07 -> state = State.GROUND
            0x1B -> state = State.OSC_ESC
            else -> {}
        }
    }

    /** DCS/SOS/PM/APC payload: same shape as [osc]. */
    private fun string(ch: Char) {
        when (ch.code) {
            0x07 -> state = State.GROUND
            0x1B -> state = State.STRING_ESC
            else -> {}
        }
    }

    /** Saw ESC inside a string sequence: `\` completes ST; anything else starts a fresh escape. */
    private fun stringEsc(ch: Char) {
        if (ch == '\\') {
            state = State.GROUND
        } else {
            // A bare ESC in the payload that isn't ST — read it as the start of a real escape
            // rather than losing the rest of the stream inside the string state.
            state = State.ESC
            esc(ch)
        }
    }

    /** The DEC/xterm private-prefix byte of the sequence being dispatched, if any. */
    private fun privatePrefix(): Char? = params.firstOrNull()?.takeIf { it in "<=>?" }

    /** Numeric parameters, sub-parameters dropped (SGR reads them itself via [sgrTokens]). */
    private fun parseParams(): List<Int> =
        params.toString().let { if (privatePrefix() != null) it.substring(1) else it }
            .split(';')
            .map { it.substringBefore(':').toIntOrNull() ?: 0 }

    private fun dispatch(final: Char) {
        val priv = privatePrefix()
        val args = parseParams()
        if (priv != null) {
            // The only private sequences we act on are the DEC modes. Everything else in this
            // space (cursor-style, xterm window ops, modifyOtherKeys, device queries we have no
            // way to answer) is swallowed rather than printed.
            when (final) {
                'h' -> if (priv == '?') decModes(args, true)
                'l' -> if (priv == '?') decModes(args, false)
                else -> {}
            }
            return
        }
        fun arg(i: Int, default: Int) = args.getOrNull(i)?.takeIf { it != 0 } ?: default
        val n = arg(0, 1)
        when (final) {
            'A' -> screen.moveCursor(-n, 0)                              // cursor up
            'B', 'e' -> screen.moveCursor(n, 0)                          // cursor down / VPR
            'C', 'a' -> screen.moveCursor(0, n)                          // cursor forward / HPR
            'D' -> screen.moveCursor(0, -n)                              // cursor back
            'E' -> screen.setCursor(screen.cursor.row + n, 0)            // CNL
            'F' -> screen.setCursor(screen.cursor.row - n, 0)            // CPL
            'G', '`' -> screen.setCursor(screen.cursor.row, n - 1)       // CHA / HPA absolute column
            'd' -> screen.setCursor(n - 1, screen.cursor.col)            // VPA absolute row
            'H', 'f' -> screen.setCursor(arg(0, 1) - 1, arg(1, 1) - 1)   // position (1-based)
            'J' -> when (args.firstOrNull() ?: 0) {                      // erase display
                0 -> screen.eraseToScreenEnd()
                1 -> screen.eraseToScreenStart()
                2, 3 -> screen.clear()
            }
            'K' -> when (args.firstOrNull() ?: 0) {                      // erase line
                0 -> screen.eraseToLineEnd()
                1 -> screen.eraseToLineStart()
                2 -> screen.eraseLine()
            }
            'L' -> screen.insertLines(n)                                 // IL
            'M' -> screen.deleteLines(n)                                 // DL
            'P' -> screen.deleteChars(n)                                 // DCH
            '@' -> screen.insertChars(n)                                 // ICH
            'X' -> screen.eraseChars(n)                                  // ECH
            'S' -> screen.scrollUp(n)                                    // SU
            'T' -> screen.scrollDown(n)                                  // SD
            'r' -> screen.setScrollRegion(arg(0, 1) - 1, arg(1, screen.rows) - 1) // DECSTBM
            's' -> screen.saveCursor()
            'u' -> screen.restoreCursor()
            'm' -> screen.pen = applySgr(screen.pen, sgrTokens())
            // 'c' (device attributes), 'n' (device status) and 't' (window ops) are *queries*.
            // A real terminal replies on the input channel; we have nothing truthful to say, so
            // we stay silent — but we must still not print the request.
            else -> {}
        }
    }

    /**
     * DEC private modes (`CSI ? … h/l`). Only the ones that change what is on screen are acted
     * on; the rest (autowrap, mouse reporting, bracketed paste, focus events) are deliberately
     * swallowed — modelling them would change nothing a reader can see.
     */
    private fun decModes(args: List<Int>, on: Boolean) {
        for (mode in args) when (mode) {
            25 -> screen.cursorVisible = on                              // DECTCEM
            // The alternate screen. 1049 additionally saves/restores the cursor around the
            // switch; 47/1047 are the older pair that don't.
            1049 -> if (on) { screen.saveCursor(); screen.enterAltScreen() } else { screen.exitAltScreen(); screen.restoreCursor() }
            47, 1047 -> if (on) screen.enterAltScreen() else screen.exitAltScreen()
            1048 -> if (on) screen.saveCursor() else screen.restoreCursor()
            else -> {}
        }
    }

    /**
     * SGR parameters, each already split on its ISO 8613-6 `:` sub-parameters, so
     * `38:2:30:144:255` and `38;2;30;144;255` can both be read as one colour instead of five
     * unrelated codes.
     */
    private fun sgrTokens(): List<List<Int>> =
        params.toString()
            .split(';')
            .map { tok -> tok.split(':').map { it.toIntOrNull() ?: 0 } }

    /**
     * Fold SGR codes into the current pen.
     *
     * The 38/48 extended-colour introducers must be consumed **as a unit**. The previous
     * implementation split on ';' and folded every number independently, so
     * `ESC[38;2;30;144;255m` ran `38` (ignored), `2` (nothing), then `30` — plain "foreground
     * black" — and 144/255 fell through. A truecolour prompt therefore painted itself the
     * darkest colour in the palette against a near-black terminal panel. That is wrong output,
     * not graceful degradation.
     */
    private fun applySgr(start: Sgr, tokens: List<List<Int>>): Sgr {
        var s = start
        var i = 0
        while (i < tokens.size) {
            val code = tokens[i].firstOrNull() ?: 0
            if (code == 38 || code == 48) {
                val (colour, consumed) = readExtendedColor(tokens, i)
                if (colour != null) s = if (code == 38) s.copy(fg = colour) else s.copy(bg = colour)
                i += consumed
                continue
            }
            s = when (code) {
                0 -> Sgr.DEFAULT
                1 -> s.copy(bold = true)
                4 -> s.copy(underline = true)
                7 -> s.copy(inverse = true)
                21, 22 -> s.copy(bold = false)
                24 -> s.copy(underline = false)
                27 -> s.copy(inverse = false)
                in 30..37 -> s.copy(fg = TermColor.Indexed(code - 30))
                39 -> s.copy(fg = null)
                in 40..47 -> s.copy(bg = TermColor.Indexed(code - 40))
                49 -> s.copy(bg = null)
                // The bright half (aixterm). `ls --color` and every modern prompt use these;
                // folding them to nothing lost half the palette.
                in 90..97 -> s.copy(fg = TermColor.Indexed(code - 90 + 8))
                in 100..107 -> s.copy(bg = TermColor.Indexed(code - 100 + 8))
                // Italic, blink, conceal, strike, framing, fonts — not modelled by [Sgr]; left
                // alone rather than mangled into an attribute they are not.
                else -> s
            }
            i++
        }
        return s
    }

    /**
     * Read the colour introduced by a 38/48 token at [i]. Returns the colour (null if the shape
     * is one we don't recognise) and how many tokens it consumed, so the caller resumes at the
     * next real SGR code rather than mid-colour.
     */
    private fun readExtendedColor(tokens: List<List<Int>>, i: Int): Pair<TermColor?, Int> {
        val tok = tokens[i]
        if (tok.size > 1) {
            // Colon form — self-contained in this one token: 38:5:n or 38:2:r:g:b (and the
            // strict 38:2:<colour-space>:r:g:b, hence "the last three").
            return when (tok[1]) {
                5 -> TermColor.Indexed(tok.getOrElse(2) { 0 }) to 1
                2 -> if (tok.size >= 5) TermColor.Rgb(rgb(tok[tok.size - 3], tok[tok.size - 2], tok[tok.size - 1])) to 1 else null to 1
                else -> null to 1
            }
        }
        // Semicolon form — the mode and its arguments are the following tokens.
        return when (tokens.getOrNull(i + 1)?.firstOrNull()) {
            5 -> TermColor.Indexed(tokens.getOrNull(i + 2)?.firstOrNull() ?: 0) to 3
            2 -> TermColor.Rgb(
                rgb(
                    tokens.getOrNull(i + 2)?.firstOrNull() ?: 0,
                    tokens.getOrNull(i + 3)?.firstOrNull() ?: 0,
                    tokens.getOrNull(i + 4)?.firstOrNull() ?: 0,
                ),
            ) to 5
            // Unrecognised mode: swallow it too, so its argument bytes can't be read as codes.
            else -> null to 2
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private companion object {
        /** Guard against an unbounded parameter run in a malformed/hostile stream. */
        const val MAX_PARAM_CHARS = 128
    }
}
