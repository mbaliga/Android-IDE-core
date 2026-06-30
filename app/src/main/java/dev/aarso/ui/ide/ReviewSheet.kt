package dev.aarso.ui.ide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.domain.diff.ChangeSet
import dev.aarso.domain.diff.Decision
import dev.aarso.domain.diff.FileChange
import dev.aarso.domain.diff.LineDiff
import dev.aarso.domain.diff.ReviewSession
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.theme.LocalHyleColors

/**
 * The shared **diff-review** sheet (agentic-ide §2/§5), now **per-hunk** over the tested
 * `ReviewSession`: each file's change is split into minimal hunks you approve or reject
 * independently; only approved hunks are applied. Review-first — every hunk is approved by
 * default but any can be dropped. Reused by the agentic repo loop (#1) and editable CodeLens (#2).
 */
@Composable
fun ReviewSheet(
    changeSet: ChangeSet,
    title: String,
    commitLabel: String = "Commit",
    busy: Boolean = false,
    onCommit: (ChangeSet) -> Unit,
    onCancel: () -> Unit,
) {
    val files = remember(changeSet) { changeSet.effective }
    // One ReviewSession per file (for the hunk structure) + a Compose-observable approve flag/hunk.
    val sessions = remember(changeSet) { files.map { ReviewSession(it.path, it.oldText, it.newText) } }
    val approved = remember(changeSet) {
        sessions.map { s -> mutableStateListOf<Boolean>().apply { repeat(s.hunks.size) { add(true) } } }
    }

    fun approvedChangeSet(): ChangeSet {
        val out = sessions.mapIndexedNotNull { i, s ->
            s.hunks.indices.forEach { j -> s.decide(j, if (approved[i][j]) Decision.APPROVED else Decision.REJECTED) }
            if (s.anyApproved) s.approvedChange().takeIf { !it.isNoop } else null
        }
        return ChangeSet(out)
    }

    val anyApproved = approved.any { it.any { a -> a } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HyleTitle(title)
        if (files.isEmpty()) {
            Text(
                "No changes proposed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            val totalHunks = sessions.sumOf { it.hunks.size }
            Text(
                "${files.size} file(s) · $totalHunks hunk(s). Toggle any hunk off to leave it out.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                files.indices.forEach { i ->
                    item(key = files[i].path) { FileHunks(files[i], sessions[i], approved[i]) }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HyleButton("Cancel", onClick = onCancel)
            Spacer(Modifier.weight(1f))
            HyleButton(
                if (busy) "…" else commitLabel,
                onClick = { onCommit(approvedChangeSet()) },
                enabled = anyApproved && !busy && files.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun FileHunks(fc: FileChange, session: ReviewSession, approved: MutableList<Boolean>) {
    val c = LocalHyleColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, c.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(fc.path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(
                "${fc.op.name.lowercase()} · +${fc.stat.added} −${fc.stat.removed} · ${session.hunks.size} hunk(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.hunks.isEmpty()) {
                Text("(no textual hunks — whole-file change)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            session.hunks.forEachIndexed { j, hunk ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = c.violet,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (approved[j]) "apply" else "skip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(checked = approved[j], onCheckedChange = { approved[j] = it })
                }
                HunkLines(hunk)
            }
        }
    }
}

@Composable
private fun HunkLines(hunk: LineDiff.Hunk) {
    val c = LocalHyleColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (hunk.lines.size > 10) 200.dp else (hunk.lines.size * 18 + 8).dp)
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface)
            .padding(6.dp),
    ) {
        hunk.lines.forEach { line ->
            val (prefix, color) = when (line) {
                is LineDiff.Line.Add -> "+" to c.success
                is LineDiff.Line.Remove -> "-" to MaterialTheme.colorScheme.error
                is LineDiff.Line.Context -> " " to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                "$prefix${line.text}".ifBlank { " " },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                maxLines = 1,
            )
        }
    }
}
