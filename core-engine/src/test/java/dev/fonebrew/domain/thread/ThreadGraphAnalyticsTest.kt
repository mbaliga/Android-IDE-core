package dev.fonebrew.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThreadGraphAnalyticsTest {

    private val at = Instant.parse("2026-08-12T00:00:00Z")

    private fun n(
        id: String,
        kind: ThreadNodeKind = ThreadNodeKind.MESSAGE,
        rootId: String = "root-1",
        parentId: String? = null,
        outcome: DelegationOutcome? = null,
        atMillis: Long = at.toEpochMilli(),
    ) = ThreadGraphNode(id = id, kind = kind, rootId = rootId, parentId = parentId, at = Instant.ofEpochMilli(atMillis), outcome = outcome)

    private fun e(from: String, to: String, kind: ThreadEdgeKind) = ThreadGraphEdge(from, to, kind)

    private fun graph(nodes: List<ThreadGraphNode>, edges: List<ThreadGraphEdge> = emptyList()) =
        ThreadGraph(generatedAtUtc = at, nodes = nodes, edges = edges)

    // ---- nodeStats -----------------------------------------------------------------------------

    @Test fun `a node with no edges gets all-zero stats`() {
        val g = graph(listOf(n("a")))
        val stats = ThreadGraphAnalytics.nodeStats(g).single()
        assertEquals(ThreadGraphAnalytics.NodeStats("a", 0, 0, 0), stats)
    }

    @Test fun `a straight reply chain has zero branchiness throughout`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "a")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY), e("a", "b", ThreadEdgeKind.REPLY)),
        )
        val stats = ThreadGraphAnalytics.nodeStats(g).associateBy { it.nodeId }
        assertEquals(0, stats.getValue("root").branchiness)
        assertEquals(1, stats.getValue("root").outDegree)
        assertEquals(0, stats.getValue("a").branchiness)
        assertEquals(1, stats.getValue("a").inDegree)
        assertEquals(1, stats.getValue("a").outDegree)
        assertEquals(0, stats.getValue("b").branchiness)
        assertEquals(1, stats.getValue("b").inDegree)
        assertEquals(0, stats.getValue("b").outDegree)
    }

    @Test fun `a branch point with two children has branchiness 1, with three children has branchiness 2`() {
        val g2 = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "root")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY), e("root", "b", ThreadEdgeKind.REPLY)),
        )
        assertEquals(1, ThreadGraphAnalytics.nodeStats(g2).single { it.nodeId == "root" }.branchiness)

        val g3 = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "root"), n("c", parentId = "root")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY), e("root", "b", ThreadEdgeKind.REPLY), e("root", "c", ThreadEdgeKind.REPLY)),
        )
        assertEquals(2, ThreadGraphAnalytics.nodeStats(g3).single { it.nodeId == "root" }.branchiness)
    }

    @Test fun `a MARKER_ANCHOR edge counts toward plain degree but never toward branchiness`() {
        val g = graph(
            listOf(n("msg"), n("mk", kind = ThreadNodeKind.MARKER, parentId = "msg")),
            listOf(e("mk", "msg", ThreadEdgeKind.MARKER_ANCHOR)),
        )
        val stats = ThreadGraphAnalytics.nodeStats(g).associateBy { it.nodeId }
        assertEquals(1, stats.getValue("mk").outDegree)
        assertEquals(0, stats.getValue("mk").branchiness)
        assertEquals(1, stats.getValue("msg").inDegree)
        assertEquals(0, stats.getValue("msg").branchiness)
    }

    @Test fun `nodeStats is sorted by nodeId`() {
        val g = graph(listOf(n("z"), n("a"), n("m")))
        assertEquals(listOf("a", "m", "z"), ThreadGraphAnalytics.nodeStats(g).map { it.nodeId })
    }

    // ---- decisionOutcomeRollup -------------------------------------------------------------------

    @Test fun `an empty graph rolls up to EMPTY_ROLLUP`() {
        assertEquals(ThreadGraphAnalytics.EMPTY_ROLLUP, ThreadGraphAnalytics.decisionOutcomeRollup(graph(emptyList())))
    }

    @Test fun `a graph with no outcome-carrying nodes rolls up to zero, not fabricated`() {
        val g = graph(listOf(n("a"), n("b")))
        assertEquals(ThreadGraphAnalytics.EMPTY_ROLLUP, ThreadGraphAnalytics.decisionOutcomeRollup(g))
    }

    @Test fun `decided sums kept + reverted + pending across DELEGATION nodes`() {
        val g = graph(
            listOf(
                n("d1", kind = ThreadNodeKind.DELEGATION, outcome = DelegationOutcome.KEPT),
                n("d2", kind = ThreadNodeKind.DELEGATION, outcome = DelegationOutcome.KEPT),
                n("d3", kind = ThreadNodeKind.DELEGATION, outcome = DelegationOutcome.REVERTED),
                n("d4", kind = ThreadNodeKind.DELEGATION, outcome = DelegationOutcome.PENDING),
                n("msg"), // no outcome — must not be counted
            ),
        )
        val rollup = ThreadGraphAnalytics.decisionOutcomeRollup(g)
        assertEquals(4, rollup.decided)
        assertEquals(2, rollup.kept)
        assertEquals(1, rollup.reverted)
        assertEquals(1, rollup.pending)
        assertEquals(rollup.decided, rollup.kept + rollup.reverted + rollup.pending)
    }

    // ---- chapterSummaries -----------------------------------------------------------------------

    private fun marker(
        id: String,
        rootId: String = "root-1",
        kind: ThreadMarkerKind,
        atMillis: Long,
        label: String? = null,
    ) = ThreadMarker(
        id = id, rootId = rootId, kind = kind,
        // CHAPTER requires a non-blank anchorMsgId (ThreadMarker's own init) — this fixture
        // corpus never needs the anchor to resolve to a real graph node (chapterSummaries reads
        // the marker's own `at`/`label`/`rootId`, never anchorMsgId), so any non-blank id honestly
        // satisfies the domain type without fabricating a fake message elsewhere.
        anchorMsgId = if (kind == ThreadMarkerKind.CHAPTER) "$id-anchor" else null,
        label = label, at = atMillis, source = ThreadMarkerSource.USER,
    )

    @Test fun `no CHAPTER markers yields an empty summary list, never a fabricated one`() {
        val g = graph(listOf(n("a", atMillis = 100L)))
        val markers = listOf(marker("mk-1", kind = ThreadMarkerKind.SESSION_START, atMillis = 50L))
        assertTrue(ThreadGraphAnalytics.chapterSummaries(g, markers).isEmpty())
    }

    @Test fun `a single chapter counts every message at or after its own timestamp`() {
        val g = graph(
            listOf(
                n("before", atMillis = 100L),
                n("at-boundary", atMillis = 200L),
                n("after", atMillis = 300L),
            ),
        )
        val markers = listOf(marker("ch-1", kind = ThreadMarkerKind.CHAPTER, atMillis = 200L, label = "Chapter one"))
        val summary = ThreadGraphAnalytics.chapterSummaries(g, markers).single()
        assertEquals("ch-1", summary.markerId)
        assertEquals("Chapter one", summary.label)
        assertEquals(2, summary.nodeCount) // at-boundary + after, never "before"
    }

    @Test fun `two chapters split messages at the second chapter's own timestamp, exclusive`() {
        val g = graph(
            listOf(
                n("m1", atMillis = 100L),
                n("m2", atMillis = 200L), // exactly at chapter 2's boundary -> belongs to chapter 2
                n("m3", atMillis = 300L),
            ),
        )
        val markers = listOf(
            marker("ch-1", kind = ThreadMarkerKind.CHAPTER, atMillis = 100L, label = "One"),
            marker("ch-2", kind = ThreadMarkerKind.CHAPTER, atMillis = 200L, label = "Two"),
        )
        val summaries = ThreadGraphAnalytics.chapterSummaries(g, markers).associateBy { it.markerId }
        assertEquals(1, summaries.getValue("ch-1").nodeCount) // only m1
        assertEquals(2, summaries.getValue("ch-2").nodeCount) // m2 + m3
    }

    @Test fun `sessionsCrossed counts SESSION_START markers within the chapter's own span`() {
        val g = graph(listOf(n("m1", atMillis = 100L)))
        val markers = listOf(
            marker("ch-1", kind = ThreadMarkerKind.CHAPTER, atMillis = 0L, label = "One"),
            marker("s-in", kind = ThreadMarkerKind.SESSION_START, atMillis = 50L),
            marker("s-also-in", kind = ThreadMarkerKind.SESSION_START, atMillis = 90L),
        )
        assertEquals(2, ThreadGraphAnalytics.chapterSummaries(g, markers).single().sessionsCrossed)
    }

    @Test fun `chapters in different conversations are summarized independently`() {
        val g = graph(
            listOf(
                n("a1", rootId = "root-a", atMillis = 10L),
                n("b1", rootId = "root-b", atMillis = 10L),
            ),
        )
        val markers = listOf(
            marker("ch-a", rootId = "root-a", kind = ThreadMarkerKind.CHAPTER, atMillis = 0L, label = "A"),
            marker("ch-b", rootId = "root-b", kind = ThreadMarkerKind.CHAPTER, atMillis = 0L, label = "B"),
        )
        val summaries = ThreadGraphAnalytics.chapterSummaries(g, markers).associateBy { it.markerId }
        assertEquals(1, summaries.getValue("ch-a").nodeCount)
        assertEquals(1, summaries.getValue("ch-b").nodeCount)
        assertEquals("root-a", summaries.getValue("ch-a").rootId)
    }

    // ---- hotPaths ---------------------------------------------------------------------------------

    @Test fun `a straight chain has no hot paths`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "a")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY), e("a", "b", ThreadEdgeKind.REPLY)),
        )
        assertTrue(ThreadGraphAnalytics.hotPaths(g).isEmpty())
    }

    @Test fun `a node with two recorded continuations is a hot path with the correct ancestry`() {
        val g = graph(
            listOf(n("root"), n("mid", parentId = "root"), n("a", parentId = "mid"), n("b", parentId = "mid")),
            listOf(
                e("root", "mid", ThreadEdgeKind.REPLY),
                e("mid", "a", ThreadEdgeKind.REPLY),
                e("mid", "b", ThreadEdgeKind.REPLY),
            ),
        )
        val hotPath = ThreadGraphAnalytics.hotPaths(g).single()
        assertEquals("mid", hotPath.headNodeId)
        assertEquals(2, hotPath.branchCount)
        assertEquals(listOf("root", "mid"), hotPath.ancestryPath)
    }

    @Test fun `hotPaths ancestry follows a FORK edge across conversation roots`() {
        val g = graph(
            listOf(
                n("root-1", rootId = "root-1"),
                n("fork-root", kind = ThreadNodeKind.FORK_ROOT, rootId = "fork-root", parentId = "root-1"),
                n("a", rootId = "fork-root", parentId = "fork-root"),
                n("b", rootId = "fork-root", parentId = "fork-root"),
            ),
            listOf(
                e("root-1", "fork-root", ThreadEdgeKind.FORK),
                e("fork-root", "a", ThreadEdgeKind.REPLY),
                e("fork-root", "b", ThreadEdgeKind.REPLY),
            ),
        )
        val hotPath = ThreadGraphAnalytics.hotPaths(g).single()
        assertEquals(listOf("root-1", "fork-root"), hotPath.ancestryPath)
    }

    @Test fun `hotPaths sorts by branchCount descending, then headNodeId ascending`() {
        // Two independent branch points: "root" branches into 2, "mid" branches into 3.
        val g2 = graph(
            listOf(
                n("root"), n("r-a", parentId = "root"), n("r-b", parentId = "root"),
                n("mid"), n("m-a", parentId = "mid"), n("m-b", parentId = "mid"), n("m-c", parentId = "mid"),
            ),
            listOf(
                e("root", "r-a", ThreadEdgeKind.REPLY), e("root", "r-b", ThreadEdgeKind.REPLY),
                e("mid", "m-a", ThreadEdgeKind.REPLY), e("mid", "m-b", ThreadEdgeKind.REPLY), e("mid", "m-c", ThreadEdgeKind.REPLY),
            ),
        )
        val paths = ThreadGraphAnalytics.hotPaths(g2)
        assertEquals(listOf("mid", "root"), paths.map { it.headNodeId }) // branchCount 3 before 2
    }

    @Test fun `minBranchCount raises the bar for what counts as hot`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "root")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY), e("root", "b", ThreadEdgeKind.REPLY)),
        )
        assertTrue(ThreadGraphAnalytics.hotPaths(g, minBranchCount = 3).isEmpty())
        assertEquals(1, ThreadGraphAnalytics.hotPaths(g, minBranchCount = 2).size)
    }

    @Test fun `hotPaths rejects a minBranchCount below 1`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadGraphAnalytics.hotPaths(graph(emptyList()), minBranchCount = 0)
        }
    }

    // ---- hotPathEdges -------------------------------------------------------------------------

    @Test fun `no branch points yields no hot-path edges`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY)),
        )
        assertTrue(ThreadGraphAnalytics.hotPathEdges(g).isEmpty())
    }

    @Test fun `a two-child branch point yields exactly one INFERRED HOT_PATH edge with a structural because`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "root")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY), e("root", "b", ThreadEdgeKind.REPLY)),
        )
        val hotEdges = ThreadGraphAnalytics.hotPathEdges(g)
        val edge = hotEdges.single()
        assertEquals(ThreadEdgeKind.HOT_PATH, edge.kind)
        assertEquals(EdgeDerivation.INFERRED, edge.derivation)
        assertEquals("a", edge.from) // sorted-id pick
        assertEquals("b", edge.to)
        assertEquals("shares a branch point (root) recorded with 2 continuations", edge.because)
    }

    @Test fun `a three-child branch point yields n-1 star-shaped edges, never every pair`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "root"), n("c", parentId = "root")),
            listOf(
                e("root", "a", ThreadEdgeKind.REPLY), e("root", "b", ThreadEdgeKind.REPLY), e("root", "c", ThreadEdgeKind.REPLY),
            ),
        )
        val hotEdges = ThreadGraphAnalytics.hotPathEdges(g)
        assertEquals(2, hotEdges.size) // 3 children -> 2 edges, not 3-choose-2 = 3
        assertTrue(hotEdges.all { it.from == "a" })
        assertEquals(setOf("b", "c"), hotEdges.map { it.to }.toSet())
    }

    @Test fun `every hot-path edge is INFERRED, never EXTRACTED — the adversarial fixture's own contract`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "root")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY), e("root", "b", ThreadEdgeKind.REPLY)),
        )
        ThreadGraphAnalytics.hotPathEdges(g).forEach { edge ->
            assertEquals(EdgeDerivation.INFERRED, edge.derivation)
        }
    }

    @Test fun `every hot-path because is purely structural — names only the branch point and a count`() {
        // Mechanical guard mirroring ObserverScriptTest's own banned-word discipline: matches the
        // exact structural template, never free text that could smuggle in a judgment/interpretive
        // claim (fixtures/thread/adversarial/thread-graph-hot-path-extracted-derivation names the
        // exact defect this rejects).
        val pattern = Regex("""^shares a branch point \(.+\) recorded with \d+ continuations$""")
        val g = graph(
            listOf(n("root"), n("a", parentId = "root"), n("b", parentId = "root"), n("c", parentId = "root")),
            listOf(
                e("root", "a", ThreadEdgeKind.REPLY), e("root", "b", ThreadEdgeKind.REPLY), e("root", "c", ThreadEdgeKind.REPLY),
            ),
        )
        ThreadGraphAnalytics.hotPathEdges(g).forEach { edge ->
            assertTrue("because '${edge.because}' doesn't match the purely structural template", pattern.matches(edge.because!!))
        }
    }

    @Test fun `hotPathEdges rejects a minBranchCount below 1`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadGraphAnalytics.hotPathEdges(graph(emptyList()), minBranchCount = 0)
        }
    }

    // ---- orphanedBranches ---------------------------------------------------------------------

    @Test fun `a leaf message with no bookmark is an orphaned branch`() {
        val g = graph(
            listOf(n("root"), n("leaf", parentId = "root")),
            listOf(e("root", "leaf", ThreadEdgeKind.REPLY)),
        )
        assertEquals(listOf("leaf"), ThreadGraphAnalytics.orphanedBranches(g).map { it.nodeId })
    }

    @Test fun `a leaf with a DECISION bookmark anchored to it is not orphaned`() {
        val g = graph(
            listOf(
                n("root"), n("leaf", parentId = "root"),
                n("dec-1", kind = ThreadNodeKind.DECISION, parentId = "leaf"),
            ),
            listOf(
                e("root", "leaf", ThreadEdgeKind.REPLY),
                e("dec-1", "leaf", ThreadEdgeKind.DECISION_ANCHOR),
            ),
        )
        assertTrue(ThreadGraphAnalytics.orphanedBranches(g).none { it.nodeId == "leaf" })
    }

    @Test fun `a node with a recorded continuation is never orphaned`() {
        val g = graph(
            listOf(n("root"), n("a", parentId = "root")),
            listOf(e("root", "a", ThreadEdgeKind.REPLY)),
        )
        assertTrue(ThreadGraphAnalytics.orphanedBranches(g).none { it.nodeId == "root" })
    }

    @Test fun `a MARKER node itself is never reported as an orphaned branch`() {
        val g = graph(
            listOf(n("msg"), n("mk", kind = ThreadNodeKind.MARKER, parentId = "msg")),
            listOf(e("mk", "msg", ThreadEdgeKind.MARKER_ANCHOR)),
        )
        assertTrue(ThreadGraphAnalytics.orphanedBranches(g).none { it.nodeId == "mk" })
        // "msg" itself has no structural child and no DECISION bookmark, so it IS orphaned.
        assertEquals(listOf("msg"), ThreadGraphAnalytics.orphanedBranches(g).map { it.nodeId })
    }

    @Test fun `a leafless RUN_ROOT is still eligible to be orphaned`() {
        val g = graph(listOf(n("run-1", kind = ThreadNodeKind.RUN_ROOT)))
        assertEquals(listOf("run-1"), ThreadGraphAnalytics.orphanedBranches(g).map { it.nodeId })
    }

    @Test fun `orphanedBranches is sorted by nodeId`() {
        val g = graph(
            listOf(n("root"), n("z", parentId = "root"), n("a", parentId = "root")),
            listOf(e("root", "z", ThreadEdgeKind.REPLY), e("root", "a", ThreadEdgeKind.REPLY)),
        )
        assertEquals(listOf("a", "z"), ThreadGraphAnalytics.orphanedBranches(g).map { it.nodeId })
    }

    @Test fun `an empty graph has no orphaned branches, no hot paths, and no chapter summaries`() {
        val g = graph(emptyList())
        assertTrue(ThreadGraphAnalytics.orphanedBranches(g).isEmpty())
        assertTrue(ThreadGraphAnalytics.hotPaths(g).isEmpty())
        assertTrue(ThreadGraphAnalytics.chapterSummaries(g, emptyList()).isEmpty())
        assertNull(ThreadGraphAnalytics.nodeStats(g).firstOrNull())
    }
}
