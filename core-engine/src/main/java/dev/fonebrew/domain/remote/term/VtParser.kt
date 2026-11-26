package dev.fonebrew.domain.remote.term

/**
 * A VT100/xterm parser that folds a terminal stream into a [ScreenBuffer]. A small state
 * machine - GROUND -> ESC -> CSI / OSC / STRING / CHARSET - handling what a real shell session
 * actually emits: cursor movement (relative, absolute, column-absolute), erase, insert/delete,
 * scroll regions, SGR colour (including xterm-256 and 24-bit truecolour), the alternate screen,
 * and the string sequences (OSC/DCS/APC) a prompt or a curses app wraps its titles in.
 *
 * **Unknown sequences are swallowed rather than printed as garbage** - a real terminal does the
 * same, so the screen stays the remote's legible voice.
 *
 * Beyond drawing, this class is where the stream's *requests* are honoured, narrowly:
 *  - input modes land in [modes] (DECCKM, keypad, bracketed paste, focus, mouse + SGR encoding);
 *  - a fixed set of **queries** is answered through [replies]: DA1 (`CSI c`), DA2 (`CSI > c`),
 *    DSR 5n / 6n / ?6n and window size `CSI 18 t`. Replies have fixed shapes (no attacker text is
 *    ever echoed), exist only when [replies] is wired, and are capped per [feed] call;
 *  - OSC 0/2 (title) and OSC 7 (cwd) go to [meta] and [events]; OSC 52 *write* is surfaced as a
 *    [ClipboardWriteRequest] event and never applied here; OSC 52 *read* (`?`) is ignored;
 *  - BEL fires [TerminalEvents.onBell].
 * Anything else we do not model (pixel-size reports, DECRQM, mouse in UTF-8/urxvt encodings,
 * kitty keyboard queries) is swallowed silently - unknown stays unknown, no invented answer.
 *
 * Feed decoded text (the session decodes bytes with [dev.fonebrew.domain.terminal.Utf8StreamDecoder]).
 * Pure; JVM-tested.
 */
