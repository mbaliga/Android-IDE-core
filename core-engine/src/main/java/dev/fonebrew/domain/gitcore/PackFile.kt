package dev.fonebrew.domain.gitcore

import java.io.OutputStream

/**
 * Reader for one pack v2 file plus its [PackIndex]. Delta chains are resolved iteratively (no
 * recursion), the chain length is capped, OFS bases must point strictly backwards (so an OFS
 * cycle is impossible), and resolved bases go through a byte-bounded LRU so memory stays under
 * [GitLimits.deltaCacheBytes] plus one working object.
 *
 * [externalBase] resolves REF_DELTA bases that are not in this pack (thin packs / cross-pack);
 * it receives the remaining chain budget so a cycle across packs terminates.
 */
class PackFile(
    private val fs: FileSystemPort,
    val packPath: String,
    val index: PackIndex,
    private val limits: GitLimits = GitLimits(),
    private val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    private val externalBase: (ObjectId, Int) -> RawObject? = { _, _ -> null },
) : AutoCloseable {

    private val src: RandomAccessSource = fs.openRandomAccess(packPath)
    private val dataEnd: Long
    private val reader: PackReader
    val objectCount: Int

    init {
        try {
            val w = algorithm.byteLength
            if (src.length < PackIo.HEADER_LEN + w) malformed("pack too small")
            val h = ByteArray(12)
            if (src.read(0, h, 0, 12) != 12) malformed("truncated pack header")
            if (h[0] != 'P'.code.toByte() || h[1] != 'A'.code.toByte() || h[2] != 'C'.code.toByte() || h[3] != 'K'.code.toByte()) malformed("bad pack signature")
            val ver = be32(h, 4)
            if (ver != 2L && ver != 3L) throw GitException(GitErrorKind.UNSUPPORTED, "pack version $ver")
            val count = be32(h, 8)
            if (count != index.objectCount.toLong()) malformed("pack object count ($count) disagrees with index (${index.objectCount})")
            objectCount = index.objectCount
            dataEnd = src.length - w
            reader = PackReader(src, dataEnd, { id -> index.find(id)?.offset }, limits, algorithm, externalBase)
            val trailer = ByteArray(w)
            if (src.read(dataEnd, trailer, 0, w) != w || !trailer.contentEquals(index.packChecksum())) {
                throw GitException(GitErrorKind.CHECKSUM, "pack trailer does not match its index")
            }
        } catch (t: Throwable) { src.close(); throw t }
    }

    private fun be32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    fun has(id: ObjectId) = index.contains(id)

    fun read(id: ObjectId, chainBudget: Int = limits.maxDeltaChain): RawObject? {
        val e = index.find(id) ?: return null
        return reader.readAt(e.offset, chainBudget)
    }

    /** Full-file hash over the pack body vs. its trailer. Expensive; for indexing and fsck-like checks. */
    fun verifyChecksum() {
        val md = algorithm.newDigest()
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        while (pos < dataEnd) {
            val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), dataEnd - pos).toInt())
            if (n <= 0) malformed("truncated pack")
            md.update(buf, 0, n)
            pos += n
        }
        val want = ByteArray(algorithm.byteLength)
        src.read(dataEnd, want, 0, want.size)
        if (!md.digest().contentEquals(want)) throw GitException(GitErrorKind.CHECKSUM, "pack checksum mismatch")
    }

    /** Checks the idx CRC of one object's packed bytes against the pack. */
    fun verifyCrc(id: ObjectId): Boolean {
        val pos = index.positionOf(id)
        if (pos < 0) return false
        val start = index.offsetAt(pos)
        val h = PackIo.readHeader(src, start, algorithm)
        return PackIo.crc32(src, start, reader.compressedEnd(start, h)) == index.crcAt(pos)
    }

    fun sizeAt(offset: Long) = reader.sizeAt(offset)
    fun typeAt(offset: Long) = reader.typeAt(offset)
    fun readAt(offset: Long, chainBudget: Int = limits.maxDeltaChain) = reader.readAt(offset, chainBudget)
    fun cachedBytes(): Long = reader.cachedBytes()

    /** Streams a non-delta object's body to [out] without materialising it. False if deltified or absent. */
    fun streamNonDelta(id: ObjectId, out: OutputStream): Boolean {
        val e = index.find(id) ?: return false
        return reader.streamNonDeltaAt(e.offset, out)
    }

    fun sizeOf(id: ObjectId): Long? = index.find(id)?.let { reader.sizeAt(it.offset) }
    fun typeOf(id: ObjectId): ObjectType? = index.find(id)?.let { reader.typeAt(it.offset) }

    override fun close() { src.close(); index.close() }
}
