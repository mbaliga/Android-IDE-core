package dev.aarso.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.domain.format.LocaleFormat
import dev.aarso.domain.ledger.BudgetRing
import dev.aarso.domain.ledger.LedgerAggregations
import dev.aarso.domain.ledger.RingState
import java.util.Locale

/**
 * Presentational Compose components for the **"Myself" usage views** (Doc 07 — the usage
 * ledger surfaces over [dev.aarso.domain.ledger.LedgerAggregations]). These are the
 * legible-cognitive-sovereignty cards: how many tokens went where, what it cost, and —
 * the headline — **how much of your thinking stayed on your own device**.
 *
 * **Pure UI, no app singleton.** Every input is an already-computed domain aggregation
 * result ([LedgerAggregations.Totals], [LedgerAggregations.ProvenanceSplit],
 * `Map<String, ProviderRollup>`, [RingState]) plus a [Locale] and an ISO-4217
 * `currencyCode`. There is no ViewModel, no I/O, no clock, no container reference — the
 * caller folds the ledger (binding rule 1: on-device aggregation, no telemetry) and hands
 * us the result. Styling matches the wireframe register of
 * [dev.aarso.ui.develop.DevelopRoom] and [ProvenanceComponents] (boxy borders,
 * `MaterialTheme.colorScheme` / typography, Material 3 — **not** Hyle), and depends only
 * on Compose material3 + foundation.
 *
 * **Numbers are always locale-formatted (Doc 00 §3.3, binding).** Every token count goes
 * through [LocaleFormat.tokens], every percentage through [LocaleFormat.percent] (which
 * takes a 0..1 fraction), every money figure through [LocaleFormat.currencyMinor] (minor
 * units → localized currency). No raw `toString()` / `%,d` on a user-facing number.
 *
 * **Text-forward charts (Doc 07 §6 / Doc 00 §3.5: every chart has a data-table + spoken
 * summary).** Each "chart-like" view here is really a few [Box]es with `fillMaxWidth`
 * fractions for the bar, but the *meaning* lives in the exact numbers rendered as text
 * right beside it — the bar only adds a glanceable shape on top, it is never the sole
 * carrier. Where a bar appears, the enclosing element also exposes a
 * `Modifier.semantics { contentDescription = … }` so a TalkBack user hears the same
 * figures a sighted user reads. Nothing here uses colour as the only signal.
 *
 * **Cloud is a watched object (binding rule 2).** The sovereignty framing is honest and
 * non-coercive: on-device work is free and private; cloud work is watched and paid. The
 * budget ring is the user's *own* informational ceiling — never a paywall, throttle, or
 * engagement nudge (see [dev.aarso.domain.ledger.BudgetRing]).
 */

/* --------------------------------------------------------------- shared bar */

/**
 * A two-segment split bar: a leading segment that fills [leadingFraction] of the width
 * (clamped to `0..1`) in [leadingColor], the remainder in [trailingColor]. Purely
 * decorative — callers always render the exact figures as text alongside, and attach the
 * spoken summary to an enclosing element. The bar carries no `contentDescription` of its
 * own so a screen reader is not made to read a shape twice.
 */
@Composable
private fun SplitBar(
    leadingFraction: Double,
    leadingColor: Color,
    trailingColor: Color,
) {
    val frac = leadingFraction.coerceIn(0.0, 1.0).toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(trailingColor),
    ) {
        if (frac > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(leadingColor),
            )
        }
    }
}

/** A bordered card matching the wireframe register, with a title and free content. */
@Composable
private fun LedgerCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(12.dp),
        content = content,
    )
}

/* ----------------------------------------------------------- input / output */

