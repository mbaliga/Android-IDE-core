package dev.fonebrew.domain.gitcore.textmerge

/**
 * Text is carried through the merge as "raw" strings: every byte of the file becomes the char with
 * the same value (ISO-8859-1). That makes the merge byte-exact for ANY encoding (UTF-8, Latin-1,
 * a stray invalid byte) without the engine ever guessing a charset, and a raw line compares equal
 * exactly when its bytes do. UI code converts at the edge with [toUtf8Text] / [fromUtf8Text].
 */
object RawText {
    fun fromBytes(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)
    fun toBytes(raw: String): ByteArray = raw.toByteArray(Charsets.ISO_8859_1)

    /** Decodes raw (byte-per-char) text as UTF-8 for display; invalid sequences become U+FFFD. */
    fun toUtf8Text(raw: String): String = String(toBytes(raw), Charsets.UTF_8)

    /** Encodes ordinary text as UTF-8 bytes carried as raw. */
    fun fromUtf8Text(text: String): String = fromBytes(text.toByteArray(Charsets.UTF_8))

    /**
     * Splits into lines, each KEEPING its terminator. Only `\n` terminates a line, so a CRLF file
     * keeps `\r` inside the line (a CRLF line and an LF line with the same words are different
     * lines, as in git). A final line without a terminator is a line of its own. "" has no lines.
     */
    fun splitLines(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < raw.length) {
            val nl = raw.indexOf('\n', start)
            if (nl < 0) { out.add(raw.substring(start)); break }
            out.add(raw.substring(start, nl + 1))
            start = nl + 1
        }
        return out
    }

    /** True when the line ends with CRLF. */
    fun isCrlf(line: String) = line.endsWith("\r\n")

    /** git's own heuristic: a NUL byte in the first 8000 bytes means binary. */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }
}
