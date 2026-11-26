package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPlanTest {
    private val registry = LoopTools.describeOnly

    private fun httpNode(id: String, url: String) =
        BpmnNode(id, BpmnNodeKind.SERVICE_TASK, id, ext = ToolNodes.toExt("http_request", mapOf("url" to url)))

    private fun graph(vararg nodes: BpmnNode) = BpmnGraph(
        "g", nodes = listOf(BpmnNode("s", BpmnNodeKind.START_EVENT)) + nodes + BpmnNode("e", BpmnNodeKind.END_EVENT),
        edges = emptyList(),
    )

    @Test fun `the plan lists every host once and every tool`() {
        val g = graph(httpNode("a", "https://one.example/x"), httpNode("b", "https://two.example/y"), httpNode("c", "https://one.example/z"),
            BpmnNode("s1", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("search_workspace", mapOf("query" to "cats"))))
        val plan = RunPlan.of(g, registry, emptyMap())
        assertEquals(setOf("one.example", "two.example"), plan.hosts)
        assertEquals(setOf("http_request", "search_workspace"), plan.toolIds)
        assertTrue(plan.usesNetwork)
    }

    @Test fun `hosts resolve through params`() {
        val g = graph(httpNode("a", "https://\${h}/x"))
        assertEquals(setOf("api.example.org"), RunPlan.of(g, registry, mapOf("h" to "api.example.org")).hosts)
        // Unresolved placeholder is invalid, not a silently-empty host.
        assertTrue(RunPlan.of(g, registry, emptyMap()).invalid.isNotEmpty())
    }

    @Test fun `a plan is covered only by a confirmation naming every tool and host`() {
        val g = graph(httpNode("a", "https://one.example/x"))
        val plan = RunPlan.of(g, registry, emptyMap())
        assertEquals(listOf("tool http_request", "host one.example"), plan.uncovered(null))
        assertEquals(listOf("host one.example"), plan.uncovered(RunConfirmation(setOf("http_request"), emptySet())))
        assertTrue(plan.uncovered(RunConfirmation.accepting(plan)).isEmpty())
    }

    @Test fun `a loop with no tools needs no confirmation`() {
        val plan = RunPlan.of(graph(BpmnNode("t", BpmnNodeKind.TASK)), registry, emptyMap())
        assertTrue(plan.isEmpty)
        assertFalse(RunConfirmationPresenter.present(plan, null, imported = false, hasCloudSteps = true).needed)
    }

    // ── the sheet ────────────────────────────────────────────────────────────────

    @Test fun `the sheet lists every host with a network glyph and label`() {
        val g = graph(httpNode("a", "https://one.example/x"), httpNode("b", "https://two.example/y"))
        val sheet = RunConfirmationPresenter.present(RunPlan.of(g, registry, emptyMap()), setOf("one.example", "two.example"), false, false)
        assertTrue(sheet.needed)
        assertEquals(2, sheet.hostLines.size)
        assertTrue(sheet.hostLines.all { it.startsWith("⇄ network: ") })
        assertTrue(sheet.headline, sheet.headline.contains("2 hosts"))
        assertTrue(sheet.toolLines.single().contains("http_request"))
        assertTrue(sheet.toolLines.single().contains("network"))
    }

    @Test fun `a host not in the last confirmed set is flagged NEW`() {
        val g = graph(httpNode("a", "https://one.example/x"), httpNode("b", "https://two.example/y"))
        val sheet = RunConfirmationPresenter.present(RunPlan.of(g, registry, emptyMap()), setOf("one.example"), false, false)
        assertFalse(sheet.hostLines.first { it.contains("one.example") }.contains("NEW"))
        assertTrue(sheet.hostLines.first { it.contains("two.example") }.contains("NEW since you last confirmed"))
    }

    @Test fun `a loop never confirmed before says so`() {
        val g = graph(httpNode("a", "https://one.example/x"))
        val sheet = RunConfirmationPresenter.present(RunPlan.of(g, registry, emptyMap()), null, false, false)
        assertTrue(sheet.hostLines.single().contains("first time"))
    }

    @Test fun `an imported loop is called out prominently`() {
        val g = graph(httpNode("a", "https://one.example/x"))
        val sheet = RunConfirmationPresenter.present(RunPlan.of(g, registry, emptyMap()), null, imported = true, hasCloudSteps = false)
        assertTrue(sheet.warnings.first().startsWith("⚠ This loop came from an import"))
    }

    @Test fun `cloud steps get the flow-into-cloud warning`() {
        val g = graph(httpNode("a", "https://one.example/x"))
        val sheet = RunConfirmationPresenter.present(RunPlan.of(g, registry, emptyMap()), null, false, hasCloudSteps = true)
        assertTrue(sheet.warnings.any { it.contains("cloud models (watched objects)") })
    }

    @Test fun `unsupported and invalid nodes block the sheet`() {
        val g = graph(BpmnNode("u", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("teleport", emptyMap())), httpNode("a", "http://insecure.example"))
        val sheet = RunConfirmationPresenter.present(RunPlan.of(g, registry, emptyMap()), null, false, false)
        assertTrue(sheet.blocked)
        assertTrue(sheet.warnings.any { it.contains("Unsupported in this version") })
        assertTrue(sheet.warnings.any { it.contains("https only") })
    }

    // ── the import notice ────────────────────────────────────────────────────────

    @Test fun `import notice lists hosts from the graph, including param-driven ones`() {
        val g = graph(httpNode("a", "https://one.example/x"), httpNode("b", "https://\${h}/y"))
        val notice = EgressNotice.of(g, registry)
        assertTrue(notice.present)
        assertEquals(listOf("one.example", "(host set by a parameter)"), notice.hosts)
        assertTrue(notice.lines().first().startsWith("⇄ Network: this loop contacts one.example"))
    }

    @Test fun `import notice is absent for a model-only loop`() {
        val n = EgressNotice.of(graph(BpmnNode("t", BpmnNodeKind.TASK)), registry)
        assertFalse(n.present)
        assertTrue(n.lines().isEmpty())
        assertNull(null)
    }

    @Test fun `import notice shows tools this build does not know`() {
        val n = EgressNotice.of(graph(BpmnNode("u", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("teleport", mapOf("url" to "https://x.example")))), registry)
        assertTrue(n.present)
        assertEquals(listOf("teleport"), n.toolIds)
    }
}
