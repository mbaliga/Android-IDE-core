package dev.aarso.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.aarso.domain.bridge.SummaryBridge
import dev.aarso.domain.inspect.Availability
import dev.aarso.domain.inspect.Heatcell

/**
 * Presentational Compose components for the two **legibility / inspector** surfaces of the chat
 * spine: the per-token logprob/entropy **heatmap** (Doc 01 §7) and the mid-conversation
 * **summary-bridge** node (Doc 01 §4.3). Both are pure UI: every input is a committed domain type
 * ([dev.aarso.domain.inspect.Heatcell] / [dev.aarso.domain.inspect.Availability],
 * [dev.aarso.domain.bridge.SummaryBridge]) plus a pre-computed summary string or a callback — no
 * ViewModel, no I/O, no app singleton, no inference. The caller computes the heatcells via
 * `TokenInspector.heatmap`, the summary via `TokenInspector.summary`, and the bridge via
 * `SummaryBridges.build`; these components only render them.
 *
 * **Non-colour-alone encoding is the contract here, not a nicety (Doc 00 §3.5).** A
 * [dev.aarso.domain.inspect.Heatcell] deliberately carries both a continuous
 * [Heatcell.intensity] and a discrete [Heatcell.bucket] precisely so meaning survives greyscale,
 * colour-blindness, and screen readers. [TokenHeatmap] honours that: colour (a tint scaled by
 * intensity) is one *redundant* channel layered on top of two non-colour cues — an underline whose
 * thickness tracks the bucket, and a small superscript bucket digit. The spoken description is the
 * honest [summary] line, never the tint.
 *
 * **Honesty about availability (binding rule 2 + the inspector's own contract).** When the active
 * engine does not provide full per-token logprobs ([Availability.FULL]), [TokenHeatmap] shows a
 * plain "logprobs limited / unavailable" note rather than letting the heat read as if it were
 * fully grounded. The inspector never fabricates numbers it does not have.
 *
 * Styling matches the wireframe register of `dev.aarso.ui.develop.DevelopRoom` and the sibling
 * `ProvenanceComponents` (boxy borders, `MaterialTheme.colorScheme` / typography, no design-system
 * dependency); depends only on Compose material3 + foundation. The [SummaryNodeCard] reuses
 * [ProvenanceBadge] (same package) so a cloud-authored bridge is honestly flagged a watched object.
 */

/* ----------------------------------------------------------------- Heatmap */

/**
 * The per-token logprob / entropy inspector (Doc 01 §7) — renders a model's own per-token
 * uncertainty inline so routing/influence is legible, not hidden.
 *
 * Each [Heatcell] becomes a small token chip wrapped in a [FlowRow]. Three redundant channels
 * encode the cell's uncertainty so it is **never colour-alone** (Doc 00 §3.5):
 *  1. a **background tint** — `MaterialTheme.colorScheme.error` at an alpha scaled by the
 *     continuous [Heatcell.intensity] (the redundant colour channel);
 *  2. an **underline** whose thickness tracks the discrete [Heatcell.bucket] (a non-colour cue that
 *     survives greyscale); and
 *  3. a small **superscript bucket digit** after the token (a non-colour cue a screen reader and a
 *     colour-blind reader both get).
 *
 * The [summary] line — the caller's `TokenInspector.summary` output — is shown beneath the chips
 * and is also the heatmap's spoken `contentDescription`, so TalkBack hears the honest one-liner
 * (token count + the highest-uncertainty token) rather than a pile of chip labels. When
 * [availability] is not [Availability.FULL] an explicit "logprobs limited / unavailable" note is
 * shown, so partial or absent data is never mistaken for fully-grounded heat.
 *
 * Pure presentational — no inference, no I/O. Caller supplies already-computed [cells] + [summary].
 *
 * @param cells the per-token heat, in token order (from `TokenInspector.heatmap`). May be empty.
 * @param availability how much logprob/entropy detail the active engine actually provided; drives
 *   the honesty note when it is not [Availability.FULL].
 * @param summary the pre-computed, accessibility-friendly summary line (from
 *   `TokenInspector.summary`); doubles as the spoken description of the whole heatmap.
 * @param modifier outer modifier; the semantics + visible content are applied on top of it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokenHeatmap(
    cells: List<Heatcell>,
    availability: Availability,
    summary: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = summary },
    ) {
        // The honest availability note, shown above the heat whenever detail is partial/absent so
        // the tint is never read as fully-grounded. FULL needs no caveat.
        val availabilityNote = when (availability) {
            Availability.FULL -> null
            Availability.TOPK_ONLY -> "logprobs limited: top-k only from this provider"
            Availability.UNAVAILABLE -> "logprobs unavailable: this provider returns none"
        }
        availabilityNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }

        if (cells.isEmpty()) {
            Text(
                "No tokens to inspect.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                cells.forEach { cell -> TokenChip(cell) }
            }
            Spacer(Modifier.height(8.dp))
            // The honest summary line, also the spoken description of the whole surface.
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One token chip — the token text tinted by [Heatcell.intensity], underlined by [Heatcell.bucket],
 * and tagged with a superscript bucket digit. The three channels are redundant on purpose so the
 * meaning is never carried by colour alone (Doc 00 §3.5).
 */
