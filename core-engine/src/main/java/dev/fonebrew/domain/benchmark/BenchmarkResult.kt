package dev.fonebrew.domain.benchmark

/** How a run ended. Every ending is recorded; none is silently dropped. */
enum class BenchmarkOutcome(val glyph: String, val label: String) {
    COMPLETED("✓", "Completed"),
    STOPPED("■", "Stopped by you"),
    ABORTED_THERMAL("⚠", "Aborted: phone too hot"),
    FAILED("✗", "Failed"),
}

/** Facts about the phone, supplied by the Android side. Null/blank means the platform did not say. */
data class DeviceFingerprint(
    val deviceModel: String?,
    val androidRelease: String?,
    val sdkInt: Int?,
    val abi: String?,
    /** See data/benchmark/CpuFeatureString: required-extension verdict plus the raw Features line. */
    val cpuFeatures: String?,
)

/** What was benchmarked. Always an on-device GGUF; see [BenchmarkPreflight]. */
data class BenchmarkTarget(
    val modelId: String,
    val modelFileName: String,
    val modelPath: String,
    val fileSizeBytes: Long?,
) {
    /** Quantisation read from the file name only; null when the name does not say. */
    val quantization: String? get() = QuantizationName.fromFileName(modelFileName)
}

object QuantizationName {
    private val RE = Regex("""(?i)(?<![A-Za-z0-9])(IQ\d(?:_[A-Z0-9]+)*|Q\d(?:_[A-Z0-9]+)*|BF16|F16|F32)(?![A-Za-z0-9])""")

    fun fromFileName(name: String): String? =
        RE.find(name.substringBeforeLast('.').replace('-', '.').replace(' ', '.'))?.value?.uppercase()
}

/**
 * One generation. Every number that the engine or clock could not give is null (Unknown), not 0.
 *
 * - [promptTokens]: the engine's own countTokens(prompt text). Excludes chat-template overhead, so
 *   [prefillTokensPerSec] slightly under-reads real prefill speed. Null when countTokens failed.
 * - [emittedTokens]: tokens the engine delivered on its stream (its own unit).
 * - [ttftMillis]: generate() start to first token; includes template render + prefill + 1st sample.
 * - [decodeTokensPerSec]: (emitted - 1) / (last - first token time); null under 2 tokens.
 */
data class RepeatMeasurement(
    val promptId: String,
    val repeatIndex: Int,
    val promptTokens: Int?,
    val emittedTokens: Int,
    val ttftMillis: Double?,
    val prefillTokensPerSec: Double?,
    val decodeTokensPerSec: Double?,
    val thermalAfter: ThermalLevel?,
)

data class PromptSummary(
    val promptId: String,
    val promptTokens: Int?,
    val ttftMillis: StatSummary,
    val prefillTokensPerSec: StatSummary,
    val decodeTokensPerSec: StatSummary,
)

/**
 * The record of one run: measured on this device at this time, unverified by anyone else, and
 * carrying no verdict. Probe fields are null when the platform did not provide them.
 */
data class BenchmarkResult(
    val runId: String,
    val modelId: String,
    val modelFileName: String,
    val quantization: String?,
    val fileSizeBytes: Long?,
    val tokenizerId: String?,
    val contextSize: Int,
    val decodeTokensRequested: Int,
    val repeatsPlanned: Int,
    val device: DeviceFingerprint,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val thermalBefore: ThermalLevel?,
    val thermalAfter: ThermalLevel?,
    val batteryPercentBefore: Int?,
    val batteryPercentAfter: Int?,
    val pssKbBeforeLoad: Long?,
    val pssKbAfterLoad: Long?,
    val pssKbAfterGeneration: Long?,
    val loadMillis: Double?,
    val repeats: List<RepeatMeasurement>,
    val outcome: BenchmarkOutcome,
    /** Why it ended this way (thermal reading, exception class, ...); null for a clean completion. */
    val outcomeDetail: String?,
    /** Plain-language notes on Unknowns (a probe that was unavailable, a count that failed). */
    val notes: List<String>,
) {
    /** Per prompt, in plan order of first appearance. */
    fun summaries(): List<PromptSummary> =
        repeats.groupBy { it.promptId }.map { (id, rs) ->
            val ordered = rs.sortedBy { it.repeatIndex }
            PromptSummary(
                promptId = id,
                promptTokens = ordered.firstNotNullOfOrNull { it.promptTokens },
                ttftMillis = BenchmarkStats.of(ordered.map { it.ttftMillis }),
                prefillTokensPerSec = BenchmarkStats.of(ordered.map { it.prefillTokensPerSec }),
                decodeTokensPerSec = BenchmarkStats.of(ordered.map { it.decodeTokensPerSec }),
            )
        }

    companion object {
        const val SCOPE_STATEMENT =
            "Measured on this device at this time; unverified by anyone else. No verdict is implied."
    }
}
