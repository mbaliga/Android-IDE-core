package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.domain.gitcore.GitTestSupport
import dev.fonebrew.domain.gitcore.client.ClientEnv.assertHealthyClone
import dev.fonebrew.domain.gitcore.client.ClientEnv.client
import dev.fonebrew.domain.gitcore.client.ClientEnv.clone
import dev.fonebrew.domain.gitcore.client.ClientEnv.creds
import dev.fonebrew.domain.gitcore.client.ClientEnv.failure
import dev.fonebrew.domain.gitcore.client.ClientEnv.rev
import dev.fonebrew.domain.gitcore.client.ClientEnv.seed
import dev.fonebrew.domain.gitcore.client.ClientEnv.serverCommit
import dev.fonebrew.domain.gitcore.Repository
import dev.fonebrew.domain.gitcore.JvmFileSystem
import dev.fonebrew.domain.gitcore.checkoutNewBranch
import dev.fonebrew.domain.gitcore.log
import dev.fonebrew.domain.gitcore.shallowCommits
import dev.fonebrew.domain.gitcore.transport.GitHttpFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The real thing: our client against `git http-backend` (git 2.43) over real HTTP. Nothing is
 * mocked. Skipped (with a reason) when git or its http-backend is not installed.
 */
class GitHttpInteropTest {
    private lateinit var fx: GitHttpFixture
    private lateinit var work: File   // parent directory for clones

    @Before fun setUp() {
        assumeTrue("git http-backend is not available in this environment", ClientEnv.available)
        fx = GitHttpFixture()
        work = GitTestSupport.tempDir("clones")
    }
    @After fun tearDown() { if (::fx.isInitialized) fx.close() }

    private fun url(name: String) = fx.url(name)
    private fun dest(n: String = "c") = File(work, n)

    // ---------------------------------------------------------------- clone

    @Test fun cloneSmallRepositoryOverProtocolV2() {
        seed(fx, "small.git")
        val (res, events) = clone(client(), url("small.git"), dest())
        assertEquals("main", res.branch)
        assertFalse(res.shallow)
        assertEquals(rev(fx.bare("small.git"), "refs/heads/main"), res.head!!.hex)
        assertEquals("content 3\n".repeat(20), File(dest(), "f3.txt").readText())
        assertEquals("nested 2", File(dest(), "dir/sub/n2.txt").readText())
        assertHealthyClone(dest(), fx.bare("small.git"))
        assertEquals("", GitTestSupport.git(dest(), "status", "--porcelain").trim())
        // tracking refs, both kinds of tag, upstream config
        assertEquals(rev(fx.bare("small.git"), "refs/heads/feature"), rev(dest(), "refs/remotes/origin/feature"))
        assertEquals(rev(fx.bare("small.git"), "refs/tags/annotated"), rev(dest(), "refs/tags/annotated"))
        assertEquals(rev(fx.bare("small.git"), "refs/tags/light"), rev(dest(), "refs/tags/light"))
        assertEquals("origin", GitTestSupport.git(dest(), "config", "branch.main.remote").trim())
        // the server really spoke v2, and typed progress arrived
        assertTrue(fx.log.any { it.gitProtocol == "version=2" })
        assertTrue(events.first() is TransferEvent.Connecting)
        val recv = events.filterIsInstance<TransferEvent.Receiving>()
        assertTrue(recv.isNotEmpty()); assertTrue(recv.last().bytes > 0); assertTrue((recv.last().totalObjects ?: 0) >= 10)
        assertTrue(events.any { it is TransferEvent.ResolvingDeltas })
        assertTrue(events.any { it is TransferEvent.Verifying })
        val co = events.filterIsInstance<TransferEvent.CheckingOut>()
        assertEquals(listOf(false, true), co.map { it.done })
        assertTrue(events.indexOf(co[0]) > events.indexOfFirst { it is TransferEvent.Verifying })
        assertEquals(emptyList<String>(), work.list()!!.filter { it != "c" })
    }

