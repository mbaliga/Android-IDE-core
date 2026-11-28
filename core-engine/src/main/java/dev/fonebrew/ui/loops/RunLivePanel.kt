package dev.fonebrew.ui.loops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.asystemofcells.hyle.cells.HyleCard
import dev.fonebrew.domain.loop.AgentStatus
import dev.fonebrew.domain.loop.RunPhase
import dev.fonebrew.domain.loop.RunView

/**
 * The live run view: what is running right now, what it is saying as it says it, which branch a
 * gateway took, and the agent tree. A pure function of [view] (a fold over the run's event stream)
 * and [nowMs] (so elapsed times tick) — it holds no state of its own. All wording and glyphs come
 * from [RunLiveFormat]. Status is glyph + word, never colour alone.
 */
@Composable
internal fun RunLivePanel(
    view: RunView,
    nowMs: Long,
    labelOf: (String) -> String?,
    edgeLabel: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    if (view.phase == RunPhase.IDLE) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            RunLiveFormat.header(view, nowMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        view.gateways.takeLast(3).forEach { g ->
            Text(
                RunLiveFormat.gateway(g, labelOf, edgeLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The tree: the run, then each agent beneath it, indented by depth.
        view.tree().forEach { row ->
            val a = row.agent
            if (a.id == "run") return@forEach // the header already speaks for the run itself
            val indent = (row.depth - 1).coerceAtLeast(0) * 14
            HyleCard(modifier = Modifier.fillMaxWidth().padding(start = indent.dp), selected = a.status == AgentStatus.RUNNING) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${RunLiveFormat.glyph(a.status)} ${a.stepIndex?.let { "${it + 1}. " } ?: ""}" +
                                (a.nodeId?.let(labelOf) ?: a.label) + " · ${RunLiveFormat.word(a.status)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            RunLiveFormat.agentMeta(a, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        a.error != null -> Text(a.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        a.text.isNotEmpty() -> Text(
                            // A running step shows its live tail (monospace, so streaming doesn't jitter
                            // the layout); a finished one shows its output as before.
                            if (a.status == AgentStatus.RUNNING) RunLiveFormat.tail(a.text) else a.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (a.status == AgentStatus.RUNNING) FontFamily.Monospace else null,
                        )
                        a.status == AgentStatus.RUNNING -> Text(
                            "waiting for the model…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
