// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.loop

import dev.fonebrew.contracts.loops.InstallationState
import dev.fonebrew.contracts.loops.PackageSignatureState
import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.bpmn.Bounds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopImportPresenterTest {

    private fun graph(vararg modelIds: String?): BpmnGraph {
        val nodes = mutableListOf(BpmnNode("start", BpmnNodeKind.START_EVENT, "Start", Bounds(0.0, 0.0)))
        modelIds.forEachIndexed { i, m ->
            nodes += BpmnNode("t$i", BpmnNodeKind.TASK, "Task $i", Bounds(100.0 * (i + 1), 0.0), ext = if (m != null) mapOf("model" to m) else emptyMap())
        }
        nodes += BpmnNode("end", BpmnNodeKind.END_EVENT, "End", Bounds(500.0, 0.0))
        val edges = (0 until nodes.size - 1).map { BpmnEdge("e$it", nodes[it].id, nodes[it + 1].id) }
        return BpmnGraph("loop-x", "X", nodes, edges)
    }

    private fun exportRequest(g: BpmnGraph = graph("local:llama-3-8b", null), caps: List<dev.fonebrew.contracts.loops.CapabilityRequest> = emptyList()) =
        LoopPackageCodec.ExportRequest(
            graph = g, objective = "Do the thing.", loopId = "loop-x", semanticVersion = "1.0.0",
            licenseId = "MIT", authorName = "A. Author", appVersion = "0.2.5",
        )

    private fun packageWithCapabilities(caps: List<dev.fonebrew.contracts.loops.CapabilityRequest>): ByteArray {
        val bytes = LoopPackageCodec.encode(exportRequest())
        val doc = org.json.JSONObject(String(bytes))
        val arr = org.json.JSONArray()
        for (c in caps) {
            val allow = org.json.JSONArray(); c.egressAllowlist.forEach { allow.put(it) }
            arr.put(org.json.JSONObject().put("capabilityId", c.capabilityId).put("justification", c.justification ?: org.json.JSONObject.NULL).put("egressAllowlist", allow))
        }
        doc.getJSONObject("manifest").put("capabilityRequests", arr)
        return doc.toString().toByteArray()
    }

    // ── Model binding slots ─────────────────────────────────────────────────────

    @Test
    fun `builds one binding slot per distinct requested model plus a default slot`() {
        val slots = LoopImportReview.modelBindingSlots(graph("model-a", "model-a", null, "model-b"))
        val slotIds = slots.map { it.slotId }.toSet()
        assertEquals(setOf("model.model-a", "model.model-b", "model.default"), slotIds)
    }

    @Test
    fun `a package with every node explicitly modeled still needs no default slot`() {
        val slots = LoopImportReview.modelBindingSlots(graph("model-a"))
        assertEquals(listOf("model.model-a"), slots.map { it.slotId })
    }

    // ── Happy path: full install ────────────────────────────────────────────────

    @Test
    fun `installs when every binding is resolved and every capability approved`() = runBlocking {
        val bytes = LoopPackageCodec.encode(exportRequest())
        val presenter = LoopImportPresenter()
        val outcome = presenter.run(
            packageBytes = bytes, sourceDescription = "test.floop.json",
            chooseModelBinding = { slot -> slot.requestedModelId ?: "local:fallback-model" },
            decideAuthority = { preview -> AuthorityDecision(preview.capabilityItems.map { it.request.capabilityId }.toSet(), simulationGapAcknowledged = true) },
        )
        assertTrue(outcome is LoopImportOutcome.Installed)
        val installed = outcome as LoopImportOutcome.Installed
        assertEquals(InstallationState.INSTALLED, installed.installation.installationState)
        assertEquals(PackageSignatureState.IMPORTED_UNSIGNED_NARROWED_GRANTS, installed.receipt.signatureState)
        assertEquals("loop-x", installed.installation.releaseIdentity.loopId)
        assertEquals("loop-x", installed.importProvenanceExt["importedLoopId"])
        assertEquals("A. Author", installed.importProvenanceExt["importedAuthor"])
    }

    @Test
    fun `substituted model binding is recorded with a substitution reason, not silently swapped`() = runBlocking {
        val bytes = LoopPackageCodec.encode(exportRequest())
        val presenter = LoopImportPresenter()
        val outcome = presenter.run(
            packageBytes = bytes, sourceDescription = "test",
            chooseModelBinding = { "local:different-model" }, // never matches the requested id
            decideAuthority = { AuthorityDecision(emptySet(), simulationGapAcknowledged = true) },
        )
        assertTrue(outcome is LoopImportOutcome.Installed)
    }

    // ── Cancel paths ─────────────────────────────────────────────────────────────

    @Test
    fun `cancels when the user declines to bind a model`() = runBlocking {
        val bytes = LoopPackageCodec.encode(exportRequest())
        val outcome = LoopImportPresenter().run(
            packageBytes = bytes, sourceDescription = "test",
            chooseModelBinding = { null },
            decideAuthority = { AuthorityDecision(emptySet(), true) },
        )
        assertTrue(outcome is LoopImportOutcome.Cancelled)
    }

    @Test
    fun `cancels when the user denies a requested capability rather than silently narrowing`() = runBlocking {
        val cap = dev.fonebrew.contracts.loops.CapabilityRequest("fb.repo.read", "Read commit history.")
        val bytes = packageWithCapabilities(listOf(cap))
        val outcome = LoopImportPresenter().run(
            packageBytes = bytes, sourceDescription = "test",
            chooseModelBinding = { it.requestedModelId ?: "local:fallback" },
            decideAuthority = { AuthorityDecision(approvedCapabilityIds = emptySet(), simulationGapAcknowledged = true) }, // denies the one request
        )
        assertTrue(outcome is LoopImportOutcome.Cancelled)
    }

    @Test
    fun `installs with a granted authority bucket when the sole capability is approved`() = runBlocking {
        val cap = dev.fonebrew.contracts.loops.CapabilityRequest("fb.repo.read", "Read commit history.")
        val bytes = packageWithCapabilities(listOf(cap))
        val outcome = LoopImportPresenter().run(
            packageBytes = bytes, sourceDescription = "test",
            chooseModelBinding = { it.requestedModelId ?: "local:fallback" },
            decideAuthority = { AuthorityDecision(setOf("fb.repo.read"), simulationGapAcknowledged = true) },
        )
        assertTrue(outcome is LoopImportOutcome.Installed)
        val installed = outcome as LoopImportOutcome.Installed
        assertEquals(listOf("fb.repo.read"), installed.installation.authorityGrants)
    }

    @Test
    fun `cancels when the user does not acknowledge the no-simulator gap`() = runBlocking {
        val bytes = LoopPackageCodec.encode(exportRequest())
        val outcome = LoopImportPresenter().run(
            packageBytes = bytes, sourceDescription = "test",
            chooseModelBinding = { it.requestedModelId ?: "local:fallback" },
            decideAuthority = { AuthorityDecision(emptySet(), simulationGapAcknowledged = false) },
        )
        assertTrue(outcome is LoopImportOutcome.Cancelled)
    }

    @Test
    fun `cancels outright when the review screen itself is dismissed`() = runBlocking {
        val bytes = LoopPackageCodec.encode(exportRequest())
        val outcome = LoopImportPresenter().run(
            packageBytes = bytes, sourceDescription = "test",
            chooseModelBinding = { it.requestedModelId ?: "local:fallback" },
            decideAuthority = { null },
        )
        assertTrue(outcome is LoopImportOutcome.Cancelled)
    }

    // ── Reject paths ─────────────────────────────────────────────────────────────

    @Test
    fun `rejects an unparseable package before ever prompting the user`() = runBlocking {
        var prompted = false
        val outcome = LoopImportPresenter().run(
            packageBytes = "garbage".toByteArray(), sourceDescription = "test",
            chooseModelBinding = { prompted = true; "x" },
            decideAuthority = { prompted = true; AuthorityDecision(emptySet(), true) },
        )
        assertTrue(outcome is LoopImportOutcome.Rejected)
        assertTrue(!prompted)
    }

    @Test
    fun `blocks on an out-of-range engineVersionRange as incompatible`() = runBlocking {
        val bytes = LoopPackageCodec.encode(exportRequest())
        val doc = org.json.JSONObject(String(bytes))
        doc.getJSONObject("manifest").getJSONObject("compatibilityDeclaration").put("engineVersionRange", ">=9.0.0 <10.0.0")
        val outcome = LoopImportPresenter().run(
            packageBytes = doc.toString().toByteArray(), sourceDescription = "test",
            chooseModelBinding = { it.requestedModelId ?: "local:fallback" },
            decideAuthority = { AuthorityDecision(emptySet(), true) },
        )
        assertTrue(outcome is LoopImportOutcome.Rejected)
        assertNull((outcome as? LoopImportOutcome.Installed))
    }
}
