package dev.fonebrew.domain.ide

import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import dev.fonebrew.domain.pm.BoardCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoWorkLoopTest {

    private fun card() = BoardCard(
        id = "1", number = 42, title = "Fix the crash on send", body = "NPE in Sender",
        isOpen = true, labels = listOf("status:doing"), assignees = emptyList(),
        updatedAt = "", url = "https://host/issues/42",
    )

    private val files = mapOf("src/Sender.kt" to "fun send() = null!!")

    private fun reader() = RepoReader { path -> files[path] }

    @Test fun `approved run reads, proposes, and commits with an issue-referencing message`() = runTest {
        var committedMessage: String? = null
        var committedSet: ChangeSet? = null
        val proposer = ChangeProposer { _, ctx ->
            ChangeSet.of(ctx, ctx.mapValues { "fun send() = 0" })
        }
        val committer = ChangeCommitter { cs, msg -> committedSet = cs; committedMessage = msg; Result.success("sha123") }

        val loop = RepoWorkLoop(reader(), proposer, committer)
        var reviewed: ChangeSet? = null
        val result = loop.run(card(), listOf("src/Sender.kt")) { cs -> reviewed = cs; true }

        assertTrue(result.committed)
        assertEquals("sha123", result.commitId)
        assertEquals("Address #42: Fix the crash on send", committedMessage)
        assertEquals(1, committedSet!!.effective.size)
        assertTrue(reviewed!!.unifiedDiff().contains("src/Sender.kt"))
    }

    @Test fun `rejecting the proposal is a no-op (no commit)`() = runTest {
        var committerCalled = false
        val proposer = ChangeProposer { _, ctx -> ChangeSet.of(ctx, ctx.mapValues { "changed" }) }
        val committer = ChangeCommitter { _, _ -> committerCalled = true; Result.success("x") }

        val result = RepoWorkLoop(reader(), proposer, committer).run(card(), listOf("src/Sender.kt")) { false }

        assertFalse(result.committed)
        assertFalse(committerCalled)
        assertEquals("rejected by reviewer", result.reason)
    }

    @Test fun `an empty proposal never prompts or commits`() = runTest {
        var prompted = false
        var committerCalled = false
        // Proposer returns the same content → no effective change.
        val proposer = ChangeProposer { _, ctx -> ChangeSet.of(ctx, ctx) }
        val committer = ChangeCommitter { _, _ -> committerCalled = true; Result.success("x") }

        val result = RepoWorkLoop(reader(), proposer, committer).run(card(), listOf("src/Sender.kt")) { prompted = true; true }

        assertFalse(result.committed)
        assertFalse(prompted)
        assertFalse(committerCalled)
        assertEquals("no change proposed", result.reason)
    }

    @Test fun `a commit failure is reported, not thrown`() = runTest {
        val proposer = ChangeProposer { _, ctx -> ChangeSet.of(ctx, ctx.mapValues { "changed" }) }
        val committer = ChangeCommitter { _, _ -> Result.failure(RuntimeException("409 conflict")) }

        val result = RepoWorkLoop(reader(), proposer, committer).run(card(), listOf("src/Sender.kt")) { true }

        assertFalse(result.committed)
        assertEquals("409 conflict", result.reason)
    }

    @Test fun `missing files are simply absent from the proposer context`() = runTest {
        var seenContext: Map<String, String>? = null
        val proposer = ChangeProposer { _, ctx -> seenContext = ctx; ChangeSet(emptyList()) }
        RepoWorkLoop(reader(), proposer, { _, _ -> Result.success("x") })
            .run(card(), listOf("src/Sender.kt", "does/not/exist.kt")) { true }
        assertEquals(setOf("src/Sender.kt"), seenContext!!.keys)
    }

    // ---- commit anchors (graph-wave lane D) ---------------------------------------------------

    private fun committingProposer() = ChangeProposer { _, ctx -> ChangeSet.of(ctx, ctx.mapValues { "fun send() = 0" }) }

    @Test fun `a successful commit mints a CommitAnchor node carrying the sha and repoRef`() = runTest {
        val committer = ChangeCommitter { _, _ -> Result.success("sha123") }
        val loop = RepoWorkLoop(reader(), committingProposer(), committer, now = { 42L }, idGen = { "anchor-1" })

        val result = loop.run(card(), listOf("src/Sender.kt"), repoRef = "me/proj@main") { true }

        assertTrue(result.committed)
        val anchor = result.commitAnchor
        assertEquals("anchor-1", anchor?.id)
        assertNull(anchor?.parentId)
        assertEquals(42L, anchor?.createdAt)
        assertEquals("sha123", anchor?.metadata?.get(dev.fonebrew.domain.ide.CommitAnchor.SHA_KEY))
        assertEquals("me/proj@main", anchor?.metadata?.get(dev.fonebrew.domain.ide.CommitAnchor.REPO_KEY))
        // The anchor's content is the same real commit message the history records, not a
        // second, divergent summary.
        assertEquals("Address #42: Fix the crash on send", anchor?.content)
    }

    @Test fun `commitAnchor omits the repo key when no repoRef is supplied — never a fabricated one`() = runTest {
        val committer = ChangeCommitter { _, _ -> Result.success("sha123") }
        val result = RepoWorkLoop(reader(), committingProposer(), committer).run(card(), listOf("src/Sender.kt")) { true }
        assertTrue(result.committed)
        assertNull(result.commitAnchor?.metadata?.get(dev.fonebrew.domain.ide.CommitAnchor.REPO_KEY))
    }

    @Test fun `no commitAnchor when the commit is rejected, empty, or fails — nothing happened, nothing minted`() = runTest {
        val committer = ChangeCommitter { _, _ -> Result.success("sha123") }

        val rejected = RepoWorkLoop(reader(), committingProposer(), committer).run(card(), listOf("src/Sender.kt")) { false }
        assertNull(rejected.commitAnchor)

        val emptyProposer = ChangeProposer { _, ctx -> ChangeSet.of(ctx, ctx) }
        val noProposal = RepoWorkLoop(reader(), emptyProposer, committer).run(card(), listOf("src/Sender.kt")) { true }
        assertNull(noProposal.commitAnchor)

        val failingCommitter = ChangeCommitter { _, _ -> Result.failure(RuntimeException("409")) }
        val failed = RepoWorkLoop(reader(), committingProposer(), failingCommitter).run(card(), listOf("src/Sender.kt")) { true }
        assertNull(failed.commitAnchor)
    }
}
