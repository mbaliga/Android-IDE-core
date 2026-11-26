package dev.fonebrew.domain.gitcore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class GitObjectsTest {
    private val rnd = Random(42)

    private fun randId() = ObjectId(ByteArray(20).also { rnd.nextBytes(it) })
    private fun randName(): String {
        val alphabet = "abcXYZ019._-é世 "
        val len = 1 + rnd.nextInt(12)
        return (0 until len).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }
    private fun expectError(kind: GitErrorKind, block: () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: GitException) { assertEquals(kind, e.kind) }
    }

    @Test fun `well-known empty blob and empty tree ids`() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", Blob(ByteArray(0)).id().hex)
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", Tree(emptyList()).id().hex)
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", Blob("hello\n".toByteArray()).id().hex)
    }

    @Test fun `object id hex round trips and rejects junk`() {
        repeat(100) { val id = randId(); assertEquals(id, ObjectId.fromHex(id.hex)); assertEquals(id, ObjectId.fromHex(id.hex.uppercase())) }
        assertNull(ObjectId.fromHexOrNull("zz"))
        assertNull(ObjectId.fromHexOrNull("a".repeat(39)))
        assertTrue(HashAlgorithm.SHA1.zero().isZero)
    }

    @Test fun `tree round trips over random entries and sorts directories as if slash-terminated`() {
        repeat(200) {
            val entries = (0 until rnd.nextInt(30)).map {
                val mode = listOf(FileMode.REGULAR, FileMode.EXECUTABLE, FileMode.SYMLINK, FileMode.TREE, FileMode.GITLINK)[rnd.nextInt(5)]
                TreeEntry(mode, randName() + it, randId())
            }
            val t = Tree(entries)
            val bytes = t.body()
            val back = Tree.parse(bytes)
            assertEquals(t.entries, back.entries)
            assertArrayEquals(bytes, back.body())
            assertEquals(t.id(), back.id())
        }
        // "a" (tree) sorts as "a/" so it falls after "a.b" but before "a0".
        val t = Tree(listOf(TreeEntry(FileMode.TREE, "a", randId()), TreeEntry(FileMode.REGULAR, "a.b", randId()), TreeEntry(FileMode.REGULAR, "a0", randId())))
        assertEquals(listOf("a.b", "a", "a0"), t.entries.map { it.name })
        // a file named "a" sorts before "a.b".
        val t2 = Tree(listOf(TreeEntry(FileMode.REGULAR, "a.b", randId()), TreeEntry(FileMode.REGULAR, "a", randId())))
        assertEquals(listOf("a", "a.b"), t2.entries.map { it.name })
    }

    @Test fun `tree mode is written without leading zero and zero-padded input keeps its true hash`() {
        val id = randId()
        val body = "40000 d\u0000".toByteArray() + id.toByteArray()
        assertEquals(body.toList(), Tree(listOf(TreeEntry(FileMode.TREE, "d", id))).body().toList())
        val padded = "040000 d\u0000".toByteArray() + id.toByteArray()
        val parsed = Tree.parse(padded)
        assertEquals(FileMode.TREE, parsed.entries[0].mode)
        assertArrayEquals(padded, parsed.body())
    }

    @Test fun `non utf8 tree names survive byte exact`() {
        val name = byteArrayOf(0x66, 0xE9.toByte(), 0xFF.toByte())
        val id = randId()
        val body = "100644 ".toByteArray() + name + byteArrayOf(0) + id.toByteArray()
        val t = Tree.parse(body)
        assertArrayEquals(name, t.entries[0].nameBytes)
        assertArrayEquals(body, t.body())
    }

    @Test fun `malformed trees return typed errors`() {
        val id = randId().toByteArray()
        expectError(GitErrorKind.MALFORMED) { Tree.parse("100644 a".toByteArray()) }
        expectError(GitErrorKind.MALFORMED) { Tree.parse("100644 a\u0000".toByteArray() + id.copyOf(5)) }
        expectError(GitErrorKind.MALFORMED) { Tree.parse("10x644 a\u0000".toByteArray() + id) }
        expectError(GitErrorKind.MALFORMED) { Tree.parse("100644 \u0000".toByteArray() + id) }
        expectError(GitErrorKind.MALFORMED) { Tree.parse(" a\u0000".toByteArray() + id) }
        expectError(GitErrorKind.MALFORMED) { Tree.parse("1006440000 a\u0000".toByteArray() + id) }
        expectError(GitErrorKind.LIMIT_EXCEEDED) {
            val one = "100644 a\u0000".toByteArray() + id
            Tree.parse(one + one, GitLimits(maxTreeEntries = 1))
        }
    }

    @Test fun `signature formats and parses including negative and half-hour zones`() {
        for (tz in listOf(0, 330, -480, 765, -30)) {
            val s = Signature("A B", "a@b.c", 1_700_000_000L, tz)
            assertEquals(s, Signature.parse(s.format()))
        }
        assertEquals("A <a@b> 5 -0530", Signature("A", "a@b", 5, -330).format())
        expectError(GitErrorKind.MALFORMED) { Signature.parse("no email 1 +0000") }
        expectError(GitErrorKind.MALFORMED) { Signature.parse("A <a> x +0000") }
        expectError(GitErrorKind.MALFORMED) { Signature.parse("A <a> 1 +00") }
    }

    @Test fun `commit round trips with parents encoding and multi-line gpgsig`() {
        val sig = "-----BEGIN PGP SIGNATURE-----\n\nabcdef\nghijkl\n-----END PGP SIGNATURE-----"
        val c = Commit(
            randId(), listOf(randId(), randId()),
            Signature("Au Thor", "au@x.y", 1_600_000_000, 120), Signature("Co Mitter", "co@x.y", 1_600_000_100, -300),
            "subject\n\nbody line\n",
            listOf("encoding" to "ISO-8859-1", "gpgsig" to sig),
        )
        val back = Commit.parse(c.body())
        assertEquals(c, back)
        assertEquals(sig, back.gpgSignature)
        assertEquals("ISO-8859-1", back.encoding)
        assertEquals("subject", back.summary)
        assertArrayEquals(c.body(), back.body())
        // The blank continuation line must be a single space on disk.
        assertTrue(String(c.body()).contains("gpgsig -----BEGIN PGP SIGNATURE-----\n \n abcdef"))
    }

    @Test fun `commit mergetag with nested continuation round trips and parsed commit keeps exact bytes`() {
        val raw = ("tree ${randId().hex}\nparent ${randId().hex}\nauthor A <a@b> 1 +0000\ncommitter A <a@b> 1 +0000\n" +
            "mergetag object ${randId().hex}\n type commit\n tag v1\n \n msg\n\nmerge it\n").toByteArray()
        val c = Commit.parse(raw)
        assertEquals(1, c.extraHeaders.size)
        assertArrayEquals(raw, c.body())
        assertEquals(Commit.parse(c.body()), c)
    }

    @Test fun `random commits round trip`() {
        repeat(100) {
            val c = Commit(randId(), (0 until rnd.nextInt(4)).map { randId() },
                Signature(randName().trim().ifEmpty { "x" }, "e${rnd.nextInt()}@h", rnd.nextInt(Int.MAX_VALUE).toLong(), (rnd.nextInt(49) - 24) * 30),
                Signature(randName().trim().ifEmpty { "x" }, "c@h", rnd.nextInt(Int.MAX_VALUE).toLong(), 0),
                randName() + "\n\n" + randName())
            assertEquals(c, Commit.parse(c.body()))
            assertEquals(c.id(), Commit.parse(c.body()).id())
        }
    }

    @Test fun `non utf8 commit message keeps its hash`() {
        val head = "tree ${randId().hex}\nauthor A <a@b> 1 +0000\ncommitter A <a@b> 1 +0000\n\n".toByteArray()
        val raw = head + byteArrayOf(0xE9.toByte(), 0xFF.toByte(), 0x0A)
        assertArrayEquals(raw, Commit.parse(raw).body())
    }

    @Test fun `malformed commits return typed errors`() {
        expectError(GitErrorKind.MALFORMED) { Commit.parse("".toByteArray()) }
        expectError(GitErrorKind.MALFORMED) { Commit.parse("tree zz\n\n".toByteArray()) }
        expectError(GitErrorKind.MALFORMED) { Commit.parse(" cont\n\n".toByteArray()) }
        expectError(GitErrorKind.MALFORMED) { Commit.parse("tree ${randId().hex}\n\nmsg".toByteArray()) }
        expectError(GitErrorKind.MALFORMED) { Commit.parse("tree ${randId().hex}\nauthor A <a> 1 +0000".toByteArray()) }
        expectError(GitErrorKind.LIMIT_EXCEEDED) { Commit.parse(ByteArray(100), GitLimits(maxCommitBytes = 10)) }
    }

    @Test fun `tag round trips`() {
        val t = Tag(randId(), ObjectType.COMMIT, "v1.0", Signature("T", "t@x", 99, 60), "release\n")
        assertEquals(t, Tag.parse(t.body()))
        val lightweightLess = Tag(randId(), ObjectType.TREE, "x", null, "")
        assertEquals(lightweightLess, Tag.parse(lightweightLess.body()))
        expectError(GitErrorKind.MALFORMED) { Tag.parse("object ${randId().hex}\ntype nope\ntag x\n\n".toByteArray()) }
        expectError(GitErrorKind.MALFORMED) { Tag.parse("type commit\n\n".toByteArray()) }
    }

    @Test fun `zlib inflate enforces caps and detects truncation`() {
        val data = ByteArray(10_000) { 7 }
        val z = Zlib.deflate(data)
        assertArrayEquals(data, Zlib.inflate(z, 0, z.size, 1_000_000, 10_000).data)
        expectError(GitErrorKind.LIMIT_EXCEEDED) { Zlib.inflate(z, 0, z.size, 100) }
        expectError(GitErrorKind.MALFORMED) { Zlib.inflate(z, 0, z.size, 1_000_000, 9_999) }
        expectError(GitErrorKind.MALFORMED) { Zlib.inflate(z, 0, z.size - 4, 1_000_000) }
        expectError(GitErrorKind.MALFORMED) { Zlib.inflate(ByteArray(8) { 0x55 }, 0, 8, 1000) }
        // A 1 GiB zip bomb is refused at the cap without allocating it.
        val bomb = Zlib.deflate(ByteArray(8 * 1024 * 1024))
        expectError(GitErrorKind.LIMIT_EXCEEDED) { Zlib.inflate(bomb, 0, bomb.size, 1024 * 1024) }
    }
}
