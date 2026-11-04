package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.StateGlyphKeys
import java.math.BigDecimal
import java.util.Currency

/** One loop run as last reported by the phone, with when the watch heard it. */
data class LoopEntry(val snapshot: LoopRunSnapshot, val receivedAtMs: Long)

/** Most-recently-updated first; bounded so a long-lived watch never accumulates runs without limit. */
data class LoopsState(val runs: List<LoopEntry> = emptyList()) {
    companion object {
        /** Enough for a glance; the Loops screen is a read-only peek, not a history. */
        const val MAX_RUNS: Int = 12
    }
}

sealed interface LoopEvent : WatchEvent {
    /** One run's snapshot arrived (the phone sends one message per run). */
    data class RunReceived(val snapshot: LoopRunSnapshot, val atMs: Long) : LoopEvent
}

object LoopsReducer {
    fun reduce(state: LoopsState, event: LoopEvent): LoopsState = when (event) {
        is LoopEvent.RunReceived -> {
            val entry = LoopEntry(event.snapshot, event.atMs)
            LoopsState((listOf(entry) + state.runs.filter { it.snapshot.runId != event.snapshot.runId }).take(LoopsState.MAX_RUNS))
        }
    }
}

/** A loop run as words + a shape. */
data class LoopRow(
    val runId: String,
    val name: String,
    val glyph: Glyph,
    /** The state in words ("running", "complete", …); an unknown key is shown as itself, legibly. */
    val stateLabel: String,
    val stepLine: String,
    val spendLine: String,
    val ageLabel: String,
    val spoken: String,
)

object LoopsPresenter {
    fun rows(state: LoopsState, nowMs: Long): List<LoopRow> = state.runs.map { row(it, nowMs) }

    fun row(entry: LoopEntry, nowMs: Long): LoopRow {
        val s = entry.snapshot
        val (glyph, label) = glyphFor(s.stateGlyphKey)
        val age = "as of " + RelativeTime.ago(entry.receivedAtMs, nowMs)
        val step = s.stepName?.let { "step: $it" } ?: "no step yet"
        val spend = SpendLabel.line(s.spend)
        return LoopRow(
            runId = s.runId,
            name = s.loopName,
            glyph = glyph,
            stateLabel = label,
            stepLine = step,
            spendLine = spend,
            ageLabel = age,
            spoken = "${s.loopName}, $label, $step, $spend, $age",
        )
    }

    /** Known v1 keys get their own shape; an unknown (newer-phone) key gets the generic shape and *its own key as the label*. */
    fun glyphFor(key: String): Pair<Glyph, String> = when (key) {
        StateGlyphKeys.RUNNING -> Glyph.LOOP_RUNNING to "running"
        StateGlyphKeys.COMPLETE -> Glyph.LOOP_COMPLETE to "complete"
        StateGlyphKeys.STOPPED -> Glyph.LOOP_STOPPED to "stopped"
        StateGlyphKeys.FAILED -> Glyph.LOOP_FAILED to "failed"
        else -> Glyph.LOOP_UNKNOWN to key
    }
}

/**
 * A ledger spend as words. The contract carries an integer in the currency's **minor unit** and an
 * `estimated` flag; this renders both honestly — an estimate is `~` and "(estimated)", a missing figure
 * is "not recorded" (never `0`), and a currency whose minor-unit exponent this JVM does not know is
 * shown in raw minor units, labelled as such, rather than guessed at.
 */
object SpendLabel {
    fun line(spend: Spend?): String {
        if (spend == null) return "spend: not recorded"
        val amount = amount(spend)
        return if (spend.estimated) "spend: ~$amount (estimated)" else "spend: $amount"
    }

    private fun amount(spend: Spend): String {
        val digits = try {
            Currency.getInstance(spend.currency).defaultFractionDigits
        } catch (_: IllegalArgumentException) {
            -1
        }
        if (digits < 0) return "${spend.amountMinor} ${spend.currency} (minor units)"
        val major = BigDecimal.valueOf(spend.amountMinor).movePointLeft(digits).setScale(digits)
        return "${spend.currency} ${major.toPlainString()}"
    }
}
