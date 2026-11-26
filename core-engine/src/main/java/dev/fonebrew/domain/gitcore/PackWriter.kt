package dev.fonebrew.domain.gitcore

import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Writes a pack v2 with every object stored whole (no deltas) - valid for push, larger than a
 * deltified pack. Streams one object at a time, so memory is bounded by the largest single
 * object that cannot be streamed. Returns the pack checksum (its trailer).
 */
object PackWriter {
    fun write(db: ObjectDatabase, ids: List<ObjectId>, out: OutputStream, algorithm: HashAlgorithm = HashAlgorithm.SHA1): ObjectId {
        val md = algorithm.newDigest()
        val sink = object : OutputStream() {
            override fun write(b: Int) { md.update(b.toByte()); out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); out.write(b, off, len) }
        }
        val head = ByteArray(12)
        "PACK".toByteArray(Charsets.US_ASCII).copyInto(head)
        head[7] = 2
        val n = ids.size
        head[8] = (n ushr 24).toByte(); head[9] = (n ushr 16).toByte(); head[10] = (n ushr 8).toByte(); head[11] = n.toByte()
        sink.write(head)
        for (id in ids) {
            val (type, size) = db.peek(id) ?: throw GitException(GitErrorKind.NOT_FOUND, "object ${id.abbrev()} not in store")
            sink.write(entryHeader(type.packCode, size))
            // Compress straight into the pack; the Deflater is flushed by finish(), not closed over `sink`.
            val def = Deflater(Deflater.DEFAULT_COMPRESSION)
            try {
                val dos = DeflaterOutputStream(object : OutputStream() {
                    override fun write(b: Int) = sink.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)
                }, def)
                if (!db.streamTo(id, dos)) throw GitException(GitErrorKind.NOT_FOUND, "object ${id.abbrev()} vanished")
                dos.finish()
            } finally { def.end() }
        }
        val trailer = ObjectId(md.digest(), algorithm)
        out.write(trailer.toByteArray())
        out.flush()
        return trailer
    }

    internal fun entryHeader(typeCode: Int, size: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        var c = (typeCode shl 4) or (size and 15).toInt()
        var s = size ushr 4
        while (s != 0L) { o.write(c or 0x80); c = (s and 0x7f).toInt(); s = s ushr 7 }
        o.write(c)
        return o.toByteArray()
    }
}
