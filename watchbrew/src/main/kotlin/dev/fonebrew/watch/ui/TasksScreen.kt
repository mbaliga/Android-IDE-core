package dev.fonebrew.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.fonebrew.watch.core.TasksPresenter
import dev.fonebrew.watch.core.TasksView
import dev.fonebrew.watch.core.WatchState
import dev.fonebrew.watch.runtime.WatchController

/**
 * Tasks: the phone's TaskStore at a glance. **Optimistic-free**: every row shows the state the phone
 * last *confirmed*; tapping asks the phone, and the row only changes when the phone says so (a
 * separate "Asking phone…" line marks the outstanding request). The phone is the single writer.
 * Offline, the last confirmed list stays visible with its age and toggles are not offered.
 */
@Composable
internal fun TasksScreen(state: WatchState, controller: WatchController) {
    val listState = rememberScalingLazyListState()
    val now by rememberNowMs(30_000L)
    val view = TasksPresenter.present(state.tasks, state.connection, now)

    ScreenFrame(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                ListHeader { Text(if (view is TasksView.Data) "Tasks · ${view.pendingCount} pending" else "Tasks") }
            }
            when (view) {
                is TasksView.NoData -> {
                    item { Note(view.reason) }
                    item { CompactChip(onClick = controller::refresh, label = { Text("Check again") }) }
                }

                is TasksView.Data -> {
                    item { Note(view.freshness) }
                    view.offlineReason?.let { reason -> item { Note(reason) } }
                    if (view.rows.isEmpty()) item { Note("No tasks on the phone.") }
                    items(view.rows, key = { it.id }) { row ->
                        Chip(
                            onClick = { controller.toggleTask(row.id) },
                            enabled = row.tappable,
                            colors = ChipDefaults.secondaryChipColors(),
                            icon = { GlyphIcon(row.glyph) },
                            label = {
                                Text(if (row.isUntitled) "(untitled)" else row.title, maxLines = 3)
                            },
                            secondaryLabel = {
                                // State in words, then any outstanding request or outcome — all text.
                                Text(listOfNotNull(row.stateLabel, row.pendingLabel, row.notice).joinToString(" · "), maxLines = 4)
                            },
                        )
                    }
                    if (view.hiddenPending > 0) item { Note("+ ${view.hiddenPending} more pending on the phone") }
                }
            }
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}
