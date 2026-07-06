package dev.aarso.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.domain.provenance.ProvenanceState
import dev.aarso.domain.provenance.RoutingDecision

/**
 * Presentational Compose components for the **provenance / legible-routing** surfaces
 * (Doc 00 §3.6, Doc 01 §6.5). Pure UI: every input is a domain type
 * ([dev.aarso.domain.provenance.ProvenanceState], [dev.aarso.domain.provenance.RoutingDecision])
 * or a pre-formatted string — no ViewModel, no I/O, no app singleton, no currency formatting
 * (the caller formats cost via its own LocaleFormat and hands us [costText]).
 *
 * **Colour-independence (a11y) is the contract here, not a nicety.** The provenance model
 * deliberately encodes a non-colour identity — a stable [ProvenanceState.iconKey] glyph and a
 * human [ProvenanceState.label] — precisely so meaning survives greyscale, colour-blindness,
 * and screen readers. These components honour that: the glyph **and** the label always carry the
 * meaning, colour may only ever *add* on top. We never signal "cloud vs on-device" by colour
 * alone. A `Modifier.semantics { contentDescription = … }` gives TalkBack a spoken form
 * (e.g. "on-device" / "cloud, watched").
 *
 * **Watched-object treatment (binding rule 2).** When a unit of work reached off-device
 * ([ProvenanceState.watched] == true — i.e. CLOUD or MIXED), the badge appends a visible
 * "· watched" marker and folds ", watched" into the spoken content description. The honest
 * "this left my device" claim is therefore legible at a glance *and* audible, never hidden
 * behind colour or a tooltip.
 *
 * Styling matches the wireframe register of `dev.aarso.ui.develop.DevelopRoom` (boxy borders,
 * MaterialTheme.colorScheme / typography, no design-system dependency), and depends only on
 * Compose material3 + foundation.
 */

/** The non-colour primary cue: resolve the domain [ProvenanceState.iconKey] to a text glyph. */
private fun glyphFor(iconKey: String): String = when (iconKey) {
    "home" -> "⌂"
    "cloud" -> "☁"
    "split" -> "◐"
    "question" -> "?"
    else -> "?"
}

/**
 * A provenance badge — **glyph + label, never colour-alone**.
 *
 * Renders the [ProvenanceState]'s text glyph (mapped from its [ProvenanceState.iconKey]) next to
 * its human [ProvenanceState.label]. When the state is a watched object
 * ([ProvenanceState.watched]) it appends a small "· watched" marker. Both cues are textual, so a
 * colour-blind reader and a TalkBack user get the full meaning; the attached
 * `contentDescription` (e.g. "On-device" or "Cloud, watched") is what a screen reader speaks.
 *
 * @param state the domain provenance receipt to render.
 * @param modifier outer modifier; the semantics + visible content are applied on top of it.
 */
@Composable
fun ProvenanceBadge(state: ProvenanceState, modifier: Modifier = Modifier) {
    val description = if (state.watched) "${state.label}, watched" else state.label
    Row(
        modifier = modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            glyphFor(state.iconKey),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            state.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.watched) {
            Spacer(Modifier.width(4.dp))
            Text(
                "· watched",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The **legible-routing strip** (Doc 01 §6.5): one honest line the user can read and override.
 *
 * Shows, on a single boxy/wireframe row, the [RoutingDecision]'s model, its tier label, the
 * one-line [RoutingDecision.why], the **pre-formatted** [costText] (the caller formats currency
 * via its own LocaleFormat — this component never formats money), and a [ProvenanceBadge] for the
 * decision's [RoutingDecision.provenance]. If [onOverride] is non-null an "Override" text button
 * is shown so the user stays in the loop and can replace the system's choice.
 *
 * Colour is never the sole carrier of meaning here either — every field is text, and the
 * provenance is conveyed by the badge's glyph + label (+ watched marker), not by tint.
 *
 * @param decision the routing contract to surface.
 * @param costText pre-formatted cost string (e.g. "~₹0.40" or "free"); formatted by the caller.
 * @param onOverride optional callback; when non-null an "Override" button is rendered.
 * @param modifier outer modifier applied to the bordered row.
 */
@Composable
fun RoutingDecisionStrip(
    decision: RoutingDecision,
    costText: String,
    onOverride: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            decision.model.ifEmpty { "(no model)" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Dot()
        Text(
            decision.tier.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Dot()
        Text(
            decision.why,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Dot()
        Text(
            costText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Dot()
        ProvenanceBadge(decision.provenance)
        if (onOverride != null) {
            TextButton(onClick = onOverride) {
                Text("Override", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** A thin middle-dot separator between the strip's fields — text, so it stays legible greyscale. */
@Composable
private fun Dot() {
    Text(
        "·",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
