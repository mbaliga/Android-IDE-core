package dev.fonebrew.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.LocalModel
import dev.fonebrew.data.benchmark.BenchmarkController
import dev.fonebrew.data.benchmark.BenchmarkUiState
import dev.fonebrew.domain.benchmark.BenchmarkPlan
import dev.fonebrew.domain.benchmark.BenchmarkProgress
import dev.fonebrew.domain.benchmark.BenchmarkResult
import dev.fonebrew.domain.benchmark.BenchmarkTextExport
import dev.fonebrew.inference.toSpec

/**
 * Models-screen entry point: one card, one button. The benchmark itself lives in a full-screen
 * dialog so the existing Models layout is untouched. Nothing starts from here; the dialog opens
 * a screen that explains the run and waits for its own Start tap.
 */
@Composable
fun BenchmarkEntry(models: List<LocalModel>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    HyleCard(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column {
            Text("Benchmark", style = MaterialTheme.typography.titleMedium)
            Text(
                "Measure how fast a downloaded model runs on this phone. Nothing runs until you tap Start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            HyleButton("Open benchmark", onClick = { open = true }, secondary = true)
        }
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { BenchmarkScreen(models, onClose = { open = false }) }
        }
    }
}

@Composable
fun BenchmarkScreen(models: List<LocalModel>, onClose: () -> Unit) {
    val controller = (LocalContext.current.applicationContext as FonebrewApp).container.benchmarkController
    val state by controller.state.collectAsState()
    val history by controller.history.collectAsState()
    val plan = remember { BenchmarkPlan.default() }
    var selected by remember { mutableStateOf(models.firstOrNull()?.name) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("On-device benchmark", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            is BenchmarkUiState.Running -> RunningPanel(s, controller)
            is BenchmarkUiState.Idle -> SetupPanel(models, selected, { selected = it }, plan, controller, s.refusal)
        }
        Spacer(Modifier.height(20.dp))
        Text("Results", style = MaterialTheme.typography.titleMedium)
        Text(
            BenchmarkResult.SCOPE_STATEMENT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (history.isEmpty()) {
            Text("? No results yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        } else {
            Spacer(Modifier.height(8.dp))
            HyleButton(
                "Copy results as text",
                onClick = { clipboard.setText(AnnotatedString(BenchmarkTextExport.render(history))) },
                secondary = true,
            )
            Text(
                "Copies to your clipboard only. Nothing is sent anywhere.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            history.groupBy { it.modelFileName }.forEach { (model, runs) ->
                Spacer(Modifier.height(12.dp))
                Text(model, style = MaterialTheme.typography.titleSmall)
                runs.asReversed().forEach { ResultCard(it) }
            }
        }
    }
}

@Composable
private fun SetupPanel(
    models: List<LocalModel>,
    selected: String?,
    onSelect: (String) -> Unit,
    plan: BenchmarkPlan,
    controller: BenchmarkController,
    refusal: String?,
) {
    Text("What Start will do", style = MaterialTheme.typography.titleSmall)
    listOf(
        "Load the model you pick below into memory (it must already be on this phone).",
        "Run ${plan.generationCount} generations (${plan.prompts.size} prompts x ${plan.repeats} repeats, up to " +
            "${plan.decodeTokens} tokens each), context ${plan.contextSize}.",
        "Take roughly several minutes. That is a rough guess, not a measurement: larger models take longer, " +
            "and the real time is recorded in the result.",
        "Warm the phone. It stops by itself if the phone reports thermal status SEVERE or worse.",
        "Keep running with the screen off. You can Stop at any time.",
        "Never download anything, never use the network, never use a cloud model.",
    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }

    Spacer(Modifier.height(12.dp))
    Text("Model", style = MaterialTheme.typography.titleSmall)
    models.forEach { m ->
        val isSel = m.name == selected
        TextButton(onClick = { onSelect(m.name) }) {
            Text((if (isSel) "● selected: " else "○ ") + m.name)
        }
    }

    val spec = models.firstOrNull { it.name == selected }?.toSpec()
    val willUnload = spec != null && controller.wouldUnloadResidentModel(spec)
    if (willUnload) {
        Text(
            "⚠ A model is currently loaded for chat. Starting unloads it first; any chat using it will have to reload.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    refusal?.let {
        Text("✗ Not started: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    HyleButton(
        if (willUnload) "Unload chat model and start" else "Start benchmark",
        onClick = { spec?.let { controller.start(it, plan, unloadResidentModel = willUnload) } },
        enabled = spec != null,
    )
}

@Composable
private fun RunningPanel(s: BenchmarkUiState.Running, controller: BenchmarkController) {
    val p = s.progress
    val line = when (p.phase) {
        BenchmarkProgress.Phase.LOADING -> "◐ Loading the model"
        BenchmarkProgress.Phase.GENERATING -> "◐ Generating: ${p.promptLabel ?: ""} (${p.completedGenerations + 1} of ${p.totalGenerations})"
        BenchmarkProgress.Phase.UNLOADING -> "◐ Unloading the model"
        BenchmarkProgress.Phase.DONE -> "✓ Finishing"
    }
    Text(line, style = MaterialTheme.typography.bodyMedium)
    Text(
        "Thermal status: ${BenchmarkTextExport.level(p.thermal)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LinearProgressIndicator(
        progress = { if (p.totalGenerations == 0) 0f else p.completedGenerations / p.totalGenerations.toFloat() },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    if (s.stopRequested) {
        Text("■ Stopping after the current token...", style = MaterialTheme.typography.bodySmall)
    } else {
        HyleButton("Stop", onClick = { controller.stop() }, secondary = true)
    }
}

@Composable
private fun ResultCard(r: BenchmarkResult) {
    HyleCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column {
            Text("${r.outcome.glyph} ${r.outcome.label}", style = MaterialTheme.typography.titleSmall)
            r.outcomeDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "load ${BenchmarkTextExport.ms(r.loadMillis)} | thermal ${BenchmarkTextExport.level(r.thermalBefore)} -> " +
                    "${BenchmarkTextExport.level(r.thermalAfter)} | battery ${BenchmarkTextExport.pct(r.batteryPercentBefore)} -> " +
                    BenchmarkTextExport.pct(r.batteryPercentAfter),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "PSS MB: ${BenchmarkTextExport.mb(r.pssKbBeforeLoad)} / ${BenchmarkTextExport.mb(r.pssKbAfterLoad)} / " +
                    "${BenchmarkTextExport.mb(r.pssKbAfterGeneration)} (before load / after load / after generation)",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text("prompt | tokens | TTFT ms | prefill t/s | decode t/s | decode drift", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            r.summaries().forEach { s ->
                Text(
                    "${s.promptId} | ${s.promptTokens ?: "unknown"} | ${BenchmarkTextExport.num(s.ttftMillis.median)} | " +
                        "${BenchmarkTextExport.num(s.prefillTokensPerSec.median)} | ${BenchmarkTextExport.num(s.decodeTokensPerSec.median)} | " +
                        (s.decodeTokensPerSec.driftPercent?.let { BenchmarkTextExport.num(it) + "%" } ?: "unknown"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text("Medians; copy as text for the full spread. Unknown means not measured.", style = MaterialTheme.typography.labelSmall)
        }
    }
}
