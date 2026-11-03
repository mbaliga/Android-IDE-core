package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.Usage

/**
 * Who answered, as the wrist shows it. The watched-object rule (binding rule 2, design doc section 1)
 * reaches the wrist: a cloud engine is shown with the cloud glyph and the words "cloud · watched"
 * (the phone UI's own "· watched" register); an on-device engine is shown with the house glyph and
 * "on-device". **The wrist never hides who answered**, and it never *upgrades* a claim: see
 * [AskReducer] for how a reply whose chunks disagree is resolved toward "watched".
 */
data class Provenance(
    val glyph: Glyph,
    /** "on-device" or "cloud · watched". Always shown with the glyph (shape + words, never hue). */
    val label: String,
    /** The engine id as the phone reported it, shortened with an explicit "…" if it is long. */
    val engine: String,
    val watched: Boolean,
    /** What a screen reader says. */
    val spoken: String,
)

object ProvenancePresenter {
    private const val MAX_ENGINE_CHARS = 24

    fun of(engineId: String, watchedObject: Boolean): Provenance {
        val engine = shorten(engineId)
        return if (watchedObject) {
            Provenance(Glyph.CLOUD_WATCHED, "cloud · watched", engine, true, "Answered by $engineId, a cloud model, watched object")
        } else {
            Provenance(Glyph.ON_DEVICE, "on-device", engine, false, "Answered on-device by $engineId")
        }
    }

    /** Truncates with a visible ellipsis — a clipped id is marked as clipped, never silently cut. */
    internal fun shorten(engineId: String): String =
        if (engineId.length <= MAX_ENGINE_CHARS) engineId else engineId.take(MAX_ENGINE_CHARS - 1) + "…"
}

/**
 * Token usage as words. **Real numbers only** (the contract's rule, mirrored): an engine-reported
 * count prints plain; an estimate prints with `~` and the word "est."; an unknown count prints
 * "unknown" — never `0`, because a real zero and "don't know" are different facts.
 */
object UsageLabel {
    fun line(usage: Usage): String {
        if (usage.inputTokens == null && usage.outputTokens == null) return "token usage unknown"
        val body = "${part("in", usage.inputTokens, usage.estimated)} · ${part("out", usage.outputTokens, usage.estimated)}"
        return if (usage.estimated) "est. $body" else body
    }

    private fun part(label: String, count: Long?, estimated: Boolean): String = when {
        count == null -> "$label unknown"
        estimated -> "$label ~$count"
        else -> "$label $count"
    }
}
