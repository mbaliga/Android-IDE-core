package dev.aarso.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.domain.scope.ContextAssembly
import dev.aarso.domain.state.UiState

/**
 * Reusable presentational components for the **state matrix** (Doc 00 §3.8) and the
 * **context budget meter** (Doc 03 §5.2). Pure UI: every input is a committed pure-domain
 * type ([UiState], [ContextAssembly.BudgetMeter]) and nothing here touches a ViewModel, IO,
 * coroutine, or singleton. Styled with Material 3 ([MaterialTheme]) only, matching the
 * conventions in `ui/develop/DevelopRoom.kt`.
 */

/**
 * The one **state renderer** every surface can reuse so the seven-state matrix is rendered
 * uniformly (Doc 00 §3.8): a surface that can only draw "ready" is lying about the world it
 * lives in. Pass the surface's [UiState] and a [ready] slot that draws the payload; this
 * function handles the other six cases honestly — guidance instead of a dead spinner or a
 * blank that looks like a bug.
 *
 * Cases:
 *  - **Loading** — a neutral skeleton block plus a quiet "Loading…" label.
 *  - **Empty** — the *useful* empty: a centred "Nothing here yet" so the surface reads as
 *    intentionally empty, not broken.
 *  - **Partial** — renders [ready] over the partial value, then a small note naming the
 *    [UiState.Partial.failedSlice] that didn't load (never discard the good data).
 *  - **Ready** — just [ready] over the full value.
 *  - **Error** — the human [UiState.Error.cause]; names the failed
 *    [UiState.Error.watchedObject] (cloud is a visibly *watched* dependency) when present;
 *    offers a **Retry** [TextButton] only when [onRetry] is supplied *and* the error is
 *    [UiState.Error.retryable].
 *  - **Offline** — an "Offline" line plus the on-device alternative
 *    ([UiState.Offline.onDeviceAlternative]) when one exists — offline is a re-route, not a
 *    dead end (on-device is always the default).
 *  - **PermissionBlocked** — what is missing ([UiState.PermissionBlocked.what]) and the
 *    concrete [UiState.PermissionBlocked.unblockHint], never a bare "denied".
 *
 * @param state the surface's current state.
 * @param onRetry optional retry action; only wired up for retryable [UiState.Error]s.
 * @param ready slot that renders the [UiState.Ready]/[UiState.Partial] payload.
 */
@Composable
fun <T> StatePane(
    state: UiState<T>,
    onRetry: (() -> Unit)? = null,
    ready: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> {
            Column(Modifier.fillMaxWidth()) {
                // Skeleton-ish placeholder block — no spinner needed for the matrix floor.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Loading…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UiState.Empty -> {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Nothing here yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UiState.Partial -> {
            Column(Modifier.fillMaxWidth()) {
                ready(state.value)
                Spacer(Modifier.height(8.dp))
                val slice = state.failedSlice
                Text(
                    if (slice != null) "Some items couldn't load: $slice" else "Some items couldn't load",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UiState.Ready -> ready(state.value)

        is UiState.Error -> {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    state.cause,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                state.watchedObject?.let { watched ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Watched object: $watched",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onRetry != null && state.retryable) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        is UiState.Offline -> {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.onDeviceAlternative?.let { alt ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "On-device alternative: $alt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is UiState.PermissionBlocked -> {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    state.what,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    state.unblockHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A legible **context-budget meter** (Doc 03 §5.2): a horizontal track with a fractional
 * fill driven by the meter's [ContextAssembly.BudgetMeter.fractionUsed], a percent caption,
 * and a textual corpus-vs-conversation split so the user can see *where* the budget went —
 * legibility over a bare bar.
 *
 * The fill width is `Modifier.fillMaxWidth(fraction)` over the meter's clamped
 * `[0, 1]` fraction-used; the percent is computed as a plain `(fraction * 100).toInt()`
 * (no locale formatting pulled in on purpose). The split line reports corpus tokens vs the
 * conversation/reply reservation, and flags [ContextAssembly.BudgetMeter.overBudget] when
 * pinned pieces pushed past the corpus budget (the honest over-budget signal — the bar still
 * clamps to full). A [semantics] `contentDescription` summarises the fill for TalkBack.
 *
 * @param meter the pure-domain budget reading.
 * @param label the meter's heading (defaults to "Context budget").
 * @param modifier layout modifier for the whole meter column.
 */
@Composable
fun BudgetMeter(
    meter: ContextAssembly.BudgetMeter,
    label: String = "Context budget",
    modifier: Modifier = Modifier,
) {
    val fraction = meter.fractionUsed.coerceIn(0.0, 1.0).toFloat()
    val percent = (meter.fractionUsed * 100).toInt()

    val description = buildString {
        append("$label: $percent% of budget used")
        append(", ${meter.usedTokens} of ${meter.totalTokens} tokens")
        if (meter.overBudget) append(", over budget on pinned context")
    }

    Column(modifier.semantics { contentDescription = description }) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))

        // Track + fractional fill. The track is the budget; the fill is what's used.
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "$percent% of budget" + if (meter.overBudget) " · over budget (pins kept)" else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (meter.overBudget) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        // Corpus-vs-conversation split, textual (legibility over the bar alone).
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "corpus ${meter.corpusTokens}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "conversation ${meter.reservedTokens}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "/ ${meter.totalTokens} total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
