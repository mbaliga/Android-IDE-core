package dev.fonebrew.domain.benchmark

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.inference.InferenceEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Where a run is, for the progress line. Carried as a label + glyph in the UI, not a colour. */
data class BenchmarkProgress(
    val phase: Phase,
    val completedGenerations: Int,
    val totalGenerations: Int,
    val promptLabel: String? = null,
    val thermal: ThermalLevel? = null,
) {
    enum class Phase { LOADING, GENERATING, UNLOADING, DONE }
}

/**
 * Drives an [InferenceEngine] through a [BenchmarkPlan] and records what the injected [clock] saw.
 * Pure: all time, thermal, memory and battery input arrives through interfaces, so the arithmetic
 * is exact in JVM tests. It makes no network call and has no cloud path: the engine it is handed
 * is whatever [BenchmarkPreflight] allowed, a local model.
 *
 * Rules the runner keeps:
 *  - Numbers come from the engine's own counts and the clock. If a count or probe is unavailable
 *    the field is null (Unknown) and a note says why; nothing is estimated.
 *  - Stop: [shouldStop] is polled on every token and between generations. The model is unloaded.
 *  - Heat: thermal status SEVERE or worse before the run, between generations, and every
 *    [THERMAL_CHECK_EVERY] tokens inside a generation aborts the run. The generation in flight is
 *    discarded (its timing is no longer a clean measurement) and the abort says so.
 *  - Real coroutine cancellation unloads the model and rethrows; it is not turned into a result.
 *  - Every other ending (failure, stop, abort) still returns a [BenchmarkResult] with the repeats
 *    completed so far.
 */
