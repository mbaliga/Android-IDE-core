package dev.fonebrew.domain.benchmark

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** Asserts the runner's own arithmetic against a scripted engine and a fake clock. */
class BenchmarkRunnerTest {

    private fun gen5(first: Long, rest: Long) = Gen(listOf(first) + List(4) { rest })

    @Test fun exactNumbersForTwoRepeats() = runTest {
        val clock = FakeClock()
        // repeat 1: ttft 500ms, 4 tokens over 400ms -> 10 t/s. repeat 2: ttft 250ms, 4 over 200ms -> 20 t/s.
        val engine = ScriptedEngine(clock, loadMs = 2_000, promptTokens = mapOf("hello" to 100)) {
            if (it == 0) gen5(500, 100) else gen5(250, 50)
        }
        val r = runner(engine, clock).run(TARGET, onePrompt(2), DEVICE)

        assertEquals(BenchmarkOutcome.COMPLETED, r.outcome)
        assertEquals(2000.0, r.loadMillis!!, 1e-9)
        val a = r.repeats[0]
        assertEquals(100, a.promptTokens)
        assertEquals(5, a.emittedTokens)
        assertEquals(500.0, a.ttftMillis!!, 1e-9)
        assertEquals(200.0, a.prefillTokensPerSec!!, 1e-9) // 100 tokens / 0.5 s
        assertEquals(10.0, a.decodeTokensPerSec!!, 1e-9)   // 4 gaps / 0.4 s
        val b = r.repeats[1]
        assertEquals(250.0, b.ttftMillis!!, 1e-9)
        assertEquals(400.0, b.prefillTokensPerSec!!, 1e-9)
        assertEquals(20.0, b.decodeTokensPerSec!!, 1e-9)

        val s = r.summaries().single()
        assertEquals(15.0, s.decodeTokensPerSec.median!!, 1e-9)   // even count: mean of middle two
        assertEquals(10.0, s.decodeTokensPerSec.min!!, 1e-9)
        assertEquals(20.0, s.decodeTokensPerSec.max!!, 1e-9)
        assertEquals(100.0, s.decodeTokensPerSec.driftPercent!!, 1e-9) // 10 -> 20
        assertEquals(375.0, s.ttftMillis.median!!, 1e-9)
    }

