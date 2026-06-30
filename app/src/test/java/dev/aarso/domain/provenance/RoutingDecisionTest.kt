package dev.aarso.domain.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingDecisionTest {

    // ---- Tier → provenance mapping ----

    @Test
    fun onDeviceTierIsLocal() {
        assertEquals(ProvenanceState.LOCAL, Tier.ON_DEVICE.provenance)
    }

    @Test
    fun runnerTierIsLocal() {
        // The user's own machine is sovereign — LOCAL, never CLOUD.
        assertEquals(ProvenanceState.LOCAL, Tier.RUNNER.provenance)
        assertFalse(Tier.RUNNER.provenance.watched)
    }

    @Test
    fun cloudTierIsCloudAndWatched() {
        assertEquals(ProvenanceState.CLOUD, Tier.CLOUD.provenance)
        assertTrue(Tier.CLOUD.provenance.watched)
    }

    @Test
    fun everyTierHasLabel() {
        for (t in Tier.entries) assertTrue(t.label.isNotBlank())
    }

    // ---- summaryLine ----

    @Test
    fun summaryLineFormat() {
        val d = RoutingDecision(
            model = "phi-3-mini",
            tier = Tier.ON_DEVICE,
            why = "on-device default (free, private)",
            estCostMinor = 0L,
            provenance = ProvenanceState.LOCAL,
        )
        assertEquals(
            "phi-3-mini · On-device · on-device default (free, private) · ~0",
            d.summaryLine(),
        )
    }

    @Test
    fun summaryLineCarriesMinorCost() {
        val d = RoutingDecision(
            model = "claude",
            tier = Tier.CLOUD,
            why = "cloud (your choice)",
            estCostMinor = 1234L,
            provenance = ProvenanceState.CLOUD,
        )
        assertTrue(d.summaryLine().endsWith("~1234"))
        assertTrue(d.summaryLine().contains("Cloud"))
    }

    // ---- applyOverride ----

    @Test
    fun overrideToCloudRecomputesProvenance() {
        val base = RoutingDecision(
            model = "phi-3-mini",
            tier = Tier.ON_DEVICE,
            why = "on-device default (free, private)",
            estCostMinor = 0L,
            provenance = ProvenanceState.LOCAL,
        )
        val out = base.applyOverride(RoutingOverride(model = "gpt-4o", tier = Tier.CLOUD))
        assertEquals("gpt-4o", out.model)
        assertEquals(Tier.CLOUD, out.tier)
        assertEquals(ProvenanceState.CLOUD, out.provenance)
        assertTrue(out.provenance.watched)
        assertEquals("overridden by user", out.why)
    }

    @Test
    fun overrideToRunnerIsLocal() {
        val base = RoutingDecision(
            model = "gpt-4o",
            tier = Tier.CLOUD,
            why = "cloud (your choice)",
            estCostMinor = 500L,
            provenance = ProvenanceState.CLOUD,
        )
        val out = base.applyOverride(RoutingOverride(model = "llama-local", tier = Tier.RUNNER))
        assertEquals(Tier.RUNNER, out.tier)
        assertEquals(ProvenanceState.LOCAL, out.provenance)
        assertFalse(out.provenance.watched)
        assertEquals("overridden by user", out.why)
    }

    @Test
    fun overrideKeepsCostEstimate() {
        // Override re-routes but does not re-price; the estimate carries over.
        val base = RoutingDecision(
            model = "a",
            tier = Tier.CLOUD,
            why = "cloud (your choice)",
            estCostMinor = 999L,
            provenance = ProvenanceState.CLOUD,
        )
        val out = base.applyOverride(RoutingOverride(model = "b", tier = Tier.ON_DEVICE))
        assertEquals(999L, out.estCostMinor)
    }

    // ---- Router.decide branches ----

    @Test
    fun decidePrefersOnDeviceWhenAvailable() {
        val d = Router.decide(
            preferOnDevice = true,
            hasOnDeviceModel = true,
            hasCloudKey = true,
            onDeviceModel = "phi-3-mini",
            cloudModel = "gpt-4o",
            estCloudCostMinor = 100L,
        )
        assertEquals(Tier.ON_DEVICE, d.tier)
        assertEquals("phi-3-mini", d.model)
        assertEquals(0L, d.estCostMinor)
        assertEquals(ProvenanceState.LOCAL, d.provenance)
        assertEquals("on-device default (free, private)", d.why)
    }

    @Test
    fun decideFallsBackToCloudWhenNoLocalModel() {
        val d = Router.decide(
            preferOnDevice = true,
            hasOnDeviceModel = false,
            hasCloudKey = true,
            onDeviceModel = null,
            cloudModel = "gpt-4o",
            estCloudCostMinor = 250L,
        )
        assertEquals(Tier.CLOUD, d.tier)
        assertEquals("gpt-4o", d.model)
        assertEquals(250L, d.estCostMinor)
        assertEquals(ProvenanceState.CLOUD, d.provenance)
        assertTrue(d.provenance.watched)
        assertEquals("no on-device model — using cloud", d.why)
    }

    @Test
    fun decideCloudByChoiceWhenNotPreferringOnDevice() {
        val d = Router.decide(
            preferOnDevice = false,
            hasOnDeviceModel = true,
            hasCloudKey = true,
            onDeviceModel = "phi-3-mini",
            cloudModel = "gpt-4o",
            estCloudCostMinor = 70L,
        )
        assertEquals(Tier.CLOUD, d.tier)
        assertEquals("cloud (your choice)", d.why)
        assertEquals(70L, d.estCostMinor)
    }

    @Test
    fun decideOnDeviceAsOnlyOptionEvenIfNotPreferred() {
        val d = Router.decide(
            preferOnDevice = false,
            hasOnDeviceModel = true,
            hasCloudKey = false,
            onDeviceModel = "phi-3-mini",
            cloudModel = null,
            estCloudCostMinor = 0L,
        )
        assertEquals(Tier.ON_DEVICE, d.tier)
        assertEquals("phi-3-mini", d.model)
        assertEquals(ProvenanceState.LOCAL, d.provenance)
        assertEquals("on-device (only option available)", d.why)
    }

    @Test
    fun decideCloudKeyWithoutCloudModelFallsThrough() {
        // Key present but no model id → not cloud-available; on-device is the only option.
        val d = Router.decide(
            preferOnDevice = true,
            hasOnDeviceModel = true,
            hasCloudKey = true,
            onDeviceModel = "phi-3-mini",
            cloudModel = null,
            estCloudCostMinor = 100L,
        )
        assertEquals(Tier.ON_DEVICE, d.tier)
    }

    @Test
    fun decideNoModelAvailableIsHonestPlaceholder() {
        val d = Router.decide(
            preferOnDevice = true,
            hasOnDeviceModel = false,
            hasCloudKey = false,
            onDeviceModel = null,
            cloudModel = null,
            estCloudCostMinor = 0L,
        )
        assertEquals("", d.model)
        assertEquals(ProvenanceState.UNKNOWN, d.provenance)
        assertEquals("no model available", d.why)
        assertFalse(d.provenance.watched)
    }

    @Test
    fun decidedProvenanceAlwaysMatchesTierExceptPlaceholder() {
        // Invariant: a real decision's provenance equals tier.provenance.
        val d = Router.decide(
            preferOnDevice = true,
            hasOnDeviceModel = true,
            hasCloudKey = false,
            onDeviceModel = "m",
            cloudModel = null,
            estCloudCostMinor = 0L,
        )
        assertEquals(d.tier.provenance, d.provenance)
    }
}
