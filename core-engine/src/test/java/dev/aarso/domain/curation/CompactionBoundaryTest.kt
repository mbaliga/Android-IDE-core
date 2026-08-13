package dev.aarso.domain.curation

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.thread.ThreadMarker
import dev.aarso.domain.thread.ThreadMarkerKind
import dev.aarso.domain.thread.ThreadMarkerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompactionBoundaryTest {

    private fun node(id: String, content: String) = MessageNode(
        id = id, parentId = null, role = Role.ASSISTANT, content = content, createdAt = 0L,
    )

    private fun marker(anchorMsgId: String?, at: Long, kind: ThreadMarkerKind = ThreadMarkerKind.COMPACTION_RUN) = ThreadMarker(
        id = "marker-$anchorMsgId-$at", rootId = "root", anchorMsgId = anchorMsgId, kind = kind, at = at,
        source = ThreadMarkerSource.SYSTEM,
    )

    // ---- newestBoundaryAnchorId --------------------------------------------------------------

    @Test fun `no markers means no boundary`() {
        assertNull(CompactionBoundary.newestBoundaryAnchorId(emptyList(), listOf("m1", "m2")))
    }

    @Test fun `a single COMPACTION_RUN marker on the path is the boundary`() {
        val result = CompactionBoundary.newestBoundaryAnchorId(listOf(marker("m1", at = 10L)), listOf("m1", "m2", "m3"))
        assertEquals("m1", result)
    }

    @Test fun `picks the marker whose anchor is closest to the leaf, not the latest timestamp`() {
        // m1's marker has the LATER timestamp but sits earlier in the path — the leaf-closest
        // anchor (m2) must win, since that's the one that actually governs this path's prompt.
        val markers = listOf(marker("m1", at = 100L), marker("m2", at = 10L))
        val result = CompactionBoundary.newestBoundaryAnchorId(markers, listOf("m1", "m2", "m3"))
        assertEquals("m2", result)
    }

    @Test fun `a marker whose anchor is not on this path is ignored`() {
        val markers = listOf(marker("off-path", at = 999L))
        assertNull(CompactionBoundary.newestBoundaryAnchorId(markers, listOf("m1", "m2")))
    }

    @Test fun `non-COMPACTION_RUN marker kinds are ignored`() {
        val markers = listOf(marker("m1", at = 10L, kind = ThreadMarkerKind.SESSION_START))
        assertNull(CompactionBoundary.newestBoundaryAnchorId(markers, listOf("m1", "m2")))
    }

    @Test fun `a marker with a null anchor is ignored`() {
        val markers = listOf(marker(null, at = 10L))
        assertNull(CompactionBoundary.newestBoundaryAnchorId(markers, listOf("m1", "m2")))
    }

    // ---- effectivePath ------------------------------------------------------------------------

    @Test fun `a null boundary returns the path unchanged`() {
        val path = listOf(node("m1", "a"), node("m2", "b"))
        assertEquals(path, CompactionBoundary.effectivePath(path, null, emptyList()))
    }

    @Test fun `an unresolvable boundary id returns the path unchanged`() {
        val path = listOf(node("m1", "a"), node("m2", "b"))
        assertEquals(path, CompactionBoundary.effectivePath(path, "not-in-path", emptyList()))
    }

    @Test fun `messages after the boundary are untouched even with a matching receipt entry`() {
        val path = listOf(node("m1", "old"), node("m2", "new"))
        val entries = listOf(CompactedMessage("m2", MessageFate.GIST, resolution(Fidelity.F1), "should be ignored"))
        val result = CompactionBoundary.effectivePath(path, boundaryAnchorId = "m1", receiptEntries = entries)
        assertEquals(listOf("old", "new"), result.map { it.content })
    }

    @Test fun `a GIST entry at or before the boundary replaces the content`() {
        val path = listOf(node("m1", "the original text"))
        val entries = listOf(CompactedMessage("m1", MessageFate.GIST, resolution(Fidelity.F1), "the gist"))
        val result = CompactionBoundary.effectivePath(path, "m1", entries)
        assertEquals(listOf("the gist"), result.map { it.content })
    }

    @Test fun `a DROPPED entry removes the message entirely`() {
        val path = listOf(node("m1", "keep"), node("m2", "drop me"))
        val entries = listOf(
            CompactedMessage("m1", MessageFate.KEPT_VERBATIM, resolution(Fidelity.F3), "keep"),
            CompactedMessage("m2", MessageFate.DROPPED, resolution(Fidelity.F0), null),
        )
        val result = CompactionBoundary.effectivePath(path, "m2", entries)
        assertEquals(listOf("m1"), result.map { it.id })
    }

    @Test fun `KEPT_VERBATIM keeps the original node untouched`() {
        val original = node("m1", "exact text")
        val entries = listOf(CompactedMessage("m1", MessageFate.KEPT_VERBATIM, resolution(Fidelity.F3), "exact text"))
        val result = CompactionBoundary.effectivePath(listOf(original), "m1", entries)
        assertEquals(original, result.single())
    }

    @Test fun `a message at or before the boundary with no receipt entry is kept as-is`() {
        val path = listOf(node("m1", "untouched"), node("m2", "boundary"))
        val result = CompactionBoundary.effectivePath(path, "m2", emptyList())
        assertEquals(listOf("untouched", "boundary"), result.map { it.content })
    }

    @Test fun `a null text on a non-dropped fate falls back to the original content rather than blanking it`() {
        val path = listOf(node("m1", "fallback text"))
        val entries = listOf(CompactedMessage("m1", MessageFate.GIST, resolution(Fidelity.F1), text = null))
        val result = CompactionBoundary.effectivePath(path, "m1", entries)
        assertEquals("fallback text", result.single().content)
    }

    @Test fun `a full run mixes drop, gist, and verbatim correctly across the whole path`() {
        val path = listOf(node("root", "system"), node("m1", "droppable"), node("m2", "ordinary"), node("m3", "protected"), node("m4", "after boundary"))
        val entries = listOf(
            CompactedMessage("root", MessageFate.GIST, resolution(Fidelity.F1), "sys gist"),
            CompactedMessage("m1", MessageFate.DROPPED, resolution(Fidelity.F0), null),
            CompactedMessage("m2", MessageFate.GIST, resolution(Fidelity.F1), "gist of ordinary"),
            CompactedMessage("m3", MessageFate.KEPT_VERBATIM, resolution(Fidelity.F3), "protected"),
        )
        val result = CompactionBoundary.effectivePath(path, boundaryAnchorId = "m3", receiptEntries = entries)
        assertEquals(listOf("root", "m2", "m3", "m4"), result.map { it.id })
        assertEquals(listOf("sys gist", "gist of ordinary", "protected", "after boundary"), result.map { it.content })
    }

    private fun resolution(fidelity: Fidelity) = ResolvedFidelity(
        fidelity = fidelity, mustInclude = false, isFailureTombstone = false, reason = FidelityReason.DEFAULT_ORDINARY,
    )
}