class BenchmarkRunner(
    private val engine: InferenceEngine,
    private val clock: BenchmarkClock,
    private val thermal: ThermalProbe,
    private val memory: MemoryProbe,
    private val battery: BatteryProbe,
    private val newRunId: () -> String,
) {

    private class StopSignal : RuntimeException()
    private class ThermalSignal(val level: ThermalLevel) : RuntimeException()

    suspend fun run(
        target: BenchmarkTarget,
        plan: BenchmarkPlan,
        device: DeviceFingerprint,
        shouldStop: () -> Boolean = { false },
        onProgress: (BenchmarkProgress) -> Unit = {},
    ): BenchmarkResult {
        val notes = mutableListOf<String>()
        fun <T> Reading<T>.noted(what: String): T? {
            whyUnknown?.let { notes += "$what unknown: $it" }
            return valueOrNull
        }

        val total = plan.generationCount
        val startedAt = clock.wallMillis()
        val thermalBefore = thermal.read().noted("thermal status before")
        val batteryBefore = battery.percent().noted("battery before")
        val pssBeforeLoad = memory.pssKb().noted("memory before load")

        var outcome = BenchmarkOutcome.COMPLETED
        var detail: String? = null
        var loadMillis: Double? = null
        var pssAfterLoad: Long? = null
        var pssAfterGen: Long? = null
        var thermalAfterSeen: ThermalLevel? = null
        var loaded = false
        val repeats = mutableListOf<RepeatMeasurement>()

        try {
            if (thermalBefore?.isAbortLevel == true) {
                outcome = BenchmarkOutcome.ABORTED_THERMAL
                detail = "Not started: thermal status was already ${thermalBefore.name} before loading."
            } else if (shouldStop()) {
                outcome = BenchmarkOutcome.STOPPED
                detail = "Stopped before loading the model."
            } else {
                onProgress(BenchmarkProgress(BenchmarkProgress.Phase.LOADING, 0, total, thermal = thermalBefore))
                val t0 = clock.nanos()
                engine.loadModel(target.modelPath, plan.contextSize)
                loadMillis = (clock.nanos() - t0) / NANOS_PER_MILLI
                loaded = true
                pssAfterLoad = memory.pssKb().noted("memory after load")
                val thermalSeen = thermal.read()
                if (thermalSeen is Reading.Unknown) {
                    notes += "thermal status unavailable during the run: the heat abort could not act."
                }

                var done = 0
                loop@ for (prompt in plan.prompts) {
                    for (i in 1..plan.repeats) {
                        if (shouldStop()) {
                            outcome = BenchmarkOutcome.STOPPED
                            detail = "Stopped by you after $done of $total generations."
                            break@loop
                        }
                        val hot = thermal.read().valueOrNull?.takeIf { it.isAbortLevel }
                        if (hot != null) {
                            outcome = BenchmarkOutcome.ABORTED_THERMAL
                            detail = "Thermal status ${hot.name} after $done of $total generations."
                            break@loop
                        }
                        onProgress(
                            BenchmarkProgress(
                                BenchmarkProgress.Phase.GENERATING, done, total, prompt.label,
                                thermal = thermal.read().valueOrNull,
                            ),
                        )
                        val m = try {
                            measure(prompt, i, plan, notes, shouldStop)
                        } catch (_: StopSignal) {
                            outcome = BenchmarkOutcome.STOPPED
                            detail = "Stopped by you during \"${prompt.label}\" repeat $i; that repeat was discarded."
                            break@loop
                        } catch (t: ThermalSignal) {
                            outcome = BenchmarkOutcome.ABORTED_THERMAL
                            detail = "Thermal status ${t.level.name} during \"${prompt.label}\" repeat $i; that repeat was discarded."
                            break@loop
                        }
                        repeats += m
                        thermalAfterSeen = m.thermalAfter
                        done++
                        if (m.thermalAfter?.isAbortLevel == true && done < total) {
                            outcome = BenchmarkOutcome.ABORTED_THERMAL
                            detail = "Thermal status ${m.thermalAfter.name} after $done of $total generations."
                            break@loop
                        }
                    }
                }
                pssAfterGen = memory.pssKb().noted("memory after generation")
            }
        } catch (e: CancellationException) {
            if (loaded) withContext(NonCancellable) { runCatching { engine.unload() } }
            throw e
        } catch (e: Throwable) {
            outcome = BenchmarkOutcome.FAILED
            detail = describeFailure(e)
        }

        if (loaded) {
            onProgress(BenchmarkProgress(BenchmarkProgress.Phase.UNLOADING, repeats.size, total))
            withContext(NonCancellable) {
                runCatching { engine.unload() }.onFailure { notes += "unload failed: ${describeFailure(it)}" }
            }
        }

        val thermalAfter = thermal.read().noted("thermal status after") ?: thermalAfterSeen
        val batteryAfter = battery.percent().noted("battery after")
        val endedAt = clock.wallMillis()
        onProgress(BenchmarkProgress(BenchmarkProgress.Phase.DONE, repeats.size, total, thermal = thermalAfter))

        return BenchmarkResult(
            runId = newRunId(),
            modelId = target.modelId,
            modelFileName = target.modelFileName,
            quantization = target.quantization,
            fileSizeBytes = target.fileSizeBytes,
            tokenizerId = runCatching { engine.tokenizerId }.getOrNull(),
            contextSize = plan.contextSize,
            decodeTokensRequested = plan.decodeTokens,
            repeatsPlanned = plan.repeats,
            device = device,
            startedAtMillis = startedAt,
            endedAtMillis = endedAt,
            thermalBefore = thermalBefore,
            thermalAfter = thermalAfter,
            batteryPercentBefore = batteryBefore,
            batteryPercentAfter = batteryAfter,
            pssKbBeforeLoad = pssBeforeLoad,
            pssKbAfterLoad = pssAfterLoad,
            pssKbAfterGeneration = pssAfterGen,
            loadMillis = loadMillis,
            repeats = repeats,
            outcome = outcome,
            outcomeDetail = detail,
            notes = notes,
        )
    }

    private suspend fun measure(
        prompt: BenchmarkPrompt,
        repeatIndex: Int,
        plan: BenchmarkPlan,
        notes: MutableList<String>,
        shouldStop: () -> Boolean,
    ): RepeatMeasurement {
        val promptTokens: Int? = try {
            engine.countTokens(prompt.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (repeatIndex == 1) notes += "prompt token count unavailable for \"${prompt.label}\": ${describeFailure(e)}"
            null
        }
        if (repeatIndex == 1 && promptTokens != null && promptTokens + plan.decodeTokens > plan.contextSize) {
            notes += "\"${prompt.label}\" is $promptTokens tokens + up to ${plan.decodeTokens} decoded, over the " +
                "${plan.contextSize}-token context; engine behaviour at the limit is unmeasured."
        }

        val messages = listOf(
            MessageNode(id = "bench-${prompt.id}-$repeatIndex", parentId = null, role = Role.USER, content = prompt.text, createdAt = 0L),
        )
        // Fixed seed and zero temperature: the same work every run, so differences are the device's.
        val params = SamplingParams(temperature = 0f, topK = 1, maxTokens = plan.decodeTokens, seed = SEED)

        var emitted = 0
        var firstAt = 0L
        var lastAt = 0L
        val startAt = clock.nanos()
        engine.generate(messages, params).collect {
            val now = clock.nanos()
            if (emitted == 0) firstAt = now
            lastAt = now
            emitted++
            if (shouldStop()) throw StopSignal()
            if (emitted % THERMAL_CHECK_EVERY == 0) {
                thermal.read().valueOrNull?.takeIf { l -> l.isAbortLevel }?.let { l -> throw ThermalSignal(l) }
            }
        }

        val ttftNanos = if (emitted >= 1) firstAt - startAt else null
        val ttftMs = ttftNanos?.let { it / NANOS_PER_MILLI }
        val prefillTps = if (promptTokens != null && ttftNanos != null && ttftNanos > 0) {
            promptTokens / (ttftNanos / NANOS_PER_SECOND)
        } else {
            null
        }
        val decodeNanos = lastAt - firstAt
        val decodeTps = if (emitted >= 2 && decodeNanos > 0) (emitted - 1) / (decodeNanos / NANOS_PER_SECOND) else null
        if (emitted == 0) notes += "\"${prompt.label}\" repeat $repeatIndex emitted no tokens."

        return RepeatMeasurement(
            promptId = prompt.id,
            repeatIndex = repeatIndex,
            promptTokens = promptTokens,
            emittedTokens = emitted,
            ttftMillis = ttftMs,
            prefillTokensPerSec = prefillTps,
            decodeTokensPerSec = decodeTps,
            thermalAfter = thermal.read().valueOrNull,
        )
    }

    companion object {
        const val THERMAL_CHECK_EVERY = 16
        private const val SEED = 42L
        private const val NANOS_PER_MILLI = 1_000_000.0
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /**
         * Class name plus a short message with any path reduced to its last segment, so a model's
         * directory (and anything key-shaped that rode in a message) never lands in saved results.
         */
        internal fun describeFailure(t: Throwable): String {
            val msg = (t.message ?: "").split(Regex("\\s+")).joinToString(" ") { tok ->
                if ('/' in tok) tok.trimEnd('/', ':', ',', '.').substringAfterLast('/') else tok
            }.take(160)
            val name = t::class.simpleName ?: "Error"
            return if (msg.isBlank()) name else "$name: $msg"
        }
    }
}
