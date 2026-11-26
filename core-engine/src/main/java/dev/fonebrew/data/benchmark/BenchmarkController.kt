package dev.fonebrew.data.benchmark

import dev.fonebrew.domain.benchmark.BatteryProbe
import dev.fonebrew.domain.benchmark.BenchmarkClock
import dev.fonebrew.domain.benchmark.BenchmarkEngine
import dev.fonebrew.domain.benchmark.BenchmarkPlan
import dev.fonebrew.domain.benchmark.BenchmarkPreflight
import dev.fonebrew.domain.benchmark.BenchmarkProgress
import dev.fonebrew.domain.benchmark.BenchmarkResult
import dev.fonebrew.domain.benchmark.BenchmarkRunner
import dev.fonebrew.domain.benchmark.DeviceFingerprint
import dev.fonebrew.domain.benchmark.MemoryProbe
import dev.fonebrew.domain.benchmark.Preflight
import dev.fonebrew.domain.benchmark.ThermalProbe
import dev.fonebrew.domain.model.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What the benchmark screen shows. Each state has a glyph + label in the UI; none relies on hue. */
sealed class BenchmarkUiState {
    /** Nothing running. [refusal] is the stated reason the last Start (or the selection) was refused. */
    data class Idle(val refusal: String? = null) : BenchmarkUiState()
    data class Running(val progress: BenchmarkProgress, val stopRequested: Boolean) : BenchmarkUiState()
}

/** Keeps the process alive while a run is going. Android: the existing GenerationService FGS. */
interface ForegroundHold {
    fun acquire()
    fun release()
}

/**
 * Owns the single benchmark run, process-wide (so leaving the screen does not cancel it).
 *
 * A run only ever begins inside [start], which the screen calls from an explicit Start tap; there
 * is no scheduler, deep link or auto-start. [BenchmarkPreflight] refuses anything that is not a
 * downloaded on-device GGUF, so there is no cloud path and no network call here. The shared
 * resident model is only unloaded when the caller passes [start]'s `unloadResidentModel = true`,
 * which the screen sets only after saying so on the Start button.
 */
class BenchmarkController(
    private val scope: CoroutineScope,
    private val engineFor: (ModelSpec) -> BenchmarkEngine?,
    private val unavailableReason: (ModelSpec) -> String?,
    private val residentModelLoaded: (ModelSpec) -> Boolean,
    private val unloadResidentModel: suspend () -> Unit,
    private val store: BenchmarkHistoryStore,
    private val clock: BenchmarkClock,
    private val thermal: ThermalProbe,
    private val memory: MemoryProbe,
    private val battery: BatteryProbe,
    private val device: () -> DeviceFingerprint,
    private val foreground: ForegroundHold,
    private val fileExists: (String) -> Boolean = { File(it).isFile },
    private val fileSize: (String) -> Long? = { p -> File(p).takeIf { it.isFile }?.length() },
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow<BenchmarkUiState>(BenchmarkUiState.Idle())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    /** The last run's result, kept until the next Start so the screen can show it. */
    private val _last = MutableStateFlow<BenchmarkResult?>(null)
    val last: StateFlow<BenchmarkResult?> = _last.asStateFlow()

    val history: StateFlow<List<BenchmarkResult>> get() = store.results

    private val stopFlag = AtomicBoolean(false)

    val isRunning: Boolean get() = _state.value is BenchmarkUiState.Running

    /** True when starting would unload a model a chat may be holding; the screen says so. */
    fun wouldUnloadResidentModel(spec: ModelSpec): Boolean = residentModelLoaded(spec)

    fun start(spec: ModelSpec, plan: BenchmarkPlan = BenchmarkPlan.default(), unloadResidentModel: Boolean = false) {
        if (isRunning) return
        val resident = residentModelLoaded(spec)
        val check = BenchmarkPreflight.check(
            spec = spec,
            fileExists = fileExists,
            fileSizeBytes = fileSize,
            busy = resident && !unloadResidentModel,
        )
        val target = when (check) {
            is Preflight.Refused -> {
                _state.value = BenchmarkUiState.Idle(check.reason)
                return
            }
            is Preflight.Ready -> check.target
        }
        val engine = engineFor(spec)
        if (engine == null) {
            _state.value = BenchmarkUiState.Idle(unavailableReason(spec) ?: "No on-device engine can run this model.")
            return
        }
        stopFlag.set(false)
        _last.value = null
        _state.value = BenchmarkUiState.Running(
            BenchmarkProgress(BenchmarkProgress.Phase.LOADING, 0, plan.generationCount), stopRequested = false,
        )
        foreground.acquire()
        scope.launch {
            try {
                if (resident && unloadResidentModel) unloadResidentModel()
                val runner = BenchmarkRunner(engine, clock, thermal, memory, battery, newRunId)
                val result = runner.run(
                    target = target,
                    plan = plan,
                    device = device(),
                    shouldStop = { stopFlag.get() },
                    onProgress = { p -> _state.value = BenchmarkUiState.Running(p, stopFlag.get()) },
                )
                store.append(result)
                _last.value = result
                _state.value = BenchmarkUiState.Idle()
            } catch (e: CancellationException) {
                _state.value = BenchmarkUiState.Idle("The benchmark was cancelled; no result was saved.")
                throw e
            } catch (e: Throwable) {
                _state.value = BenchmarkUiState.Idle("The benchmark could not run: ${e::class.simpleName}.")
            } finally {
                withContext(NonCancellable) { foreground.release() }
            }
        }
    }

    /** Cooperative: the runner sees this on the next token and unloads the model. */
    fun stop() {
        if (!isRunning) return
        stopFlag.set(true)
        (_state.value as? BenchmarkUiState.Running)?.let { _state.value = it.copy(stopRequested = true) }
    }
}
