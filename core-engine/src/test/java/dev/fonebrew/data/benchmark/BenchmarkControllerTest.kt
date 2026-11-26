package dev.fonebrew.data.benchmark

import dev.fonebrew.domain.benchmark.BenchmarkOutcome
import dev.fonebrew.domain.benchmark.BenchmarkPlan
import dev.fonebrew.domain.benchmark.BenchmarkPrompt
import dev.fonebrew.domain.benchmark.DEVICE
import dev.fonebrew.domain.benchmark.FakeBattery
import dev.fonebrew.domain.benchmark.FakeClock
import dev.fonebrew.domain.benchmark.FakeMemory
import dev.fonebrew.domain.benchmark.FakeThermal
import dev.fonebrew.domain.benchmark.Gen
import dev.fonebrew.domain.benchmark.ScriptedEngine
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Hold : ForegroundHold {
        var acquired = 0; var released = 0
        override fun acquire() { acquired++ }
        override fun release() { released++ }
    }

    private fun local() = ModelSpec(
        id = "local:m.gguf", displayName = "m", family = "gguf", contextWindow = 4096, tokenizerId = "t",
        templateId = TemplateId.PLAIN, runtime = Runtime.LOCAL_GGUF, modelPath = "/models/m-Q4_K_M.gguf",
    )

    private fun cloud() = local().copy(runtime = Runtime.CLOUD, modelPath = null, providerId = "p", watched = true)

    private val plan = BenchmarkPlan(listOf(BenchmarkPrompt("p", "P", "hello", 4)), decodeTokens = 4, repeats = 2, contextSize = 1024)

    private class Rig(
        val controller: BenchmarkController,
        val engine: ScriptedEngine,
        val hold: Hold,
        val store: BenchmarkHistoryStore,
        val engineAsked: () -> Int,
        val unloadedResident: () -> Int,
    )

    private fun rig(
        scope: TestScope, folder: File, resident: Boolean = false,
        gens: (Int) -> Gen = { Gen(listOf(10, 10, 10)) },
    ): Rig {
        val clock = FakeClock()
        val engine = ScriptedEngine(clock, gens = gens)
        val hold = Hold()
        val store = BenchmarkHistoryStore(File(folder, "h.json"))
        var asked = 0
        var unloaded = 0
        val c = BenchmarkController(
            scope = scope.backgroundScope,
            engineFor = { asked++; engine },
            unavailableReason = { "engine unavailable" },
            residentModelLoaded = { resident },
            unloadResidentModel = { unloaded++ },
            store = store, clock = clock, thermal = FakeThermal(), memory = FakeMemory(), battery = FakeBattery(),
            device = { DEVICE }, foreground = hold,
            fileExists = { true }, fileSize = { 7L }, newRunId = { "rid" },
        )
        return Rig(c, engine, hold, store, { asked }, { unloaded })
    }

    @Test fun nothingRunsUntilStartIsCalled() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(this, tmp.root)
        advanceUntilIdle()
        assertEquals(0, r.engine.loadCalls)
        assertEquals(0, r.hold.acquired)
        assertTrue(r.controller.state.value is BenchmarkUiState.Idle)
    }

    @Test fun cloudModelIsRefusedWithoutTouchingAnyEngine() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(this, tmp.root)
        r.controller.start(cloud(), plan)
        val s = r.controller.state.value as BenchmarkUiState.Idle
        assertTrue(s.refusal!!.contains("cloud"))
        assertEquals(0, r.engineAsked())
        assertEquals(0, r.hold.acquired)
        assertTrue(r.store.results.value.isEmpty())
    }

    @Test fun startRunsStoresAndReleasesTheForegroundHold() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(this, tmp.root)
        r.controller.start(local(), plan)
        advanceUntilIdle()
        assertEquals(1, r.hold.acquired); assertEquals(1, r.hold.released)
        assertEquals(1, r.store.results.value.size)
        assertEquals(BenchmarkOutcome.COMPLETED, r.store.results.value.single().outcome)
        assertEquals(2, r.store.results.value.single().repeats.size)
        assertEquals("rid", r.controller.last.value!!.runId)
        assertTrue(r.controller.state.value is BenchmarkUiState.Idle)
        assertEquals(1, r.engine.unloadCalls)
    }

    @Test fun residentModelBlocksUnlessTheCallerOptedIntoUnloading() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(this, tmp.root, resident = true)
        r.controller.start(local(), plan)
        assertTrue((r.controller.state.value as BenchmarkUiState.Idle).refusal!!.contains("already running"))
        assertEquals(0, r.unloadedResident()); assertEquals(0, r.engine.loadCalls)
        r.controller.start(local(), plan, unloadResidentModel = true)
        advanceUntilIdle()
        assertEquals(1, r.unloadedResident())
        assertEquals(1, r.store.results.value.size)
    }

    @Test fun stopEndsTheRunAsStoppedAndStillReleases() = runTest(UnconfinedTestDispatcher()) {
        lateinit var rg: Rig
        rg = rig(this, tmp.root, gens = { Gen(List(6) { 5L }) { n -> if (n == 2) rg.controller.stop() } })
        rg.controller.start(local(), plan)
        advanceUntilIdle()
        val res = rg.store.results.value.single()
        assertEquals(BenchmarkOutcome.STOPPED, res.outcome)
        assertEquals(1, rg.hold.released)
        assertEquals(1, rg.engine.unloadCalls)
        assertFalse(rg.controller.isRunning)
    }

    @Test fun aSecondStartWhileRunningIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        lateinit var rg: Rig
        rg = rig(this, tmp.root, gens = { Gen(listOf(5, 5)) { n -> if (n == 1) rg.controller.start(local(), plan) } })
        rg.controller.start(local(), plan)
        advanceUntilIdle()
        assertEquals(1, rg.hold.acquired)
        assertEquals(1, rg.store.results.value.size)
    }
}
