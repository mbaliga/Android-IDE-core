package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.domain.gitcore.GitTestSupport
import dev.fonebrew.domain.gitcore.ObjectId
import dev.fonebrew.domain.gitcore.ObjectType
import dev.fonebrew.domain.gitcore.client.ClientEnv.assertNoRepoAt
import dev.fonebrew.domain.gitcore.client.ClientEnv.client
import dev.fonebrew.domain.gitcore.client.ClientEnv.clone
import dev.fonebrew.domain.gitcore.client.ClientEnv.failure
import dev.fonebrew.domain.gitcore.client.ClientEnv.seed
import dev.fonebrew.domain.gitcore.client.ClientEnv.serverCommit
import dev.fonebrew.domain.gitcore.transport.GitHttpFixture
import dev.fonebrew.domain.gitcore.transport.GitHttpFixture.Answer
import dev.fonebrew.domain.gitcore.transport.PktLineWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A malicious or broken server (a REAL git server whose answers are tampered with in flight). The
 * client must refuse, say why in a typed way, leave every ref exactly as it was, and (for a clone)
 * leave nothing at the target path.
 */
class HostileServerTest {
    private lateinit var fx: GitHttpFixture
    private lateinit var work: File
    private fun dest() = File(work, "c")

    @Before fun setUp() {
        assumeTrue("git http-backend is not available in this environment", ClientEnv.available)
        fx = GitHttpFixture(); work = GitTestSupport.tempDir("hostile")
    }
    @After fun tearDown() { if (::fx.isInitialized) fx.close() }

    private fun isFetch(m: String, p: String, body: ByteArray) = m == "POST" && p.endsWith("git-upload-pack") && !String(body, Charsets.ISO_8859_1).contains("command=ls-refs")
    private fun band1(b: ByteArray) = b.toList().chunked(60000).map { PktLineWriter.build { data(byteArrayOf(1) + it.toByteArray()) } }.fold(ByteArray(0)) { a, c -> a + c }
    private fun v2Pack(pack: ByteArray) = PktLineWriter.build { text("packfile") } + band1(pack) + "0000".toByteArray()
    private fun v0Pack(pack: ByteArray) = PktLineWriter.build { text("NAK") } + band1(pack) + "0000".toByteArray()
    private fun refs(dir: File) = GitTestSupport.git(dir, "for-each-ref", "--format=%(refname) %(objectname)")

    /** Flips the last byte of the last pack data frame, i.e. a byte of the pack's own checksum trailer. */
    private fun corruptPackTrailer(real: Answer): Answer {
        val b = real.body.copyOf(); var pos = 0; var last = -1
        while (pos + 4 <= b.size) {
            val len = String(b, pos, 4, Charsets.ISO_8859_1).toInt(16)
            if (len < 4) { pos += 4; continue }
            if (len > 4 && b[pos + 4].toInt() == 1) last = pos + len - 1
            pos += len
        }
        check(last > 0) { "no pack frame found in the real answer" }
        b[last] = (b[last] + 1).toByte()
        return Answer(real.status, real.contentType, b)
    }

    private fun hostile(v0: Boolean = false, answer: (real: Answer) -> Answer) {
        fx.v0Only = v0
        fx.tamper = { m, p, body, proxy -> if (isFetch(m, p, body)) answer(proxy()) else null }
    }

    private fun assertCloneRefusedCleanly(expect: (GitFailure) -> Boolean) {
        val f = failure { clone(client(), fx.url("h.git"), dest()) }
        assertTrue("unexpected failure $f", expect(f))
        assertNoRepoAt(work, dest())
    }

    private val integrityOrProtocol = { f: GitFailure -> f is GitFailure.Integrity || f is GitFailure.ProtocolError }

    @Test fun badPackChecksumIsRefusedOnCloneV2() {
        seed(fx, "h.git")
        hostile { real -> corruptPackTrailer(real) }
        assertCloneRefusedCleanly { it is GitFailure.Integrity }
    }

    @Test fun badPackChecksumIsRefusedOnCloneV0() {
        seed(fx, "h.git")
        hostile(v0 = true) { real -> corruptPackTrailer(real) }
        assertCloneRefusedCleanly { it is GitFailure.Integrity }
    }

    @Test fun truncatedAnswerIsRefusedCleanly() {
        seed(fx, "h.git")
        hostile { real -> Answer(real.status, real.contentType, real.body.copyOf(real.body.size / 2)) }
        assertCloneRefusedCleanly(integrityOrProtocol)
    }

    @Test fun aPackThatDoesNotContainTheWantedObjectsIsRefused() {
        seed(fx, "h.git")
        val pack = GitTestSupport.PackBuilder().apply { whole(ObjectType.BLOB, "unrelated".toByteArray()) }.build()
        hostile { real -> Answer(real.status, real.contentType, v2Pack(pack)) }
        assertCloneRefusedCleanly { it is GitFailure.Integrity && it.explanation.contains("Nothing in your repository was changed") }
    }

