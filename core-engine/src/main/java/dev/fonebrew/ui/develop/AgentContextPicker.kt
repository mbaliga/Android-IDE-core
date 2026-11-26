package dev.fonebrew.ui.develop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fonebrew.domain.agent.AgentRepo
import dev.fonebrew.domain.agent.ContextMeter
import dev.fonebrew.domain.agent.ContextPick
import dev.fonebrew.domain.agent.RepoEntry
import dev.fonebrew.domain.agent.RepoOutcome

/**
 * Pick files or folders from the repo tree to seed the agent's context, with a live size meter
 * ([ContextMeter]: glyph + label, token figure flagged as an estimate). Read-only: it lists
 * through [repo] and never writes. Folder sizes are unknown until the run, and the meter says so.
 */
@Composable
fun AgentContextPicker(
    repo: AgentRepo?,
    picks: SnapshotStateList<ContextPick>,
    windowTokens: Int,
) {
    var dir by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RepoEntry>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val sizes = remember { mutableMapOf<String, Long>() }

    LaunchedEffect(repo, dir) {
        problem = null
        if (repo == null) { entries = emptyList(); return@LaunchedEffect }
        when (val r = repo.list(dir)) {
            is RepoOutcome.Ok -> { entries = r.value.sortedWith(compareBy({ !it.isDir }, { it.name })); r.value.forEach { e -> e.size?.let { sizes[e.path] = it } } }
            is RepoOutcome.Missing -> { entries = emptyList(); problem = "folder not found" }
            is RepoOutcome.Failed -> { entries = emptyList(); problem = r.reason }
        }
    }

    val known = picks.filter { !it.isDir }.sumOf { sizes[it.path] ?: 0L }
    val unknownFolders = picks.count { it.isDir } + picks.count { !it.isDir && sizes[it.path] == null }
    Text("Context from the repo (optional)", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    Hint(ContextMeter.assess(known, unknownFolders, windowTokens).text)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WireButton("/ root", selected = dir.isEmpty(), onClick = { dir = "" })
        if (dir.isNotEmpty()) WireButton("↑ ${dir.substringBeforeLast('/', "")}/", onClick = { dir = dir.substringBeforeLast('/', "") })
    }
    problem?.let { Hint("Could not list /$dir: $it") }
    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        entries.forEach { e ->
            val picked = picks.any { it.path == e.path && it.isDir == e.isDir }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WireButton(
                    (if (picked) "☑ " else "☐ ") + (if (e.isDir) e.name + "/" else e.name) + (e.size?.let { "  ($it B)" } ?: ""),
                    selected = picked,
                    onClick = {
                        val p = ContextPick(e.path, e.isDir)
                        if (picked) picks.remove(p) else picks.add(p)
                    },
                )
                if (e.isDir) WireButton("open ▸", onClick = { dir = e.path })
            }
        }
    }
    if (picks.isNotEmpty()) Hint("Picked: " + picks.joinToString(", ") { it.path + if (it.isDir) "/" else "" })
}
