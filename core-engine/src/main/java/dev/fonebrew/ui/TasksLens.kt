package dev.fonebrew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fonebrew.data.DownloadCenter
import dev.aarso.hyle.theme.LocalHyleColors

/**
 * The **Tasks** lens of the chat room's tab row (Chat / Terminal / Tasks): what is running
 * underneath right now — active generation (model + phase) and every live [DownloadCenter]
 * transfer, verbatim from the same stores that drive the chrome elsewhere (the TopDock strip,
 * the Models cards). Nothing here is a new state machine — it is the basement window onto the
 * existing ones. Read-only: acting on the work (sending, branching, cancelling a download)
 * stays in the surfaces that own those verbs.
 *
 * History: this was the bottom `CenterViewTabBar`'s "Background Tasks" lens; that bar is
 * deleted (2026-08-21 consolidation — it showed chat's tabs in every room, and its read-only
 * "Terminal" transcript lens collided with the real PTY terminal's name), and this lens moved
 * into ChatScreen's own tab row, which only exists in the chat room.
 */
@Composable
fun TasksLens(
    center: DownloadCenter,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val ac = LocalHyleColors.current
    val downloads by center.active.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val rows = downloads.values.toList()

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.isGenerating) {
            item(key = "generation") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Generating — ${ui.activeModelLabel}", color = ac.textHigh, fontSize = 13.sp)
                    Text(
                        if (ui.genPhase == GenPhase.LOADING) "loading model" else "streaming tokens",
                        color = ac.textMid,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        items(rows, key = { it.request.id }) { state ->
            val total = state.progress.totalBytes
            val fraction = if (total > 0) state.progress.downloadedBytes.toFloat() / total else 0f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.request.fileName, color = ac.textHigh, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        when {
                            state.running -> "${(fraction * 100).toInt()}%"
                            state.progress.error != null -> "failed"
                            state.progress.done -> "done"
                            else -> "paused"
                        },
                        color = if (state.progress.error != null) ac.error else ac.textMid,
                        fontSize = 11.sp,
                    )
                }
                if (state.running) {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = ac.violet,
                        trackColor = ac.inset,
                    )
                }
            }
        }
        if (rows.isEmpty() && !ui.isGenerating) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Nothing running in the background.", color = ac.textMid, fontSize = 13.sp)
                    Text(
                        "Generation and model downloads appear here while they run.",
                        color = ac.textDisabled,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        item(key = "tail-space") { Spacer(Modifier.height(8.dp)) }
    }
}
