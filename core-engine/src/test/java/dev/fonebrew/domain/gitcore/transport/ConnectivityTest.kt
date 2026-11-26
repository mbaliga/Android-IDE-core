package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.ObjectId
import dev.fonebrew.domain.gitcore.TestRepo
import dev.fonebrew.domain.gitcore.treeOf
import dev.fonebrew.domain.gitcore.Identity
import dev.fonebrew.domain.gitcore.createTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ConnectivityTest {
    private fun TestRepo.loose(id: ObjectId) = File(gitDir(), "objects/${id.hex.substring(0, 2)}/${id.hex.substring(2)}")
    private fun TestRepo.history(): Triple<ObjectId, ObjectId, ObjectId> {
        write("a.txt", "one"); write("dir/b.txt", "bee")
        val c1 = commitAll("c1")
        write("a.txt", "two")
        val c2 = commitAll("c2")
        write("dir/c.txt", "see")
        val c3 = commitAll("c3")
        return Triple(c1, c2, c3)
    }
    private fun integrity(body: () -> Unit) {
        try { body(); fail("expected INTEGRITY") } catch (e: TransportException) { assertEquals(TransportErrorKind.INTEGRITY, e.kind) }
    }

    @Test fun completeHistoryPasses() {
        val t = TestRepo(); val (_, _, c3) = t.history()
        Connectivity.verify(t.repo.db, listOf(c3), emptySet(), emptySet())
    }

    @Test fun missingBlobIsRefused() {
        val t = TestRepo(); val (_, _, c3) = t.history()
        val blob = t.repo.db.readTree(t.repo.db.readCommit(c3).tree).find("a.txt")!!.id
        assertTrue(t.loose(blob).delete())
        integrity { Connectivity.verify(t.repo.db, listOf(c3), emptySet(), emptySet()) }
    }

    @Test fun missingSubtreeIsRefused() {
        val t = TestRepo(); val (_, _, c3) = t.history()
        val sub = t.repo.db.readTree(t.repo.db.readCommit(c3).tree).find("dir")!!.id
        assertTrue(t.loose(sub).delete())
        integrity { Connectivity.verify(t.repo.db, listOf(c3), emptySet(), emptySet()) }
    }

    @Test fun missingParentCommitIsRefusedUnlessKnownGoodOrShallow() {
        val t = TestRepo(); val (c1, c2, c3) = t.history()
        assertTrue(t.loose(c2).delete())
        integrity { Connectivity.verify(t.repo.db, listOf(c3), emptySet(), emptySet()) }
        Connectivity.verify(t.repo.db, listOf(c3), setOf(c2), emptySet())      // trusted boundary: not walked
        Connectivity.verify(t.repo.db, listOf(c3), emptySet(), setOf(c3))      // shallow root: parents not required
        assertTrue(c1 != c2)
    }

    @Test fun missingTipIsRefused() {
        val t = TestRepo(); val (_, _, c3) = t.history()
        assertTrue(t.loose(c3).delete())
        integrity { Connectivity.verify(t.repo.db, listOf(c3), emptySet(), emptySet()) }
    }

    @Test fun corruptObjectContentIsRefusedNotTrusted() {
        val t = TestRepo(); val (_, _, c3) = t.history()
        val blob = t.repo.db.readTree(t.repo.db.readCommit(c3).tree).find("a.txt")!!.id
        // Tree objects are parsed and hash-verified; swap a tree for another tree's bytes.
        val tree = t.repo.db.readCommit(c3).tree
        val other = t.repo.db.readCommit(t.repo.db.readCommit(c3).parents[0]).tree
        t.loose(tree).writeBytes(t.loose(other).readBytes())
        integrity { Connectivity.verify(t.repo.db, listOf(c3), emptySet(), emptySet()) }
        assertTrue(blob != tree)
    }

    @Test fun annotatedTagChainIsFollowedToItsTarget() {
        val t = TestRepo(); val (_, _, c3) = t.history()
        val tagId = t.repo.createTag("v1", "HEAD", "release", Identity("T", "t@x"))
        Connectivity.verify(t.repo.db, listOf(tagId), emptySet(), emptySet())
        assertTrue(t.loose(c3).delete())
        integrity { Connectivity.verify(t.repo.db, listOf(tagId), emptySet(), emptySet()) }
    }

    @Test fun trustedBoundaryTreesAreNotRewalkedButNewObjectsAre() {
        val t = TestRepo(); val (c1, _, c3) = t.history()
        // Everything of c1 is trusted; c3 introduced dir/c.txt, whose blob we remove: must still be caught.
        val sub = t.repo.db.readTree(t.repo.db.readCommit(c3).tree).find("dir")!!.id
        val c = t.repo.db.readTree(sub).find("c.txt")!!.id
        assertTrue(t.loose(c).delete())
        integrity { Connectivity.verify(t.repo.db, listOf(c3), setOf(c1), emptySet()) }
        assertEquals(t.repo.treeOf(c3), t.repo.db.readCommit(c3).tree)
    }
}
