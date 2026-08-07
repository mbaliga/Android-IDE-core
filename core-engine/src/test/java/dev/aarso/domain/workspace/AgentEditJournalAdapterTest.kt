package dev.aarso.domain.workspace

import dev.aarso.contracts.workspace.BufferJournalEntry
import dev.aarso.contracts.workspace.JournalOpType
import dev.aarso.contracts.workspace.OriginKind
import dev.aarso.contracts.workspace.WorkspaceJournal
import dev.aarso.domain.diff.ChangeSet
import dev.aarso.domain.diff.FileChange
import dev.aarso.domain.ide.ChangeCommitter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [WorkspaceJournal] fake -- this test is about the adapter's own wiring logic, not
 *  about journal persistence (covered by RoomWorkspaceJournalTest). */
private class FakeJournal : WorkspaceJournal {
    val appended = mutableListOf<BufferJournalEntry>()
    override suspend fun append(change: BufferJournalEntry) { appended += change }
    override suspend fun checkpoint(workspaceId: String) = throw UnsupportedOperationException("not used by this test")
    override suspend fun restore(workspaceId: String) = null
}

class AgentEditJournalAdapterTest {

    private fun changeSet(vararg changes: Triple<String, String, String>) =
        ChangeSet(changes.map { (path, old, new) -> FileChange(path, old, new) })

    @Test
    fun `a successful commit journals one AGENT SET_FULL_CONTENT entry per effective file change`() = runTest {
        val delegate = ChangeCommitter { _, _ -> Result.success("commit-sha-123") }
        val journal = FakeJournal()
        val adapter = AgentEditJournalAdapter(
            delegate = delegate, journal = journal, originPrincipal = "agent:repo-work-loop",
            nextSequence = AgentEditJournalAdapter.inMemorySequencer()
        )

        val outcome = adapter.commit(changeSet(Triple("a.kt", "old a", "new a"), Triple("b.kt", "old b", "new b")), "fix things")

        assertEquals("commit-sha-123", outcome.getOrNull())
        assertEquals(2, journal.appended.size)
        assertTrue(journal.appended.all { it.originKind == OriginKind.AGENT })
        assertTrue(journal.appended.all { it.originPrincipal == "agent:repo-work-loop" })
        assertTrue(journal.appended.all { it.opType == JournalOpType.SET_FULL_CONTENT })
        assertEquals(setOf("new a", "new b"), journal.appended.map { it.content }.toSet())
    }

    @Test
    fun `a no-op change (oldText equals newText) is not journaled`() = runTest {
        val delegate = ChangeCommitter { _, _ -> Result.success("sha") }
        val journal = FakeJournal()
        val adapter = AgentEditJournalAdapter(delegate, journal, "agent:x", nextSequence = AgentEditJournalAdapter.inMemorySequencer())

        adapter.commit(changeSet(Triple("same.kt", "identical", "identical")), "no-op")

        assertTrue(journal.appended.isEmpty())
    }

    @Test
    fun `a failed commit journals nothing -- the delegate's failure is not silently overridden into a journaled edit`() = runTest {
        val delegate = ChangeCommitter { _, _ -> Result.failure(IllegalStateException("push rejected")) }
        val journal = FakeJournal()
        val adapter = AgentEditJournalAdapter(delegate, journal, "agent:x", nextSequence = AgentEditJournalAdapter.inMemorySequencer())

        val outcome = adapter.commit(changeSet(Triple("a.kt", "old", "new")), "attempt")

        assertTrue(outcome.isFailure)
        assertTrue(journal.appended.isEmpty())
    }

    @Test
    fun `repeated edits to the same path accumulate under the same bufferId with increasing sequence`() = runTest {
        val delegate = ChangeCommitter { _, _ -> Result.success("sha") }
        val journal = FakeJournal()
        val adapter = AgentEditJournalAdapter(delegate, journal, "agent:x", nextSequence = AgentEditJournalAdapter.inMemorySequencer())

        adapter.commit(changeSet(Triple("same.kt", "v1", "v2")), "first edit")
        adapter.commit(changeSet(Triple("same.kt", "v2", "v3")), "second edit")

        assertEquals(2, journal.appended.size)
        val bufferIds = journal.appended.map { it.bufferId }.toSet()
        assertEquals(1, bufferIds.size)
        assertEquals(listOf(0L, 1L), journal.appended.map { it.sequence })
    }
}
