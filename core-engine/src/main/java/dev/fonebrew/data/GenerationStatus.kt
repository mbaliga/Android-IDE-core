package dev.fonebrew.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A process-scoped mirror of the chat's generation phase, so something outside the chat screen (the
 * home-screen "Model" widget, `dev.fonebrew.widget`) can say whether a model is loading or generating
 * without reaching into an Activity-scoped ViewModel.
 *
 * The chat stays the only writer and the source of truth: [dev.fonebrew.ui.ChatViewModel] reports its
 * own `GenPhase` here (one collector in its `init`), and resets to idle when it is cleared. Nothing here
 * runs work, starts a service or holds text: only the phase, which composer mode it belongs to, the
 * chat's active model id, where the turn in flight runs (on-device or cloud), and two timestamps. It lives only as long as the process; a process that died
 * mid-generation starts again at [Phase.IDLE], which is then the truth.
 */
class GenerationStatus(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Phase { IDLE, LOADING, GENERATING }

    /** What kind of turn the phase belongs to: the composer mode the chat was in when it started. */
    enum class Mode { TEXT, COUNCIL, IMAGE, OBJECT_3D }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val mode: Mode = Mode.TEXT,
        /** The chat's active model id, as the chat resolved it; null until a chat has reported. */
        val activeModelId: String? = null,
        /** True once a chat screen has reported at least once in this process. */
        val reported: Boolean = false,
        /** When the current non-idle [phase] began; null while idle. */
        val since: Long? = null,
        /** When the last non-idle phase ended in this process; null if none has. */
        val lastEndedAt: Long? = null,
        /**
         * Where the current turn's work runs, when it is not simply the [activeModelId]: false when any
         * part goes to a cloud model or provider (a cloud image provider, a cloud 3D object, a Council
         * with a cloud voice), true when all of it stays on this device. Null while idle, and for a
         * turn that runs on the active model (the widget then describes that model).
         */
        val runOnDevice: Boolean? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Called by the chat whenever its phase, mode, active model or the turn's provenance changes.
     * Idempotent. [runOnDevice] is dropped while idle: it only ever describes a turn in flight.
     */
    fun report(phase: Phase, mode: Mode, activeModelId: String?, runOnDevice: Boolean? = null) {
        val run = runOnDevice.takeIf { phase != Phase.IDLE }
        _state.update { prev ->
            if (prev.reported && prev.phase == phase && prev.mode == mode && prev.activeModelId == activeModelId &&
                prev.runOnDevice == run
            ) {
                prev
            } else {
                val now = clock()
                Snapshot(
                    phase = phase,
                    mode = mode,
                    activeModelId = activeModelId,
                    reported = true,
                    runOnDevice = run,
                    since = when {
                        phase == Phase.IDLE -> null
                        prev.phase == phase && prev.since != null -> prev.since
                        else -> now
                    },
                    lastEndedAt = if (phase == Phase.IDLE && prev.phase != Phase.IDLE) now else prev.lastEndedAt,
                )
            }
        }
    }

    /** The chat screen went away (ViewModel cleared): nothing it started can still be running. */
    fun chatGone() {
        _state.update { prev ->
            if (prev.phase == Phase.IDLE) prev else prev.copy(phase = Phase.IDLE, since = null, lastEndedAt = clock(), runOnDevice = null)
        }
    }
}
