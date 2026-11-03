package dev.fonebrew.ui.loops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.component.HyleContextMenu
import dev.aarso.hyle.component.HyleContextMenuItem
import dev.aarso.hyle.theme.LocalHyleColors

/**
 * `LOOP_PHONE_AUTHORING_SPEC.md` §3.2 — Stage View, the primary phone editing surface
 * (`FB-RAT-PHN-002`). All layout logic here is presentational only; every decision about *what*
 * to render (structurability, rejoins, cycle detection) already happened in [StagePresenter],
 * over the already-tested [dev.fonebrew.domain.loop.authoring.StageLinearizer]. This file just
 * draws [StagePresenter.StageNarrative].
 *
 * Undo is intentionally not offered from any of these verbs — `FB-RAT-PHN-011` is **PROPOSED,
 * not yet ratified** (`LOOP_PHONE_AUTHORING_SPEC.md` §14); mounting
 * [dev.fonebrew.domain.loop.authoring.LoopDraftUndoStack] here would be building against an
 * owner gate that has not opened. Destructive verbs get an impact-preview confirm instead
 * (§3.2's own required mitigation), not undo.
 */
@Composable
fun StageView(
    narrative: StagePresenter.StageNarrative,
    modifier: Modifier = Modifier,
    onTapNode: (String) -> Unit,
    onJumpToGraph: () -> Unit,
    onAddStage: () -> Unit,
    onInsertBefore: (String) -> Unit,
    onInsertAfter: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onConnectFrom: (String) -> Unit,
    onReorder: (nodeId: String, direction: Int) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val colors = LocalHyleColors.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (!narrative.hasStartEvent) {
            Text(
                "No start event yet. Add one from Graph view, or tap “+ Add stage” below to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        for (item in narrative.items) {
            when (item) {
                is StagePresenter.StageNarrativeItem.Card -> StageCardItem(
                    row = item.row,
                    onTap = { onTapNode(item.row.nodeId) },
                    onInsertBefore = { onInsertBefore(item.row.nodeId) },
                    onInsertAfter = { onInsertAfter(item.row.nodeId) },
                    onDuplicate = { onDuplicate(item.row.nodeId) },
                    onConnectFrom = { onConnectFrom(item.row.nodeId) },
                    onReorderUp = { onReorder(item.row.nodeId, -1) },
                    onReorderDown = { onReorder(item.row.nodeId, 1) },
                    onRequestDelete = { onRequestDelete(item.row.nodeId) },
                )
                is StagePresenter.StageNarrativeItem.Unstructured -> UnstructuredRegionCard(item.row, onJumpToGraph)
            }
        }
        if (narrative.disconnectedNodeIds.isNotEmpty()) {
            Text(
                "${narrative.disconnectedNodeIds.size} stage(s) not reachable from Start — open Graph view to connect them.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.violet, // never red (colorblind rule) — a warning, not an error state
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        HyleButton("+ Add stage", onClick = onAddStage, modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageCardItem(
    row: StagePresenter.StageCardRow,
    onTap: () -> Unit,
    onInsertBefore: () -> Unit,
    onInsertAfter: () -> Unit,
    onDuplicate: () -> Unit,
    onConnectFrom: () -> Unit,
    onReorderUp: () -> Unit,
    onReorderDown: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = LocalHyleColors.current
    var showActions by remember { mutableStateOf(false) }
    val kindLabel = when (row.kind) {
        StagePresenter.StageKind.START -> "Start"
        StagePresenter.StageKind.END -> "End"
        StagePresenter.StageKind.GATEWAY -> "Gateway"
        StagePresenter.StageKind.TASK -> "Task"
    }
    HyleCard(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), onClick = onTap) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (row.hasBoundedCycle) {
                        Text(" ↺ repeats", style = MaterialTheme.typography.labelSmall, color = colors.cyan, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Text(kindLabel + (row.modelChip?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                Text(
                    "⋮",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { showActions = true }.padding(8.dp)
                        .semantics { contentDescription = "Stage actions for ${row.title}" },
                )
                HyleContextMenu(
                    expanded = showActions,
                    onDismissRequest = { showActions = false },
                    items = listOf(
                        HyleContextMenuItem(id = "insert_before", label = "Insert stage before"),
                        HyleContextMenuItem(id = "insert_after", label = "Insert stage after"),
                        HyleContextMenuItem(id = "duplicate", label = "Duplicate"),
                        HyleContextMenuItem(id = "connect", label = "Connect from here"),
                        HyleContextMenuItem(id = "move_up", label = "Move earlier"),
                        HyleContextMenuItem(id = "move_down", label = "Move later"),
                        HyleContextMenuItem(id = "delete", label = "Delete…", destructive = true),
                    ),
                    onItemClick = { id ->
                        showActions = false
                        when (id) {
                            "insert_before" -> onInsertBefore()
                            "insert_after" -> onInsertAfter()
                            "duplicate" -> onDuplicate()
                            "connect" -> onConnectFrom()
                            "move_up" -> onReorderUp()
                            "move_down" -> onReorderDown()
                            "delete" -> onRequestDelete()
                        }
                    },
                )
            }
        }
        if (row.gatewayBranches.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                for (b in row.gatewayBranches) {
                    Text(
                        (if (b.isRepeat) "↺ " else "") + "${b.label} → ${b.targetTitle}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (b.isRepeat) colors.cyan else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            row.rejoinsAtTitle?.let {
                Text("rejoins at “$it”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** §3.2 point 3: the explicit, non-flattened unstructured-region card, with the jump-to-Graph
 *  affordance point 3 also requires. */
@Composable
private fun UnstructuredRegionCard(row: StagePresenter.UnstructuredRegionRow, onJumpToGraph: () -> Unit) {
    val colors = LocalHyleColors.current
    HyleCard(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", color = colors.violet, modifier = Modifier.padding(end = 6.dp)) // glyph first, colorblind rule
            Text("Unstructured region — “${row.gatewayTitle}”", style = MaterialTheme.typography.titleSmall, color = colors.violet)
        }
        Text(
            "This branch structure can't be shown as simple nested steps (a branch crosses into a sibling branch). Every node is listed below — nothing is hidden or flattened.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(row.inboundDescription, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        for (title in row.nodeTitlesInRegion) {
            Text("• $title", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
        }
        Text(row.outboundDescription, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onJumpToGraph, modifier = Modifier.padding(top = 4.dp)) { Text("Open in Graph view") }
    }
}

/** Delete-with-impact-preview confirm (§3.2's required delete mitigation). Owns none of the
 *  draft's own state — [onConfirm] is the caller's real `deleteNode`. */
@Composable
fun StageDeleteImpactDialog(
    impact: StagePresenter.DeleteImpactPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${impact.nodeTitle}”?") },
        text = {
            Column {
                if (impact.removedEdgeDescriptions.isNotEmpty()) {
                    Text("Connections removed:", style = MaterialTheme.typography.labelMedium)
                    for (d in impact.removedEdgeDescriptions) Text("• $d", style = MaterialTheme.typography.bodySmall)
                }
                if (impact.orphanedNodeTitles.isNotEmpty()) {
                    Text("No longer reachable from Start:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    for (t in impact.orphanedNodeTitles) Text("• $t", style = MaterialTheme.typography.bodySmall)
                }
                if (impact.removedEdgeDescriptions.isEmpty() && impact.orphanedNodeTitles.isEmpty()) {
                    Text("This stage has no connections yet.", style = MaterialTheme.typography.bodySmall)
                }
                Text("This can't be undone.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { HyleButton("Delete", onClick = onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