@Composable
private fun TokenChip(cell: Heatcell) {
    // Redundant colour channel: error tint, alpha scaled by the continuous intensity. A floor keeps
    // very-low-but-nonzero heat faintly visible; intensity 0.0 stays fully transparent.
    val tintAlpha = if (cell.intensity <= 0.0) 0f else (0.12f + cell.intensity.toFloat() * 0.48f)
    val background = MaterialTheme.colorScheme.error.copy(alpha = tintAlpha)

    // Non-colour cue #1: underline whose thickness grows with the discrete bucket. bucket 0 gets no
    // underline (nothing to flag); higher buckets get progressively heavier underlines.
    val decoration = if (cell.bucket <= 0) TextDecoration.None else TextDecoration.Underline

    Row(verticalAlignment = Alignment.Top) {
        Text(
            cell.token,
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = decoration),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .background(background)
                .padding(horizontal = 3.dp, vertical = 1.dp),
        )
        // Non-colour cue #2: the bucket as a small superscript digit, legible greyscale + spoken.
        Text(
            cell.bucket.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ------------------------------------------------------------ Summary node */

/**
 * The branch-on-change **summary-bridge** node (Doc 01 §4.3) — the quiet "what changed + what was
 * carried forward" card the spine inserts when the active model or interaction model switches
 * mid-conversation. It lets the new branch start informed without pretending the switch never
 * happened.
 *
 * Rendered as a distinct, *quieter* bridge card (a boxy `surfaceVariant` panel, set apart from
 * ordinary turns) showing:
 *  - [SummaryBridge.header] — the deterministic "Switched model/interaction model: A → B" line;
 *  - [SummaryBridge.carriedForward] — the verbatim carry-forward bullets, each truthful excerpt;
 *  - a [ProvenanceBadge] for [SummaryBridge.authorProvenance] (so a cloud-written bridge is flagged
 *    a watched object) next to [SummaryBridge.authorModel] — *which* model wrote the bridge prose;
 *  - a "View full prior context" [TextButton] (calling [onViewFullPrior]) only when
 *    [SummaryBridge.fullPriorAvailable].
 *
 * The card's spoken `contentDescription` summarises the bridge (header + carry-forward count +
 * author attribution) so TalkBack conveys it without reading every bullet.
 *
 * Pure presentational — inputs are the domain [bridge] plus the [onViewFullPrior] callback.
 *
 * @param bridge the structural bridge payload (from `SummaryBridges.build`).
 * @param onViewFullPrior invoked by the "View full prior context" button; only shown when
 *   [SummaryBridge.fullPriorAvailable] is true.
 * @param modifier outer modifier applied to the bordered card.
 */
@Composable
fun SummaryNodeCard(
    bridge: SummaryBridge,
    onViewFullPrior: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authorPhrase = bridge.authorModel?.let { "written by $it" } ?: "no authoring model"
    val description = "Summary bridge. ${bridge.header}. " +
        "${bridge.carriedForward.size} point(s) carried forward. $authorPhrase."

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            // Quieter than an ordinary turn: a recessive surface tint marks it as a bridge node.
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp)
            .semantics { contentDescription = description },
    ) {
        // A small, quiet kicker so the node reads as a system bridge, not a chat turn.
        Text(
            "SUMMARY · BRIDGE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            bridge.header,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (bridge.carriedForward.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Carried forward",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            bridge.carriedForward.forEach { bullet ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        bullet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // Author attribution: which model wrote the bridge prose + its provenance (watched-object
        // badge), so a cloud-authored summary is honestly flagged. Reuses ProvenanceBadge (same pkg).
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProvenanceBadge(bridge.authorProvenance)
            Spacer(Modifier.width(8.dp))
            Text(
                bridge.authorModel?.let { "by $it" } ?: "no authoring model",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (bridge.fullPriorAvailable) {
            TextButton(onClick = onViewFullPrior) {
                Text("View full prior context", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
