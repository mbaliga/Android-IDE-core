package dev.aarso.domain.council

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternLibraryTest {

    private val patterns = PatternLibrary.all

    @Test fun `ships the four curated patterns, addressable by id`() {
        assertEquals(4, patterns.size)
        assertEquals(listOf("moa", "self-consistency", "reflexion", "debate"), patterns.map { it.id })
        for (p in patterns) assertEquals(p, PatternLibrary.byId(p.id))
        assertNull(PatternLibrary.byId("nope"))
    }

    @Test fun `every pattern round-trips through standard BPMN`() {
        // The whole point: these are valid, transportable BPMN 2.0 that opens in any
        // tool and syncs to the user's Git host as .bpmn. (BpmnArchive is the oracle.)
        for (p in patterns) {
            val restored = BpmnArchive.read(BpmnArchive.write(p.graph))
            assertEquals("pattern ${p.id} must round-trip", p.graph, restored)
        }
    }

    @Test fun `each graph is well-formed (one start, an end, connected edges)`() {
        for (p in patterns) {
            val g = p.graph
            val ids = g.nodes.map { it.id }.toSet()
            assertEquals("ids unique in ${p.id}", g.nodes.size, ids.size)

            assertEquals("one start in ${p.id}", 1, g.nodes.count { it.kind == BpmnNodeKind.START_EVENT })
            assertTrue("an end in ${p.id}", g.nodes.any { it.kind == BpmnNodeKind.END_EVENT })

            for (e in g.edges) {
                assertTrue("edge ${e.id} source resolves in ${p.id}", e.sourceId in ids)
                assertTrue("edge ${e.id} target resolves in ${p.id}", e.targetId in ids)
            }
            // Connectivity: non-start nodes are reachable in; non-end nodes flow out.
            for (n in g.nodes) {
                if (n.kind != BpmnNodeKind.START_EVENT) {
                    assertTrue("${n.id} has an incoming edge in ${p.id}", g.incoming(n.id).isNotEmpty())
                }
                if (n.kind != BpmnNodeKind.END_EVENT) {
                    assertTrue("${n.id} has an outgoing edge in ${p.id}", g.outgoing(n.id).isNotEmpty())
                }
            }
        }
    }

    @Test fun `each pattern carries provenance on its start event`() {
        for (p in patterns) {
            assertTrue("source set for ${p.id}", p.source.isNotBlank())
            assertTrue("summary set for ${p.id}", p.summary.isNotBlank())
            val start = p.graph.nodes.first { it.kind == BpmnNodeKind.START_EVENT }
            assertEquals("start carries source for ${p.id}", p.source, start.ext["source"])
            assertTrue("start carries a pattern tag for ${p.id}", !start.ext["pattern"].isNullOrBlank())
        }
    }

    @Test fun `never labels itself MoE (CLAUDE rule 3)`() {
        // "Mixture of Agents" is a real paper title and is fine; what must never appear
        // is Aarso's own council rebranded as "Mixture of Experts" / MoE.
        for (p in patterns) {
            val haystack = buildString {
                append(p.title).append(' ').append(p.summary).append(' ').append(p.graph.name)
                for (n in p.graph.nodes) append(' ').append(n.name)
            }
            assertFalse("no 'mixture of experts' in ${p.id}", haystack.contains("mixture of experts", ignoreCase = true))
        }
        assertEquals("Mixture of Agents", PatternLibrary.mixtureOfAgents.title)
    }

    @Test fun `no node defaults to a watched cloud model (on-device default)`() {
        // Templates are model-agnostic: the user assigns models when a loop goes
        // Unused -> Running. Nothing here may pre-commit a watched cloud (CLAUDE #2).
        for (p in patterns) {
            for (n in p.graph.nodes) {
                assertFalse("${p.id}/${n.id} must not default to watched", n.ext["watched"] == "true")
                assertFalse("${p.id}/${n.id} must not name a model", n.ext.containsKey("model"))
            }
        }
    }
}
