package dev.fonebrew.domain.benchmark

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkJsonAndExportTest {

    fun sample(id: String = "r1", unknowns: Boolean = false) = BenchmarkResult(
        runId = id, modelId = "local:m", modelFileName = "m-Q4_K_M.gguf", quantization = "Q4_K_M",
        fileSizeBytes = 123L, tokenizerId = "tok", contextSize = 4096, decodeTokensRequested = 128, repeatsPlanned = 2,
        device = if (unknowns) DeviceFingerprint(null, null, null, null, null) else DEVICE,
        startedAtMillis = 10, endedAtMillis = 99,
        thermalBefore = if (unknowns) null else ThermalLevel.NONE, thermalAfter = if (unknowns) null else ThermalLevel.MODERATE,
        batteryPercentBefore = if (unknowns) null else 80, batteryPercentAfter = if (unknowns) null else 78,
        pssKbBeforeLoad = if (unknowns) null else 100_000, pssKbAfterLoad = if (unknowns) null else 4_200_000,
        pssKbAfterGeneration = if (unknowns) null else 4_300_000,
        loadMillis = if (unknowns) null else 2500.5,
        repeats = listOf(
            RepeatMeasurement("p", 1, if (unknowns) null else 100, 5, if (unknowns) null else 500.0, if (unknowns) null else 200.0, if (unknowns) null else 10.0, ThermalLevel.LIGHT.takeIf { !unknowns }),
            RepeatMeasurement("p", 2, if (unknowns) null else 100, 5, if (unknowns) null else 250.0, if (unknowns) null else 400.0, if (unknowns) null else 20.0, null),
        ),
        outcome = BenchmarkOutcome.COMPLETED, outcomeDetail = null, notes = listOf("a note"),
    )

    @Test fun roundTripPreservesEverythingIncludingUnknowns() {
        for (u in listOf(false, true)) {
            val r = sample(unknowns = u)
            assertEquals(r, BenchmarkJson.decode(JSONObject(BenchmarkJson.encode(r).toString())))
        }
    }

    @Test fun unknownIsAnAbsentKeyNeverZero() {
        val o = BenchmarkJson.encode(sample(unknowns = true))
        assertFalse(o.has("loadMillis")); assertFalse(o.has("batteryPercentBefore")); assertFalse(o.has("pssKbAfterLoad"))
        val rep = o.getJSONArray("repeats").getJSONObject(0)
        assertFalse(rep.has("decodeTokensPerSec")); assertFalse(rep.has("promptTokens"))
        assertTrue(o.getString("scope").contains("unverified"))
    }

    @Test fun unrecognisedOutcomeAndThermalDecodeSafely() {
        val o = BenchmarkJson.encode(sample())
        o.put("outcome", "SOMETHING_NEW"); o.put("thermalBefore", "LAVA")
        val d = BenchmarkJson.decode(o)
        assertEquals(BenchmarkOutcome.FAILED, d.outcome)
        assertTrue(d.outcomeDetail!!.contains("SOMETHING_NEW"))
        assertNull(d.thermalBefore)
    }

    @Test fun textExportIsReadableThenCarriesParseableJson() {
        val rs = listOf(sample("a"), sample("b", unknowns = true))
        val text = BenchmarkTextExport.render(rs)
        assertTrue(text.contains("unverified by anyone else"))
        assertTrue(text.contains("✓ Completed - m-Q4_K_M.gguf"))
        assertTrue(text.contains("decode t/s   median 15.00 (p10 10.00, p90 20.00, min 10.00, max 20.00, n=2, unknown 0, drift 100.00%)"))
        assertTrue(text.contains("load: 2500 ms") || text.contains("load: 2501 ms") || text.contains("load: 2500"))
        assertTrue(text.contains("memory PSS MB: before load 98, after load 4102, after generation 4199"))
        val json = JSONArray(text.substringAfter("JSON:\n").trim())
        assertEquals(2, json.length())
        assertEquals(rs[1], BenchmarkJson.decode(json.getJSONObject(1)))
    }

    @Test fun unknownsPrintAsUnknownNeverAsZero() {
        val one = BenchmarkTextExport.renderOne(sample(unknowns = true))
        assertTrue(one.contains("load: unknown"))
        assertTrue(one.contains("thermal: unknown -> unknown; battery: unknown -> unknown"))
        assertTrue(one.contains("prompt tokens unknown"))
        assertTrue(one.contains("decode t/s   unknown (n=0, unknown 2)"))
        assertTrue(one.contains("quant Q4_K_M"))
        assertFalse(one.contains(" 0.00"))
        assertEquals("No benchmark results yet.", BenchmarkTextExport.render(emptyList()))
    }
}
