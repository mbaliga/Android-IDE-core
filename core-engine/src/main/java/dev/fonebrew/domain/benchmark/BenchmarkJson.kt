package dev.fonebrew.domain.benchmark

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON for [BenchmarkResult]. Unknown is an *absent key* on write and null on read: a missing
 * number never becomes 0 anywhere on the round trip. Unrecognised enum names decode to null
 * (thermal) or FAILED-with-detail (outcome) rather than throwing, so one odd record cannot make
 * the whole history unreadable.
 */
object BenchmarkJson {
    const val SCHEMA = 1

    fun encode(r: BenchmarkResult): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("runId", r.runId)
        put("modelId", r.modelId)
        put("modelFileName", r.modelFileName)
        putOpt("quantization", r.quantization)
        r.fileSizeBytes?.let { put("fileSizeBytes", it) }
        putOpt("tokenizerId", r.tokenizerId)
        put("contextSize", r.contextSize)
        put("decodeTokensRequested", r.decodeTokensRequested)
        put("repeatsPlanned", r.repeatsPlanned)
        put(
            "device",
            JSONObject().apply {
                putOpt("model", r.device.deviceModel)
                putOpt("androidRelease", r.device.androidRelease)
                r.device.sdkInt?.let { put("sdkInt", it) }
                putOpt("abi", r.device.abi)
                putOpt("cpuFeatures", r.device.cpuFeatures)
            },
        )
        put("startedAtMillis", r.startedAtMillis)
        put("endedAtMillis", r.endedAtMillis)
        r.thermalBefore?.let { put("thermalBefore", it.name) }
        r.thermalAfter?.let { put("thermalAfter", it.name) }
        r.batteryPercentBefore?.let { put("batteryPercentBefore", it) }
        r.batteryPercentAfter?.let { put("batteryPercentAfter", it) }
        r.pssKbBeforeLoad?.let { put("pssKbBeforeLoad", it) }
        r.pssKbAfterLoad?.let { put("pssKbAfterLoad", it) }
        r.pssKbAfterGeneration?.let { put("pssKbAfterGeneration", it) }
        r.loadMillis?.let { put("loadMillis", it) }
        put(
            "repeats",
            JSONArray().also { arr ->
                r.repeats.forEach { m ->
                    arr.put(
                        JSONObject().apply {
                            put("promptId", m.promptId)
                            put("repeatIndex", m.repeatIndex)
                            m.promptTokens?.let { put("promptTokens", it) }
                            put("emittedTokens", m.emittedTokens)
                            m.ttftMillis?.let { put("ttftMillis", it) }
                            m.prefillTokensPerSec?.let { put("prefillTokensPerSec", it) }
                            m.decodeTokensPerSec?.let { put("decodeTokensPerSec", it) }
                            m.thermalAfter?.let { put("thermalAfter", it.name) }
                        },
                    )
                }
            },
        )
        put("outcome", r.outcome.name)
        putOpt("outcomeDetail", r.outcomeDetail)
        put("notes", JSONArray(r.notes))
        put("scope", BenchmarkResult.SCOPE_STATEMENT)
    }

    fun decode(o: JSONObject): BenchmarkResult {
        val dev = o.optJSONObject("device") ?: JSONObject()
        val reps = o.optJSONArray("repeats") ?: JSONArray()
        val notes = o.optJSONArray("notes") ?: JSONArray()
        val rawOutcome = o.optString("outcome")
        val outcome = BenchmarkOutcome.entries.firstOrNull { it.name == rawOutcome }
        return BenchmarkResult(
            runId = o.getString("runId"),
            modelId = o.getString("modelId"),
            modelFileName = o.getString("modelFileName"),
            quantization = o.optStr("quantization"),
            fileSizeBytes = o.optLng("fileSizeBytes"),
            tokenizerId = o.optStr("tokenizerId"),
            contextSize = o.getInt("contextSize"),
            decodeTokensRequested = o.getInt("decodeTokensRequested"),
            repeatsPlanned = o.getInt("repeatsPlanned"),
            device = DeviceFingerprint(
                deviceModel = dev.optStr("model"),
                androidRelease = dev.optStr("androidRelease"),
                sdkInt = dev.optInt0("sdkInt"),
                abi = dev.optStr("abi"),
                cpuFeatures = dev.optStr("cpuFeatures"),
            ),
            startedAtMillis = o.getLong("startedAtMillis"),
            endedAtMillis = o.getLong("endedAtMillis"),
            thermalBefore = o.optThermal("thermalBefore"),
            thermalAfter = o.optThermal("thermalAfter"),
            batteryPercentBefore = o.optInt0("batteryPercentBefore"),
            batteryPercentAfter = o.optInt0("batteryPercentAfter"),
            pssKbBeforeLoad = o.optLng("pssKbBeforeLoad"),
            pssKbAfterLoad = o.optLng("pssKbAfterLoad"),
            pssKbAfterGeneration = o.optLng("pssKbAfterGeneration"),
            loadMillis = o.optDbl("loadMillis"),
            repeats = (0 until reps.length()).map { i ->
                val m = reps.getJSONObject(i)
                RepeatMeasurement(
                    promptId = m.getString("promptId"),
                    repeatIndex = m.getInt("repeatIndex"),
                    promptTokens = m.optInt0("promptTokens"),
                    emittedTokens = m.getInt("emittedTokens"),
                    ttftMillis = m.optDbl("ttftMillis"),
                    prefillTokensPerSec = m.optDbl("prefillTokensPerSec"),
                    decodeTokensPerSec = m.optDbl("decodeTokensPerSec"),
                    thermalAfter = m.optThermal("thermalAfter"),
                )
            },
            outcome = outcome ?: BenchmarkOutcome.FAILED,
            outcomeDetail = if (outcome == null) "unrecognised outcome \"$rawOutcome\" in saved record" else o.optStr("outcomeDetail"),
            notes = (0 until notes.length()).map { notes.getString(it) },
        )
    }

    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.optInt0(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.optLng(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.optDbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.optThermal(k: String): ThermalLevel? =
        optStr(k)?.let { n -> ThermalLevel.entries.firstOrNull { it.name == n } }
}
