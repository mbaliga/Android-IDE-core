package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnNodeKind
import dev.aarso.domain.council.PatternLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class LoopCatalogTest {

    @Test fun `fromPattern seeds an Unused loop whose bpmn carries the pattern + provenance`() {
        val moa = PatternLibrary.mixtureOfAgents
        val loop = LoopCatalog.fromPattern(moa)
        assertEquals(LoopState.UNUSED, loop.state)
        assertEquals(moa.title, loop.name)
        assertNotNull(loop.bpmnXml)
        val g = BpmnArchive.read(loop.bpmnXml!!)
        assertEquals(moa.graph, g)                          // bpmn round-trips to the pattern graph
        val start = g.nodes.first { it.kind == BpmnNodeKind.START_EVENT }
        assertEquals(moa.source, start.ext["source"])       // provenance rides in the bpmn
    }

    @Test fun `reference surfaces the whole curated library as Unused loops`() {
        val cat = LoopCatalog.reference()
        assertEquals(PatternLibrary.all.size, cat.size)
        assertEquals(cat.size, cat.map { it.id }.toSet().size)   // ids unique
        for (loop in cat) {
            assertEquals(LoopState.UNUSED, loop.state)
            assertNotNull(loop.bpmnXml)
            // model-agnostic: no node pre-commits a watched cloud (CLAUDE.md #2)
            for (n in BpmnArchive.read(loop.bpmnXml!!).nodes) {
                assertFalse(n.ext["watched"] == "true")
                assertFalse(n.ext.containsKey("model"))
            }
        }
    }
}
