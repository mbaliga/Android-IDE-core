package dev.fonebrew.domain.editor.buffer

/** The vocabulary of `document-buffer.schema.json`'s `lineEndings`. MIXED is a fact to show, never to hide. */
enum class LineEnding(val sequence: String?) { LF("\n"), CRLF("\r\n"), CR("\r"), MIXED(null) }

/**
 * Terminator census of a text. [kind] is what the file *is* (MIXED if more than one style occurs);
 * [newline] is what a freshly typed newline should be (the dominant style; LF for a file with no
 * breaks). Because the buffer keeps raw characters, saving never "restores" these: they only steer
 * new input, so a no-op save round trip cannot change a byte.
 */
data class LineEndingProfile(val lf: Int, val crlf: Int, val cr: Int, val endsWithNewline: Boolean) {
    val kind: LineEnding
        get() = when {
            (if (lf > 0) 1 else 0) + (if (crlf > 0) 1 else 0) + (if (cr > 0) 1 else 0) > 1 -> LineEnding.MIXED
            crlf > 0 -> LineEnding.CRLF
            cr > 0 -> LineEnding.CR
            else -> LineEnding.LF
        }

    /** Dominant style; ties prefer CRLF, then LF, then CR (an explicit, stated order). */
    val newline: String
        get() = when {
            crlf >= lf && crlf >= cr && crlf > 0 -> "\r\n"
            lf >= cr && lf > 0 -> "\n"
            cr > 0 -> "\r"
            else -> "\n"
        }
}

object LineEndings {
    fun profile(text: CharSequence): LineEndingProfile {
        var lf = 0; var crlf = 0; var cr = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n') lf++
            else if (c == '\r') { if (i + 1 < n && text[i + 1] == '\n') { crlf++; i++ } else cr++ }
            i++
        }
        val ends = n > 0 && (text[n - 1] == '\n' || text[n - 1] == '\r')
        return LineEndingProfile(lf, crlf, cr, ends)
    }

    /** Rewrites every terminator in [s] to [newline] (used for typed/pasted text, never for the buffer). */
    fun normalize(s: String, newline: String): String {
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\r') { if (i + 1 < s.length && s[i + 1] == '\n') i++; sb.append(newline) }
            else if (c == '\n') sb.append(newline)
            else sb.append(c)
            i++
        }
        return sb.toString()
    }
}
