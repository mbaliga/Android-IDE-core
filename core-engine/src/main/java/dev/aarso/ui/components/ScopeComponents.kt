package dev.aarso.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.domain.scope.ContextAssembly.Assembled
import dev.aarso.domain.scope.ContextAssembly.AssemblyMode
import dev.aarso.domain.scope.CorpusPiece
import dev.aarso.domain.scope.CorpusSource
import dev.aarso.domain.scope.Scope

/**
 * Presentational Compose components for the **knowledge-scoping** surfaces (Doc 01 §3.4 /
 * Doc 03 §6). Pure UI: every input is a domain value — a [Scope], an [AssemblyMode], or a
 * fully-built [Assembled] ledger — plus plain callbacks. No ViewModel, no I/O, no app
 * singleton, no `remember`ed state of our own. The caller owns all state.
 *
 * The design thesis these components serve is **legibility** (Doc 03): the user should always
 * be able to see, and narrow, the blast radius of what the model is allowed to read. So:
 *
 *  - [ScopeChip] is the always-present composer marker — a compact pill stating *scope · mode*
 *    so the active lens is never hidden behind a menu.
 *  - [ScopeInspector] is the "what does the model know right now?" surface — it does not merely
 *    summarise, it **attributes**: it lists every included [CorpusPiece] by its source and
 *    label, and (when the corpus was truncated) every piece that was *cut*. That included/cut
 *    ledger is the legibility guarantee — the user sees precisely what the model saw and what
 *    it did not.
 *
 * **Accessibility is part of the contract.** Each surface carries a spoken
 * `Modifier.semantics { contentDescription = … }` form for TalkBack, and meaning is always
 * carried by text (label + mode + counts), never by colour alone.
 *
 * Material 3 throughout ([androidx.compose.material3.MaterialTheme]); deliberately boxy /
 * wireframe-fidelity to match the surrounding `ui/` surfaces.
 */

/** Human label for an [AssemblyMode] — the three words the UI uses for *how* context was built. */
private fun AssemblyMode.uiLabel(): String = when (this) {
    AssemblyMode.Verbatim -> "verbatim"
    AssemblyMode.PrioritizedTruncation -> "prioritized"
    AssemblyMode.Recall -> "recall"
}

/**
 * A one-line reason for an [AssemblyMode] — the "why this mode" the [ScopeInspector] shows next
 * to the mode, so the choice is legible rather than mysterious. Mirrors the floor's rule in
 * [dev.aarso.domain.scope.ContextAssembly]: everything fit (verbatim) vs over budget, included by
 * explicit priority (prioritized); recall is the reserved embedder layer.
 */
private fun AssemblyMode.reason(): String = when (this) {
    AssemblyMode.Verbatim -> "the whole scoped corpus fit the budget — nothing was cut"
    AssemblyMode.PrioritizedTruncation ->
        "over budget — included by explicit priority (pinned, then most recent); the rest cut"
    AssemblyMode.Recall -> "semantic recall (embedder-driven retrieval)"
}

/** A short, human-facing description of a [CorpusSource] — its provenance kind, for the ledger. */
private fun CorpusSource.kindLabel(): String = when (this) {
    is CorpusSource.RepoFile -> "file: $path"
    is CorpusSource.Conversation ->
        "conversation: $convId" + (nodeId?.let { " · node $it" } ?: "")
    is CorpusSource.Memory -> "memory: $entryId"
    is CorpusSource.Attachment -> "attachment: $name"
}

/**
 * The persistent **scope chip** (Doc 01 §3.4 / Doc 03 §6.1): a compact pill that lives in the
 * composer and always states the active lens as *scope · mode* — e.g. "This project · verbatim",
 * "3 selected projects · prioritized", "All projects · recall". Tapping it ([onClick]) opens the
 * scope picker / inspector.
 *
 * Boxy Material-3 styling: an outlined, rounded pill. Meaning is in the text (label + mode), so it
 * survives greyscale and screen readers; a `contentDescription` gives TalkBack a spoken form.
 *
 * @param scope the active knowledge [Scope]; its [Scope.label] is shown verbatim.
 * @param mode how this turn's context was assembled — shown as the trailing mode word.
 * @param onClick invoked when the chip is tapped (open the picker / inspector).
 * @param modifier outer [Modifier] for placement; the pill shape/border are applied inside.
 */
