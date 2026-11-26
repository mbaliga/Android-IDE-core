package dev.fonebrew.domain.gitcore

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Turns a received pack stream into `pack-<sum>.pack` + `.idx` under the pack directory.
 *
 * 1. Spools the stream to a temp file, hashing everything but the last 20 bytes, and verifies
 *    the trailer (typed CHECKSUM error on mismatch). The spool is capped by [GitLimits.maxPackBytes].
 * 2. Scans entries sequentially recording offset, CRC32 and, for whole objects, the id.
 * 3. Resolves deltas to ids (REF_DELTA bases come from this pack first, then from [db] - the thin
 *    pack case) until a fixpoint; a delta whose base never appears is a typed NOT_FOUND error.
 * 4. Thin completion: every external base that was used is appended to the pack as a whole object,
 *    the header count is patched and the trailer recomputed, so the stored pack is self-contained.
 * 5. Writes the v2 index. Duplicate object ids inside one pack are rejected as MALFORMED
 *    (git itself never emits them; accepting them would desynchronise count and index).
 *
 * Memory: per-entry bookkeeping (~50 B/object) plus the index assembled in memory (28 B/object);
 * never the pack itself except for the single object being resolved (capped by maxInMemoryObject).
 */
class PackIndexer(
    private val fs: FileSystemPort,
    private val packDir: String,
    private val db: ObjectDatabase?,
    private val limits: GitLimits = GitLimits(),
    private val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
) {
    class Result(val packPath: String, val idxPath: String, val objectCount: Int, val checksum: ObjectId, val appendedBases: Int)

    private class Rec(val offset: Long, val header: PackEntryHeader, var end: Long = 0, var crc: Long = 0, var id: ObjectId? = null)

    fun index(input: InputStream): Result {
        val tmp = "$packDir/tmp_pack_${java.lang.Long.toHexString(System.nanoTime())}_${java.util.concurrent.ThreadLocalRandom.current().nextInt(Int.MAX_VALUE)}"
        try {
            spool(input, tmp)
            return finish(tmp)
        } finally {
            fs.delete(tmp)
        }
    }

    private fun spool(input: InputStream, tmp: String) {
        val w = algorithm.byteLength
        val md = algorithm.newDigest()
        var held = ByteArray(0)
        var total = 0L
        val buf = ByteArray(32 * 1024)
        fs.openWrite(tmp).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limits.maxPackBytes) limit("pack larger than ${limits.maxPackBytes} bytes")
                out.write(buf, 0, n)
                val all = ByteArray(held.size + n)
                System.arraycopy(held, 0, all, 0, held.size)
                System.arraycopy(buf, 0, all, held.size, n)
                val keep = minOf(w, all.size)
                md.update(all, 0, all.size - keep)
                held = all.copyOfRange(all.size - keep, all.size)
            }
        }
        if (total < PackIo.HEADER_LEN + w) malformed("pack stream too short")
        if (!md.digest().contentEquals(held)) throw GitException(GitErrorKind.CHECKSUM, "received pack trailer checksum mismatch")
    }

    private fun finish(tmp: String): Result {
        val w = algorithm.byteLength
        val src = fs.openRandomAccess(tmp)
        val recs = ArrayList<Rec>()
        val byId = HashMap<ObjectId, Long>()
        val byOffset = HashMap<Long, Rec>()
        val appended = ArrayList<ObjectId>()
        var packOut: String? = null
        try {
            val dataEnd = src.length - w
            val h = ByteArray(12)
            src.read(0, h, 0, 12)
            if (String(h, 0, 4, Charsets.US_ASCII) != "PACK") malformed("bad pack signature")
            val ver = ((h[4].toLong() and 0xff) shl 24) or ((h[5].toLong() and 0xff) shl 16) or ((h[6].toLong() and 0xff) shl 8) or (h[7].toLong() and 0xff)
            if (ver != 2L && ver != 3L) throw GitException(GitErrorKind.UNSUPPORTED, "pack version $ver")
            val count = ((h[8].toLong() and 0xff) shl 24) or ((h[9].toLong() and 0xff) shl 16) or ((h[10].toLong() and 0xff) shl 8) or (h[11].toLong() and 0xff)
            if (count > limits.maxPackObjects) limit("pack declares $count objects")
            // Each entry needs at least 3 bytes (header + minimal zlib), so a lying count is caught cheaply.
            if (count * 3 > dataEnd - 12) malformed("pack object count impossible for its size")

            // Pass 1: sequential scan.
            var off = 12L
            for (i in 0 until count) {
                if (off >= dataEnd) malformed("pack ends before declared object count")
                val hd = PackIo.readHeader(src, off, algorithm)
                val rec = Rec(off, hd)
                if (hd.isDelta) {
                    rec.end = off + hd.headerLength + PackIo.inflate(src, off + hd.headerLength, dataEnd, hd.size) { _, _, _ -> }
                } else {
                    val type = ObjectType.fromPackCode(hd.typeCode) ?: malformed("bad pack object type")
                    val md = algorithm.newDigest()
                    md.update("${type.tag} ${hd.size}".toByteArray(Charsets.US_ASCII)); md.update(0)
                    val used = PackIo.inflate(src, off + hd.headerLength, dataEnd, hd.size) { b, o, l -> md.update(b, o, l) }
                    rec.end = off + hd.headerLength + used
                    val id = ObjectId(md.digest(), algorithm)
                    if (byId.put(id, off) != null) malformed("duplicate object ${id.abbrev()} in pack")
                    rec.id = id
                }
                rec.crc = PackIo.crc32(src, off, rec.end)
                recs.add(rec); byOffset[off] = rec
                off = rec.end
            }
            if (off != dataEnd) malformed("trailing bytes after last pack entry")

            // Pass 2: resolve deltas to ids.
            val thin = LinkedHashSet<ObjectId>()
            val reader = PackReader(src, dataEnd, { id -> byId[id] }, limits, algorithm) { id, _ ->
                db?.readRaw(id)?.also { thin.add(id) }
            }
            var pending = recs.filter { it.id == null }
            var guard = 0
            while (pending.isNotEmpty()) {
                val still = ArrayList<Rec>()
                for (r in pending) {
                    val obj = try { reader.readAt(r.offset) } catch (e: GitException) {
                        if (e.kind == GitErrorKind.NOT_FOUND) { still.add(r); continue } else throw e
                    }
                    val id = ObjectId.hashObject(obj.type, obj.data, algorithm)
                    val prev = byId.put(id, r.offset)
                    if (prev != null && prev != r.offset) malformed("duplicate object ${id.abbrev()} in pack")
                    r.id = id
                }
                if (still.size == pending.size) {
                    throw GitException(GitErrorKind.NOT_FOUND, "${still.size} delta(s) have a base that is neither in the pack nor in the repository")
                }
                pending = still
                if (++guard > recs.size + 1) malformed("delta resolution did not converge")
            }
            // Bases borrowed from the repository that did not end up inside the pack.
            val thinBases = thin.filter { !byId.containsKey(it) }

            var finalPath = tmp
            var checksum: ObjectId = algorithm.zero()
            var finalCount = recs.size
            val entries = ArrayList<Triple<ObjectId, Long, Long>>(recs.size + thinBases.size)
            for (r in recs) entries.add(Triple(r.id!!, r.offset, r.crc))

            if (thinBases.isEmpty()) {
                val tr = ByteArray(w); src.read(dataEnd, tr, 0, w)
                checksum = ObjectId(tr, algorithm)
            } else {
                // Rewrite: copy body with patched count, append whole bases, new trailer.
                val out2 = "$tmp.thin"
                val md = algorithm.newDigest()
                val newCount = recs.size.toLong() + thinBases.size
                fs.openWrite(out2).use { o ->
                    val sink = object : OutputStream() {
                        override fun write(b: Int) { md.update(b.toByte()); o.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); o.write(b, off, len) }
                    }
                    h[8] = (newCount ushr 24).toByte(); h[9] = (newCount ushr 16).toByte(); h[10] = (newCount ushr 8).toByte(); h[11] = newCount.toByte()
                    sink.write(h)
                    var pos = 12L
                    val buf = ByteArray(64 * 1024)
                    while (pos < dataEnd) {
                        val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), dataEnd - pos).toInt())
                        if (n <= 0) malformed("pack truncated while completing thin pack")
                        sink.write(buf, 0, n); pos += n
                    }
                    var at = dataEnd
                    for (bid in thinBases) {
                        val raw = db!!.readRaw(bid) ?: throw GitException(GitErrorKind.NOT_FOUND, "thin base ${bid.abbrev()} disappeared")
                        val bytes = ByteArrayOutputStream()
                        bytes.write(PackWriter.entryHeader(raw.type.packCode, raw.data.size.toLong()))
                        bytes.write(Zlib.deflate(raw.data))
                        val eb = bytes.toByteArray()
                        sink.write(eb)
                        val crc = CRC32(); crc.update(eb)
                        entries.add(Triple(bid, at, crc.value))
                        at += eb.size
                        appended.add(bid)
                    }
                    finalCount = newCount.toInt()
                    checksum = ObjectId(md.digest(), algorithm)
                    o.write(checksum.toByteArray())
                }
                finalPath = out2
                packOut = out2
            }
            src.close()

            val name = "pack-${checksum.hex}"
            val packPath = "$packDir/$name.pack"
            val idxPath = "$packDir/$name.idx"
            if (fs.stat(packPath) == null) fs.rename(finalPath, packPath)
            fs.writeAtomic(idxPath, buildIdx(entries, checksum))
            return Result(packPath, idxPath, finalCount, checksum, appended.size)
        } finally {
            try { src.close() } catch (_: Exception) {}
            packOut?.let { fs.delete(it) }
        }
    }

    private fun buildIdx(entries: List<Triple<ObjectId, Long, Long>>, packSum: ObjectId): ByteArray {
        val sorted = entries.sortedBy { it.first }
        val out = ByteArrayOutputStream(sorted.size * 28 + 2048)
        fun u32(v: Long) { out.write((v ushr 24).toInt() and 0xff); out.write((v ushr 16).toInt() and 0xff); out.write((v ushr 8).toInt() and 0xff); out.write(v.toInt() and 0xff) }
        out.write(byteArrayOf(0xff.toByte(), 0x74, 0x4f, 0x63)); u32(2)
        val fan = IntArray(256)
        for (e in sorted) fan[e.first.byteAt(0)]++
        var run = 0
        for (i in 0 until 256) { run += fan[i]; u32(run.toLong()) }
        for (e in sorted) out.write(e.first.toByteArray())
        for (e in sorted) u32(e.third)
        val large = ArrayList<Long>()
        for (e in sorted) {
            if (e.second < 0x8000_0000L) u32(e.second) else { u32(0x8000_0000L or large.size.toLong()); large.add(e.second) }
        }
        for (l in large) { u32(l ushr 32); u32(l and 0xffff_ffffL) }
        out.write(packSum.toByteArray())
        val body = out.toByteArray()
        val sum = algorithm.newDigest().digest(body)
        return body + sum
    }
}
