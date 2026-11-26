package dev.fonebrew.domain.gitcore

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal object Zlib {
    fun deflate(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(maxOf(32, data.size / 2))
            val buf = ByteArray(8192)
            while (!d.finished()) { out.write(buf, 0, d.deflate(buf)) }
            return out.toByteArray()
        } finally { d.end() }
    }

    class Inflated(val data: ByteArray, val consumed: Int)

    /**
     * Inflates one zlib stream from [src] at [offset], producing at most [maxOut] bytes and (when
     * [expected] >= 0) requiring exactly that many. Allocation is bounded by [maxOut] even if the
     * stream claims to be a zip bomb. Returns the bytes and how many input bytes were consumed.
     */
    fun inflate(src: ByteArray, offset: Int, length: Int = src.size - offset, maxOut: Long, expected: Long = -1): Inflated {
        if (offset < 0 || length < 0 || offset + length > src.size) malformed("compressed data out of range")
        if (expected > maxOut) limit("object of $expected bytes exceeds in-memory cap $maxOut")
        val inf = Inflater()
        try {
            inf.setInput(src, offset, length)
            val cap = if (expected >= 0) expected else maxOut
            val out = ByteArrayOutputStream(if (expected in 0..(1 shl 20)) expected.toInt() else 4096)
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (!inf.finished()) {
                val n = try { inf.inflate(buf) } catch (e: DataFormatException) {
                    throw GitException(GitErrorKind.MALFORMED, "corrupt zlib stream", cause = e)
                }
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) malformed("truncated zlib stream")
                    continue
                }
                total += n
                if (total > cap) {
                    if (expected >= 0) malformed("inflated data longer than declared size $expected")
                    limit("inflated data exceeds cap $maxOut")
                }
                out.write(buf, 0, n)
            }
            if (expected >= 0 && total != expected) malformed("inflated $total bytes, declared $expected")
            return Inflated(out.toByteArray(), inf.bytesRead.toInt())
        } finally { inf.end() }
    }
}
