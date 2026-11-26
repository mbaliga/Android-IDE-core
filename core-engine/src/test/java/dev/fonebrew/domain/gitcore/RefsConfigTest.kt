package dev.fonebrew.domain.gitcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RefsConfigTest {
    private val fs = JvmFileSystem()
    private fun expect(kind: GitErrorKind, block: () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: GitException) { assertEquals(kind, e.kind) }
    }
    private val a = ObjectId.fromHex("a".repeat(40))
    private val b = ObjectId.fromHex("b".repeat(40))

    @Test fun `ref name rules`() {
        val good = listOf("refs/heads/main", "refs/heads/feature/x-1", "refs/tags/v1.0", "HEAD", "refs/remotes/origin/main", "refs/heads/a.b")
        good.forEach { assertTrue(it, RefName.isValid(it)) }
        val bad = listOf(
            "", "main", "refs/", "refs/heads/", "refs/heads//x", "refs/heads/x/", "/refs/heads/x", "refs/heads/.hidden", "refs/heads/a..b",
            "refs/heads/a b", "refs/heads/a~1", "refs/heads/a^", "refs/heads/a:b", "refs/heads/a?b", "refs/heads/a*b", "refs/heads/a[b",
            "refs/heads/a\\b", "refs/heads/x.lock", "refs/heads/x.", "refs/heads/a@{b}", "refs/heads/a\u0001", "refs/heads/a\u007f",
            "refs/heads/.git", "refs/heads/GIT~1", "refs/heads/x/.y", "../x", "refs/heads/../../etc/passwd", "refs/heads/a\nb",
        )
        bad.forEach { assertFalse("'$it' must be rejected", RefName.isValid(it)) }
        assertNotNull(RefName.shortViolation("@"))
        assertNotNull(RefName.shortViolation("-x"))
        assertNull(RefName.shortViolation("feat/ok"))
    }

    @Test fun `update with compare and swap and lock files`() {
        val t = TestRepo()
        val r = t.repo.refs
        r.update("refs/heads/x", a, Expect.Absent)
        assertEquals(a, r.resolve("refs/heads/x"))
        expect(GitErrorKind.REF_CONFLICT) { r.update("refs/heads/x", b, Expect.Absent) }
        expect(GitErrorKind.REF_CONFLICT) { r.update("refs/heads/x", b, Expect.Id(b)) }
        r.update("refs/heads/x", b, Expect.Id(a))
        assertEquals(b, r.resolve("refs/heads/x"))
        // A held lock makes a second writer fail typed, and the lock is not left behind by a failed CAS.
        assertTrue(fs.createExclusive("${t.gitDir()}/refs/heads/x.lock", ByteArray(0)))
        expect(GitErrorKind.LOCKED) { r.update("refs/heads/x", a) }
        fs.delete("${t.gitDir()}/refs/heads/x.lock")
        expect(GitErrorKind.REF_CONFLICT) { r.update("refs/heads/x", a, Expect.Id(a)) }
        assertFalse(java.io.File(t.gitDir(), "refs/heads/x.lock").exists())
        expect(GitErrorKind.INVALID_REF_NAME) { r.update("refs/heads/../escape", a) }
        assertFalse(java.io.File(t.dir, "escape").exists())
    }

    @Test fun `directory file ref conflicts are refused and empty dirs are reclaimed`() {
        val t = TestRepo(); val r = t.repo.refs
        r.update("refs/heads/a", a)
        expect(GitErrorKind.REF_CONFLICT) { r.update("refs/heads/a/b", b) }
        r.delete("refs/heads/a")
        r.update("refs/heads/a/b", b)
        expect(GitErrorKind.REF_CONFLICT) { r.update("refs/heads/a", a) }
        r.delete("refs/heads/a/b")
        r.update("refs/heads/a", a) // empty dir a/ was pruned
        assertEquals(a, r.resolve("refs/heads/a"))
    }

    @Test fun `symbolic HEAD follows updates and detach writes the id`() {
        val t = TestRepo(); val r = t.repo.refs
        assertEquals("refs/heads/main", r.headBranch())
        assertNull(r.resolve("HEAD")) // unborn
        r.update("HEAD", a) // follows the symref into the branch
        assertEquals(a, r.resolve("refs/heads/main"))
        assertEquals("refs/heads/main", r.headBranch())
        r.detach("HEAD", b)
        assertNull(r.headBranch())
        assertEquals(b, r.resolve("HEAD"))
        r.setSymbolic("HEAD", "refs/heads/main")
        assertEquals(a, r.resolve("HEAD"))
        expect(GitErrorKind.INVALID_REF_NAME) { r.setSymbolic("HEAD", "../../x") }
    }

    @Test fun `symbolic cycles are detected`() {
        val t = TestRepo(); val r = t.repo.refs
        r.setSymbolic("refs/heads/p", "refs/heads/q")
        r.setSymbolic("refs/heads/q", "refs/heads/p")
        expect(GitErrorKind.MALFORMED) { r.resolve("refs/heads/p") }
    }

    @Test fun `packed refs are read and loose wins`() {
        val t = TestRepo(); val r = t.repo.refs
        java.io.File(t.gitDir(), "packed-refs").writeText("# pack-refs with: peeled fully-peeled sorted \n${a.hex} refs/heads/p\n${b.hex} refs/tags/t\n^${a.hex}\n")
        assertEquals(a, r.resolve("refs/heads/p"))
        assertEquals(mapOf("refs/heads/p" to a), r.list("refs/heads/"))
        r.update("refs/heads/p", b)
        assertEquals(b, r.resolve("refs/heads/p"))
        r.delete("refs/heads/p")
        assertNull(r.resolve("refs/heads/p"))
        assertEquals(b, r.resolve("refs/tags/t"))
        java.io.File(t.gitDir(), "packed-refs").writeText("zzzz refs/heads/x\n")
        expect(GitErrorKind.MALFORMED) { r.list("refs/") }
    }

    @Test fun `packAll then read`() {
        val t = TestRepo(); val r = t.repo.refs
        r.update("refs/heads/one", a); r.update("refs/heads/two/x", b)
        r.packAll()
        assertEquals(a, r.resolve("refs/heads/one"))
        assertEquals(b, r.resolve("refs/heads/two/x"))
        assertFalse(java.io.File(t.gitDir(), "refs/heads/one").exists())
    }

    @Test fun `hostile loose ref content is typed`() {
        val t = TestRepo(); val r = t.repo.refs
        java.io.File(t.gitDir(), "refs/heads/bad").writeText("not an id\n")
        expect(GitErrorKind.MALFORMED) { r.resolve("refs/heads/bad") }
        java.io.File(t.gitDir(), "refs/heads/bad").writeText("ref: ../../../etc/passwd\n")
        expect(GitErrorKind.INVALID_REF_NAME) { r.resolve("refs/heads/bad") }
        java.io.File(t.gitDir(), "refs/heads/huge").writeBytes(ByteArray(100_000) { 'a'.code.toByte() })
        expect(GitErrorKind.LIMIT_EXCEEDED) { r.resolve("refs/heads/huge") }
    }

    @Test fun `reflog appends and reads newest first`() {
        val t = TestRepo(); val r = t.repo.refs
        val who = ReflogIdentity("N", "n@x", 100, 60)
        r.update("refs/heads/m", a, Expect.Absent, who, "first")
        r.update("refs/heads/m", b, Expect.Id(a), who, "second\nline")
        val log = r.reflog("refs/heads/m")
        assertEquals(listOf("second line", "first"), log.map { it.message })
        assertEquals(a, log[0].oldId); assertEquals(b, log[0].newId)
        assertTrue(log[1].oldId.isZero)
        assertEquals(60, log[0].who.tzMinutes)
    }

    @Test fun `init non-bare and bare layouts and refuses re-init`() {
        val t = TestRepo()
        for (p in listOf("HEAD", "config", "objects/pack", "objects/info", "refs/heads", "refs/tags")) assertTrue(p, java.io.File(t.gitDir(), p).exists())
        expect(GitErrorKind.INVALID_ARGUMENT) { Repository.init(fs, t.dir.absolutePath) }
        val b = TestRepo(bare = true)
        assertTrue(b.repo.isBare)
        assertTrue(java.io.File(b.dir, "HEAD").exists())
        expect(GitErrorKind.INVALID_ARGUMENT) { b.repo.requireWorkTree() }
        val reopened = Repository.open(fs, b.dir.absolutePath)
        assertTrue(reopened.isBare)
        expect(GitErrorKind.NOT_FOUND) { Repository.open(fs, GitTestSupport.tempDir().absolutePath) }
        expect(GitErrorKind.INVALID_REF_NAME) { Repository.init(fs, GitTestSupport.tempDir().absolutePath, initialBranch = "bad name") }
    }

    @Test fun `open refuses sha256 gitfile and unknown formats`() {
        val t = TestRepo()
        t.repo.config.set("extensions", null, "objectformat", "sha256"); t.repo.saveConfig()
        expect(GitErrorKind.UNSUPPORTED) { Repository.open(fs, t.dir.absolutePath) }
        val g = GitTestSupport.tempDir()
        java.io.File(g, ".git").writeText("gitdir: /elsewhere\n")
        expect(GitErrorKind.UNSUPPORTED) { Repository.open(fs, g.absolutePath) }
        val v = TestRepo(); v.repo.config.set("core", null, "repositoryformatversion", "7"); v.repo.saveConfig()
        expect(GitErrorKind.UNSUPPORTED) { Repository.open(fs, v.dir.absolutePath) }
    }

    @Test fun `config parse supports sections subsections quoting comments and continuation`() {
        val c = GitConfig.parseText("""
            # comment
            [core]
                bare = false
                Editor = "vi # not comment" ; trailing
            [remote "origin"]
                url = https://example.com/a.git
                fetch = +refs/heads/*:refs/remotes/origin/*
                fetch = +refs/tags/*:refs/tags/*
            [branch "feat/x"]
                remote = origin
                merge = refs/heads/feat/x
            [user]
                name = A \"Q\" B
                email = a@b
                long = one \
                two
            [Flag]
                enabled
        """.trimIndent())
        assertEquals("false", c.get("core", null, "bare"))
        assertEquals("vi # not comment", c.get("core", null, "editor"))
        assertEquals("https://example.com/a.git", c.remoteUrl("origin"))
        assertEquals(2, c.getAll("remote", "origin", "fetch").size)
        assertEquals("origin", c.branchRemote("feat/x"))
        assertEquals("refs/heads/feat/x", c.branchMerge("feat/x"))
        assertEquals("A \"Q\" B", c.userName)
        assertEquals("one two", c.get("user", null, "long"))
        assertTrue(c.getBoolean("flag", null, "enabled", false))
        assertEquals(listOf("origin"), c.remoteNames())
        assertNull(c.get("remote", "ORIGIN", "url")) // subsections are case sensitive
    }

    @Test fun `config set unset and remove section preserve unrelated text`() {
        val c = GitConfig.parseText("# keep me\n[core]\n\tbare = false\n[remote \"origin\"]\n\turl = u1\n")
        c.set("remote", "origin", "url", "u2")
        c.set("remote", "upstream", "url", "u3 # not a comment")
        c.set("user", null, "name", " padded ")
        assertEquals("u2", c.remoteUrl("origin"))
        assertEquals("u3 # not a comment", c.remoteUrl("upstream"))
        assertEquals(" padded ", c.userName)
        val re = GitConfig.parseText(c.toText())
        assertEquals("u3 # not a comment", re.remoteUrl("upstream"))
        assertEquals(" padded ", re.userName)
        assertTrue(c.toText().startsWith("# keep me\n"))
        c.unset("core", null, "bare")
        assertNull(c.get("core", null, "bare"))
        c.removeSection("remote", "origin")
        assertNull(c.remoteUrl("origin"))
        assertEquals("u3 # not a comment", c.remoteUrl("upstream"))
        expect(GitErrorKind.INVALID_ARGUMENT) { c.set("bad section", null, "k", "v") }
        expect(GitErrorKind.INVALID_ARGUMENT) { c.set("core", null, "1bad", "v") }
    }

    @Test fun `identity is never invented`() {
        val t = TestRepo()
        t.repo.config.unset("user", null, "name"); t.repo.config.unset("user", null, "email")
        expect(GitErrorKind.INVALID_ARGUMENT) { t.repo.identity() }
        assertEquals(Identity("X", "x@y"), t.repo.identity(Identity("X", "x@y")))
    }
}
