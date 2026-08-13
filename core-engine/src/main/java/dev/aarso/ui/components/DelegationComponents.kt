package dev.aarso.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aarso.domain.thread.DelegationCounts

/**
 * THREAD_TOPOLOGY_PLAN.md WP8's descriptive rollup, mounted in the Instruments panel
 * ([dev.aarso.ui.ChatScreen]'s `InstrumentsPanel`): "delegated N · kept N · reverted N" — three
 * counts from [DelegationCounts.summarize], **no interpretation** (binding constraint 3 /
 * CLAUDE.md rule 4: §5b/§5c interpretation stays blocked on Issue #2). A still-`PENDING`
 * delegation contributes to `delegated` but not to either `kept`/`reverted` count shown here,
 * matching how "delegated" reads as "asked for, ever" rather than "resolved so far."
 */
@Composable
fun DelegationCountsRow(counts: DelegationCounts.Counts, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            "delegated ${counts.total} · kept ${counts.kept} · reverted ${counts.reverted}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
