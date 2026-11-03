package dev.fonebrew.data.watch

import dev.fonebrew.domain.model.DefaultModelPolicy
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.inference.InferenceEngine

/** The outcome of choosing which engine answers a relayed question. */
sealed interface EngineResolution {

    /** [engine] is a fresh instance from the same factory a phone chat turn uses; [spec] says what it is. */
    data class Ready(val spec: ModelSpec, val engine: InferenceEngine) : EngineResolution

    /** Nothing can answer; [reason] is a fixed sentence fit for a wrist (never an exception message). */
    data class Unavailable(val reason: String) : EngineResolution
}

/**
 * Decides which engine answers a wrist question. A seam, so the one rule that matters here is
 * explicit and testable: [onDevice] is the v1 policy.
 *
 * **v1 policy: on-device only, by the phone's own rule.** [onDevice] resolves through
 * [DefaultModelPolicy.resolveActive], which by design never resolves a cloud model ("a persisted cloud
 * model is deliberately not restored: cloud is opt-in per use, never an ambient default a new session
 * silently resumes", CLAUDE.md binding rule 2). A question typed on a watch is exactly that kind of
 * ambient session: it arrives while the phone is in a pocket, with no phone screen to confirm "send
 * this to a cloud provider". So today a wrist question is answered by an on-device model or not at all.
 *
 * The cloud path is nevertheless complete downstream: [WristTurnRunner] derives `watchedObject` from the
 * resolved spec (cloud runtime or `watched`), captures provider-reported usage, prices it, and the
 * watched-object glyph reaches the wrist, all exercised in tests with a cloud-shaped fake. Enabling it
 * is a deliberate act with its own owner decision, **not** done here: a resolver that only returns a cloud
 * spec after an explicit per-use opt-in on the phone (named follow-up "wrist cloud opt-in").
 */
fun interface WristEngineResolver {
    fun resolve(): EngineResolution

    companion object {

        /** Shown when the phone has no usable model at all. */
        const val NO_MODEL_REASON: String = "No model is set up on the phone yet. Open Fonebrew there to choose one."

        /** Shown when the chosen model exists but cannot run right now and the phone gave no specific reason. */
        const val NOT_RUNNABLE_REASON: String = "The phone's model is not available right now. Open Fonebrew there."

        /**
         * The v1 policy (see the interface KDoc). All inputs are lazy lambdas read at resolve time, so a
         * model downloaded or switched since the last question is seen, and nothing is captured stale.
         */
        fun onDevice(
            specs: () -> List<ModelSpec>,
            persistedModelId: () -> String?,
            engineFor: (ModelSpec) -> InferenceEngine?,
            unavailableReason: (ModelSpec) -> String?,
        ): WristEngineResolver = WristEngineResolver {
            val spec = DefaultModelPolicy.resolveActive(specs(), persistedModelId())
                ?: return@WristEngineResolver EngineResolution.Unavailable(NO_MODEL_REASON)
            val engine = engineFor(spec)
                ?: return@WristEngineResolver EngineResolution.Unavailable(
                    unavailableReason(spec)?.let { "The phone's model can't run: $it." } ?: NOT_RUNNABLE_REASON,
                )
            EngineResolution.Ready(spec, engine)
        }
    }
}

/**
 * Whether the process needs lifting for the duration of a turn. The phone's own chat holds the
 * generation foreground service for an on-device local model so a minimized app is not reclaimed with
 * a large resident model; a relayed question arrives with the app backgrounded as the NORM, so the same
 * courtesy applies. [NONE] is the JVM-test default. The Android implementation lives in the container
 * wiring; whether Android permits starting that service from a Data Layer callback is device-only
 * (foreground-service start rules), and a refusal degrades to running without the boost, exactly as the
 * phone chat does.
 */
fun interface ProcessKeepAlive {
    /** Called before generation; the returned handle is closed after (success, failure or cancel). */
    fun hold(spec: ModelSpec): AutoCloseable

    companion object {
        val NONE: ProcessKeepAlive = ProcessKeepAlive { AutoCloseable { } }

        /** Same rule as the phone chat: only a resident local GGUF needs the foreground boost. */
        fun needsBoost(spec: ModelSpec): Boolean = spec.runtime == Runtime.LOCAL_GGUF
    }
}

/**
 * KV-cache session snapshot locations (architecture spine: "KV-cache snapshots via optional
 * sessionLoadPath/sessionSavePath"), the same ones the phone's chat resumes from. Honoured only by the
 * local engine; for everything else the runner passes nothing. [loadPathFor] returns `null` when no
 * snapshot exists for that node.
 */
class KvSnapshots(
    val loadPathFor: (nodeId: String) -> String?,
    val savePathFor: (nodeId: String) -> String,
)
