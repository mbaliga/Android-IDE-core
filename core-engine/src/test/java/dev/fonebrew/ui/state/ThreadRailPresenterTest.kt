package dev.fonebrew.ui.state

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.curation.CompactionDirective
import dev.fonebrew.domain.curation.Fidelity
import dev.fonebrew.domain.curation.MessageBookmark
import dev.fonebrew.domain.curation.BookmarkKind
import dev.fonebrew.domain.curation.MessageRef
import dev.fonebrew.domain.curation.Verdict
import dev.fonebrew.domain.curation.Version
import dev.fonebrew.domain.provenance.ProvenanceState
import dev.fonebrew.domain.thread.ThreadMarker
import dev.fonebrew.domain.thread.ThreadMarkerKind
import dev.fonebrew.domain.thread.ThreadMarkerSource
import dev.fonebrew.domain.tree.PathView
import dev.fonebrew.domain.tree.TreeFork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ThreadRailPresenter] — pure, deterministic, no Android/Compose.
 */
class ThreadRailPresenterTest {

    private val t0 = 1_700_000_000_000L

    private fun node(
        id: String,
        content: String = "content-$id",
        role: Role = Role.ASSISTANT,
        metadata: Map<String, String> = emptyMap(),
    ) = MessageNode(id = id, parentId = null, role = role, content = content, createdAt = t0, metadata = metadata)

    /** A straight, unbranched path — no forks anywhere. */
    private fun step(n: MessageNode) = PathView.Step(node = n, alternativeCount = 1, activeAlternative = 0)

    @Test fun `one dash per step, in the same order`() {
        val steps = listOf(step(node("m1")), step(node("m2")), step(node("m3")))
        val view = ThreadRailPresenter.present(steps)
        assertEquals(listOf("m1", "m2", "m3"), view.dashes.map { it.nodeId })
        assertFalse(view.isEmpty)
    }

    @Test fun `empty path yields an empty rail`() {
        assertTrue(ThreadRailPresenter.present(emptyList()).isEmpty)
    }

    // ---- thickness ladder ----

    @Test fun `an ordinary message with no signals gets the F1 default thickness`() {
        val view = ThreadRailPresenter.present(listOf(step(node("m1"))))
        assertEquals(ThreadRailPresenter.THICKNESS_F1_DP, view.dashes.single().thicknessDp)
        assertFalse(view.dashes.single().tombstone)
    }

    @Test fun `a plus-2 verdict floors thickness to F3`() {
        val view = ThreadRailPresenter.present(
            steps = listOf(step(node("m1"))),
            verdicts = mapOf("m1" to Verdict("m1", 2, t0)),
        )
        assertEquals(ThreadRailPresenter.THICKNESS_F3_DP, view.dashes.single().thicknessDp)
    }

    @Test fun `a bookmark floors thickness to F2`() {
        val bookmark = MessageBookmark(id = "b1", ref = MessageRef("m1"), kind = BookmarkKind.REFERENCE, at = t0)
        val view = ThreadRailPresenter.present(
            steps = listOf(step(node("m1"))),
            bookmarks = mapOf("m1" to listOf(bookmark)),
        )
        assertEquals(ThreadRailPresenter.THICKNESS_F2_DP, view.dashes.single().thicknessDp)
        assertTrue(view.dashes.single().bookmarked)
    }

    @Test fun `an explicit F0 directive thins to F0`() {
        val view = ThreadRailPresenter.present(
            steps = listOf(step(node("m1"))),
            directives = mapOf("m1" to CompactionDirective("m1", mustInclude = false, fidelity = Fidelity.F0)),
        )
        assertEquals(ThreadRailPresenter.THICKNESS_F0_DP, view.dashes.single().thicknessDp)
    }

    @Test fun `a minus-2 verdict is a 1dp tombstone, not just thin`() {
        val view = ThreadRailPresenter.present(
            steps = listOf(step(node("m1"))),
            verdicts = mapOf("m1" to Verdict("m1", -2, t0)),
        )
        val dash = view.dashes.single()
        assertEquals(ThreadRailPresenter.THICKNESS_TOMBSTONE_DP, dash.thicknessDp)
        assertTrue(dash.tombstone)
    }

