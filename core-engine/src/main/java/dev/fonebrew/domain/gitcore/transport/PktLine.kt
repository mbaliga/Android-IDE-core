package dev.fonebrew.domain.gitcore.transport

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One pkt-line frame. `0000` flush, `0001` delimiter (protocol v2), `0002` response-end (v2). */
sealed class Pkt {
    class Data(val bytes: ByteArray) : Pkt() {
        /** The payload as text with one trailing LF removed (pkt-lines conventionally end in LF). */
        fun text(): String = String(bytes, Charsets.UTF_8).removeSuffix("\n")
        override fun toString() = "Data(${bytes.size} bytes)"
    }
    object Flush : Pkt() { override fun toString() = "Flush" }
    object Delim : Pkt() { override fun toString() = "Delim" }
    object ResponseEnd : Pkt() { override fun toString() = "ResponseEnd" }
}

object PktLimits {
    /** A whole frame, header included, may not exceed 65520 bytes (so 65516 of payload). */
    const val MAX_FRAME = 65520
    const val MAX_PAYLOAD = MAX_FRAME - 4
}

/** Reads pkt-lines strictly: a bad length, a short read or an oversize frame is a typed PROTOCOL error. */
class PktLineReader(private val input: InputStream) {
    private val head = ByteArray(4)

    /** The next frame, or null on a clean end of stream before the first byte of a frame. */
    fun read(): Pkt? {
        val n = readFully(head, 4, allowEmpty = true)
        if (n == 0) return null
        val len = hex4(head)
        return when (len) {
            0 -> Pkt.Flush
            1 -> Pkt.Delim
            2 -> Pkt.ResponseEnd
            3 -> protocol("invalid pkt-line length 0003")
            else -> {
                if (len > PktLimits.MAX_FRAME) protocol("pkt-line of $len bytes exceeds the 65520-byte limit")
                val body = ByteArray(len - 4)
                readFully(body, body.size, allowEmpty = false)
                Pkt.Data(body)
            }
        }
    }

    /** Like [read] but end of stream is a protocol error ("connection ended early"). */
    fun require(): Pkt = read() ?: protocol("the server closed the connection before finishing its answer")

    /** Reads up to and excluding the next flush, returning data frames (delim is an error here). */
    fun readUntilFlush(max: Int = 1_000_000): List<Pkt.Data> {
        val out = ArrayList<Pkt.Data>()
        while (true) {
            when (val p = require()) {
                is Pkt.Data -> { if (out.size >= max) protocol("too many pkt-lines in one section"); out.add(p) }
                Pkt.Flush -> return out
                else -> protocol("unexpected $p inside a flush-terminated section")
            }
        }
    }

    private fun readFully(buf: ByteArray, n: Int, allowEmpty: Boolean): Int {
        var off = 0
        try {
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) {
                    if (off == 0 && allowEmpty) return 0
                    protocol("the server closed the connection in the middle of a packet")
                }
                off += r
            }
        } catch (e: EOFException) {
            protocol("the server closed the connection in the middle of a packet")
        } catch (e: TransportException) {
            throw e
        } catch (e: IOException) {
            throw TransportException(TransportErrorKind.NETWORK, "connection failed while reading: ${e.javaClass.simpleName}", cause = e)
        }
        return off
    }

    private fun hex4(b: ByteArray): Int {
        var v = 0
        for (i in 0 until 4) {
            val c = b[i].toInt()
            val d = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> protocol("invalid pkt-line length header")
            }
            v = (v shl 4) or d
        }
        return v
    }
}

/** Writes pkt-lines. Payloads over [PktLimits.MAX_PAYLOAD] are a programming error, not truncated. */
class PktLineWriter(private val out: OutputStream) {
    fun data(bytes: ByteArray): PktLineWriter {
        require(bytes.size <= PktLimits.MAX_PAYLOAD) { "pkt-line payload too large" }
        val len = bytes.size + 4
        val h = ByteArray(4)
        for (i in 0 until 4) h[i] = "0123456789abcdef"[(len ushr (12 - 4 * i)) and 15].code.toByte()
        out.write(h); out.write(bytes)
        return this
    }

    /** A text line; the trailing LF git expects is appended. */
    fun text(s: String): PktLineWriter = data((s + "\n").toByteArray(Charsets.UTF_8))
    fun flush(): PktLineWriter { out.write("0000".toByteArray()); return this }
    fun delim(): PktLineWriter { out.write("0001".toByteArray()); return this }

    companion object {
        /** Renders a block of pkt-lines to bytes. */
        fun build(block: PktLineWriter.() -> Unit): ByteArray {
            val b = ByteArrayOutputStream()
            PktLineWriter(b).block()
            return b.toByteArray()
        }
    }
}
