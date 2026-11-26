package dev.fonebrew.domain.gitcore

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** One parsed pack entry header. [baseOffset] is set for OFS_DELTA, [baseId] for REF_DELTA. */
internal class PackEntryHeader(
    val typeCode: Int,
    val size: Long,
    val headerLength: Int,
    val baseOffset: Long,
    val baseId: ObjectId?,
) {
    val isDelta get() = typeCode == OFS_DELTA || typeCode == REF_DELTA

    companion object {
        const val OFS_DELTA = 6
        const val REF_DELTA = 7
        const val MAX_HEADER = 64

        /** Parses a header at the start of [buf] ([len] valid bytes). [entryOffset] is its pack offset. */
        fun parse(buf: ByteArray, len: Int, entryOffset: Long, algorithm: HashAlgorithm): PackEntryHeader {
            var p = 0
            fun next(): Int {
                if (p >= len) malformed("truncated pack entry header")
                return buf[p++].toInt() and 0xff
            }
            var c = next()
            val type = (c shr 4) and 7
            var size = (c and 15).toLong()
            var shift = 4
            while (c and 0x80 != 0) {
                c = next()
                if (shift > 56) malformed("pack entry size varint too long")
                size = size or ((c and 0x7f).toLong() shl shift)
                shift += 7
            }
            if (size < 0) malformed("pack entry size overflow")
            var baseOffset = -1L
            var baseId: ObjectId? = null
            when (type) {
                OFS_DELTA -> {
                    c = next()
                    var off = (c and 0x7f).toLong()
                    var n = 1
                    while (c and 0x80 != 0) {
                        c = next()
                        if (++n > 9) malformed("ofs-delta offset too long")
                        off = ((off + 1) shl 7) or (c and 0x7f).toLong()
                    }
                    val base = entryOffset - off
                    if (off <= 0 || base < 12) malformed("ofs-delta base offset out of range")
                    baseOffset = base
                }
                REF_DELTA -> {
                    if (p + algorithm.byteLength > len) malformed("truncated ref-delta base id")
                    baseId = ObjectId.fromBytes(buf, p, algorithm)
                    p += algorithm.byteLength
                }
                1, 2, 3, 4 -> {}
                else -> malformed("invalid pack object type $type")
            }
            return PackEntryHeader(type, size, p, baseOffset, baseId)
        }
    }
}

internal object PackIo {
    const val HEADER_LEN = 12

    fun readHeader(src: RandomAccessSource, offset: Long, algorithm: HashAlgorithm): PackEntryHeader {
        val buf = ByteArray(PackEntryHeader.MAX_HEADER)
        val n = src.read(offset, buf, 0, buf.size)
        return PackEntryHeader.parse(buf, n, offset, algorithm)
    }

    /**
     * Inflates one zlib stream starting at [start] (not past [end]) and hands output to [sink].
     * Never produces more than [expected] bytes; a stream that is shorter or longer is MALFORMED.
     * With [prefix] >= 0 it stops once that many bytes were produced (size peeking).
     * Returns the number of compressed bytes consumed (valid when the stream was read to its end).
     */
    fun inflate(src: RandomAccessSource, start: Long, end: Long, expected: Long, prefix: Long = -1, sink: (ByteArray, Int, Int) -> Unit): Long {
        val inf = Inflater()
        try {
            val inBuf = ByteArray(8192)
            val outBuf = ByteArray(16 * 1024)
            var pos = start
            var produced = 0L
            while (!inf.finished()) {
                if (prefix in 0..produced) return inf.bytesRead
                if (inf.needsInput()) {
                    val want = minOf(inBuf.size.toLong(), end - pos).toInt()
                    if (want <= 0) malformed("truncated compressed pack entry")
                    val got = src.read(pos, inBuf, 0, want)
                    if (got <= 0) malformed("truncated compressed pack entry")
                    pos += got
                    inf.setInput(inBuf, 0, got)
                }
                val room = minOf(outBuf.size.toLong(), if (prefix >= 0) prefix - produced else expected - produced + 1).toInt()
                val n = try { inf.inflate(outBuf, 0, maxOf(room, 1)) } catch (e: DataFormatException) {
                    throw GitException(GitErrorKind.MALFORMED, "corrupt zlib data in pack", cause = e)
                }
                if (n > 0) {
                    produced += n
                    if (prefix < 0 && produced > expected) malformed("pack entry inflates past declared size")
                    sink(outBuf, 0, n)
                } else if (inf.needsDictionary()) malformed("zlib preset dictionary not allowed")
            }
            if (prefix < 0 && produced != expected) malformed("pack entry inflated to $produced bytes, declared $expected")
            return inf.bytesRead
        } finally { inf.end() }
    }

    fun inflateToArray(src: RandomAccessSource, start: Long, end: Long, expected: Long, maxOut: Long): ByteArray {
        if (expected > maxOut) limit("pack object of $expected bytes exceeds in-memory cap $maxOut")
        val out = java.io.ByteArrayOutputStream(minOf(expected, 1L shl 20).toInt())
        inflate(src, start, end, expected) { b, o, l -> out.write(b, o, l) }
        return out.toByteArray()
    }

    fun crc32(src: RandomAccessSource, start: Long, endExclusive: Long): Long {
        val crc = CRC32()
        val buf = ByteArray(16 * 1024)
        var pos = start
        while (pos < endExclusive) {
            val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), endExclusive - pos).toInt())
            if (n <= 0) malformed("pack ended during crc")
            crc.update(buf, 0, n)
            pos += n
        }
        return crc.value
    }

    /** Applies a git delta to [base]. Every offset/size is bounds-checked; output is capped. */
    fun applyDelta(base: ByteArray, delta: ByteArray, maxOut: Long): ByteArray {
        var p = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                if (p >= delta.size) malformed("truncated delta header")
                val c = delta[p++].toInt() and 0xff
                if (shift > 56) malformed("delta size varint too long")
                r = r or ((c and 0x7f).toLong() shl shift)
                shift += 7
                if (c and 0x80 == 0) return r
            }
        }
        val srcSize = varint()
        val dstSize = varint()
        if (srcSize != base.size.toLong()) malformed("delta base size mismatch")
        if (dstSize < 0 || dstSize > maxOut) limit("delta result of $dstSize bytes exceeds cap $maxOut")
        val out = ByteArray(dstSize.toInt())
        var o = 0
        while (p < delta.size) {
            val op = delta[p++].toInt() and 0xff
            if (op and 0x80 != 0) {
                var off = 0L
                var size = 0L
                for (i in 0 until 4) if (op and (1 shl i) != 0) {
                    if (p >= delta.size) malformed("truncated delta copy")
                    off = off or ((delta[p++].toLong() and 0xff) shl (8 * i))
                }
                for (i in 0 until 3) if (op and (0x10 shl i) != 0) {
                    if (p >= delta.size) malformed("truncated delta copy")
                    size = size or ((delta[p++].toLong() and 0xff) shl (8 * i))
                }
                if (size == 0L) size = 0x10000
                if (off + size > base.size || off < 0) malformed("delta copy outside base")
                if (o + size > out.size) malformed("delta writes past declared size")
                System.arraycopy(base, off.toInt(), out, o, size.toInt())
                o += size.toInt()
            } else if (op != 0) {
                if (p + op > delta.size) malformed("truncated delta insert")
                if (o + op > out.size) malformed("delta writes past declared size")
                System.arraycopy(delta, p, out, o, op)
                p += op
                o += op
            } else malformed("delta opcode 0 is reserved")
        }
        if (o != out.size) malformed("delta produced $o bytes, declared ${out.size}")
        return out
    }
}