class VtParser(
    private val screen: ScreenBuffer,
    private val modes: TerminalModes = TerminalModes(),
    private val meta: TerminalMetadata = TerminalMetadata(),
    private val events: TerminalEvents = TerminalEvents.NONE,
    private val replies: TerminalReplies = TerminalReplies.NONE,
) {

    private enum class State { GROUND, ESC, CSI, CHARSET, ESC_HASH, OSC, OSC_ESC, STRING, STRING_ESC }

    private var state = State.GROUND
    private val params = StringBuilder()
    private val intermediates = StringBuilder()
    private val osc = StringBuilder()
    private var oscOverflow = false
    private var pendingHigh: Char? = null
    private var repliesThisFeed = 0

    /** Feed a chunk of decoded terminal output. */
    fun feed(text: String) {
        repliesThisFeed = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val hi = pendingHigh
            when {
                hi != null -> {
                    pendingHigh = null
                    if (Character.isLowSurrogate(ch)) { feedCp(Character.toCodePoint(hi, ch)); i++ } else feedCp(0xFFFD)
                    // a non-low-surrogate falls through to be processed on the next loop turn
                }
                Character.isHighSurrogate(ch) -> { pendingHigh = ch; i++ }
                Character.isLowSurrogate(ch) -> { feedCp(0xFFFD); i++ }
                else -> { feedCp(ch.code); i++ }
            }
        }
    }

    private fun feedCp(cp: Int) {
        when (state) {
            State.GROUND -> ground(cp)
            State.ESC -> esc(cp)
            State.CSI -> csi(cp)
            State.CHARSET -> state = State.GROUND   // swallow the charset designator byte
            State.ESC_HASH -> state = State.GROUND  // swallow the DEC line-size selector
            State.OSC -> oscByte(cp)
            State.STRING -> string(cp)
            State.OSC_ESC -> oscEsc(cp)
            State.STRING_ESC -> stringEsc(cp)
        }
    }

    private fun ground(cp: Int) {
        when (cp) {
            0x1B -> state = State.ESC          // ESC
            0x0D -> screen.carriageReturn()    // CR
            0x0A, 0x0B, 0x0C -> screen.lineFeed() // LF / VT / FF all feed a line
            0x08 -> screen.backspace()         // BS
            0x09 -> screen.tab()               // HT
            0x07 -> events.onBell()            // BEL
            else -> when {
                // C0 controls we do not model, DEL (a tofu box if printed) and the C1 range
                // (never acted on: 8-bit CSI is not honoured, it is dropped) print nothing.
                cp < 0x20 || cp == 0x7F || cp in 0x80..0x9F -> {}
                else -> screen.putCodePoint(cp)
            }
        }
    }

    private fun esc(cp: Int) {
        state = State.GROUND
        when (if (cp < 0x80) cp.toChar() else '\u0000') {
            '[' -> { params.setLength(0); intermediates.setLength(0); state = State.CSI }
            // OSC - an operating-system command (window/icon title, cwd, clipboard, hyperlinks).
            ']' -> { osc.setLength(0); oscOverflow = false; state = State.OSC }
            // DCS / SOS / PM / APC: other string sequences, likewise swallowed through ST.
            'P', 'X', '^', '_' -> state = State.STRING
            // Charset designation (and ESC % for UTF-8 selection): swallow the next byte.
            '(', ')', '*', '+', '-', '.', '/', '%' -> state = State.CHARSET
            '#' -> state = State.ESC_HASH      // DECDHL/DECDWL/DECALN - swallow the selector
            '7' -> screen.saveCursor()         // DECSC
            '8' -> screen.restoreCursor()      // DECRC
            'D' -> screen.lineFeed()           // IND
            'E' -> { screen.carriageReturn(); screen.lineFeed() } // NEL
            'M' -> screen.reverseIndex()       // RI
            'c' -> { screen.resetAll(); modes.reset() } // RIS
            '=' -> modes.applicationKeypad = true   // DECKPAM
            '>' -> modes.applicationKeypad = false  // DECKPNM
            else -> {}                         // the rest - swallow the intro
        }
    }

    private fun csi(cp: Int) {
        // A C0 control inside a CSI is executed in place and the sequence continues - which is
        // also how ESC cancels it, since ground() moves us back to State.ESC.
        if (cp < 0x20) { ground(cp); return }
        when (cp) {
            // Parameter bytes 0-9 : ; < = > ? - capped so a malformed stream cannot grow this
            // without bound.
            in 0x30..0x3F -> { if (params.length < MAX_PARAM_CHARS) params.append(cp.toChar()); return }
            // Intermediate bytes (space ! " # $ % & ' ( ) * + , - . /) - collected; a sequence
            // that has any is a different sequence from the plain one, so it is ignored below.
            in 0x20..0x2F -> { if (intermediates.length < MAX_PARAM_CHARS) intermediates.append(cp.toChar()); return }
        }
        if (cp in 0x40..0x7E) dispatch(cp.toChar())
        state = State.GROUND
    }

    /** OSC payload, collected (bounded) until BEL or ST (`ESC \`). Nothing is ever printed. */
    private fun oscByte(cp: Int) {
        when (cp) {
            0x07 -> finishOsc()
            0x1B -> state = State.OSC_ESC
            0x18, 0x1A -> { state = State.GROUND }   // CAN / SUB cancel a string
            else -> {
                if (osc.length >= MAX_OSC_CHARS) oscOverflow = true
                else osc.appendCodePoint(cp)
            }
        }
    }

    private fun oscEsc(cp: Int) {
        if (cp == '\\'.code) finishOsc()
        else { state = State.ESC; esc(cp) } // not ST: the OSC is abandoned, ESC starts afresh
    }

    private fun finishOsc() {
        state = State.GROUND
        if (!oscOverflow) handleOsc(osc.toString())
        osc.setLength(0)
        oscOverflow = false
    }

    /** DCS/SOS/PM/APC payload: swallowed through BEL / ST. */
    private fun string(cp: Int) {
        when (cp) {
            0x07, 0x18, 0x1A -> state = State.GROUND
            0x1B -> state = State.STRING_ESC
            else -> {}
        }
    }

    /** Saw ESC inside a string sequence: `\` completes ST; anything else starts a fresh escape. */
    private fun stringEsc(cp: Int) {
        if (cp == '\\'.code) {
            state = State.GROUND
        } else {
            state = State.ESC
            esc(cp)
        }
    }

    // --- OSC ------------------------------------------------------------------------------------

    private fun handleOsc(payload: String) {
        val semi = payload.indexOf(';')
        val code = (if (semi < 0) payload else payload.substring(0, semi)).toIntOrNull() ?: return
        val rest = if (semi < 0) "" else payload.substring(semi + 1)
        when (code) {
            0, 2 -> setTitle(rest)
            7 -> setWorkingDirectory(rest)
            52 -> clipboardWrite(rest)
            else -> {} // 1 (icon name), 4/10/11 colour queries, 8 hyperlinks, ...: not modelled
        }
    }

    private fun setTitle(raw: String) {
        // Untrusted text bound for a notification / tab strip: drop controls, cap the length.
        val title = raw.filter { !it.isISOControl() }.take(MAX_TITLE_CHARS)
        meta.title = title
        events.onTitle(title)
    }

    /** OSC 7: `file://host/percent-encoded/path`. Anything that is not that is ignored. */
    private fun setWorkingDirectory(url: String) {
        if (!url.startsWith("file://") || url.length > MAX_CWD_CHARS) return
        val afterScheme = url.substring("file://".length)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return
        val host = afterScheme.substring(0, slash)
        val path = percentDecode(afterScheme.substring(slash)) ?: return
        if (path.any { it.isISOControl() }) return
        meta.workingDirectoryHost = host
        meta.workingDirectory = path
        events.onWorkingDirectory(path, host)
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun percentDecode(s: String): String? {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                val v = if (i + 2 < s.length && s[i + 1].isHexDigit() && s[i + 2].isHexDigit()) s.substring(i + 1, i + 3).toInt(16) else null
                if (v == null) return null
                out.write(v); i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** OSC 52 `Pc ; Pd`. A `?` (read) is never answered - that would exfiltrate the clipboard. */
    private fun clipboardWrite(rest: String) {
        val semi = rest.indexOf(';')
        if (semi < 0) return
        val selection = rest.substring(0, semi).take(8)
        val data = rest.substring(semi + 1)
        if (data == "?") return
        if (data.length > MAX_CLIPBOARD_B64) return
        val text = try {
            String(java.util.Base64.getDecoder().decode(data), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return
        }
        events.onClipboardWriteRequested(ClipboardWriteRequest(selection.ifEmpty { "s" }, text))
    }

    // --- CSI ------------------------------------------------------------------------------------

    /** The DEC/xterm private-prefix byte of the sequence being dispatched, if any. */
    private fun privatePrefix(): Char? = params.firstOrNull()?.takeIf { it in "<=>?" }

    /** Numeric parameters, sub-parameters dropped (SGR reads them itself via [sgrTokens]). */
    private fun parseParams(): List<Int> =
        params.toString().let { if (privatePrefix() != null) it.substring(1) else it }
            .split(';')
            .map { it.substringBefore(':').toIntOrNull() ?: 0 }

    private fun dispatch(final: Char) {
        // A sequence with intermediate bytes (CSI SP q, CSI ! p, CSI $ p ...) is a different
        // sequence from the bare final byte; reading it as one would e.g. answer a query nobody
        // asked. Ignore them all.
        if (intermediates.isNotEmpty()) return
        val priv = privatePrefix()
        val args = parseParams()
        if (priv != null) {
            when (final) {
                'h' -> if (priv == '?') decModes(args, true)
                'l' -> if (priv == '?') decModes(args, false)
                'c' -> if (priv == '>' && (args.firstOrNull() ?: 0) == 0) reply(DA2) // secondary DA
                'n' -> if (priv == '?' && args.firstOrNull() == 6) { // DECXCPR
                    reply("\u001B[?${screen.cursor.row + 1};${cursorColumn()}R")
                }
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
                2 -> screen.clear()
                3 -> { screen.clear(); screen.clearScrollback() }
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
            // --- queries: answered through [replies], fixed shapes only ---
            'c' -> if ((args.firstOrNull() ?: 0) == 0) reply(DA1)        // primary DA
            'n' -> when (args.firstOrNull()) {                           // DSR
                5 -> reply("\u001B[0n")                                  // "terminal OK"
                6 -> reply("\u001B[${screen.cursor.row + 1};${cursorColumn()}R") // CPR
            }
            't' -> if (args.firstOrNull() == 18) {                       // report text-area size
                reply("\u001B[8;${screen.rows};${screen.cols}t")
            } // 14t/16t (pixel sizes) and the title-stack ops: unknown to the engine, no reply
            else -> {}
        }
    }

    /** 1-based cursor column for a position report, clamped (a pending-wrap cursor reports cols). */
    private fun cursorColumn() = (screen.cursor.col + 1).coerceAtMost(screen.cols)

    private fun reply(sequence: String) {
        if (repliesThisFeed >= MAX_REPLIES_PER_FEED) return
        repliesThisFeed++
        replies.reply(sequence)
    }

    /**
     * DEC private modes (`CSI ? ... h/l`). Beyond the display ones (cursor visibility,
     * autowrap, alternate screen), the *input* modes land in [modes] for the key / paste / mouse
     * encoders.
     */
    private fun decModes(args: List<Int>, on: Boolean) {
        for (mode in args) when (mode) {
            1 -> modes.applicationCursorKeys = on                        // DECCKM
            7 -> screen.autoWrap = on                                    // DECAWM
            25 -> screen.cursorVisible = on                              // DECTCEM
            // The alternate screen. 1049 additionally saves/restores the cursor around the
            // switch; 47/1047 are the older pair that don't.
            1049 -> if (on) { screen.saveCursor(); screen.enterAltScreen() } else { screen.exitAltScreen(); screen.restoreCursor() }
            47, 1047 -> if (on) screen.enterAltScreen() else screen.exitAltScreen()
            1048 -> if (on) screen.saveCursor() else screen.restoreCursor()
            1000 -> setMouse(MouseTracking.CLICK, on)
            1002 -> setMouse(MouseTracking.DRAG, on)
            1003 -> setMouse(MouseTracking.ANY, on)
            1004 -> modes.focusReporting = on
            1006 -> modes.mouseSgr = on
            2004 -> modes.bracketedPaste = on
            else -> {}
        }
    }

    /** Enabling selects that tracking level; disabling clears it only if it is the current one. */
    private fun setMouse(level: MouseTracking, on: Boolean) {
        if (on) modes.mouseTracking = level
        else if (modes.mouseTracking == level) modes.mouseTracking = MouseTracking.NONE
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
        const val MAX_OSC_CHARS = 90_000
        const val MAX_TITLE_CHARS = 1_024
        const val MAX_CWD_CHARS = 4_096
        /** Base64 length cap: ~64 KB decoded. Larger OSC 52 writes are dropped, not truncated. */
        const val MAX_CLIPBOARD_B64 = 87_400
        /** A hostile stream of queries cannot make us flood the pty. */
        const val MAX_REPLIES_PER_FEED = 16
        /** DA1: VT220-class (62) with ANSI colour (22). Claims nothing we do not do. */
        const val DA1 = "\u001B[?62;22c"
        /** DA2: terminal type 0, firmware 0, no ROM cartridge - honest zeros, not an xterm claim. */
        const val DA2 = "\u001B[>0;0;0c"
    }
}
