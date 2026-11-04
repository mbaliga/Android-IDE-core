package dev.fonebrew.domain.thread

import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.TreeFork
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadChainsTest {

    private fun summary(
        rootId: String,
        title: String = "conv-$rootId",
        createdMillis: Long,
        lastUpdatedAt: Long = createdMillis,
    ) = Conversations.Summary(
        rootId = rootId,
        title = title,
        modelIds = emptyList(),
        lastUpdatedAt = lastUpdatedAt,
        nodeCount = 1,
        latestLeafId = rootId,
        createdMillis = createdMillis,
    )

    private fun lineageMarker(
        newRootId: String,
        srcRootId: String,
        srcNodeId: String = "src-msg",
        kind: TreeFork.LineageKind = TreeFork.LineageKind.FORK,
        at: Long = 0L,
    ) = ThreadMarker(
        id = "lineage-$newRootId",
        rootId = newRootId,
        anchorMsgId = null,
        kind = ThreadMarkerKind.LINEAGE_SRC,
        at = at,
        source = ThreadMarkerSource.SYSTEM,
        payloadJson = JSONObject()
            .put("srcRootId", srcRootId)
            .put("srcNodeId", srcNodeId)
            .put("lineageKind", kind.name)
            .toString(),
    )

    private fun marker(rootId: String, kind: ThreadMarkerKind, anchorMsgId: String? = "m", at: Long = 0L) = ThreadMarker(
        id = "marker-$rootId-$kind-$at",
        rootId = rootId,
        anchorMsgId = if (kind == ThreadMarkerKind.CHAPTER || kind == ThreadMarkerKind.COMPACTION_RUN) anchorMsgId else null,
        kind = kind,
        label = if (kind == ThreadMarkerKind.CHAPTER) "chapter" else null,
        at = at,
        source = if (kind == ThreadMarkerKind.CHAPTER) ThreadMarkerSource.USER else ThreadMarkerSource.SYSTEM,
    )

    // ---- lineagePointer ------------------------------------------------------------------

    @Test fun `lineagePointer decodes a well-formed LINEAGE_SRC payload`() {
        val ptr = ThreadChains.lineagePointer(lineageMarker("new", "old", "old-msg", TreeFork.LineageKind.SPAWN))
        assertEquals("old", ptr!!.srcRootId)
        assertEquals("old-msg", ptr.srcNodeId)
        assertEquals(TreeFork.LineageKind.SPAWN, ptr.lineageKind)
    }

    @Test fun `lineagePointer returns null for a non-LINEAGE_SRC marker`() {
        assertNull(ThreadChains.lineagePointer(marker("r", ThreadMarkerKind.CHAPTER)))
    }

    @Test fun `lineagePointer returns null for a blank payload`() {
        val bare = ThreadMarker(
            id = "id", rootId = "r", kind = ThreadMarkerKind.LINEAGE_SRC, at = 0L,
            source = ThreadMarkerSource.SYSTEM, payloadJson = null,
        )
        assertNull(ThreadChains.lineagePointer(bare))
    }

    @Test fun `lineagePointer returns null for malformed json`() {
        val broken = ThreadMarker(
            id = "id", rootId = "r", kind = ThreadMarkerKind.LINEAGE_SRC, at = 0L,
            source = ThreadMarkerSource.SYSTEM, payloadJson = "{not json",
        )
        assertNull(ThreadChains.lineagePointer(broken))
    }

    @Test fun `lineagePointer returns null when srcRootId is missing`() {
        val partial = ThreadMarker(
            id = "id", rootId = "r", kind = ThreadMarkerKind.LINEAGE_SRC, at = 0L,
            source = ThreadMarkerSource.SYSTEM, payloadJson = JSONObject().put("srcNodeId", "m").toString(),
        )
        assertNull(ThreadChains.lineagePointer(partial))
    }

    // ---- build: basic grouping -------------------------------------------------------------

    @Test fun `a conversation with no lineage is its own singleton chain`() {
        val chains = ThreadChains.build(listOf(summary("a", createdMillis = 1L)), emptyList())
        assertEquals(1, chains.size)
        val chain = chains.single()
        assertEquals("a", chain.originRootId)
        assertEquals(1, chain.rootCount)
        assertTrue(!chain.isMultiRoot)
        assertNull(chain.links.single().lineage)
    }

    @Test fun `empty summaries produce no chains`() {
        assertTrue(ThreadChains.build(emptyList(), emptyList()).isEmpty())
    }

    @Test fun `a fork joins its source into one chain, oldest first`() {
        val summaries = listOf(
            summary("root", createdMillis = 1L),
            summary("fork", createdMillis = 10L),
        )
        val markers = listOf(lineageMarker(newRootId = "fork", srcRootId = "root", kind = TreeFork.LineageKind.FORK))
        val chains = ThreadChains.build(summaries, markers)

        assertEquals(1, chains.size)
        val chain = chains.single()
        assertTrue(chain.isMultiRoot)
        assertEquals(listOf("root", "fork"), chain.links.map { it.rootId })
        assertEquals("root", chain.originRootId)
        assertNull(chain.links[0].lineage)
        assertEquals("root", chain.links[1].lineage!!.srcRootId)
        assertEquals(TreeFork.LineageKind.FORK, chain.links[1].lineage!!.lineageKind)
    }

    @Test fun `a spawn joins its source into one chain the same way a fork does`() {
        val summaries = listOf(summary("root", createdMillis = 1L), summary("spawn", createdMillis = 10L))
        val markers = listOf(lineageMarker("spawn", "root", kind = TreeFork.LineageKind.SPAWN))
        val chain = ThreadChains.build(summaries, markers).single()
        assertEquals(TreeFork.LineageKind.SPAWN, chain.links.last().lineage!!.lineageKind)
    }

    @Test fun `a multi-level fork chain (fork of a fork) is one connected chain`() {
        val summaries = listOf(
            summary("root", createdMillis = 1L),
            summary("mid", createdMillis = 10L),
            summary("leaf", createdMillis = 20L),
        )
        val markers = listOf(
            lineageMarker("mid", "root"),
            lineageMarker("leaf", "mid"),
        )
        val chain = ThreadChains.build(summaries, markers).single()
        assertEquals(listOf("root", "mid", "leaf"), chain.links.map { it.rootId })
        assertEquals("root", chain.originRootId)
    }

    @Test fun `unrelated conversations form independent chains`() {
        val summaries = listOf(
            summary("a", createdMillis = 1L),
            summary("b", createdMillis = 2L, lastUpdatedAt = 500L),
        )
        val chains = ThreadChains.build(summaries, emptyList())
        assertEquals(2, chains.size)
        // newest activity first
        assertEquals("b", chains.first().originRootId)
    }

    @Test fun `a lineage pointer to an unknown srcRoot degrades to a singleton origin, not a drop`() {
        val summaries = listOf(summary("orphan-fork", createdMillis = 5L))
        val markers = listOf(lineageMarker("orphan-fork", srcRootId = "deleted-root"))
        val chains = ThreadChains.build(summaries, markers)
        assertEquals(1, chains.size)
        assertEquals("orphan-fork", chains.single().originRootId)
        // The lineage pointer itself is still reported on the link, even though the source is gone.
        assertEquals("deleted-root", chains.single().links.single().lineage!!.srcRootId)
    }

    // ---- build: marker counts -------------------------------------------------------------

    @Test fun `chapter session and compaction markers are counted per root`() {
        val summaries = listOf(summary("root", createdMillis = 1L))
        val markers = listOf(
            marker("root", ThreadMarkerKind.CHAPTER, at = 1L),
            marker("root", ThreadMarkerKind.CHAPTER, at = 2L),
            marker("root", ThreadMarkerKind.SESSION_START, at = 3L),
            marker("root", ThreadMarkerKind.COMPACTION_RUN, at = 4L),
        )
        val link = ThreadChains.build(summaries, markers).single().links.single()
        assertEquals(2, link.chapterCount)
        assertEquals(1, link.sessionStartCount)
        assertEquals(1, link.compactionCount)
    }

    @Test fun `chain-level totals sum every link's counts`() {
        val summaries = listOf(summary("root", createdMillis = 1L), summary("fork", createdMillis = 5L))
        val markers = listOf(
            lineageMarker("fork", "root"),
            marker("root", ThreadMarkerKind.CHAPTER, at = 1L),
            marker("fork", ThreadMarkerKind.CHAPTER, at = 6L),
            marker("fork", ThreadMarkerKind.COMPACTION_RUN, at = 7L),
        )
        val chain = ThreadChains.build(summaries, markers).single()
        assertEquals(2, chain.totalChapters)
        assertEquals(1, chain.totalCompactions)
    }

    @Test fun `markers on a root outside the summary list are ignored, not crashing`() {
        val summaries = listOf(summary("root", createdMillis = 1L))
        val markers = listOf(marker("some-other-root", ThreadMarkerKind.CHAPTER))
        val link = ThreadChains.build(summaries, markers).single().links.single()
        assertEquals(0, link.chapterCount)
    }

    @Test fun `chains are ordered by latest activity across all their links, newest first`() {
        val old = listOf(summary("old-root", createdMillis = 1L, lastUpdatedAt = 2L))
        val recentlyForked = listOf(
            summary("r2", createdMillis = 100L, lastUpdatedAt = 100L),
            summary("f2", createdMillis = 200L, lastUpdatedAt = 900L),
        )
        val markers = listOf(lineageMarker("f2", "r2"))
        val chains = ThreadChains.build(old + recentlyForked, markers)
        assertEquals(listOf("r2", "old-root"), chains.map { it.originRootId })
    }

    @Test fun `Chain title is the origin link's title`() {
        val summaries = listOf(
            summary("root", "Original ask", createdMillis = 1L),
            summary("fork", "Forked copy title", createdMillis = 5L),
        )
        val chain = ThreadChains.build(summaries, listOf(lineageMarker("fork", "root"))).single()
        assertEquals("Original ask", chain.title)
    }
}
