package dev.fonebrew.domain.benchmark

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Fake time: only the scripted engine moves it, so every expected number is exact. */
class FakeClock(var now: Long = 0L, var wall: Long = 1_700_000_000_000L) : BenchmarkClock {
    override fun nanos(): Long = now
    override fun wallMillis(): Long = wall
    fun advanceMs(ms: Long) { now += ms * 1_000_000L }
}

class FakeThermal(var level: Reading<ThermalLevel> = Reading.Known(ThermalLevel.NONE)) : ThermalProbe {
    override fun read(): Reading<ThermalLevel> = level
}

class FakeMemory(var values: List<Reading<Long>> = listOf(Reading.Known(100_000L))) : MemoryProbe {
    private var i = 0
    override fun pssKb(): Reading<Long> = values[minOf(i++, values.size - 1)]
}

class FakeBattery(var values: List<Reading<Int>> = listOf(Reading.Known(80))) : BatteryProbe {
    private var i = 0
    override fun percent(): Reading<Int> = values[minOf(i++, values.size - 1)]
}

/**
 * One scripted generation: [gapsMs] are clock advances before each emitted token (first gap = time
 * to first token). [onToken] runs after each token is emitted, to flip thermal / stop flags at an
 * exact point.
 */
class Gen(val gapsMs: List<Long>, val onToken: (Int) -> Unit = {})

class ScriptedEngine(
    private val clock: FakeClock,
    private val loadMs: Long = 2_000,
    private val promptTokens: Map<String, Int> = emptyMap(),
    private val countFails: Boolean = false,
    private val loadFails: Throwable? = null,
    private val gens: (callIndex: Int) -> Gen,
) : InferenceEngine {
    override val tokenizerId = "fake-tok"
    override val supportsLogprobs = false
    override val supportsSamplingParams = true
    override var isLoaded = false
        private set

    var loadCalls = 0
    var unloadCalls = 0
    var generateCalls = 0
    val seenParams = mutableListOf<SamplingParams>()
    var lastLoadedPath: String? = null
    var lastLoadedCtx: Int? = null

    override suspend fun loadModel(modelPath: String, contextSize: Int) {
        loadCalls++
        loadFails?.let { throw it }
        clock.advanceMs(loadMs)
        lastLoadedPath = modelPath
        lastLoadedCtx = contextSize
        isLoaded = true
    }

    override suspend fun unload() {
        unloadCalls++
        isLoaded = false
    }

    override suspend fun countTokens(text: String): Int {
        if (countFails) error("tokenizer offline")
        return promptTokens.entries.firstOrNull { (k, _) -> text.startsWith(k) }?.value ?: 100
    }

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> {
        seenParams += params
        val g = gens(generateCalls++)
        return flow {
            g.gapsMs.forEachIndexed { i, gap ->
                clock.advanceMs(gap)
                emit(GeneratedToken(text = "t$i"))
                g.onToken(i + 1)
            }
        }
    }
}

val TARGET = BenchmarkTarget("local:m-Q4_K_M.gguf", "m-Q4_K_M.gguf", "/data/user/0/dev.fonebrew/files/models/m-Q4_K_M.gguf", 4_000_000_000L)
val DEVICE = DeviceFingerprint("Pixel Test", "15", 35, "arm64-v8a", "required extensions present")

fun onePrompt(repeats: Int, decode: Int = 8, ctx: Int = 4096) = BenchmarkPlan(
    prompts = listOf(BenchmarkPrompt("p", "P", "hello world", 16)),
    decodeTokens = decode, repeats = repeats, contextSize = ctx,
)

fun runner(
    engine: InferenceEngine,
    clock: FakeClock,
    thermal: ThermalProbe = FakeThermal(),
    memory: MemoryProbe = FakeMemory(),
    battery: BatteryProbe = FakeBattery(),
) = BenchmarkRunner(engine, clock, thermal, memory, battery) { "run-1" }
