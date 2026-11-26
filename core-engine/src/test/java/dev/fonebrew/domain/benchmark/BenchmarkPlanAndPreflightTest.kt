package dev.fonebrew.domain.benchmark

import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPlanAndPreflightTest {

    private fun spec(runtime: Runtime, path: String? = null, provider: String? = null) = ModelSpec(
        id = "id", displayName = "Name", family = "f", contextWindow = 4096, tokenizerId = "t",
        templateId = TemplateId.PLAIN, runtime = runtime, modelPath = path, providerId = provider,
        watched = runtime == Runtime.CLOUD,
    )

    @Test fun defaultPlanIsFixedAndDeterministic() {
        val a = BenchmarkPlan.default()
        val b = BenchmarkPlan.default()
        assertEquals(a, b)
        assertEquals(listOf("short", "prefill-512", "prefill-1024", "prefill-2048"), a.prompts.map { it.id })
        assertEquals(12, a.generationCount)
        assertEquals(128, a.decodeTokens)
        assertEquals(4096, a.contextSize)
    }

    @Test fun prefillTextScalesWithTargetAndEndsInTheInstruction() {
        val small = BenchmarkPlan.prefillText(512)
        val big = BenchmarkPlan.prefillText(2048)
        assertTrue(small.length >= 512 * BenchmarkPlan.CHARS_PER_TOKEN)
        assertTrue(big.length >= 4 * 512 * BenchmarkPlan.CHARS_PER_TOKEN)
        assertTrue(big.endsWith("Summarise the text above in two sentences."))
    }

    @Test(expected = IllegalArgumentException::class) fun planNeedsTwoDecodeTokens() {
        BenchmarkPlan(listOf(BenchmarkPrompt("a", "a", "x", 1)), decodeTokens = 1, repeats = 1, contextSize = 10)
    }

    @Test fun cloudModelIsRefusedWithAReason() {
        val r = BenchmarkPreflight.check(spec(Runtime.CLOUD, provider = "p"), { true }, { 1L })
        assertTrue(r is Preflight.Refused)
        assertTrue((r as Preflight.Refused).reason.contains("cloud"))
    }

    @Test fun nonGgufOnDeviceModelsAreRefused() {
        assertTrue(BenchmarkPreflight.check(spec(Runtime.AICORE_NANO), { true }, { 1L }) is Preflight.Refused)
        assertTrue(BenchmarkPreflight.check(spec(Runtime.ECHO_DEV), { true }, { 1L }) is Preflight.Refused)
    }

    @Test fun missingFileIsRefusedAndNothingIsDownloaded() {
        val r = BenchmarkPreflight.check(spec(Runtime.LOCAL_GGUF, "/m/x.gguf"), { false }, { null })
        assertTrue((r as Preflight.Refused).reason.contains("never downloads"))
        assertTrue(BenchmarkPreflight.check(spec(Runtime.LOCAL_GGUF, null), { true }, { 1L }) is Preflight.Refused)
    }

    @Test fun busyIsRefused() {
        assertTrue(BenchmarkPreflight.check(spec(Runtime.LOCAL_GGUF, "/m/x.gguf"), { true }, { 1L }, busy = true) is Preflight.Refused)
    }

    @Test fun readyCarriesFileNameAndSize() {
        val r = BenchmarkPreflight.check(spec(Runtime.LOCAL_GGUF, "/m/x-Q8_0.gguf"), { true }, { 42L }) as Preflight.Ready
        assertEquals("x-Q8_0.gguf", r.target.modelFileName)
        assertEquals(42L, r.target.fileSizeBytes)
        assertEquals("Q8_0", r.target.quantization)
    }

    @Test fun quantizationOnlyFromTheName() {
        assertEquals("Q4_K_M", QuantizationName.fromFileName("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals("IQ3_XS", QuantizationName.fromFileName("m.IQ3_XS.gguf"))
        assertEquals("F16", QuantizationName.fromFileName("tiny-f16.gguf"))
        assertNull(QuantizationName.fromFileName("mystery-model.gguf"))
    }

    @Test fun thermalMappingKeepsUnknownUnknown() {
        assertEquals(Reading.Known(ThermalLevel.SEVERE), ThermalLevel.fromPlatform(3))
        assertTrue(ThermalLevel.fromPlatform(99) is Reading.Unknown)
        assertTrue(ThermalLevel.fromPlatform(-1) is Reading.Unknown)
        assertTrue(ThermalLevel.SEVERE.isAbortLevel && ThermalLevel.SHUTDOWN.isAbortLevel)
        assertTrue(!ThermalLevel.MODERATE.isAbortLevel)
    }
}
