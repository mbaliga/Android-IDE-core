package dev.fonebrew.domain.gitcore

/**
 * Reader for pack index v2 (`.idx`). Lookups are binary searches over the fanout-bounded name
 * table through [RandomAccessSource] reads, so a multi-million-object index is never loaded
 * whole. Structural validation happens at open; [verifyChecksum] is the full-file check.
 */
class PackIndex private constructor(
    private val src: RandomAccessSource,
    private val algorithm: HashAlgorithm,
    private val fanout: IntArray,
    val objectCount: Int,
    private val namesAt: Long,
    private val crcAt: Long,
    private val offAt: Long,
    private val largeAt: Long,
    private val largeCount: Long,
) : AutoCloseable {

    class Entry(val id: ObjectId, val offset: Long, val crc32: Long)

    private val w = algorithm.byteLength

    fun idAt(i: Int): ObjectId {
        require(i in 0 until objectCount)
        val b = ByteArray(w)
        readFully(namesAt + i.toLong() * w, b)
        return ObjectId(b, algorithm)
    }

    private fun readFully(pos: Long, b: ByteArray) {
        if (src.read(pos, b, 0, b.size) != b.size) malformed("truncated pack index")
    }

    private fun u32(pos: Long): Long {
        val b = ByteArray(4)
        readFully(pos, b)
        return ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
    }

    private fun u64(pos: Long): Long {
        val b = ByteArray(8)
        readFully(pos, b)
        var r = 0L
        for (x in b) r = (r shl 8) or (x.toLong() and 0xff)
        return r
    }

    /** Position of [id] in the sorted name table, or -1. */
    fun positionOf(id: ObjectId): Int {
        val first = id.byteAt(0)
        var lo = if (first == 0) 0 else fanout[first - 1]
        var hi = fanout[first] - 1
        val probe = ByteArray(w)
        val want = id.toByteArray()
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            readFully(namesAt + mid.toLong() * w, probe)
            var c = 0
            for (i in 0 until w) {
                c = (probe[i].toInt() and 0xff) - (want[i].toInt() and 0xff)
                if (c != 0) break
            }
            when {
                c == 0 -> return mid
                c < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    fun offsetAt(pos: Int): Long {
        val v = u32(offAt + pos.toLong() * 4)
        if (v and 0x8000_0000L == 0L) return v
        val idx = v and 0x7fff_ffffL
        if (idx >= largeCount) malformed("large-offset index out of range")
        return u64(largeAt + idx * 8).also { if (it < 0) malformed("pack offset overflow") }
    }

    fun crcAt(pos: Int): Long = u32(crcAt + pos.toLong() * 4)

    fun find(id: ObjectId): Entry? {
        val p = positionOf(id)
        return if (p < 0) null else Entry(id, offsetAt(p), crcAt(p))
    }

    fun contains(id: ObjectId) = positionOf(id) >= 0

    /** Positions whose id starts with the given lower-case hex prefix (for abbreviation lookup). */
    fun idsWithPrefix(hexPrefix: String, limit: Int = 8): List<ObjectId> {
        val out = ArrayList<ObjectId>()
        for (i in 0 until objectCount) {
            if (out.size >= limit) break
            val id = idAt(i)
            if (id.hex.startsWith(hexPrefix)) out.add(id)
        }
        return out
    }

    fun packChecksum(): ByteArray {
        val b = ByteArray(w)
        readFully(src.length - 2L * w, b)
        return b
    }

    /** Recomputes the idx trailing checksum over the whole file; typed error on mismatch. */
    fun verifyChecksum() {
        val md = algorithm.newDigest()
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        val end = src.length - w
        while (pos < end) {
            val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
            if (n <= 0) malformed("truncated pack index")
            md.update(buf, 0, n)
            pos += n
        }
        val want = ByteArray(w)
        readFully(end, want)
        if (!md.digest().contentEquals(want)) throw GitException(GitErrorKind.CHECKSUM, "pack index checksum mismatch")
    }

    override fun close() = src.close()

    companion object {
        private val MAGIC = byteArrayOf(0xff.toByte(), 0x74, 0x4f, 0x63)

        fun open(fs: FileSystemPort, path: String, limits: GitLimits = GitLimits(), algorithm: HashAlgorithm = HashAlgorithm.SHA1): PackIndex {
            val src = fs.openRandomAccess(path)
            try {
                val w = algorithm.byteLength
                val len = src.length
                if (len < 8 + 1024 + 2L * w) malformed("pack index too small")
                val head = ByteArray(8 + 1024)
                if (src.read(0, head, 0, head.size) != head.size) malformed("truncated pack index")
                for (i in 0 until 4) if (head[i] != MAGIC[i]) malformed("not a v2 pack index")
                val version = ((head[4].toInt() and 0xff) shl 24) or ((head[5].toInt() and 0xff) shl 16) or ((head[6].toInt() and 0xff) shl 8) or (head[7].toInt() and 0xff)
                if (version != 2) throw GitException(GitErrorKind.UNSUPPORTED, "pack index version $version")
                val fan = IntArray(256)
                var prev = 0L
                for (i in 0 until 256) {
                    val o = 8 + i * 4
                    val v = ((head[o].toLong() and 0xff) shl 24) or ((head[o + 1].toLong() and 0xff) shl 16) or ((head[o + 2].toLong() and 0xff) shl 8) or (head[o + 3].toLong() and 0xff)
                    if (v < prev) malformed("pack index fanout not monotonic")
                    if (v > Int.MAX_VALUE || v > limits.maxPackObjects) limit("pack index object count too large")
                    fan[i] = v.toInt()
                    prev = v
                }
                val n = fan[255]
                val namesAt = 8L + 1024
                val crcAt = namesAt + n.toLong() * w
                val offAt = crcAt + n.toLong() * 4
                val largeAt = offAt + n.toLong() * 4
                val tail = len - 2L * w
                if (largeAt > tail || (tail - largeAt) % 8 != 0L) malformed("pack index size inconsistent with object count")
                return PackIndex(src, algorithm, fan, n, namesAt, crcAt, offAt, largeAt, (tail - largeAt) / 8)
            } catch (t: Throwable) {
                src.close()
                throw t
            }
        }
    }
}