    @Test fun aDeltaWhoseBaseIsMissingIsRefused() {
        seed(fx, "h.git")
        val pack = GitTestSupport.PackBuilder().apply {
            refDelta(ObjectId.fromHex("ab".repeat(20)), GitTestSupport.makeDelta(ByteArray(10), 0, "abc".toByteArray()))
        }.build()
        hostile { real -> Answer(real.status, real.contentType, v2Pack(pack)) }
        assertCloneRefusedCleanly { it is GitFailure.Integrity }
    }

    @Test fun aCommitWithoutItsTreeAndParentsIsRefusedByTheConnectivityCheck() {
        seed(fx, "h.git")
        val tip = GitTestSupport.git(fx.bare("h.git"), "rev-parse", "refs/heads/main").trim()
        val raw = GitTestSupport.gitBytes(fx.bare("h.git"), "cat-file", "commit", tip)
        val pack = GitTestSupport.PackBuilder().apply { whole(ObjectType.COMMIT, raw) }.build()
        hostile { real -> Answer(real.status, real.contentType, v2Pack(pack)) }
        assertCloneRefusedCleanly { it is GitFailure.Integrity }
    }

    @Test fun anErrorFromTheServerMidStreamIsSurfaced() {
        seed(fx, "h.git")
        hostile { real -> Answer(real.status, real.contentType, PktLineWriter.build { text("packfile"); data(byteArrayOf(3) + "disk quota exceeded".toByteArray()) }) }
        assertCloneRefusedCleanly { it is GitFailure.RemoteError && it.remoteReason.contains("disk quota exceeded") }
    }

    @Test fun refsAreUntouchedAfterAHostileIncrementalFetch() {
        seed(fx, "h.git")
        clone(client(), fx.url("h.git"), dest())
        val before = refs(dest())
        val beforeHead = File(dest(), ".git/HEAD").readText()
        serverCommit(fx, "h.git", "new.txt", "new")
        hostile { real -> corruptPackTrailer(real) }
        val f = failure { runBlocking { client().fetch(dest().absolutePath) } }
        assertTrue(f.toString(), f is GitFailure.Integrity)
        assertEquals(before, refs(dest())); assertEquals(beforeHead, File(dest(), ".git/HEAD").readText())
        GitTestSupport.git(dest(), "fsck", "--full")
        // The same fetch against the honest server then succeeds: the failure left nothing wedged.
        fx.tamper = null
        runBlocking { client().fetch(dest().absolutePath) }
        assertEquals(GitTestSupport.git(fx.bare("h.git"), "rev-parse", "main").trim(), GitTestSupport.git(dest(), "rev-parse", "refs/remotes/origin/main").trim())
    }

    @Test fun refsAreUntouchedAfterAFetchWhoseObjectsAreMissing() {
        seed(fx, "h.git")
        clone(client(), fx.url("h.git"), dest())
        val before = refs(dest())
        serverCommit(fx, "h.git", "new.txt", "new")
        val pack = GitTestSupport.PackBuilder().apply { whole(ObjectType.BLOB, "unrelated2".toByteArray()) }.build()
        hostile { real -> Answer(real.status, real.contentType, v2Pack(pack)) }
        assertTrue(failure { runBlocking { client().fetch(dest().absolutePath) } } is GitFailure.Integrity)
        assertEquals(before, refs(dest()))
    }

    @Test fun hostileRefNamesInTheAdvertisementAreSkippedNotWritten() {
        seed(fx, "h.git", tags = false)
        fx.v0Only = true
        val tip = GitTestSupport.git(fx.bare("h.git"), "rev-parse", "refs/heads/main").trim()
        fx.tamper = { m, p, _, _ ->
            if (m == "GET" && p.endsWith("info/refs")) Answer(200, "application/x-git-upload-pack-advertisement", PktLineWriter.build {
                text("# service=git-upload-pack"); flush()
                data("$tip HEAD\u0000side-band-64k ofs-delta symref=HEAD:refs/heads/main\n".toByteArray())
                text("$tip refs/heads/main"); text("$tip refs/heads/../../evil"); text("$tip refs/heads/a..b"); text("$tip refs/heads/.git/x"); flush()
            }) else null
        }
        val (res, _) = clone(client(), fx.url("h.git"), dest())
        assertEquals("main", res.branch)
        val names = GitTestSupport.git(dest(), "for-each-ref", "--format=%(refname)").lines().filter { it.isNotBlank() }
        assertEquals(listOf("refs/heads/main", "refs/remotes/origin/main"), names.sorted())
        assertFalse(File(work, "evil").exists() || File(dest(), "evil").exists())
    }

    @Test fun aServerThatAnswersWithAnHtmlLoginPageIsNotTreatedAsGit() {
        seed(fx, "h.git")
        fx.tamper = { _, _, _, _ -> Answer(200, "text/html", "<html>please sign in</html>".toByteArray()) }
        assertCloneRefusedCleanly { it is GitFailure.ProtocolError }
    }
}
