package dev.fonebrew.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.fonebrew.watch.core.LoopRow
import dev.fonebrew.watch.core.LoopsPresenter
import dev.fonebrew.watch.core.WatchState
import dev.fonebrew.watch.runtime.WatchController

/**
 * Loops: read-only glances at the phone's loop runs — a state shape, the state in words, the step,
 * the run's real spend (an estimate is marked `~` and "(estimated)"; a missing figure says "not
 * recorded", never 0) and how old the glance is. The watch reads; it does not start, stop or edit.
 */
@Composable
internal fun LoopsScreen(state: WatchState, controller: WatchController) {
    val listState = rememberScalingLazyListState()
    val now by rememberNowMs(30_000L)
    val rows = LoopsPresenter.rows(state.loops, now)

    ScreenFrame(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { ListHeader { Text("Loops") } }
            if (rows.isEmpty()) {
                item { Note("No loop runs received from the phone yet.") }
                item { CompactChip(onClick = controller::refresh, label = { Text("Check again") }) }
            } else {
                items(rows, key = { it.runId }) { row -> LoopGlance(row) }
            }
        }
    }
}

@Composable
private fun LoopGlance(row: LoopRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).semantics { contentDescription = row.spoken },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(row.glyph, size = 22.dp)
        Column {
            Text(row.name, style = MaterialTheme.typography.title3, maxLines = 2)
            Text(row.stateLabel, style = MaterialTheme.typography.body2)
            Text(row.stepLine, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, maxLines = 2)
            Text(row.spendLine, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
            Text(row.ageLabel, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
        }
    }
}
