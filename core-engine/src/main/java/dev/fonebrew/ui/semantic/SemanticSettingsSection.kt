package dev.fonebrew.ui.semantic

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleSwitch

/**
 * Settings -> Models -> "Semantic search". Off by default; nothing downloads until a Download
 * button is tapped; status is a glyph + label. Render and the picker/download flows are
 * owner-verified (this container has no device).
 */
@Composable
fun SemanticSettingsSection() {
    val vm: SemanticSettingsViewModel = viewModel(factory = SemanticSettingsViewModel.Factory)
    val ui by vm.ui.collectAsState()
    val message by vm.message.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPicked(uri)
    }

    HorizontalDivider()
    Text("Semantic search", style = MaterialTheme.typography.titleMedium)
    Text(
        "Find conversations by meaning as well as by words. Off by default. When on, an embedding model " +
            "runs on this phone and results show whether they matched by words, by meaning, or both.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Use semantic search", style = MaterialTheme.typography.bodyMedium)
        HyleSwitch(checked = ui.enabled, onCheckedChange = vm::setEnabled)
    }

    Text("${ui.glyph}  ${ui.statusLabel}", style = MaterialTheme.typography.bodyMedium)
    ui.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    ui.errorLine?.let { Text("△  $it", style = MaterialTheme.typography.bodySmall) }
    message?.let {
        Text("$it  (tap to dismiss)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { vm.consumeMessage() })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.canPause) HyleButton("Pause", onClick = vm::pause)
        if (ui.canResume) HyleButton("Resume", onClick = vm::resume)
        if (ui.canRebuild) HyleButton("Rebuild", onClick = vm::rebuild)
        if (ui.canClear) HyleButton("Clear", onClick = vm::clear)
    }
    Text(ui.storageLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(ui.privacyLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("Embedding model", style = MaterialTheme.typography.titleSmall)
    ui.noModelHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    ui.models.forEach { m ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HyleChip(m.selected, { vm.selectModel(m.name) }, if (m.selected) "✓ ${m.name}" else m.name)
                HyleButton("Remove", onClick = { vm.deleteModel(m.name) })
            }
            Text("${m.sizeText}  ·  ${m.licenseText}", style = MaterialTheme.typography.labelSmall)
        }
    }
    HyleButton("Choose a .gguf file", onClick = { picker.launch(arrayOf("*/*")) })

    ui.catalog.forEach { e ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyMedium)
            Text("${e.sizeText}  ·  ${e.licenseText}", style = MaterialTheme.typography.labelSmall)
            Text(e.hostLine, style = MaterialTheme.typography.labelSmall)
            when (e.state) {
                SemanticSettingsPresenter.DownloadState.NOT_DOWNLOADED ->
                    HyleButton("Download (${e.sizeText})", onClick = { vm.download(e.id) })
                SemanticSettingsPresenter.DownloadState.DOWNLOADING -> {
                    e.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    HyleButton("Cancel download", onClick = { vm.cancelDownload(e.id) })
                }
                SemanticSettingsPresenter.DownloadState.INSTALLED -> Text("✓ On this phone", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
