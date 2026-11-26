package dev.fonebrew.domain.gitcore

import dev.fonebrew.domain.gitcore.GitTestSupport.PackBuilder
import dev.fonebrew.domain.gitcore.GitTestSupport.makeDelta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ObjectStoreTest {
    private val fs = JvmFileSystem()
    private fun newDb(limits: GitLimits = GitLimits()): Pair<ObjectDatabase, File> {
        val dir = GitTestSupport.tempDir()
        return ObjectDatabase(fs, "${dir.absolutePath}/objects", limits) to dir
    }
    private fun expect(kind: GitErrorKind, block: () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: GitException) { assertEquals(kind, e.kind) }
    }
    private fun idOf(type: ObjectType, d: ByteArray) = ObjectId.hashObject(type, d)

    @Test fun `loose write then read round trips and is idempotent`() {
        val (db, _) = newDb()
        val data = "hello loose\n".toByteArray()
        val id = db.write(ObjectType.BLOB, data)
        assertEquals(idOf(ObjectType.BLOB, data), id)
        assertEquals(id, db.write(ObjectType.BLOB, data))
        val back = db.readRaw(id)!!
        assertEquals(ObjectType.BLOB, back.type)
        assertArrayEquals(data, back.data)
        assertTrue(db.has(id))
        assertNull(db.readRaw(ObjectId.fromHex("1".repeat(40))))
        assertEquals(ObjectType.BLOB to data.size.toLong(), db.peek(id))
    }

    @Test fun `loose write leaves no temp files behind`() {
        val (db, dir) = newDb()
        val id = db.write(ObjectType.BLOB, "x".toByteArray())
        val sub = File(dir, "objects/${id.hex.take(2)}")
        assertEquals(listOf(id.hex.drop(2)), sub.list()!!.toList())
    }

    @Test fun `loose object with lying size or garbage is a typed error`() {
        val (db, dir) = newDb()
        val id = ObjectId.fromHex("ab".repeat(20))
        File(dir, "objects/ab").mkdirs()
        File(dir, "objects/ab/${id.hex.drop(2)}").writeBytes(Zlib.deflate("blob 99\u0000short".toByteArray()))
        expect(GitErrorKind.MALFORMED) { db.readRaw(id) }
        File(dir, "objects/ab/${id.hex.drop(2)}").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        expect(GitErrorKind.MALFORMED) { db.readRaw(id) }
        File(dir, "objects/ab/${id.hex.drop(2)}").writeBytes(Zlib.deflate("wat 1\u0000x".toByteArray()))
        expect(GitErrorKind.MALFORMED) { db.readRaw(id) }
    }

    @Test fun `object whose content does not hash to its name is rejected on parsed read`() {
        val (db, dir) = newDb()
        val real = db.write(ObjectType.BLOB, "one".toByteArray())
        val other = idOf(ObjectType.BLOB, "two".toByteArray())
        File(dir, "objects/${other.hex.take(2)}").mkdirs()
        File(dir, "objects/${real.hex.take(2)}/${real.hex.drop(2)}").copyTo(File(dir, "objects/${other.hex.take(2)}/${other.hex.drop(2)}"), true)
        expect(GitErrorKind.CHECKSUM) { db.read(other) }
    }

    @Test fun `loose size cap is enforced against a zip bomb`() {
        val (db, dir) = newDb(GitLimits(maxInMemoryObject = 1024))
        val id = ObjectId.fromHex("cd".repeat(20))
        File(dir, "objects/cd").mkdirs()
        File(dir, "objects/cd/${id.hex.drop(2)}").writeBytes(Zlib.deflate(("blob 4000000\u0000").toByteArray() + ByteArray(4_000_000)))
        expect(GitErrorKind.LIMIT_EXCEEDED) { db.readRaw(id) }
    }

    @Test fun `large loose blob streams without the in-memory cap`() {
        val (db, _) = newDb(GitLimits(maxInMemoryObject = 1024))
        val data = ByteArray(300_000) { (it % 251).toByte() }
        // write() is not capped (caller already holds the bytes); reading it back must stream.
        val id = db.write(ObjectType.BLOB, data)
        val out = ByteArrayOutputStream()
        assertTrue(db.streamTo(id, out))
        assertArrayEquals(data, out.toByteArray())
        expect(GitErrorKind.LIMIT_EXCEEDED) { db.readRaw(id) }
    }

    // ---------------------------------------------------------------- packs

    private fun installPack(db: ObjectDatabase, bytes: ByteArray) = db.indexPack(ByteArrayInputStream(bytes))

    @Test fun `pack of whole objects indexes and reads back`() {
        val (db, dir) = newDb()
        val b = PackBuilder()
        val blobs = (0 until 40).map { "blob number $it\n".repeat(it + 1).toByteArray() }
        blobs.forEach { b.whole(ObjectType.BLOB, it) }
        val r = installPack(db, b.build())
        assertEquals(40, r.objectCount)
        assertTrue(File(r.packPath).exists() && File(r.idxPath).exists())
        for (d in blobs) assertArrayEquals(d, db.readRaw(idOf(ObjectType.BLOB, d))!!.data)
        // The idx checksum and the pack checksum verify, and crc matches per object.
        PackIndex.open(fs, r.idxPath).use { it.verifyChecksum() }
        PackFile(fs, r.packPath, PackIndex.open(fs, r.idxPath)).use { p ->
            p.verifyChecksum()
            for (d in blobs) assertTrue(p.verifyCrc(idOf(ObjectType.BLOB, d)))
        }
        assertEquals(40, db.allPackedIds().size)
    }

    @Test fun `OFS and REF deltas resolve including chains`() {
        val (db, _) = newDb()
        val base = "line one\nline two\nline three\n".repeat(50).toByteArray()
        val b = PackBuilder()
        val baseOff = b.whole(ObjectType.BLOB, base)
        val v1 = base.copyOf(400) + "appended 1\n".toByteArray()
        val d1 = b.ofsDelta(baseOff, makeDelta(base, 400, "appended 1\n".toByteArray()), v1.size.toLong())
        val v2 = v1.copyOf(300) + "appended 2\n".toByteArray()
        b.ofsDelta(d1, makeDelta(v1, 300, "appended 2\n".toByteArray()), v2.size.toLong())
        val v3 = base.copyOf(100) + "by ref\n".toByteArray()
        b.refDelta(idOf(ObjectType.BLOB, base), makeDelta(base, 100, "by ref\n".toByteArray()))
        installPack(db, b.build())
        for (v in listOf(base, v1, v2, v3)) assertArrayEquals(v, db.readRaw(idOf(ObjectType.BLOB, v))!!.data)
        assertEquals(ObjectType.BLOB to v2.size.toLong(), db.peek(idOf(ObjectType.BLOB, v2)))
    }

    @Test fun `REF delta listed before its base still resolves`() {
        val (db, _) = newDb()
        val base = "base content here\n".repeat(20).toByteArray()
        val v = base.copyOf(50) + "tail".toByteArray()
        val b = PackBuilder()
        b.refDelta(idOf(ObjectType.BLOB, base), makeDelta(base, 50, "tail".toByteArray()))
        b.whole(ObjectType.BLOB, base)
        installPack(db, b.build())
        assertArrayEquals(v, db.readRaw(idOf(ObjectType.BLOB, v))!!.data)
    }

    @Test fun `thin pack is completed from the store and ends up self contained`() {
        val (db, _) = newDb()
        val base = "thin base\n".repeat(30).toByteArray()
        val baseId = db.write(ObjectType.BLOB, base)
        val v = base.copyOf(60) + "new\n".toByteArray()
        val b = PackBuilder()
        b.refDelta(baseId, makeDelta(base, 60, "new\n".toByteArray()))
        val r = installPack(db, b.build())
        assertEquals(1, r.appendedBases)
        assertEquals(2, r.objectCount)
        // Opens standalone (no external resolver): the base was appended.
        PackFile(fs, r.packPath, PackIndex.open(fs, r.idxPath)).use { p ->
            p.verifyChecksum()
            assertArrayEquals(v, p.read(idOf(ObjectType.BLOB, v))!!.data)
            assertArrayEquals(base, p.read(baseId)!!.data)
        }
    }

    @Test fun `thin pack with a missing base is a typed NOT_FOUND`() {
        val (db, _) = newDb()
        val b = PackBuilder()
        b.refDelta(ObjectId.fromHex("9".repeat(40)), makeDelta("x".toByteArray(), 0, "y".toByteArray()))
        expect(GitErrorKind.NOT_FOUND) { installPack(db, b.build()) }
    }

    @Test fun `corrupt trailer is rejected and nothing is installed`() {
        val (db, dir) = newDb()
        val b = PackBuilder(); b.whole(ObjectType.BLOB, "a".toByteArray())
        expect(GitErrorKind.CHECKSUM) { installPack(db, b.build(corruptTrailer = true)) }
        assertTrue(File(dir, "objects/pack").list()?.toList().orEmpty().isEmpty())
    }

    @Test fun `hostile packs yield typed errors never crashes`() {
        val (db, _) = newDb()
        fun sealed(raw: ByteArray): ByteArray = raw + java.security.MessageDigest.getInstance("SHA-1").digest(raw)
        // truncated header
        expect(GitErrorKind.MALFORMED) { installPack(db, ByteArray(5)) }
        // lying object count (huge)
        val huge = "PACK".toByteArray() + byteArrayOf(0, 0, 0, 2, 0x7f, 0, 0, 0)
        expect(GitErrorKind.LIMIT_EXCEEDED) { installPack(db, sealed(huge)) }
        // count says 3, no entries
        val none = "PACK".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 3)
        expect(GitErrorKind.MALFORMED) { installPack(db, sealed(none)) }
        // bad signature
        expect(GitErrorKind.MALFORMED) { installPack(db, sealed("PAXK".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0))) }
        // unsupported version
        expect(GitErrorKind.UNSUPPORTED) { installPack(db, sealed("PACK".toByteArray() + byteArrayOf(0, 0, 0, 9, 0, 0, 0, 0))) }
        // invalid object type 0 / 5
        for (t in listOf(0, 5)) {
            val raw = "PACK".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1) + byteArrayOf(((t shl 4) or 1).toByte()) + Zlib.deflate(byteArrayOf(1))
            expect(GitErrorKind.MALFORMED) { installPack(db, sealed(raw)) }
        }
        // entry declares size 10 but inflates to 1 byte
        val lie = PackBuilder().let { pb -> pb.whole(ObjectType.BLOB, "a".toByteArray()); pb.build() }.copyOf().also { it[12] = ((3 shl 4) or 10).toByte() }
        val lieSealed = sealed(lie.copyOfRange(0, lie.size - 20))
        expect(GitErrorKind.MALFORMED) { installPack(db, lieSealed) }
        // truncated zlib
        val tr = PackBuilder().let { pb -> pb.whole(ObjectType.BLOB, ByteArray(500) { it.toByte() }); pb.build() }
        expect(GitErrorKind.MALFORMED) { installPack(db, sealed(tr.copyOfRange(0, tr.size - 20 - 6))) }
        // trailing garbage after the last entry
        val tg = PackBuilder().let { pb -> pb.whole(ObjectType.BLOB, "a".toByteArray()); pb.build() }
        expect(GitErrorKind.MALFORMED) { installPack(db, sealed(tg.copyOfRange(0, tg.size - 20) + byteArrayOf(1, 2, 3))) }
        // duplicate object
        val dup = PackBuilder().also { it.whole(ObjectType.BLOB, "same".toByteArray()); it.whole(ObjectType.BLOB, "same".toByteArray()) }
        expect(GitErrorKind.MALFORMED) { installPack(db, dup.build()) }
    }

    @Test fun `delta with out of range copy or wrong base size is typed`() {
        val base = "0123456789".toByteArray()
        fun delta(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()
        // copy offset 5 size 20 from a 10-byte base
        expect(GitErrorKind.MALFORMED) { PackIo.applyDelta(base, delta(10, 20, 0x80 or 0x01 or 0x10, 5, 20), 1000) }
        // base size mismatch
        expect(GitErrorKind.MALFORMED) { PackIo.applyDelta(base, delta(9, 1, 1, 'a'.code), 1000) }
        // opcode 0
        expect(GitErrorKind.MALFORMED) { PackIo.applyDelta(base, delta(10, 1, 0), 1000) }
        // declared result larger than cap
        expect(GitErrorKind.LIMIT_EXCEEDED) { PackIo.applyDelta(base, delta(10, 0xff, 0xff, 0x7f), 1000) }
        // insert overruns declared size
        expect(GitErrorKind.MALFORMED) { PackIo.applyDelta(base, delta(10, 1, 2, 'a'.code, 'b'.code), 1000) }
        // result shorter than declared
        expect(GitErrorKind.MALFORMED) { PackIo.applyDelta(base, delta(10, 5, 1, 'a'.code), 1000) }
    }

    @Test fun `cyclic REF deltas do not hang or recurse`() {
        val (db, _) = newDb()
        // Two REF deltas that name each other as bases; neither id is derivable -> never resolves.
        val a = ObjectId.fromHex("a".repeat(40)); val b2 = ObjectId.fromHex("b".repeat(40))
        val pb = PackBuilder()
        pb.refDelta(a, makeDelta("x".toByteArray(), 0, "y".toByteArray()))
        pb.refDelta(b2, makeDelta("x".toByteArray(), 0, "y".toByteArray()))
        expect(GitErrorKind.NOT_FOUND) { installPack(db, pb.build()) }
    }

    @Test fun `delta chain longer than the cap is refused`() {
        val limits = GitLimits(maxDeltaChain = 5, deltaCacheBytes = 0)
        val (db, _) = newDb()
        var cur = "start\n".repeat(10).toByteArray()
        val pb = PackBuilder()
        var off = pb.whole(ObjectType.BLOB, cur)
        for (i in 0 until 12) {
            val next = cur.copyOf(20) + "step$i\n".toByteArray()
            off = pb.ofsDelta(off, makeDelta(cur, 20, "step$i\n".toByteArray()), next.size.toLong())
            cur = next
        }
        val r = installPack(db, pb.build())
        // The indexer resolves incrementally (each base is already cached); a reader with a small
        // cap must still refuse to walk the 12-deep chain.
        PackFile(fs, r.packPath, PackIndex.open(fs, r.idxPath), limits).use { p ->
            expect(GitErrorKind.LIMIT_EXCEEDED) { p.read(idOf(ObjectType.BLOB, cur)) }
        }
    }

    @Test fun `delta cache respects its byte ceiling`() {
        val limits = GitLimits(deltaCacheBytes = 4096)
        val (db, _) = newDb(limits)
        val pb = PackBuilder()
        val blobs = (0 until 30).map { i -> ByteArray(1000) { (it + i).toByte() } }
        val offs = blobs.map { pb.whole(ObjectType.BLOB, it) }
        val r = installPack(db, pb.build())
        PackFile(fs, r.packPath, PackIndex.open(fs, r.idxPath), limits).use { p ->
            for (b in blobs) p.read(idOf(ObjectType.BLOB, b))
            assertTrue("cache ${p.cachedBytes()} must stay under ceiling", p.cachedBytes() <= 4096)
        }
        assertEquals(30, offs.size)
    }

    @Test fun `large whole blob in a pack streams despite a tiny in-memory cap`() {
        val (db, _) = newDb(GitLimits(maxInMemoryObject = 2048))
        val big = ByteArray(200_000) { (it * 7).toByte() }
        val pb = PackBuilder(); pb.whole(ObjectType.BLOB, big)
        installPack(db, pb.build())
        val id = idOf(ObjectType.BLOB, big)
        expect(GitErrorKind.LIMIT_EXCEEDED) { db.readRaw(id) }
        val out = ByteArrayOutputStream()
        assertTrue(db.streamTo(id, out))
        assertArrayEquals(big, out.toByteArray())
        assertEquals(ObjectType.BLOB to big.size.toLong(), db.peek(id))
    }

    @Test fun `pack index structural attacks are typed`() {
        val dir = GitTestSupport.tempDir()
        fun open(bytes: ByteArray): PackIndex { val f = File(dir, "x.idx"); f.writeBytes(bytes); return PackIndex.open(fs, f.path) }
        expect(GitErrorKind.MALFORMED) { open(ByteArray(10)) }
        expect(GitErrorKind.MALFORMED) { open(ByteArray(8 + 1024 + 40)) } // no magic
        val good = ByteArray(8 + 1024 + 40).also { it[0] = 0xff.toByte(); it[1] = 0x74; it[2] = 0x4f; it[3] = 0x63; it[7] = 2 }
        open(good).close()
        val v3 = good.copyOf().also { it[7] = 3 }
        expect(GitErrorKind.UNSUPPORTED) { open(v3) }
        val nonMono = good.copyOf().also { it[8 + 3] = 5 } // fanout[0]=5 then fanout[1]=0
        expect(GitErrorKind.MALFORMED) { open(nonMono) }
        val bigCount = good.copyOf().also { for (i in 0 until 256) { it[8 + i * 4] = 0x7f } }
        expect(GitErrorKind.LIMIT_EXCEEDED) { open(bigCount) }
        // count says 3 objects but the file is far too short
        val short = good.copyOf().also { for (i in 0 until 256) it[8 + i * 4 + 3] = 3 }
        expect(GitErrorKind.MALFORMED) { open(short) }
    }

    @Test fun `PackWriter output is a valid pack the indexer accepts and ObjectDatabase reads`() {
        val (src, _) = newDb()
        val ids = (0 until 25).map { src.write(ObjectType.BLOB, ByteArray(50 + it * 13) { b -> (b * it).toByte() }) }
        val blob = ByteArrayOutputStream()
        PackWriter.write(src, ids, blob)
        val (dst, _) = newDb()
        val r = dst.indexPack(ByteArrayInputStream(blob.toByteArray()))
        assertEquals(25, r.objectCount)
        for (id in ids) assertEquals(src.readRaw(id)!!.data.toList(), dst.readRaw(id)!!.data.toList())
        expect(GitErrorKind.NOT_FOUND) { PackWriter.write(src, listOf(ObjectId.fromHex("7".repeat(40))), ByteArrayOutputStream()) }
    }

    @Test fun `prefix resolution finds loose and packed objects and flags ambiguity`() {
        val (db, _) = newDb()
        val id = db.write(ObjectType.BLOB, "prefix me".toByteArray())
        assertEquals(id, db.resolvePrefix(id.hex.take(8)))
        expect(GitErrorKind.NOT_FOUND) { db.resolvePrefix("0000dead") }
        expect(GitErrorKind.INVALID_ARGUMENT) { db.resolvePrefix("zz") }
        assertNotNull(db.read(id))
        assertFalse(db.has(ObjectId.fromHex("2".repeat(40))))
    }
}
