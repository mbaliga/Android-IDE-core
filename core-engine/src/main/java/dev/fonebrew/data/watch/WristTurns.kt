package dev.fonebrew.data.watch

import dev.fonebrew.watchbridge.Usage

/**
 * One relayed question, run to an answer. The seam between the relay's pure choreography
 * ([WearRelayPresenter]) and the engine bridge ([WristTurnRunner]): the presenter turns the events
 * below into contract messages, the runner produces them from the same engine path a normal chat
 * turn uses. Defined as an interface so the presenter is tested against a scripted turn and the
 * runner against fake DAOs and a fake engine, each without the other.
 *
 * **Event grammar** (the presenter relies on it, the runner's tests pin it): exactly one of
 *
 *  - `Engine, Token*, Done`  — an answer, or
 *  - `Failed`                — no answer could start (no model, engine not runnable, load failure), or
 *  - `Engine, Token*, Failed` — the engine broke mid-stream (partial text was already sent).
 *
 * If [emit] throws (the watch went away), the runner stops generating, persists what it has as a
 * stopped turn exactly like the phone's Stop button does, and rethrows so the caller can tidy up.
 */
fun interface WristTurns {
    suspend fun run(prompt: String, emit: suspend (WristTurnEvent) -> Unit)
}

sealed interface WristTurnEvent {

    /**
     * Which engine will answer, resolved before the first token. [watchedObject] is `true` exactly when
     * that engine is a cloud provider (the watched-object glyph then reaches the wrist); on-device is `false`.
     */
    data class Engine(val engineId: String, val watchedObject: Boolean) : WristTurnEvent

    /** Streamed answer text, exactly as the engine produced it. */
    data class Token(val text: String) : WristTurnEvent

    /** The answer finished; [usage] says honestly how its token counts were obtained. */
    data class Done(val usage: TurnUsage) : WristTurnEvent

    /** The question could not be answered, in words a person can read on a wrist. */
    data class Failed(val reason: String) : WristTurnEvent
}

/**
 * How a finished turn's token counts were obtained. Mirrors the contract's [Usage] without importing
 * its constructors into the engine bridge, and keeps the three honest cases distinct so none can be
 * mistaken for another.
 */
sealed interface TurnUsage {

    /** The provider itself reported these counts (a cloud engine's captured usage). Authoritative. */
    data class EngineReported(val inputTokens: Long, val outputTokens: Long) : TurnUsage

    /**
     * The provider reported nothing; these are the engine's own tokenizer counts (exact for the
     * tokenizer, but the prompt side approximates the templated prompt). Either may be `null` when the
     * tokenizer failed: that is "unknown", never 0.
     */
    data class Estimated(val inputTokens: Long?, val outputTokens: Long?) : TurnUsage

    /** Nothing reported and no tokenizer to ask. */
    data object Unknown : TurnUsage
}

/** The wire form of [this]: engine-reported stays `estimated = false`; everything else is flagged estimated. */
internal fun TurnUsage.toWire(): Usage = when (this) {
    is TurnUsage.EngineReported -> Usage.engineReported(inputTokens.coerceAtLeast(0), outputTokens.coerceAtLeast(0))
    is TurnUsage.Estimated -> Usage.estimate(inputTokens?.coerceAtLeast(0), outputTokens?.coerceAtLeast(0))
    TurnUsage.Unknown -> Usage.unknown()
}
