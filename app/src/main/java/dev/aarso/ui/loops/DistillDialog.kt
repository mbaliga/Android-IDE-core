package dev.aarso.ui.loops

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
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
import dev.aarso.service.OnDeviceDictation
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
    val context = LocalContext.current
    val container = (context.applicationContext as FonebrewApp).container
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf("") }
    // Set when the source came from a picked file, so the distiller's provenance says the file's
    // name rather than "pasted text" — cleared on a fresh pick only, not on every keystroke, so
    // lightly touching up what was read in doesn't lose the attribution.
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var modelId by remember { mutableStateOf(runnable.firstOrNull()?.id) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Voice intake (docs/design/voice-input.md): push-to-talk, on-device recognizer only — never
    // the networked SpeechRecognizer, never ambient listening (CLAUDE.md rule 1). Feeds the same
    // `source` field paste/URL/file-pick already use, so distillation itself needs no new path.
    var listening by remember { mutableStateOf(false) }
    val dictation = remember { OnDeviceDictation(context) }
    DisposableEffect(Unit) { onDispose { dictation.destroy() } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) error = "Microphone permission is needed for voice input."
    }
    fun beginListening() {
        if (busy || listening) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        error = null
        listening = dictation.start(
            onPartial = {},
            onFinal = { text ->
                listening = false
                if (text.isNotBlank()) {
                    source = if (source.isBlank()) text else "$source $text"
                    pickedFileName = null
                }
            },
            onError = { msg -> listening = false; error = msg },
        )
    }
    fun endListening() {
        if (listening) dictation.stop()
    }

    val options = runnable.map { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }

    // A doc from the phone, not just a URL/pasted text (owner ask). v1 reads plain-text-decodable
    // files only — .txt/.md and similar. PDF/DOCX are real, common formats but binary ones this
    // app has no parser for; rather than dump garbled bytes into the source, this recognises their
    // magic numbers and says so honestly (rule 6) instead of pretending to read them.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            error = "Couldn't read that file."
            return@rememberLauncherForActivityResult
        }
        val fileName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        when {
            bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() ->
                error = "PDF text extraction isn't wired up yet — export it as .txt/.md, or paste the text directly."
            bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() ->
                error = "That looks like a .docx (or other zip-based) document — not readable yet. " +
                    "Export it as .txt/.md, or paste the text directly."
            else -> {
                val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
                val sample = text?.take(4000)
                val controlRatio = sample
                    ?.count { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }
                    ?.let { it.toFloat() / sample.length.coerceAtLeast(1) }
                    ?: 1f
                if (text == null || controlRatio > 0.01f) {
                    error = "That file doesn't look like plain text — export it as .txt/.md, or paste the text directly."
                } else {
                    source = text
                    pickedFileName = fileName
                }
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Distill a loop", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Paste a method description (a paper's method section, a training recipe, an " +
                        "agent architecture) — a URL to an article about one, a file, or hold the " +
                        "mic and describe it out loud. The model reads it " +
                        "and proposes a loop topology; it always lands as a draft you review before " +
                        "it can run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HyleField(
                    value = source,
                    onValueChange = { source = it; pickedFileName = null },
                    label = "Text or URL",
                    singleLine = false,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !busy,
                    ) { Text("Choose a file…") }
                    pickedFileName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    // Present only where on-device recognition actually exists — no networked
                    // fallback, so where it's unavailable the control is simply absent, not a
                    // dead button (docs/design/voice-input.md).
                    if (OnDeviceDictation.isAvailable(context)) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                )
                                .pointerInput(busy) {
                                    detectTapGestures(
                                        onPress = {
                                            beginListening()
                                            try {
                                                awaitRelease()
                                            } finally {
                                                endListening()
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val tint = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
                                // A minimal hand-drawn mic glyph — capsule head + stand — matching
                                // this app's own drawn-glyph convention (no icon-font dependency).
                                drawRoundRect(
                                    tint,
                                    topLeft = Offset(size.width * 0.3f, 0f),
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.4f, size.height * 0.62f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.2f),
                                )
                                drawArc(
                                    tint,
                                    startAngle = 20f,
                                    sweepAngle = 140f,
                                    useCenter = false,
                                    topLeft = Offset(size.width * 0.08f, size.height * 0.22f),
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.62f),
                                    style = Stroke(width = size.width * 0.09f),
                                )
                                drawLine(
                                    tint,
                                    Offset(size.width * 0.5f, size.height * 0.84f),
                                    Offset(size.width * 0.5f, size.height),
                                    strokeWidth = size.width * 0.09f,
                                )
                            }
                        }
                        if (listening) {
                            Spacer(Modifier.width(6.dp))
                            Text("listening…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
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
                                            sourceLabel = pickedFileName ?: if (isUrl) typed else "pasted text",
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
