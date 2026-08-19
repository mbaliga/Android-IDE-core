package dev.fonebrew.ui.spatial

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fonebrew.data.DownloadCenter
import dev.fonebrew.ui.ChatViewModel
import dev.fonebrew.ui.GenPhase
import dev.fonebrew.ui.theme.LocalHyleColors

/**
 * The two non-conversation lenses of the centre room (see [CenterView]). Both read the
 * SAME state the conversation view reads — [ChatViewModel.uiState]'s annotated path and
 * [DownloadCenter.active] — because the whole point is that they are re-renderings of one
 * activity, not places of their own. Read-only in v1: acting on the work (sending,
 * branching, cancelling a download) stays in the surfaces that own those verbs.
 */

// ── Terminal: the ground-level raw transcript ────────────────────────────────────────

/**
 * The thread as a terminal would show it: every node of the active path in arrival
 * order, monospace, role-prefixed, with the routing facts (model id, branch points) that
 * the conversation view renders as chrome shown here as plain text — the legibility
 * thesis applied to our own output. Verbatim content, no markdown rendering: this lens
 * exists precisely to show what the pretty lens is built from.
 */
@Composable
fun CenterTerminalView(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val ac = LocalHyleColors.current
    val ui by viewModel.uiState.collectAsState()
    val mono = FontFamily.Monospace

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "banner") {
            Text(
                "dev.fonebrew — raw view of the active thread (${ui.steps.size} nodes)",
                color = ac.textDisabled,
                fontFamily = mono,
                fontSize = 11.sp,
            )
        }
        items(ui.steps, key = { it.node.id }) { step ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val head = buildString {
                    append(if (step.node.role == dev.fonebrew.domain.Role.USER) "❯ " else "● ")
                    append(step.node.role.wire)
                    step.node.modelId?.let { append(" · ").append(it) }
                    if (step.isBranchPoint) {
                        append("  ⎇ ").append(step.activeAlternative).append("/").append(step.alternativeCount)
                    }
                }
                Text(head, color = ac.violet, fontFamily = mono, fontSize = 11.sp)
                Text(step.node.content, color = ac.textHigh, fontFamily = mono, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        if (ui.isGenerating) {
            item(key = "streaming") {
                Text(
                    (ui.streamingText ?: "") + "▌",
                    color = ac.textMid,
                    fontFamily = mono,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        if (ui.steps.isEmpty() && !ui.isGenerating) {
            item(key = "empty") {
                Text("(no thread yet — send something in Conversation)", color = ac.textDisabled, fontFamily = mono, fontSize = 12.sp)
            }
        }
    }
}

// ── Background tasks: the basement ───────────────────────────────────────────────────

/**
 * What is running underneath right now: active generation (model + phase) and every
 * live [DownloadCenter] transfer, verbatim from the same stores that drive the chrome
 * elsewhere (the TopDock strip, the Models cards). Nothing here is a new state machine —
 * it is the basement window onto the existing ones.
 */
@Composable
fun CenterBackgroundView(
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
