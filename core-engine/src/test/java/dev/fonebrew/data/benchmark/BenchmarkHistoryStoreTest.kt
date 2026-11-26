package dev.fonebrew.data.benchmark

import dev.fonebrew.domain.benchmark.BenchmarkJson
import dev.fonebrew.domain.benchmark.BenchmarkOutcome
import dev.fonebrew.domain.benchmark.BenchmarkResult
import dev.fonebrew.domain.benchmark.DeviceFingerprint
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BenchmarkHistoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun result(id: String) = BenchmarkResult(
        runId = id, modelId = "m", modelFileName = "m.gguf", quantization = null, fileSizeBytes = null, tokenizerId = null,
        contextSize = 4096, decodeTokensRequested = 8, repeatsPlanned = 1,
        device = DeviceFingerprint(null, null, null, null, null), startedAtMillis = 1, endedAtMillis = 2,
        thermalBefore = null, thermalAfter = null, batteryPercentBefore = null, batteryPercentAfter = null,
        pssKbBeforeLoad = null, pssKbAfterLoad = null, pssKbAfterGeneration = null, loadMillis = null,
        repeats = emptyList(), outcome = BenchmarkOutcome.COMPLETED, outcomeDetail = null, notes = emptyList(),
    )

    private fun file() = File(tmp.root, "sub/benchmark-history.json")

    @Test fun appendPersistsAcrossInstances() {
        BenchmarkHistoryStore(file()).apply { append(result("a")); append(result("b")) }
        assertEquals(listOf("a", "b"), BenchmarkHistoryStore(file()).results.value.map { it.runId })
        assertFalse(File(file().path + ".tmp").exists())
    }

    @Test fun historyIsBoundedToTheLast50() {
        val s = BenchmarkHistoryStore(file())
        (1..55).forEach { s.append(result("r$it")) }
        assertEquals(50, s.results.value.size)
        assertEquals("r6", s.results.value.first().runId)
        assertEquals("r55", s.results.value.last().runId)
        val reopened = BenchmarkHistoryStore(file())
        assertEquals(50, reopened.results.value.size)
        assertEquals("r6", reopened.results.value.first().runId)
    }

    @Test fun corruptFileIsKeptAsCorruptAndHistoryStartsEmpty() {
        file().apply { parentFile!!.mkdirs(); writeText("{not json") }
        val s = BenchmarkHistoryStore(file())
        assertTrue(s.results.value.isEmpty())
        assertTrue(File(file().path + ".corrupt").readText().contains("not json"))
        s.append(result("x"))
        assertEquals(listOf("x"), BenchmarkHistoryStore(file()).results.value.map { it.runId })
    }

    @Test fun oneUndecodableRecordIsSkippedNotFatal() {
        val arr = JSONArray().put(BenchmarkJson.encode(result("good"))).put(org.json.JSONObject().put("runId", "bad"))
        file().apply { parentFile!!.mkdirs(); writeText(arr.toString()) }
        assertEquals(listOf("good"), BenchmarkHistoryStore(file()).results.value.map { it.runId })
    }

    @Test fun storedJsonCarriesNoModelPath() {
        BenchmarkHistoryStore(file()).append(result("a"))
        assertFalse(file().readText().contains("/data/"))
    }
}
