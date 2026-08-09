package dev.aarso.domain.search

import dev.aarso.contracts.workspace.BufferSnapshotEntry
import dev.aarso.contracts.workspace.LineEndings
import dev.aarso.contracts.workspace.ResourceProvider
import dev.aarso.contracts.workspace.ResourceUri
import dev.aarso.domain.contracts.Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkspaceSearchProjectorTest {

    private val entry = BufferSnapshotEntry(
        bufferId = "buf1", resourceUri = ResourceUri(ResourceProvider.LOCAL, "local://src/A.kt"),
        journalSequence = 3, dirty = true, encoding = "UTF-8", lineEndings = LineEndings.LF,
        contentDigest = Digest.ofUtf8("class A")
    )

    @Test
    fun `project maps a buffer snapshot into a SearchDoc keyed by bufferId, titled by displayPath`() {
        val doc = WorkspaceSearchProjector.project(entry, "class A { }", "src/A.kt", 1000L)
        assertEquals("buf1", doc.id)
        assertEquals("src/A.kt", doc.title)
        assertEquals("class A { }", doc.body)
        assertEquals(SearchKind.TEXT, doc.kind)
        assertEquals(1000L, doc.lastActivityMillis)
    }

    @Test
    fun `project truncates a long body into a bounded, newline-flattened snippet`() {
        val longContent = "line one\n" + "x".repeat(500)
        val doc = WorkspaceSearchProjector.project(entry, longContent, "src/A.kt", 1000L)
        assertTrue(doc.snippet.length <= 240)
        assertTrue(!doc.snippet.contains('\n'))
    }

    @Test
    fun `freshnessOf carries the snapshot's journalSequence and is never stale at index time`() {
        val freshness = WorkspaceSearchProjector.freshnessOf(entry, Instant.parse("2026-08-07T12:00:00Z"))
        assertEquals("3", freshness.sourceRevisionOrSequence)
        assertEquals(false, freshness.isStale)
    }
}