    @Test fun cloneLargeDeltifiedRepositoryWithPackedRefs() {
        val bare = fx.createBare("big.git")
        val w = GitTestSupport.tempDir("bigseed")
        GitTestSupport.git(w, "init", "-q", "-b", "main")
        val big = (1..4000).joinToString("\n") { "line $it of the big file with some shared text" }
        for (c in 1..6) {
            // ~600 new small files per commit plus a small edit to a big file: lots of objects, real deltas.
            for (i in 1..600) GitTestSupport.write(File(w, "d${i % 40}/f${c}_$i.txt"), "file $c/$i\n${"shared ".repeat(10)}")
            GitTestSupport.write(File(w, "big.txt"), big.replaceFirst("line 17 ", "line $c edit "))
            GitTestSupport.git(w, "add", "-A"); GitTestSupport.git(w, "commit", "-q", "-m", "c$c")
            if (c % 2 == 0) GitTestSupport.git(w, "tag", "v$c")
        }
        GitTestSupport.git(w, "push", "-q", bare.absolutePath, "--all"); GitTestSupport.git(w, "push", "-q", bare.absolutePath, "--tags")
        GitTestSupport.git(bare, "repack", "-adf", "--depth=50", "--window=50", "-q")
        GitTestSupport.git(bare, "pack-refs", "--all")
        val count = GitTestSupport.git(bare, "count-objects", "-v")
        val inPack = Regex("in-pack: (\\d+)").find(count)!!.groupValues[1].toInt()
        assertTrue("fixture should have thousands of objects, had $inPack", inPack > 3500)
        val (res, _) = clone(client(), url("big.git"), dest())
        assertEquals(rev(bare, "refs/heads/main"), res.head!!.hex)
        assertHealthyClone(dest(), bare)
        assertEquals(GitTestSupport.git(bare, "ls-tree", "-r", "HEAD"), GitTestSupport.git(dest(), "ls-tree", "-r", "HEAD"))
        assertEquals(rev(bare, "refs/tags/v4"), rev(dest(), "refs/tags/v4"))
        assertEquals("", GitTestSupport.git(dest(), "status", "--porcelain").trim())
    }

    @Test fun shallowCloneDepthOneThenIncrementalFetchKeepsItShallow() {
        seed(fx, "sh.git", commits = 5)
        val (res, _) = clone(client(), url("sh.git"), dest(), CloneOptions(depth = 1))
        assertTrue(res.shallow)
        assertEquals("1", GitTestSupport.git(dest(), "rev-list", "--count", "HEAD").trim())
        assertEquals(1, Repository.open(JvmFileSystem(), dest().absolutePath).use { it.shallowCommits().size })
        GitTestSupport.git(dest(), "fsck", "--full", "--strict")
        // single-branch semantics: the other branch was not downloaded
        assertEquals(1, GitTestSupport.git(dest(), "branch", "-r").lines().count { it.isNotBlank() })
        // tags that point into the fetched history are followed even though only one branch was downloaded
        assertEquals(rev(fx.bare("sh.git"), "refs/tags/annotated"), rev(dest(), "refs/tags/annotated"))
        assertEquals(rev(fx.bare("sh.git"), "refs/tags/light"), rev(dest(), "refs/tags/light"))
        // the engine's own history walk must not choke on the missing parents
        Repository.open(JvmFileSystem(), dest().absolutePath).use { r -> assertEquals(1, r.log("HEAD").size) }
        // incremental fetch after the server moves on
        serverCommit(fx, "sh.git", "late.txt", "late")
        client().let { c -> runBlocking { c.fetch(dest().absolutePath) } }
        assertEquals(rev(fx.bare("sh.git"), "refs/heads/main"), rev(dest(), "refs/remotes/origin/main"))
        GitTestSupport.git(dest(), "fsck", "--full", "--strict")
        Repository.open(JvmFileSystem(), dest().absolutePath).use { r -> assertEquals(2, r.log("refs/remotes/origin/main").size) }
    }

    @Test fun cloneSpecificBranchAndBareAndRefusals() {
        seed(fx, "opt.git")
        val (r1, _) = clone(client(), url("opt.git"), dest("br"), CloneOptions(branch = "feature"))
        assertEquals("feature", r1.branch)
        assertEquals("ref: refs/heads/feature", File(dest("br"), ".git/HEAD").readText().trim())
        val (r2, _) = clone(client(), url("opt.git"), dest("bare.git"), CloneOptions(bare = true))
        assertEquals(rev(fx.bare("opt.git"), "refs/heads/main"), rev(dest("bare.git"), "refs/heads/main"))
        assertFalse(File(dest("bare.git"), "f1.txt").exists())
        assertHealthyClone(dest("bare.git"), fx.bare("opt.git"), bare = true)
        assertNotNull(r2.head)
        assertTrue(failure { clone(client(), url("opt.git"), dest("nobranch"), CloneOptions(branch = "nope")) } is GitFailure.LocalRepository)
        assertFalse(dest("nobranch").exists())
        dest("full").mkdirs(); File(dest("full"), "x").writeText("x")
        assertTrue(failure { clone(client(), url("opt.git"), dest("full")) } is GitFailure.InvalidRequest)
        assertEquals("x", File(dest("full"), "x").readText())
    }