@Composable
fun ScopeChip(
    scope: Scope,
    mode: AssemblyMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modeWord = mode.uiLabel()
    val spoken = "Knowledge scope: ${scope.label}, mode $modeWord. Tap to change scope."
    Box(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = spoken },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                scope.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                " · ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modeWord,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The **scope inspector** (Doc 03 §6.2) — the "what does the model know right now?" surface.
 * Given a fully-built [Assembled] ledger, it makes the turn's context legible end to end:
 *
 *  1. the active **scope** (the lens) with a "Change scope" action ([onChangeScope]);
 *  2. the **mode** and its one-line *reason* (why verbatim vs prioritized);
 *  3. a simple **budget bar** filled to [dev.aarso.domain.scope.ContextAssembly.BudgetMeter.fractionUsed],
 *     with the token reading underneath and an honest *over budget* marker when pins exceeded the
 *     corpus budget;
 *  4. the **attributed lists** — the legibility guarantee:
 *      - "Included ({n})" — every [Assembled.included] piece, by source kind + label;
 *      - "Not included ({n})" — every [Assembled.cut] piece, shown only when the corpus was
 *        truncated, so the user sees exactly what the model did *not* get.
 *
 * Pure presentational: no state, no I/O. Scrolls vertically so a large ledger stays reachable.
 * Each list row carries its own spoken `contentDescription` for TalkBack.
 *
 * @param assembled the legibility ledger to render (scope, mode, meter, included/cut).
 * @param onChangeScope invoked from the "Change scope" button.
 * @param modifier outer [Modifier]; a vertical scroll + padding are applied inside.
 */
@Composable
fun ScopeInspector(
    assembled: Assembled,
    onChangeScope: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meter = assembled.meter
    val fraction = meter.fractionUsed.coerceIn(0.0, 1.0).toFloat()
    val modeWord = assembled.mode.uiLabel()

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // 1 — scope + change action.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What the model can see",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChangeScope) { Text("Change scope") }
        }
        Text(
            "Scope: ${assembled.scope.label}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = "Knowledge scope is ${assembled.scope.label}"
            },
        )
        Spacer(Modifier.height(8.dp))

        // 2 — mode + reason.
        Text(
            "Mode: $modeWord",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            assembled.mode.reason(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // 3 — budget bar (a simple inline fill from the meter's fraction).
        val pct = (fraction * 100).toInt()
        val budgetSpoken =
            "Context budget $pct percent used: ${meter.usedTokens} of ${meter.totalTokens} tokens" +
                (if (meter.overBudget) ", over the corpus budget — pinned pieces were kept" else "")
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .semantics { contentDescription = budgetSpoken },
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .background(
                        if (meter.overBudget) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    ),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "$pct% · ${meter.usedTokens} / ${meter.totalTokens} tokens" +
                " (corpus ${meter.corpusTokens}, reserved ${meter.reservedTokens})" +
                (if (meter.overBudget) " · over budget (pins kept)" else ""),
            style = MaterialTheme.typography.labelSmall,
            color = if (meter.overBudget) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        // 4a — the included ledger.
        Text(
            "Included (${assembled.included.size})",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        if (assembled.included.isEmpty()) {
            Text(
                "Nothing — the scoped corpus is empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            assembled.included.forEach { piece ->
                CorpusRow(piece, included = true)
            }
        }

        // 4b — the cut ledger, only when something was actually truncated.
        if (assembled.cut.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Not included (${assembled.cut.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            assembled.cut.forEach { piece ->
                CorpusRow(piece, included = false)
            }
        }
    }
}

/**
 * One attributed row of the included/cut ledger: the piece's [CorpusSource] kind, its
 * human [CorpusPiece.label], and its token cost. A spoken `contentDescription` states whether the
 * piece was included or cut so the ledger is fully readable by TalkBack.
 */
@Composable
private fun CorpusRow(piece: CorpusPiece, included: Boolean) {
    val kind = piece.source.kindLabel()
    val pinTag = if (piece.pinned) " · pinned" else ""
    val spoken =
        (if (included) "Included" else "Not included") +
            ": ${piece.label}, $kind, ${piece.tokenCount} tokens" +
            (if (piece.pinned) ", pinned" else "")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = spoken },
    ) {
        Text(
            piece.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (included) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "$kind · ${piece.tokenCount} tokens$pinTag",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
