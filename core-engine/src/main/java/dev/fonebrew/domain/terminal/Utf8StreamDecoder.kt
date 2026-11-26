package dev.fonebrew.domain.terminal

/**
 * A stateful UTF-8 decoder for a byte stream that arrives in arbitrary chunks (a pty read is
 * whatever the kernel had, not a character boundary). `String(bytes)` per chunk corrupts any
 * multibyte character that straddles two reads; this carries the incomplete tail to the next
 * [decode] call instead.
 *
 * Malformed input never desynchronises the stream: each maximal invalid subsequence becomes one
 * U+FFFD and decoding resumes at the next byte that can start a character (the WHATWG /
 * Unicode "maximal subpart" practice). Overlong forms, surrogates (ED A0..BF) and values above
 * U+10FFFF are invalid. Splitting a valid or invalid stream at any point gives the same output
 * as decoding it whole (tested at every split point).
 *
 * Not thread-safe; one decoder per stream.
 */
class Utf8StreamDecoder {
    private var cp = 0
    private var needed = 0           // continuation bytes still expected
    private var lower = 0x80         // allowed range for the NEXT continuation byte
    private var upper = 0xBF

    /** Decode [len] bytes of [bytes] from [off]; a trailing incomplete character is held back. */
    fun decode(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String {
        val sb = StringBuilder(len)
        var i = off
        val end = off + len
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            if (needed == 0) {
                when {
                    b < 0x80 -> sb.append(b.toChar())
                    b in 0xC2..0xDF -> start(b and 0x1F, 1, 0x80, 0xBF)
                    b == 0xE0 -> start(b and 0x0F, 2, 0xA0, 0xBF)
                    b == 0xED -> start(b and 0x0F, 2, 0x80, 0x9F)
                    b in 0xE1..0xEF -> start(b and 0x0F, 2, 0x80, 0xBF)
                    b == 0xF0 -> start(b and 0x07, 3, 0x90, 0xBF)
                    b in 0xF1..0xF3 -> start(b and 0x07, 3, 0x80, 0xBF)
                    b == 0xF4 -> start(b and 0x07, 3, 0x80, 0x8F)
                    else -> sb.append('�') // 0x80..0xC1 lone continuation / overlong lead, 0xF5..0xFF
                }
                i++
            } else if (b in lower..upper) {
                cp = (cp shl 6) or (b and 0x3F)
                needed--
                lower = 0x80; upper = 0xBF
                if (needed == 0) sb.appendCodePoint(cp)
                i++
            } else {
                // The sequence ended early: one replacement for what we had, and this byte is
                // re-examined as a potential start of a new character (so it is not swallowed).
                needed = 0; lower = 0x80; upper = 0xBF
                sb.append('�')
            }
        }
        return sb.toString()
    }

    /** End of stream: an incomplete trailing character becomes U+FFFD. Resets the decoder. */
    fun finish(): String {
        val out = if (needed > 0) "�" else ""
        reset()
        return out
    }

    fun reset() { cp = 0; needed = 0; lower = 0x80; upper = 0xBF }

    private fun start(bits: Int, more: Int, lo: Int, hi: Int) {
        cp = bits; needed = more; lower = lo; upper = hi
    }
}