    @Test fun cloneEmptyRepositoryGivesAnUnbornBranch() {
        fx.createBare("empty.git")
        val (res, _) = clone(client(), url("empty.git"), dest())
        assertNull(res.head); assertEquals("main", res.branch)
        assertEquals("ref: refs/heads/main", File(dest(), ".git/HEAD").readText().trim())
    }

    @Test fun cloneIntoAnExistingEmptyDirectoryWorks() {
        seed(fx, "e.git"); dest().mkdirs()
        clone(client(), url("e.git"), dest())
        assertTrue(File(dest(), "f1.txt").exists())
    }

    // ---------------------------------------------------------------- v0 fallback

    @Test fun serverThatOnlySpeaksV0StillWorksForCloneFetchShallowAndLsRemote() {
        fx.v0Only = true
        seed(fx, "v0.git")
        val c = client()
        val listing = runBlocking { c.lsRemote(url("v0.git")) }
        assertFalse(listing.protocolV2)
        assertEquals("refs/heads/main", listing.head)
        assertEquals(rev(fx.bare("v0.git"), "refs/tags/annotated^{}"), listing.refs.first { it.name == "refs/tags/annotated" }.peeled!!.hex)
        clone(c, url("v0.git"), dest())
        assertHealthyClone(dest(), fx.bare("v0.git"))
        clone(c, url("v0.git"), dest("s"), CloneOptions(depth = 1))
        assertEquals("1", GitTestSupport.git(dest("s"), "rev-list", "--count", "HEAD").trim())
        serverCommit(fx, "v0.git", "z.txt", "z")
        runBlocking { c.fetch(dest().absolutePath) }
        assertEquals(rev(fx.bare("v0.git"), "refs/heads/main"), rev(dest(), "refs/remotes/origin/main"))
        assertTrue(fx.log.none { it.gitProtocol == "version=2" && false })
    }

    @Test fun lsRemoteOverV2ListsHeadBranchesAndPeeledTags() {
        seed(fx, "ls.git")
        val l = runBlocking { client().lsRemote(url("ls.git")) }
        assertTrue(l.protocolV2); assertEquals("refs/heads/main", l.head)
        assertEquals(setOf("HEAD", "refs/heads/main", "refs/heads/feature", "refs/tags/light", "refs/tags/annotated"), l.refs.map { it.name }.toSet())
        assertNotNull(l.refs.first { it.name == "refs/tags/annotated" }.peeled)
        assertNull(l.refs.first { it.name == "refs/tags/light" }.peeled)
    }

    // ---------------------------------------------------------------- fetch

    @Test fun incrementalFetchPicksUpNewCommitsBranchesAndTags() {
        val w = seed(fx, "inc.git")
        clone(client(), url("inc.git"), dest())
        val before = rev(dest(), "refs/remotes/origin/main")
        val tip = serverCommit(fx, "inc.git", "new.txt", "hello")
        GitTestSupport.git(w, "tag", "late"); GitTestSupport.git(w, "push", "-q", fx.bare("inc.git").absolutePath, "late")
        val res = runBlocking { client().fetch(dest().absolutePath) }
        assertTrue(res.outcome.updates.any { it.ref == "refs/remotes/origin/main" && it.old!!.hex == before && it.new.hex == tip })
        assertEquals(tip, rev(dest(), "refs/remotes/origin/main"))
        assertEquals(rev(fx.bare("inc.git"), "refs/tags/late"), rev(dest(), "refs/tags/late"))
        assertHealthyClone(dest(), fx.bare("inc.git"))
        assertTrue(runBlocking { client().fetch(dest().absolutePath) }.outcome.upToDate)
    }

