package dev.fonebrew.domain.terminal

import dev.fonebrew.domain.remote.term.TerminalModes

/**
 * Prepares pasted text for the pty. Pure.
 *
 * **Paste is data, never control.** Before anything else, every character that could act as a
 * control is removed: ESC (so the bracketed-paste end marker `ESC[201~` - or any escape sequence
 * - cannot be smuggled in to break out of the bracket and run the remainder as typed input), the
 * rest of C0 except TAB / CR / LF (so a paste cannot carry Ctrl-C, Ctrl-D, ...), DEL, and the C1
 * range (including 8-bit CSI). Newlines (`CRLF`, `LF`, `CR`) are normalised to CR, which is what
 * Enter sends. Stripping happens in one pass over the input and ESC is gone entirely, so removal
 * cannot reassemble a marker.
 *
 * With [TerminalModes.bracketedPaste] on, the result is wrapped in `ESC[200~ ... ESC[201~`.
 */
object PasteEncoder {
    private const val START = "\u001B[200~"
    private const val END = "\u001B[201~"

    fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' -> { sb.append('\r'); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                c == '\n' -> sb.append('\r')
                c == '\t' -> sb.append('\t')
                c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun encode(text: String, modes: TerminalModes): ByteArray {
        val body = sanitize(text)
        val out = if (modes.bracketedPaste) START + body + END else body
        return out.toByteArray(Charsets.UTF_8)
    }
}
