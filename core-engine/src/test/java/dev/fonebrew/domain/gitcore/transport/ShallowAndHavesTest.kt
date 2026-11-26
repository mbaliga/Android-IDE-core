package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.TestRepo
import dev.fonebrew.domain.gitcore.ancestors
import dev.fonebrew.domain.gitcore.log
import dev.fonebrew.domain.gitcore.mergeBase
import dev.fonebrew.domain.gitcore.objectsToSend
import dev.fonebrew.domain.gitcore.shallowCommits
import dev.fonebrew.domain.gitcore.writeShallow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShallowAndHavesTest {
    private fun TestRepo.chain(n: Int) = (1..n).map { write("f.txt", "v$it"); commitAll("c$it") }

    @Test fun shallowFileRoundTripsAndEmptyRemovesIt() {
        val t = TestRepo(); val ids = t.chain(3)
        assertTrue(t.repo.shallowCommits().isEmpty())
        t.repo.writeShallow(setOf(ids[2], ids[1]))
        assertEquals(setOf(ids[1], ids[2]), t.repo.shallowCommits())
        t.repo.writeShallow(emptySet())
        assertFalse(File(t.gitDir(), "shallow").exists())
    }

    @Test fun historyWalksTreatAShallowCommitAsARootEvenWhenItsParentIsAbsent() {
        val t = TestRepo(); val ids = t.chain(4)
        // Simulate a depth-1 clone's store: the parent objects are not there.
        for (id in ids.dropLast(1)) assertTrue(File(t.gitDir(), "objects/${id.hex.substring(0, 2)}/${id.hex.substring(2)}").delete())
        t.repo.writeShallow(setOf(ids.last()))
        assertEquals(setOf(ids.last()), t.repo.ancestors(ids.last()))
        assertEquals(1, t.repo.log(listOf(ids.last())).size)
        assertEquals(ids.last(), t.repo.mergeBase(ids.last(), ids.last()))
        assertTrue(t.repo.objectsToSend(listOf(ids.last()), emptyList()).contains(ids.last()))
    }

    @Test fun haveSelectionIsBoundedStartsWithTipsAndIsNewestFirst() {
        val t = TestRepo(); val ids = t.chain(40)
        val h = HaveSelector.select(t.repo, limit = 10)
        assertEquals(10, h.size)
        assertEquals(ids.last(), h.first())                       // the ref tip comes first
        assertEquals(ids.reversed().take(10), h)                  // then recent history, newest first
        assertEquals(40, HaveSelector.select(t.repo).size)        // default limit covers a small repo fully
        assertEquals(emptyList<Any>(), HaveSelector.select(TestRepo().repo))
    }

    @Test fun haveSelectionSkipsMissingObjectsInsteadOfFailing() {
        val t = TestRepo(); val ids = t.chain(5)
        assertTrue(File(t.gitDir(), "objects/${ids[2].hex.substring(0, 2)}/${ids[2].hex.substring(2)}").delete())
        val h = HaveSelector.select(t.repo)
        assertEquals(listOf(ids[4], ids[3]), h)
    }
}