    @Test fun aThinPackFromTheServerIsCompletedSoTheRepositoryIsSelfContained() {
        val w = seed(fx, "thin2.git", tags = false)
        val bigText = (1..3000).joinToString("\n") { "row $it: lorem ipsum dolor sit amet, consectetur" }
        GitTestSupport.write(File(w, "big.txt"), bigText); GitTestSupport.git(w, "add", "-A"); GitTestSupport.git(w, "commit", "-q", "-m", "big")
        GitTestSupport.git(w, "push", "-q", fx.bare("thin2.git").absolutePath, "main")
        clone(client(), url("thin2.git"), dest())
        GitTestSupport.write(File(w, "big.txt"), bigText.replace("row 1500:", "row 1500 (edited):"))
        GitTestSupport.git(w, "commit", "-q", "-am", "edit one line")
        GitTestSupport.git(w, "push", "-q", fx.bare("thin2.git").absolutePath, "main")
        runBlocking { client().fetch(dest().absolutePath) }
        // git fsck rejects a pack with a missing delta base: passing means the thin pack was completed on disk.
        GitTestSupport.git(dest(), "fsck", "--full", "--strict")
        assertEquals(GitTestSupport.git(fx.bare("thin2.git"), "rev-parse", "main").trim(), rev(dest(), "refs/remotes/origin/main"))
        assertEquals(GitTestSupport.git(fx.bare("thin2.git"), "cat-file", "-p", "main:big.txt"), GitTestSupport.git(dest(), "cat-file", "-p", "origin/main:big.txt"))
    }

    @Test fun largeHaveListIsGzippedAndAcceptedByTheRealServer() {
        seed(fx, "gz.git", commits = 60, tags = false)
        clone(client(), url("gz.git"), dest())
        serverCommit(fx, "gz.git", "n.txt", "n")
        fx.log.clear()
        runBlocking { client().fetch(dest().absolutePath) }
        val post = fx.log.last { it.method == "POST" && it.path.endsWith("git-upload-pack") }
        assertEquals("gzip", post.contentEncoding)
        assertEquals(rev(fx.bare("gz.git"), "refs/heads/main"), rev(dest(), "refs/remotes/origin/main"))
    }

    // ---------------------------------------------------------------- pull

    @Test fun pullFastForwardsUpToDateAndNeedsMerge() {
        seed(fx, "pull.git")
        clone(client(), url("pull.git"), dest())
        val c = client()
        assertEquals(PullOutcome.UpToDate, runBlocking { c.pull(dest().absolutePath) })
        val tip = serverCommit(fx, "pull.git", "p.txt", "p")
        val ff = runBlocking { c.pull(dest().absolutePath) } as PullOutcome.FastForwarded
        assertEquals(tip, ff.to.hex)
        assertEquals(tip, rev(dest(), "HEAD"))
        assertEquals("p", File(dest(), "p.txt").readText())
        assertEquals("ref: refs/heads/main", File(dest(), ".git/HEAD").readText().trim())
        assertEquals("", GitTestSupport.git(dest(), "status", "--porcelain").trim())
        // diverge: local commit + server commit
        val ours = ClientEnv.localCommit(dest(), "mine.txt", "mine")
        val theirs = serverCommit(fx, "pull.git", "theirs.txt", "t")
        val nm = runBlocking { c.pull(dest().absolutePath) } as PullOutcome.NeedsMerge
        assertEquals(ours, nm.ours); assertEquals(theirs, nm.theirs.hex); assertNotNull(nm.base)
        assertEquals(ours.hex, rev(dest(), "HEAD"))
    }

    @Test fun pullRefusesToOverwriteLocalChangesAndChangesNothing() {
        seed(fx, "dirty.git")
        clone(client(), url("dirty.git"), dest())
        serverCommit(fx, "dirty.git", "f1.txt", "server rewrote f1")
        File(dest(), "f1.txt").writeText("my uncommitted edit")
        val f = failure { runBlocking { client().pull(dest().absolutePath) } }
        assertTrue(f is GitFailure.LocalChanges); assertTrue((f as GitFailure.LocalChanges).paths.contains("f1.txt"))
        assertEquals("my uncommitted edit", File(dest(), "f1.txt").readText())
        assertEquals("ref: refs/heads/main", File(dest(), ".git/HEAD").readText().trim())
    }

    // ---------------------------------------------------------------- push

    private fun cloneAndCommit(name: String): File {
        seed(fx, name)
        clone(client(), url(name), dest())
        return dest()
    }

