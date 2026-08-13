package dev.aarso.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.domain.tree.SiblingCompares
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.theme.LocalHyleColors

/**
 * THREAD_TOPOLOGY_PLAN.md WP2's "Compare alternatives" surface — the pager row's `‹ n/m ›` control
 * only shows one alternative at a time; this sheet stacks every sibling at the fork so a person can
 * actually weigh them before picking, rather than clicking through one at a time. Pure presentational:
 * [compare] is the already-computed [SiblingCompares.Compare] (from
 * [dev.aarso.ui.ChatViewModel.openCompare]); [onContinue] is `branchFrom` on the chosen alternative's
 * tip. Human-as-aggregator by default (this app's thesis) — nothing here ranks or recommends, it only
 * lays the honest facts (preview, turn count, last activity, models used) side by side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareSheet(
    compare: SiblingCompares.Compare,
    onContinue: (leafId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Compare alternatives", style = MaterialTheme.typography.titleMedium)
            Text(
                "${compare.alternatives.size} alternative(s) at this branch",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(compare.alternatives, key = { it.childId }) { alt ->
                    CompareCard(alt, onContinue = { onContinue(alt.leafId) })
                }
            }
        }
    }
}

@Composable
private fun CompareCard(alt: SiblingCompares.Alternative, onContinue: () -> Unit) {
    val activeNote = if (alt.isActive) " · currently active" else ""
    val description = "Alternative, ${alt.turnCount} turn(s)$activeNote. ${alt.preview}"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = if (alt.isActive) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (alt.isActive) {
                Text(
                    "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalHyleColors.current.violet,
                )
            }
            Text(alt.preview.ifBlank { "(no text)" }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "${alt.turnCount} turn(s)" + (alt.modelIds.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString(", ")}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                HyleButton("Continue with this", onClick = onContinue)
            }
        }
    }
}
