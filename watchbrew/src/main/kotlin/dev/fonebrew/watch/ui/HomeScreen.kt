package dev.fonebrew.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.fonebrew.watch.core.ConnectionPresenter
import dev.fonebrew.watch.core.WatchState

/** The front door: the link state (always visible, tappable to re-check), then Ask, Tasks, Loops. */
@Composable
internal fun HomeScreen(state: WatchState, onAsk: () -> Unit, onTasks: () -> Unit, onLoops: () -> Unit, onRefresh: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val link = ConnectionPresenter.present(state.connection)
    val pending = state.tasks.snapshot?.pendingCount
    ScreenFrame(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Chip(
                    onClick = onRefresh,
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = { GlyphIcon(link.glyph) },
                    label = { Text(link.headline, maxLines = 2) },
                    secondaryLabel = { Text(link.detail, maxLines = 3) },
                )
            }
            item { Chip(onClick = onAsk, label = { Text("Ask") }, secondaryLabel = { Text("Voice or keyboard") }) }
            item {
                Chip(
                    onClick = onTasks,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text("Tasks") },
                    secondaryLabel = { Text(pending?.let { "$it pending" } ?: "not received yet") },
                )
            }
            item {
                Chip(
                    onClick = onLoops,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text("Loops") },
                    secondaryLabel = { Text(if (state.loops.runs.isEmpty()) "no runs yet" else "${state.loops.runs.size} recent") },
                )
            }
        }
    }
}