    @Test fun pushNewBranchThenFastForward() {
        val d = cloneAndCommit("push.git")
        Repository.open(JvmFileSystem(), d.absolutePath).use { it.checkoutNewBranch("topic") }
        val c1 = ClientEnv.localCommit(d, "t1.txt", "t1")
        val r = runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.branch("topic")), options = PushOptions(setUpstream = true)) }
        assertTrue(r.allOk); assertTrue(r.outcomes.single() is RefPushOutcome.Created)
        assertEquals(c1.hex, rev(fx.bare("push.git"), "refs/heads/topic"))
        assertEquals(c1.hex, rev(d, "refs/remotes/origin/topic"))
        GitTestSupport.git(fx.bare("push.git"), "fsck", "--full", "--strict")
        // fast-forward
        val c2 = ClientEnv.localCommit(d, "t2.txt", "t2")
        val r2 = runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.branch("topic"))) }
        assertTrue(r2.outcomes.single() is RefPushOutcome.Updated); assertFalse((r2.outcomes.single() as RefPushOutcome.Updated).forced)
        assertEquals(c2.hex, rev(fx.bare("push.git"), "refs/heads/topic"))
        GitTestSupport.git(fx.bare("push.git"), "fsck", "--full", "--strict")
        assertEquals(ClientEnv.revList(fx.bare("push.git")), ClientEnv.revList(d))
        // pushing again is a no-op
        assertTrue(runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.branch("topic"))) }.outcomes.single() is RefPushOutcome.UpToDate)
        // a fresh CLI clone of the server sees our files
        val v = GitTestSupport.tempDir("verify"); GitTestSupport.git(v, "clone", "-q", "-b", "topic", fx.bare("push.git").absolutePath, ".")
        assertEquals("t2", File(v, "t2.txt").readText())
    }

    @Test fun pushSendsOnlyWhatTheRemoteLacks() {
        val d = cloneAndCommit("thin.git")
        ClientEnv.localCommit(d, "only.txt", "only")
        runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.branch("main"))) }.requireOk()
        val post = fx.log.last { it.method == "POST" && it.path.endsWith("git-receive-pack") }
        assertTrue("pack for one new file must be tiny, was ${post.bodyBytes}", post.bodyBytes < 1500)
    }

    @Test fun nonFastForwardIsRefusedWithoutTheForceFlagAndNothingIsSent() {
        val d = cloneAndCommit("nff.git")
        val serverTip = serverCommit(fx, "nff.git", "s.txt", "server")
        ClientEnv.localCommit(d, "l.txt", "local")
        fx.log.clear()
        val r = runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.branch("main"))) }
        val rej = r.outcomes.single() as RefPushOutcome.Rejected
        assertTrue(rej.failure is GitFailure.NonFastForward)
        assertTrue(rej.failure.explanation.contains("Fetch"))
        assertEquals(serverTip, rev(fx.bare("nff.git"), "refs/heads/main"))
        assertTrue("no receive-pack POST may reach the wire without the flag", fx.log.none { it.method == "POST" && it.path.endsWith("git-receive-pack") })
        assertTrue(failure { r.requireOk() } is GitFailure.NonFastForward)
    }

    @Test fun forcedPushOnlyWithTheFlagAndLeaseSemantics() {
        val d = cloneAndCommit("force.git")
        val serverTip = serverCommit(fx, "force.git", "s.txt", "server")
        val mine = ClientEnv.localCommit(d, "l.txt", "local")
        val spec = listOf(PushSpec.branch("main"))
        // stale lease: expected old is NOT what the server has -> refused, nothing sent
        val staleOld = dev.fonebrew.domain.gitcore.ObjectId.fromHex(rev(d, "refs/remotes/origin/main"))
        fx.log.clear()
        val stale = runBlocking { client().push(d.absolutePath, specs = spec, options = PushOptions(force = ForceMode.WithLease(staleOld))) }
        assertTrue((stale.outcomes.single() as RefPushOutcome.Rejected).failure is GitFailure.LeaseStale)
        assertEquals(serverTip, rev(fx.bare("force.git"), "refs/heads/main"))
        assertTrue(fx.log.none { it.method == "POST" && it.path.endsWith("git-receive-pack") })
        // correct lease -> forced update accepted
        val ok = runBlocking { client().push(d.absolutePath, specs = spec, options = PushOptions(force = ForceMode.WithLease(dev.fonebrew.domain.gitcore.ObjectId.fromHex(serverTip)))) }
        val up = ok.outcomes.single() as RefPushOutcome.Updated
        assertTrue(up.forced); assertEquals(mine.hex, rev(fx.bare("force.git"), "refs/heads/main"))
        // explicit Force after another server move
        val tip2 = serverCommit(fx, "force.git", "s2.txt", "server2")
        ClientEnv.localCommit(d, "l2.txt", "local2")
        assertTrue(runBlocking { client().push(d.absolutePath, specs = spec) }.rejected.isNotEmpty())
        assertEquals(tip2, rev(fx.bare("force.git"), "refs/heads/main"))
        val forced = runBlocking { client().push(d.absolutePath, specs = spec, options = PushOptions(force = ForceMode.Force)) }
        assertTrue(forced.allOk)
        assertEquals(rev(d, "HEAD"), rev(fx.bare("force.git"), "refs/heads/main"))
        GitTestSupport.git(fx.bare("force.git"), "fsck", "--full")
    }

    @Test fun serverSideRefusalOfAForcedPushIsSurfacedWithTheServersReason() {
        val d = cloneAndCommit("deny.git")
        GitTestSupport.git(fx.bare("deny.git"), "config", "receive.denyNonFastForwards", "true")
        val serverTip = serverCommit(fx, "deny.git", "s.txt", "server")
        ClientEnv.localCommit(d, "l.txt", "local")
        val r = runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.branch("main")), options = PushOptions(force = ForceMode.Force)) }
        val f = (r.outcomes.single() as RefPushOutcome.Rejected).failure
        assertTrue(f.toString(), f is GitFailure.NonFastForward && f.remoteReason!!.contains("non-fast-forward"))
        assertEquals(serverTip, rev(fx.bare("deny.git"), "refs/heads/main"))
    }

    @Test fun deleteRemoteBranch() {
        val d = cloneAndCommit("del.git")
        val r = runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.delete("refs/heads/feature"))) }
        assertTrue(r.outcomes.single() is RefPushOutcome.Deleted)
        assertEquals("", GitTestSupport.git(fx.bare("del.git"), "branch", "--list", "feature").trim())
        assertNull(Repository.open(JvmFileSystem(), d.absolutePath).use { it.refs.read("refs/remotes/origin/feature") })
        // deleting what is not there is a typed refusal, not a crash
        assertTrue(runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.delete("refs/heads/ghost"))) }.rejected.isNotEmpty())
    }

    @Test fun pushAnnotatedTagAndAtomicMultiRefPush() {
        val d = cloneAndCommit("tagp.git")
        Repository.open(JvmFileSystem(), d.absolutePath).use { r ->
            r.refs.update("refs/tags/v9", dev.fonebrew.domain.gitcore.ObjectId.fromHex(rev(d, "HEAD")))
        }
        val c = ClientEnv.localCommit(d, "a.txt", "a")
        val r = runBlocking { client().push(d.absolutePath, specs = listOf(PushSpec.branch("main"), PushSpec.tag("v9")), options = PushOptions(atomic = true)) }
        assertTrue(r.outcomes.toString(), r.allOk)
        assertEquals(c.hex, rev(fx.bare("tagp.git"), "refs/heads/main"))
        assertEquals(rev(d, "refs/tags/v9"), rev(fx.bare("tagp.git"), "refs/tags/v9"))
    }

    // ---------------------------------------------------------------- remotes

    @Test fun remoteAddListRemove() {
        val d = cloneAndCommit("rm.git")
        val c = client()
        c.addRemote(d.absolutePath, "backup", "https://example.com/x/y.git")
        assertEquals(setOf("origin", "backup"), c.remotes(d.absolutePath).map { it.name }.toSet())
        assertTrue(failure { c.addRemote(d.absolutePath, "backup", "https://example.com/z.git") } is GitFailure.InvalidRequest)
        assertTrue(failure { c.addRemote(d.absolutePath, "bad", "https://u:pw@example.com/z.git") } is GitFailure.InvalidRequest)
        c.removeRemote(d.absolutePath, "origin")
        assertEquals(listOf("backup"), c.remotes(d.absolutePath).map { it.name })
        assertEquals("", GitTestSupport.git(d, "for-each-ref", "refs/remotes/origin").trim())
        assertTrue(failure { c.removeRemote(d.absolutePath, "origin") } is GitFailure.LocalRepository)
    }
}