/**
 * **Input vs output tokens, plus the estimated cost** for a period
 * ([LedgerAggregations.Totals]).
 *
 * Renders the exact token counts via [LocaleFormat.tokens] (input, output and the
 * `totalTokens` sum) and the total estimated cost via [LocaleFormat.currencyMinor]
 * ([currencyCode] in ISO-4217), then a simple split bar showing the input share of the
 * total. Cost is honestly labelled *estimated* — the underlying figure is an estimate
 * unless a provider reported real usage. The whole card carries a spoken summary so the
 * split is audible, not only visible.
 *
 * @param totals the grand totals fold over the period's ledger entries.
 * @param locale the locale every number is formatted against (grouping, digits, separators).
 * @param currencyCode ISO-4217 code (e.g. "USD", "INR") for the cost figure.
 */
@Composable
fun InputOutputCard(
    totals: LedgerAggregations.Totals,
    locale: Locale,
    currencyCode: String,
    showCost: Boolean = true,
) {
    val total = totals.totalTokens
    val inputFraction = if (total == 0L) 0.0 else totals.inputTokens.toDouble() / total.toDouble()

    val inputText = LocaleFormat.tokens(totals.inputTokens, locale)
    val outputText = LocaleFormat.tokens(totals.outputTokens, locale)
    val totalText = LocaleFormat.tokens(total, locale)
    val costText = LocaleFormat.currencyMinor(totals.estCostMinor, currencyCode, locale)

    // Cost is a per-loop execution boundary, not a profile headline (brief §9): the Myself
    // views pass showCost = false. The card keeps the cost row for cost-bearing surfaces
    // (a loop's run trace, the reconciliation overlay).
    val summary = "Tokens this period: $totalText total, $inputText in, $outputText out." +
        if (showCost) " Estimated cost $costText." else ""

    LedgerCard {
        Column(Modifier.fillMaxWidth().semantics { contentDescription = summary }) {
            Text("Tokens", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Input", style = MaterialTheme.typography.labelMedium)
                Text(inputText, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Output", style = MaterialTheme.typography.labelMedium)
                Text(outputText, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", style = MaterialTheme.typography.labelMedium)
                Text(totalText, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            SplitBar(
                leadingFraction = inputFraction,
                leadingColor = MaterialTheme.colorScheme.primary,
                trailingColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            if (showCost) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Estimated cost",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(costText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/* ------------------------------------------------------------- sovereignty */

/**
 * **The sovereignty headline** ([LedgerAggregations.ProvenanceSplit]): on-device vs cloud
 * token split, with the **sovereignty ratio** — the fraction of your tokens that stayed
 * local — shown as a percentage via [LocaleFormat.percent] (which takes the ratio's 0..1
 * value directly).
 *
 * On-thesis framing, stated plainly and non-manipulatively: **on-device is free and
 * private; cloud is watched and paid** (binding rule 2 — cloud is a watched object). The
 * exact on-device and cloud token counts are rendered as text via [LocaleFormat.tokens],
 * a fraction bar shows the local share, and a `contentDescription` gives TalkBack the same
 * percentage and counts a sighted user reads.
 *
 * @param split the provenance fold (on-device / cloud tokens + `sovereigntyRatio`).
 * @param locale the locale every number/percentage is formatted against.
 */
@Composable
fun SovereigntyCard(
    split: LedgerAggregations.ProvenanceSplit,
    locale: Locale,
) {
    val ratioText = LocaleFormat.percent(split.sovereigntyRatio, locale)
    val onDeviceText = LocaleFormat.tokens(split.onDeviceTokens, locale)
    val cloudText = LocaleFormat.tokens(split.cloudTokens, locale)

    val summary = "$ratioText of your tokens stayed on this device. " +
        "On-device $onDeviceText tokens, free and private. " +
        "Cloud $cloudText tokens, watched and paid."

    LedgerCard {
        Column(Modifier.fillMaxWidth().semantics { contentDescription = summary }) {
            Text("Sovereignty", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "$ratioText on-device",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "On-device is free and private. Cloud is watched and paid.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SplitBar(
                leadingFraction = split.sovereigntyRatio,
                leadingColor = MaterialTheme.colorScheme.primary,
                trailingColor = MaterialTheme.colorScheme.tertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("⌂ On-device", style = MaterialTheme.typography.labelMedium)
                Text(onDeviceText, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "☁ Cloud · watched",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(cloudText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/* -------------------------------------------------------------- by provider */

/**
 * **Per-provider usage list** (`provider · tokens · cost`) over the
 * [LedgerAggregations.byProvider] rollup map.
 *
 * Rows are rendered in the map's own iteration order — `byProvider` already returns a
 * `LinkedHashMap` sorted by total tokens descending (heaviest first), so we never re-sort
 * and the visible order matches the aggregation's contract. Each row shows the provider
 * label, its `totalTokens` via [LocaleFormat.tokens], and its `estCostMinor` via
 * [LocaleFormat.currencyMinor] ([currencyCode] ISO-4217). This is the data-table form of
 * the provider breakdown (Doc 07 §6) — text only, no chart needed.
 *
 * @param rollups provider → rollup, ordered as the aggregation returns them.
 * @param locale the locale every number is formatted against.
 * @param currencyCode ISO-4217 code for each row's cost.
 */
@Composable
fun ByProviderList(
    rollups: Map<String, LedgerAggregations.ProviderRollup>,
    locale: Locale,
    currencyCode: String,
    showCost: Boolean = true,
) {
    LedgerCard {
        Text("By provider", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (rollups.isEmpty()) {
            Text(
                "No usage recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@LedgerCard
        }
        rollups.forEach { (provider, rollup) ->
            val tokensText = LocaleFormat.tokens(rollup.totalTokens, locale)
            val costText = LocaleFormat.currencyMinor(rollup.estCostMinor, currencyCode, locale)
            val rowSummary = "$provider: $tokensText tokens" + if (showCost) ", $costText" else ""
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .semantics { contentDescription = rowSummary },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    provider,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(tokensText, style = MaterialTheme.typography.labelMedium)
                if (showCost) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        costText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------- budget ring */

/**
 * **A textual "budget ring"** ([RingState] from [BudgetRing.fill]) — the user's own
 * informational ceiling, surfaced legibly.
 *
 * Renders the percent of the ceiling used via [LocaleFormat.percent] (over
 * [RingState.fraction], already clamped to `0..1`) plus a fraction bar, framed as the
 * user's *own* chosen denominator. On-thesis and explicitly **non-manipulative**: this is
 * not a paywall, a throttle, or an engagement nudge — crossing the ceiling changes nothing
 * the app does. When [RingState.crossed] is true a gentle, neutral note says so (no alarm
 * language, no "upgrade", no dark pattern). A `contentDescription` speaks the same figure.
 *
 * @param ring the computed ring state (used / ceiling / fraction / crossed).
 * @param label a human label for what this ceiling meters (e.g. "Cloud spend", "Cloud tokens").
 * @param locale the locale the percentage is formatted against.
 */
@Composable
fun BudgetRingView(
    ring: RingState,
    label: String,
    locale: Locale,
) {
    val unbounded = ring.ceiling <= 0L
    val pctText = LocaleFormat.percent(ring.fraction, locale)

    val summary = if (unbounded) {
        "$label: no ceiling set."
    } else if (ring.crossed) {
        "$label: $pctText of your ceiling used. You have crossed the ceiling you set."
    } else {
        "$label: $pctText of your ceiling used."
    }

    LedgerCard {
        Column(Modifier.fillMaxWidth().semantics { contentDescription = summary }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (!unbounded) {
                    Text(pctText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Your own informational ceiling. Crossing it changes nothing — it just lets you see it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (unbounded) {
                Text(
                    "No ceiling set.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SplitBar(
                    leadingFraction = ring.fraction,
                    leadingColor = MaterialTheme.colorScheme.primary,
                    trailingColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                if (ring.crossed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "You've crossed the ceiling you set for yourself.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