    // ---- version spine, restricted to the active path ----

    @Test fun `a node before a later Version tip on the same path is floored to F2`() {
        val steps = listOf(step(node("m1")), step(node("m2")), step(node("tip")))
        val view = ThreadRailPresenter.present(
            steps = steps,
            versionsByTip = mapOf("tip" to Version(id = "v1", branchTipMsgId = "tip", name = "v1", at = t0)),
        )
        // m1 and m2 are ancestors of the tip on this path -> F2 floor.
        assertEquals(ThreadRailPresenter.THICKNESS_F2_DP, view.dashes[0].thicknessDp)
        assertEquals(ThreadRailPresenter.THICKNESS_F2_DP, view.dashes[1].thicknessDp)
        // The tip itself is also floored (it's on its own spine).
        assertEquals(ThreadRailPresenter.THICKNESS_F2_DP, view.dashes[2].thicknessDp)
        assertTrue(view.dashes[2].versionFlag)
        assertFalse(view.dashes[0].versionFlag)
    }

    @Test fun `a node after the only Version tip is NOT floored`() {
        val steps = listOf(step(node("tip")), step(node("after")))
        val view = ThreadRailPresenter.present(
            steps = steps,
            versionsByTip = mapOf("tip" to Version(id = "v1", branchTipMsgId = "tip", name = "v1", at = t0)),
        )
        assertEquals(ThreadRailPresenter.THICKNESS_F2_DP, view.dashes[0].thicknessDp)
        assertEquals(ThreadRailPresenter.THICKNESS_F1_DP, view.dashes[1].thicknessDp) // ordinary default
    }

    // ---- marker accents (chapter / session / compaction), keyed by anchor ----

    @Test fun `chapter, session-start, and compaction-run markers surface as distinct accents`() {
        fun marker(kind: ThreadMarkerKind, anchor: String) = ThreadMarker(
            id = "mk-$anchor-$kind", rootId = "root", anchorMsgId = anchor, kind = kind,
            label = if (kind == ThreadMarkerKind.CHAPTER) "Chapter one" else null,
            at = t0, source = ThreadMarkerSource.USER,
        )
        val steps = listOf(step(node("m1")), step(node("m2")), step(node("m3")))
        val view = ThreadRailPresenter.present(
            steps = steps,
            markersByAnchor = mapOf(
                "m1" to listOf(marker(ThreadMarkerKind.CHAPTER, "m1")),
                "m2" to listOf(marker(ThreadMarkerKind.SESSION_START, "m2")),
                "m3" to listOf(marker(ThreadMarkerKind.COMPACTION_RUN, "m3")),
            ),
        )
        assertTrue(view.dashes[0].chapterBracket)
        assertFalse(view.dashes[0].sessionHairline)
        assertFalse(view.dashes[0].compactionDiamond)

        assertTrue(view.dashes[1].sessionHairline)
        assertFalse(view.dashes[1].chapterBracket)

        assertTrue(view.dashes[2].compactionDiamond)
        assertFalse(view.dashes[2].sessionHairline)
    }

    @Test fun `a node with no marker gets no accents`() {
        val view = ThreadRailPresenter.present(listOf(step(node("m1"))))
        val d = view.dashes.single()
        assertFalse(d.chapterBracket)
        assertFalse(d.sessionHairline)
        assertFalse(d.compactionDiamond)
    }

    // ---- lineage branch glyph (root-node metadata, TreeFork's own keys) ----

    @Test fun `a forked root carries the FORK lineage glyph`() {
        val root = node("root", metadata = mapOf(TreeFork.LINEAGE_KIND_KEY to TreeFork.LineageKind.FORK.name))
        val view = ThreadRailPresenter.present(listOf(step(root), step(node("m2"))))
        assertEquals(TreeFork.LineageKind.FORK, view.dashes[0].lineageBranch)
        assertNull(view.dashes[1].lineageBranch)
    }

    @Test fun `a spawned root carries the SPAWN lineage glyph`() {
        val root = node("root", metadata = mapOf(TreeFork.LINEAGE_KIND_KEY to TreeFork.LineageKind.SPAWN.name))
        val view = ThreadRailPresenter.present(listOf(step(root)))
        assertEquals(TreeFork.LineageKind.SPAWN, view.dashes[0].lineageBranch)
    }

