package dev.fonebrew.domain.gitcore.transport

import java.io.InputStream

/**
 * Demultiplexes a side-band / side-band-64k stream: band 1 is payload (a pack, or the nested
 * pkt-lines of a report-status), band 2 is human progress text delivered to [onProgress], band 3
 * is a fatal remote error and becomes a REMOTE_ERROR [TransportException]. A flush ends the
 * stream (reads then return -1). Nothing is buffered beyond one frame.
 */
class SideBandInputStream(
    private val reader: PktLineReader,
    private val onProgress: (String) -> Unit = {},
    private val cancel: CancelToken = CancelToken.NONE,
    /** A frame the caller already consumed while looking for the start of the stream (an ACK/NAK scan). */
    initial: Pkt.Data? = null,
) : InputStream() {
    private var queued: Pkt? = initial
    private var cur: ByteArray = ByteArray(0)
    private var pos = 0
    private var done = false
    private val pending = StringBuilder()

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= cur.size) {
            if (done) return -1
            cancel.check()
            if (!nextFrame()) return -1
        }
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n)
        pos += n
        return n
    }

    private fun nextFrame(): Boolean {
        val next = queued ?: reader.require()
        queued = null
        when (val p = next) {
            Pkt.Flush, Pkt.ResponseEnd -> { flushProgress(); done = true; return false }
            Pkt.Delim -> protocol("unexpected delimiter inside a side-band stream")
            is Pkt.Data -> {
                val bytes = p.bytes
                if (bytes.isEmpty()) return true
                if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "ERR ") {
                    throw TransportException(TransportErrorKind.REMOTE_ERROR, "the server reported: ${clean(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))}")
                }
                when (bytes[0].toInt()) {
                    1 -> { cur = bytes.copyOfRange(1, bytes.size); pos = 0 }
                    2 -> progress(String(bytes, 1, bytes.size - 1, Charsets.UTF_8))
                    3 -> throw TransportException(TransportErrorKind.REMOTE_ERROR, "the server reported: ${clean(String(bytes, 1, bytes.size - 1, Charsets.UTF_8))}")
                    else -> protocol("unknown side-band channel ${bytes[0].toInt()}")
                }
                return true
            }
        }
    }

    private fun progress(text: String) {
        pending.append(text)
        if (pending.length > 64 * 1024) pending.setLength(64 * 1024)
        var start = 0
        for (i in pending.indices) {
            val c = pending[i]
            if (c == '\n' || c == '\r') {
                val line = pending.substring(start, i)
                if (line.isNotBlank()) onProgress(line.trim())
                start = i + 1
            }
        }
        pending.delete(0, start)
    }

    private fun flushProgress() {
        if (pending.isNotBlank()) onProgress(pending.toString().trim())
        pending.setLength(0)
    }

    companion object {
        /** Server text is untrusted: keep it one printable line of bounded length. */
        fun clean(s: String): String = s.trim().replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "?").lines().first().take(500)
    }
}
