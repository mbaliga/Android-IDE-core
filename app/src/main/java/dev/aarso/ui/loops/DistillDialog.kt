package dev.aarso.ui.loops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.aarso.FonebrewApp
import dev.aarso.data.DocumentFetcher
import dev.aarso.domain.loop.DistillResult
import dev.aarso.domain.loop.Distiller
import dev.aarso.domain.loop.DocumentExtract
import dev.aarso.domain.loop.Loop
import dev.aarso.domain.model.ModelSpec
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleDropdownField
import dev.aarso.hyle.cells.HyleField
import dev.aarso.inference.EngineGenerator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Loop distillation's intake (docs/design/loop-distillation.md, build-order step 3): a source
 * in, a loop out. **Pasting the method text yourself is the most sovereign intake — no fetch at
 * all** — so that's the default reading of whatever's typed here; a source that looks like a URL
 * is fetched and reduced to plain text first ([DocumentFetcher] + [DocumentExtract]), a **watched
 * fetch** that only happens because the user named that exact location and tapped Distill, never
 * in the background.
 *
 * Division of labour stays with [Distiller]: the picked model classifies the topology, code
 * builds the guaranteed-valid graph. The result always lands as an **Unused** draft with
 * provenance in its start event — "a legible first draft you refine," never a black box that
 * just runs.
 */
@Composable
fun DistillDialog(
    runnable: List<ModelSpec>,
    onDismiss: () -> Unit,
    onDistilled: (Loop) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(runnable.firstOrNull()?.id) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val options = runnable.map { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Distill a loop", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Paste a method description (a paper's method section, a training recipe, an " +
                        "agent architecture) — or a URL to an article about one. The model reads it " +
                        "and proposes a loop topology; it always lands as a draft you review before " +
                        "it can run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HyleField(
                    value = source,
                    onValueChange = { source = it },
                    label = "Text or URL",
                    singleLine = false,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                if (runnable.isNotEmpty()) {
                    HyleDropdownField(
                        value = modelId?.let { id -> runnable.firstOrNull { it.id == id } }
                            ?.let { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }
                            ?: options.first(),
                        options = options,
                        onSelect = { idx -> modelId = runnable[idx].id },
                        label = "Model",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    HyleButton(
                        if (busy) "Working…" else "Distill",
                        enabled = !busy && source.isNotBlank() && modelId != null,
                        onClick = {
                            val spec = runnable.firstOrNull { it.id == modelId } ?: return@HyleButton
                            val typed = source.trim()
                            val isUrl = typed.startsWith("http://") || typed.startsWith("https://")
                            busy = true
                            error = null
                            status = if (isUrl) "Fetching…" else "Distilling…"
                            scope.launch {
                                val jobId = container.backgroundJobs.start(
                                    "Distill: ${typed.take(50)}${if (typed.length > 50) "…" else ""}",
                                    "distill",
                                )
                                val textResult = if (isUrl) {
                                    DocumentFetcher().fetch(typed).map(DocumentExtract::extractText)
                                } else {
                                    Result.success(typed)
                                }
                                textResult.fold(
                                    onSuccess = { text ->
                                        status = "Distilling…"
                                        val generator = EngineGenerator(
                                            container.engineProvider.engineFor(spec)!!,
                                            spec.modelPath,
                                        )
                                        val result = Distiller(generator).distill(
                                            source = text,
                                            sourceLabel = if (isUrl) typed else "pasted text",
                                            distilledBy = spec.displayName,
                                            distilledOn = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                                            id = UUID.randomUUID().toString(),
                                            now = System.currentTimeMillis(),
                                        )
                                        when (result) {
                                            is DistillResult.Ok -> {
                                                container.backgroundJobs.finish(jobId)
                                                busy = false; status = null
                                                onDistilled(result.loop)
                                            }
                                            is DistillResult.Failed -> {
                                                container.backgroundJobs.finish(jobId, failed = true)
                                                busy = false; status = null
                                                error = "Couldn't distil a loop: ${result.reason}"
                                            }
                                        }
                                    },
                                    onFailure = { e ->
                                        container.backgroundJobs.finish(jobId, failed = true)
                                        busy = false; status = null
                                        error = "Couldn't fetch that URL: ${e.message}"
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
