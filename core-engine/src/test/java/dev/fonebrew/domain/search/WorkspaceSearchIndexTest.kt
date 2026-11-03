package dev.fonebrew.domain.search

import dev.fonebrew.contracts.search.IndexSourceKind
import dev.fonebrew.contracts.search.SearchCancellation
import dev.fonebrew.contracts.workspace.BufferSnapshotEntry
import dev.fonebrew.contracts.workspace.LineEndings
import dev.fonebrew.contracts.workspace.ResourceProvider
import dev.fonebrew.contracts.workspace.ResourceUri
import dev.fonebrew.domain.contracts.Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkspaceSearchIndexTest {

    private fun snapshot(bufferId: String, content: String, sequence: Long = 1) = BufferSnapshotEntry(
        bufferId = bufferId, resourceUri = ResourceUri(ResourceProvider.LOCAL, "local://$bufferId.kt"),
        journalSequence = sequence, dirty = true, encoding = "UTF-8", lineEndings = LineEndings.LF,
        contentDigest = Digest.ofUtf8(content)
    )

    @Test
    fun `an indexed buffer is findable by a term in its content`() {
        val index = WorkspaceSearchIndex()
        index.index(snapshot("buf1", "fun authorityEngine(): Decision"), "fun authorityEngine(): Decision", "AuthorityEngine.kt")

        val results = index.search("authorityEngine", Instant.now().toEpochMilli())
        assertEquals(1, results.size)
        assertEquals("buf1", results.single().hit.doc.id)
        assertEquals(IndexSourceKind.WORKSPACE_BUFFER, results.single().provenance.sourceKind)
    }

    @Test
    fun `re-indexing the same bufferId replaces, not duplicates`() {
        val index = WorkspaceSearchIndex()
        index.index(snapshot("buf1", "version one"), "version one", "a.kt")
        index.index(snapshot("buf1", "version two"), "version two", "a.kt")

        assertEquals(1, index.size())
        val results = index.search("two", Instant.now().toEpochMilli())
        assertEquals(1, results.size)
    }

    @Test
    fun `remove takes a buffer out of the index -- FB-RAT-WS-006, indexes are disposable`() {
        val index = WorkspaceSearchIndex()
        index.index(snapshot("buf1", "findme"), "findme", "a.kt")
        index.remove("buf1")

        assertEquals(0, index.size())
        assertTrue(index.search("findme", Instant.now().toEpochMilli()).isEmpty())
    }

    @Test
    fun `a cancelled search returns no results even if the underlying rank pass already ran`() {
        val index = WorkspaceSearchIndex()
        index.index(snapshot("buf1", "findme"), "findme", "a.kt")

        val cancelled = SearchCancellation { true }
        val results = index.search("findme", Instant.now().toEpochMilli(), cancellation = cancelled)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `provenance freshness carries the buffer's journalSequence as its source revision`() {
        val index = WorkspaceSearchIndex()
        index.index(snapshot("buf1", "content", sequence = 7), "content", "a.kt")

        val provenance = index.search("content", Instant.now().toEpochMilli()).single().provenance
        assertEquals("7", provenance.freshness.sourceRevisionOrSequence)
        assertEquals(false, provenance.freshness.isStale)
    }
}
