package dev.aarso.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ObserverScriptTest {

    private val at = Instant.parse("2026-08-12T00:00:00Z")

    private fun n(id: String, kind: ThreadNodeKind) = ThreadGraphNode(id = id, kind = kind, rootId = "root-1", at = at)

    // ---- describe(graph) ----------------------------------------------------------------------

    @Test fun `an empty graph produces the honest 'no activity yet' remark, not an empty list`() {
        val remarks = ObserverScript.describe(ThreadGraph(generatedAtUtc = at))
        assertEquals(listOf("No conversation activity captured yet."), remarks)
    }

    @Test fun `describe reports correct counts per node kind`() {
        val graph = ThreadGraph(
            generatedAtUtc = at,
            nodes = listOf(
                n("m1", ThreadNodeKind.MESSAGE), n("m2", ThreadNodeKind.MESSAGE), n("m3", ThreadNodeKind.MESSAGE),
                n("f1", ThreadNodeKind.FORK_ROOT),
                n("s1", ThreadNodeKind.SPAWN_ROOT), n("s2", ThreadNodeKind.SPAWN_ROOT),
                n("mk1", ThreadNodeKind.MARKER),
                n("dg1", ThreadNodeKind.DELEGATION),
            ),
        )
        val remarks = ObserverScript.describe(graph)
        assertTrue(remarks.any { it.contains("3 messages") })
        assertTrue(remarks.any { it.contains("1 fork branched") })
        assertTrue(remarks.any { it.contains("2 conversations spawned") })
        assertTrue(remarks.any { it.contains("1 marker placed") })
        assertTrue(remarks.any { it.contains("1 delegation recorded") })
    }

    @Test fun `describe omits a remark line entirely for a zero count rather than saying '0 forks'`() {
        val graph = ThreadGraph(generatedAtUtc = at, nodes = listOf(n("m1", ThreadNodeKind.MESSAGE)))
        val remarks = ObserverScript.describe(graph)
        assertTrue(remarks.none { it.contains("fork") })
        assertTrue(remarks.none { it.contains("spawn") })
        assertTrue(remarks.none { it.contains("marker") })
        assertTrue(remarks.none { it.contains("delegation") })
    }

    @Test fun `singular vs plural wording matches the count`() {
        val one = ThreadGraph(generatedAtUtc = at, nodes = listOf(n("m1", ThreadNodeKind.MESSAGE)))
        assertTrue(ObserverScript.describe(one).any { it == "1 message across the captured tree." })

        val two = ThreadGraph(generatedAtUtc = at, nodes = listOf(n("m1", ThreadNodeKind.MESSAGE), n("m2", ThreadNodeKind.MESSAGE)))
        assertTrue(ObserverScript.describe(two).any { it == "2 messages across the captured tree." })
    }

    // ---- describeDelta --------------------------------------------------------------------------

    @Test fun `an empty Diff produces the honest 'no change' remark`() {
        val diff = ThreadDeltas.diff(ThreadGraph(generatedAtUtc = at), ThreadGraph(generatedAtUtc = at))
        assertEquals(listOf("No structural change since the last snapshot."), ObserverScript.describeDelta(diff))
    }

    @Test fun `describeDelta reports added and removed counts`() {
        val before = ThreadGraph(generatedAtUtc = at, nodes = listOf(n("a", ThreadNodeKind.MESSAGE)))
        val after = ThreadGraph(
            generatedAtUtc = at,
            nodes = listOf(n("a", ThreadNodeKind.MESSAGE), n("b", ThreadNodeKind.MESSAGE)),
            edges = listOf(ThreadGraphEdge("a", "b", ThreadEdgeKind.REPLY)),
        )
        val remarks = ObserverScript.describeDelta(ThreadDeltas.diff(before, after))
        assertTrue(remarks.any { it.contains("1 node added") })
        assertTrue(remarks.any { it.contains("1 edge added") })
    }

    // ---- tone: the "anti-overseer" guard, checked mechanically, not by eye --------------------

    private val bannedSubstrings = listOf(
        "you should", "you must", "you failed", "you strayed", "warning", "alert",
        "overseer", "watching you", "why did you",
    )

    @Test fun `describe never emits second-person judgment or surveillance language`() {
        val graph = ThreadGraph(
            generatedAtUtc = at,
            nodes = listOf(
                n("m1", ThreadNodeKind.MESSAGE), n("f1", ThreadNodeKind.FORK_ROOT),
                n("s1", ThreadNodeKind.SPAWN_ROOT), n("mk1", ThreadNodeKind.MARKER), n("dg1", ThreadNodeKind.DELEGATION),
            ),
        )
        val text = ObserverScript.describe(graph).joinToString(" ").lowercase()
        bannedSubstrings.forEach { banned -> assertFalse("remark text contained banned phrase '$banned': $text", text.contains(banned)) }
    }

    @Test fun `describeDelta never emits second-person judgment or surveillance language`() {
        val before = ThreadGraph(generatedAtUtc = at, nodes = listOf(n("a", ThreadNodeKind.MESSAGE)))
        val after = ThreadGraph(generatedAtUtc = at, nodes = emptyList())
        val text = ObserverScript.describeDelta(ThreadDeltas.diff(before, after)).joinToString(" ").lowercase()
        bannedSubstrings.forEach { banned -> assertFalse("remark text contained banned phrase '$banned': $text", text.contains(banned)) }
    }
}