    @Test fun passesPathAndContextToTheEngineAndRequestsGreedyFixedDecode() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { gen5(10, 10) }
        runner(engine, clock).run(TARGET, onePrompt(1, decode = 8, ctx = 2048), DEVICE)
        assertEquals(TARGET.modelPath, engine.lastLoadedPath)
        assertEquals(2048, engine.lastLoadedCtx)
        assertEquals(listOf(8), engine.seenMaxTokens)
        assertEquals(listOf(42L), engine.seenSeeds)
    }

    @Test fun recordsWallTimesProbesAndUnloads() = runTest {
        val clock = FakeClock(wall = 1000)
        val engine = ScriptedEngine(clock) { gen5(10, 10) }
        val mem = FakeMemory(listOf(Reading.Known(100L), Reading.Known(900L), Reading.Known(950L)))
        val bat = FakeBattery(listOf(Reading.Known(80), Reading.Known(79)))
        val thermal = FakeThermal(Reading.Known(ThermalLevel.LIGHT))
        val r = runner(engine, clock, thermal, mem, bat).run(TARGET, onePrompt(1), DEVICE)
        assertEquals(100L, r.pssKbBeforeLoad)
        assertEquals(900L, r.pssKbAfterLoad)
        assertEquals(950L, r.pssKbAfterGeneration)
        assertEquals(80, r.batteryPercentBefore)
        assertEquals(79, r.batteryPercentAfter)
        assertEquals(ThermalLevel.LIGHT, r.thermalBefore)
        assertEquals(ThermalLevel.LIGHT, r.thermalAfter)
        assertEquals("Q4_K_M", r.quantization)
        assertEquals("fake-tok", r.tokenizerId)
        assertEquals(1, engine.unloadCalls)
        assertEquals(1000L, r.startedAtMillis)
        assertNull(r.outcomeDetail)
    }

    @Test fun unknownProbesStayNullAndAreNoted() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { gen5(10, 10) }
        val r = runner(
            engine, clock,
            FakeThermal(Reading.Unknown("no thermal service")),
            FakeMemory(listOf(Reading.Unknown("no pss"))),
            FakeBattery(listOf(Reading.Unknown("no battery"))),
        ).run(TARGET, onePrompt(1), DEVICE)
        assertNull(r.thermalBefore); assertNull(r.thermalAfter)
        assertNull(r.pssKbBeforeLoad); assertNull(r.pssKbAfterLoad); assertNull(r.pssKbAfterGeneration)
        assertNull(r.batteryPercentBefore); assertNull(r.batteryPercentAfter)
        assertEquals(BenchmarkOutcome.COMPLETED, r.outcome)
        assertTrue(r.notes.any { "thermal status before unknown: no thermal service" in it })
        assertTrue(r.notes.any { "heat abort could not act" in it })
        assertTrue(r.notes.any { "battery after unknown" in it })
    }

    @Test fun unavailablePromptCountIsUnknownNotEstimated() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock, countFails = true) { gen5(500, 100) }
        val r = runner(engine, clock).run(TARGET, onePrompt(2), DEVICE)
        val m = r.repeats[0]
        assertNull(m.promptTokens)
        assertNull(m.prefillTokensPerSec)
        assertEquals(500.0, m.ttftMillis!!, 1e-9)
        assertEquals(10.0, m.decodeTokensPerSec!!, 1e-9)
        assertEquals(1, r.notes.count { "prompt token count unavailable" in it }) // once per prompt, not per repeat
        assertNull(r.summaries().single().promptTokens)
        assertEquals(0, r.summaries().single().prefillTokensPerSec.known)
    }

    @Test fun zeroTokensIsUnknownNotZeroRate() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { Gen(emptyList()) }
        val r = runner(engine, clock).run(TARGET, onePrompt(1), DEVICE)
        val m = r.repeats.single()
        assertEquals(0, m.emittedTokens)
        assertNull(m.ttftMillis); assertNull(m.prefillTokensPerSec); assertNull(m.decodeTokensPerSec)
        assertTrue(r.notes.any { "emitted no tokens" in it })
    }

    @Test fun oneTokenHasTtftButNoDecodeRate() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock, promptTokens = mapOf("hello" to 50)) { Gen(listOf(250)) }
        val m = runner(engine, clock).run(TARGET, onePrompt(1), DEVICE).repeats.single()
        assertEquals(250.0, m.ttftMillis!!, 1e-9)
        assertEquals(200.0, m.prefillTokensPerSec!!, 1e-9)
        assertNull(m.decodeTokensPerSec)
    }

    @Test fun zeroLengthTtftLeavesPrefillUnknown() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { Gen(listOf(0, 10, 10)) }
        val m = runner(engine, clock).run(TARGET, onePrompt(1), DEVICE).repeats.single()
        assertEquals(0.0, m.ttftMillis!!, 1e-9)
        assertNull(m.prefillTokensPerSec) // would be a division by zero, not infinity
        assertEquals(100.0, m.decodeTokensPerSec!!, 1e-9)
    }

    @Test fun promptOverContextIsNoted() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock, promptTokens = mapOf("hello" to 4000)) { gen5(10, 10) }
        val r = runner(engine, clock).run(TARGET, onePrompt(1, decode = 200, ctx = 4096), DEVICE)
        assertTrue(r.notes.any { "over the 4096-token context" in it })
    }

    @Test fun thermalAlreadySevereNeverLoadsTheModel() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { gen5(10, 10) }
        val r = runner(engine, clock, FakeThermal(Reading.Known(ThermalLevel.SEVERE))).run(TARGET, onePrompt(2), DEVICE)
        assertEquals(BenchmarkOutcome.ABORTED_THERMAL, r.outcome)
        assertEquals(0, engine.loadCalls)
        assertEquals(0, engine.unloadCalls)
        assertTrue(r.repeats.isEmpty())
        assertTrue(r.outcomeDetail!!.contains("SEVERE"))
    }

    @Test fun thermalSevereBetweenGenerationsAbortsKeepingFinishedRepeats() = runTest {
        val clock = FakeClock()
        val thermal = FakeThermal()
        val engine = ScriptedEngine(clock) { i ->
            Gen(listOf(10, 10)) { n -> if (i == 1 && n == 2) thermal.level = Reading.Known(ThermalLevel.CRITICAL) }
        }
        val r = runner(engine, clock, thermal).run(TARGET, onePrompt(4), DEVICE)
        assertEquals(BenchmarkOutcome.ABORTED_THERMAL, r.outcome)
        assertEquals(2, r.repeats.size)
        assertEquals(ThermalLevel.CRITICAL, r.repeats.last().thermalAfter)
        assertEquals(2, engine.generateCalls)
        assertEquals(1, engine.unloadCalls)
        assertTrue(r.outcomeDetail!!.contains("CRITICAL"))
        assertEquals(ThermalLevel.CRITICAL, r.thermalAfter)
    }

    @Test fun thermalSevereMidGenerationAbortsAndDiscardsThatRepeat() = runTest {
        val clock = FakeClock()
        val thermal = FakeThermal()
        val engine = ScriptedEngine(clock) { i ->
            Gen(List(40) { 5L }) { n -> if (i == 1 && n == 3) thermal.level = Reading.Known(ThermalLevel.SEVERE) }
        }
        val r = runner(engine, clock, thermal).run(TARGET, onePrompt(3), DEVICE)
        assertEquals(BenchmarkOutcome.ABORTED_THERMAL, r.outcome)
        assertEquals(1, r.repeats.size) // the second generation was cut off and discarded
        assertTrue(r.outcomeDetail!!.contains("discarded"))
        assertEquals(1, engine.unloadCalls)
    }

    @Test fun stopBeforeLoadingNeverLoads() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { gen5(10, 10) }
        val r = runner(engine, clock).run(TARGET, onePrompt(2), DEVICE, shouldStop = { true })
        assertEquals(BenchmarkOutcome.STOPPED, r.outcome)
        assertEquals(0, engine.loadCalls)
    }

    @Test fun stopMidGenerationDiscardsThatRepeatAndUnloads() = runTest {
        val clock = FakeClock()
        var stop = false
        val engine = ScriptedEngine(clock) { i ->
            Gen(List(10) { 5L }) { n -> if (i == 1 && n == 4) stop = true }
        }
        val r = runner(engine, clock).run(TARGET, onePrompt(5), DEVICE, shouldStop = { stop })
        assertEquals(BenchmarkOutcome.STOPPED, r.outcome)
        assertEquals(1, r.repeats.size)
        assertEquals(1, engine.unloadCalls)
        assertTrue(r.outcomeDetail!!.contains("discarded"))
    }

    @Test fun stopBetweenGenerationsReportsCount() = runTest {
        val clock = FakeClock()
        var stop = false
        val engine = ScriptedEngine(clock) { i -> Gen(listOf(5, 5)) { n -> if (i == 1 && n == 2) stop = true } }
        val r = runner(engine, clock).run(TARGET, onePrompt(5), DEVICE, shouldStop = { stop })
        assertEquals(BenchmarkOutcome.STOPPED, r.outcome)
        // The flag flips after generation #2's last token, so that repeat completes and the next
        // loop iteration is the one that stops.
        assertEquals(2, r.repeats.size)
        assertTrue(r.outcomeDetail!!.contains("after 2 of 5"))
    }

    @Test fun engineFailureIsRecordedWithoutThePathAndStillUnloads() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { i ->
            if (i == 1) Gen(listOf(5, 5)) { n -> if (n == 1) error("cannot read /data/user/0/dev.fonebrew/files/models/m.gguf: bad") }
            else Gen(listOf(5, 5))
        }
        val r = runner(engine, clock).run(TARGET, onePrompt(3), DEVICE)
        assertEquals(BenchmarkOutcome.FAILED, r.outcome)
        assertEquals(1, r.repeats.size)
        val d = r.outcomeDetail!!
        assertTrue(d.startsWith("IllegalStateException"))
        assertFalse(d.contains("/data/"))
        assertTrue(d.contains("m.gguf"))
        assertEquals(1, engine.unloadCalls)
    }

    @Test fun loadFailureIsFailedAndNeverUnloads() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock, loadFails = RuntimeException("Failed to load model: /sdcard/x/m.gguf")) { gen5(1, 1) }
        val r = runner(engine, clock).run(TARGET, onePrompt(1), DEVICE)
        assertEquals(BenchmarkOutcome.FAILED, r.outcome)
        assertNull(r.loadMillis)
        assertEquals(0, engine.unloadCalls)
        assertFalse(r.outcomeDetail!!.contains("/sdcard"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun coroutineCancellationUnloadsAndRethrowsInsteadOfReturningAResult() = runTest {
        val clock = FakeClock()
        val gate = CompletableDeferred<Unit>()
        val engine = object : BenchmarkEngine by ScriptedEngine(clock, gens = { gen5(1, 1) }) {
            var unloaded = 0
            override suspend fun unload() { unloaded++ }
            override suspend fun loadModel(modelPath: String, contextSize: Int) {}
            override fun generate(promptText: String, maxTokens: Int, seed: Long) = flow<Unit> { gate.await() }
        }
        var outcome: Result<BenchmarkResult>? = null
        val job = launch {
            outcome = runCatching { runner(engine, clock).run(TARGET, onePrompt(1), DEVICE) }
        }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertTrue(outcome!!.exceptionOrNull() is CancellationException)
        assertEquals(1, engine.unloaded)
    }

    @Test fun progressReportsPhasesAndCounts() = runTest {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock) { gen5(10, 10) }
        val seen = mutableListOf<BenchmarkProgress>()
        runner(engine, clock).run(TARGET, onePrompt(2), DEVICE, onProgress = { seen += it })
        assertEquals(BenchmarkProgress.Phase.LOADING, seen.first().phase)
        assertEquals(listOf(0, 1), seen.filter { it.phase == BenchmarkProgress.Phase.GENERATING }.map { it.completedGenerations })
        assertEquals(BenchmarkProgress.Phase.DONE, seen.last().phase)
        assertEquals(2, seen.last().completedGenerations)
        assertNotNull(seen.last().totalGenerations)
    }
}
