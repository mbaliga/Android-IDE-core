package dev.fonebrew.domain.gitcore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Random

class GitIndexTest {
    private val rnd = Random(9)
    private fun id() = ObjectId(ByteArray(20).also { rnd.nextBytes(it) })
    private fun expect(kind: GitErrorKind, block: () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: GitException) { assertEquals(kind, e.kind) }
    }
    private fun entry(path: String, stage: Int = 0, mode: Int = FileMode.REGULAR) =
        IndexEntry(path, id(), mode, stage, rnd.nextInt(100000).toLong(), 1_700_000_000, 123, 1_700_000_001, 456, 7, 8, 1000, 1000)

    private fun seal(body: ByteArray) = body + MessageDigest.getInstance("SHA-1").digest(body)

    @Test fun `round trip over random entries is byte exact`() {
        repeat(30) {
            val ix = GitIndex()
            val names = (0 until rnd.nextInt(60)).map { "d${rnd.nextInt(5)}/f${rnd.nextInt(1000)}é${if (rnd.nextBoolean()) "x" else ""}" }.distinct()
            for (n in names) ix.put(entry(n, 0, if (rnd.nextBoolean()) FileMode.EXECUTABLE else FileMode.REGULAR))
            val bytes = ix.toBytes()
            val back = GitIndex.parse(bytes)
            assertEquals(ix.entries(), back.entries())
            assertArrayEquals(bytes, back.toBytes())
        }
    }

    @Test fun `entries sort by raw bytes and stages order after path`() {
        val ix = GitIndex()
        ix.put(entry("b")); ix.put(entry("a/z")); ix.put(entry("a.b")); ix.put(entry("a0"))
        assertEquals(listOf("a.b", "a/z", "a0", "b"), ix.entries().map { it.path })
        val c = GitIndex()
        c.put(entry("f", 3)); c.put(entry("f", 1)); c.put(entry("f", 2))
        assertEquals(listOf(1, 2, 3), c.entries().map { it.stage })
        assertTrue(c.hasConflicts)
        c.put(entry("f", 0))
        assertFalse(c.hasConflicts); assertEquals(1, c.size)
    }

    @Test fun `file and directory clashes are resolved by put`() {
        val ix = GitIndex()
        ix.put(entry("a")); ix.put(entry("a/b"))
        assertNull(ix.get("a")); assertEquals(1, ix.size)
        ix.put(entry("a"))
        assertNull(ix.get("a/b")); assertEquals(1, ix.size)
    }

    @Test fun `flags carry stage assume-valid and long names`() {
        val long = "d/" + "n".repeat(5000)
        val ix = GitIndex()
        ix.put(entry("short", 2).copy(assumeValid = true))
        val back = GitIndex.parse(ix.toBytes())
        assertEquals(2, back.get("short", 2)!!.stage)
        assertTrue(back.get("short", 2)!!.assumeValid)
        // name length field saturates at 0xfff; a 5000-byte path must exceed the default 4096 path cap on read
        val big = GitIndex(); big.put(IndexEntry("x/" + "n".repeat(3000), id(), FileMode.REGULAR))
        assertEquals(1, GitIndex.parse(big.toBytes()).size)
        val over = GitIndex()
        over.entries() // empty ok
        expect(GitErrorKind.UNSAFE_PATH) { GitIndex().put(IndexEntry(long, id(), FileMode.REGULAR)) }
    }

    @Test fun `optional extensions are dropped and required ones are refused`() {
        val ix = GitIndex(); ix.put(entry("a"))
        val plain = ix.toBytes()
        val body = plain.copyOf(plain.size - 20)
        val withTree = body + "TREE".toByteArray() + byteArrayOf(0, 0, 0, 3, 1, 2, 3)
        val parsed = GitIndex.parse(seal(withTree))
        assertEquals(ix.entries(), parsed.entries())
        assertArrayEquals(plain, parsed.toBytes()) // dropped on rewrite
        expect(GitErrorKind.UNSUPPORTED) { GitIndex.parse(seal(body + "link".toByteArray() + byteArrayOf(0, 0, 0, 0))) }
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(seal(body + "TREE".toByteArray() + byteArrayOf(0, 0, 0, 99))) }
    }

    @Test fun `corrupt or hostile index files are typed errors`() {
        val ix = GitIndex(); ix.put(entry("dir/a")); ix.put(entry("dir/b"))
        val good = ix.toBytes()
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(ByteArray(5)) }
        expect(GitErrorKind.CHECKSUM) { GitIndex.parse(good.copyOf().also { it[30] = (it[30] + 1).toByte() }) }
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(seal("XXXX".toByteArray() + good.copyOfRange(4, good.size - 20))) }
        expect(GitErrorKind.UNSUPPORTED) { GitIndex.parse(seal(good.copyOf(good.size - 20).also { it[7] = 4 })) }
        // count claims 100 entries
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(seal(good.copyOf(good.size - 20).also { it[11] = 100 })) }
        expect(GitErrorKind.LIMIT_EXCEEDED) { GitIndex.parse(seal(good.copyOf(good.size - 20).also { it[8] = 0x7f }), GitLimits(maxIndexEntries = 10)) }
        // truncated mid-entry
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(seal(good.copyOf(40))) }
        // padding must be NUL
        val dirty = good.copyOf(good.size - 20)
        val nul = (12 until dirty.size).first { dirty[it] == 0.toByte() && it > 12 + 62 }
        dirty[nul] = 1
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(seal(dirty)) }
    }

    @Test fun `unsafe paths in an index file are refused on read`() {
        for (bad in listOf("../evil", ".git/config", "a/../b", "/abs", "a//b", "C:/x", "back\\slash", "dir/.GIT/hooks", "x/git~1/y")) {
            val body = rawIndex(bad)
            expect(GitErrorKind.UNSAFE_PATH) { GitIndex.parse(seal(body)) }
        }
    }

    @Test fun `unsorted or duplicate entries are refused`() {
        val a = rawIndex("a", "b"); val dup = rawIndex("a", "a"); val rev = rawIndex("b", "a")
        GitIndex.parse(seal(a))
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(seal(dup)) }
        expect(GitErrorKind.MALFORMED) { GitIndex.parse(seal(rev)) }
    }

    private fun rawIndex(vararg paths: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun u32(v: Long) { o.write((v ushr 24).toInt() and 0xff); o.write((v ushr 16).toInt() and 0xff); o.write((v ushr 8).toInt() and 0xff); o.write(v.toInt() and 0xff) }
        o.write("DIRC".toByteArray()); u32(2); u32(paths.size.toLong())
        for (p in paths) {
            val start = o.size()
            repeat(6) { u32(0) }; u32(FileMode.REGULAR.toLong()); u32(0); u32(0); u32(0)
            o.write(ByteArray(20)); val nb = p.toByteArray()
            val fl = minOf(nb.size, 0xfff); o.write(fl ushr 8); o.write(fl and 0xff); o.write(nb)
            repeat(8 - ((o.size() - start) % 8)) { o.write(0) }
        }
        return o.toByteArray()
    }

    @Test fun `writeTree builds nested trees and rejects conflicts`() {
        val t = TestRepo()
        val blob = t.repo.db.write(ObjectType.BLOB, "x".toByteArray())
        val ix = GitIndex()
        ix.put(IndexEntry("a/b/c.txt", blob, FileMode.REGULAR)); ix.put(IndexEntry("a/d", blob, FileMode.EXECUTABLE)); ix.put(IndexEntry("z", blob, FileMode.REGULAR))
        val root = ix.writeTree(t.repo.db)
        assertEquals(setOf("a/b/c.txt", "a/d", "z"), t.repo.db.flattenTree(root).keys)
        ix.put(IndexEntry("c", blob, FileMode.REGULAR, 1))
        expect(GitErrorKind.INVALID_ARGUMENT) { ix.writeTree(t.repo.db) }
    }

    @Test fun `index write takes a lock and a held lock is typed`() {
        val t = TestRepo()
        val ix = GitIndex(); ix.put(entry("a"))
        ix.write(t.repo.fs, t.repo.indexPath)
        assertEquals(1, GitIndex.read(t.repo.fs, t.repo.indexPath).size)
        java.io.File(t.repo.indexPath + ".lock").writeText("x")
        expect(GitErrorKind.LOCKED) { ix.write(t.repo.fs, t.repo.indexPath) }
    }
}