    @Test fun `an ordinary root carries no lineage glyph`() {
        val view = ThreadRailPresenter.present(listOf(step(node("root"))))
        assertNull(view.dashes[0].lineageBranch)
    }

    // ---- provenance dual-channel (structural — ProvenanceTest's own idiom) ----

    @Test fun `provenance differs, non-colour channel differs`() {
        val steps = listOf(step(node("m1")), step(node("m2")), step(node("m3")), step(node("m4")))
        val byId = mapOf("m1" to ProvenanceState.LOCAL, "m2" to ProvenanceState.CLOUD, "m3" to ProvenanceState.MIXED, "m4" to ProvenanceState.UNKNOWN)
        val view = ThreadRailPresenter.present(steps, provenanceOf = { byId.getValue(it.id) })

        for (a in view.dashes) for (b in view.dashes) {
            if (a.nodeId != b.nodeId) {
                assertNotEquals("provenance itself must differ per fixture", a.provenance, b.provenance)
                assertNotEquals(
                    "non-colour glyph (iconKey) must differ whenever provenance differs",
                    a.provenance.iconKey,
                    b.provenance.iconKey,
                )
                assertNotEquals(
                    "non-colour label must differ whenever provenance differs",
                    a.provenance.label,
                    b.provenance.label,
                )
            }
        }
    }

    @Test fun `provenanceOf defaults to UNKNOWN — no claim, not guessed`() {
        val view = ThreadRailPresenter.present(listOf(step(node("m1"))))
        assertEquals(ProvenanceState.UNKNOWN, view.dashes.single().provenance)
    }

    // ---- the live, in-flight-generation dash ----

    @Test fun `no live generation means no trailing dash`() {
        val view = ThreadRailPresenter.present(listOf(step(node("m1"))))
        assertEquals(1, view.dashes.size)
        assertFalse(view.dashes.any { it.live })
    }

    @Test fun `a local live generation appends a still dash - no pulse`() {
        val view = ThreadRailPresenter.present(
            steps = listOf(step(node("m1"))),
            live = ThreadRailPresenter.LiveGeneration(watched = false),
        )
        val liveDash = view.dashes.last()
        assertTrue(liveDash.live)
        assertEquals(ThreadRailPresenter.LIVE_DASH_ID, liveDash.nodeId)
        assertEquals(ProvenanceState.LOCAL, liveDash.provenance)
        assertFalse("motion is reserved for watched-cloud generation only", liveDash.pulseWatched)
    }

    @Test fun `a watched-cloud live generation appends a pulsing dash`() {
        val view = ThreadRailPresenter.present(
            steps = listOf(step(node("m1"))),
            live = ThreadRailPresenter.LiveGeneration(watched = true),
        )
        val liveDash = view.dashes.last()
        assertEquals(ProvenanceState.CLOUD, liveDash.provenance)
        assertTrue(liveDash.pulseWatched)
    }

    // ---- pure gesture math ----

    @Test fun `indexForFraction clamps and spans the full dash count`() {
        assertEquals(-1, ThreadRailPresenter.indexForFraction(0, 0.5f))
        assertEquals(0, ThreadRailPresenter.indexForFraction(1, 0.9f))
        assertEquals(0, ThreadRailPresenter.indexForFraction(5, -1f))
        assertEquals(4, ThreadRailPresenter.indexForFraction(5, 2f))
        assertEquals(0, ThreadRailPresenter.indexForFraction(5, 0f))
        assertEquals(4, ThreadRailPresenter.indexForFraction(5, 1f))
        assertEquals(2, ThreadRailPresenter.indexForFraction(5, 0.5f))
    }

    @Test fun `ticksCrossed is the absolute step distance`() {
        assertEquals(0, ThreadRailPresenter.ticksCrossed(3, 3))
        assertEquals(4, ThreadRailPresenter.ticksCrossed(1, 5))
        assertEquals(4, ThreadRailPresenter.ticksCrossed(5, 1))
    }
}
