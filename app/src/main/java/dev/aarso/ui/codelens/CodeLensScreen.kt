package dev.aarso.ui.codelens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.aarso.ui.theme.LocalHyleColors
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The **Lens**: a draggable piece of "smart glass" the user passes over a file. Where
 * the glass sits, the code beneath it is replaced — on the lens surface itself — by a
 * plain-English reading of those lines; outside the glass the code stays fully readable
 * (syntax-highlighted). Legibility for someone who does not read code (owner's brief).
 *
 * [explain] is supplied by the caller (the app/VM layer wires it to `CodeLens.explain`
 * over an on-device or watched-cloud model), so this screen stays free of engine and
 * network concerns. When the interpreter is a watched cloud provider the caller passes
 * [watched] = true; a badge then keeps that binding rule visible on the glass. The call
 * is **debounced** until the glass settles, so dragging never fires a model call per
 * frame (each call may be a cloud round-trip — cost/latency-aware).
 *
 * No device/emulator in CI: the gesture/scroll feel here is owner-verified.
 */
@Composable
fun CodeLensScreen(
    code: String,
    fileName: String,
    explain: suspend (lines: List<String>, fileExt: String) -> String?,
    modifier: Modifier = Modifier,
    explainerLabel: String? = null,
    watched: Boolean = false,
    coveredLines: Int = 6,
    /** Full repo path used for the commit (falls back to [fileName]). */
    filePath: String? = null,
    /** When supplied, the lens becomes editable: edit → review → this commits. Returns commit id. */
    onCommit: (suspend (newText: String, message: String) -> Result<String>)? = null,
) {
    val c = LocalHyleColors.current
    val editScope = androidx.compose.runtime.rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var draft by remember(code) { mutableStateOf(code) }
    var reviewing by remember { mutableStateOf(false) }
    var commitBusy by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val lines = remember(code) { code.split('\n') }
    val ext = remember(fileName) { fileName.substringAfterLast('.', "") }

    val lineHeight = 20.dp
    val lineHeightPx = with(density) { lineHeight.toPx() }
    val glassHeightPx = lineHeightPx * coveredLines

    val scroll = rememberScrollState()
    var lensX by remember { mutableStateOf(0f) }
    var lensY by remember { mutableStateOf(0f) }
    var viewportW by remember { mutableStateOf(0) }
    var viewportH by remember { mutableStateOf(0) }

    val startLine by remember {
        derivedStateOf {
            floor((scroll.value + lensY) / lineHeightPx).toInt()
                .coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        }
    }
    val endExclusive = (startLine + coveredLines).coerceAtMost(lines.size)
    val under = remember(startLine, endExclusive, lines) {
        if (lines.isEmpty()) emptyList() else lines.subList(startLine, endExclusive)
    }

    var state by remember { mutableStateOf<LensState>(LensState.Idle) }
    LaunchedEffect(startLine, lines) {
        state = LensState.Loading
        delay(SETTLE_MS)
        state = runCatching { explain(under, ext) }.fold(
            onSuccess = { if (it.isNullOrBlank()) LensState.Empty else LensState.Loaded(it) },
            onFailure = { LensState.Error(it.message ?: "could not read this") },
        )
    }

    // The glass shows meaning through *focus*, not a status word: while the model is
    // reading (or the lens is still moving) the reading is out of focus (blurred); when
    // it settles the lens focuses and the meaning sharpens. The last reading lingers,
    // blurred, while the next one resolves — the lens is re-focusing, not blanking.
    var lastReading by remember { mutableStateOf("") }
    LaunchedEffect(state) { (state as? LensState.Loaded)?.let { lastReading = it.text } }
    val settled = state is LensState.Loaded || state is LensState.Empty || state is LensState.Error
    val focusBlur by animateDpAsState(
        targetValue = if (settled) 0.dp else 11.dp,
        animationSpec = tween(if (settled) 300 else 90, easing = FastOutSlowInEasing),
        label = "lensFocus",
    )

    Column(modifier.fillMaxSize().background(c.ink)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fileName,
                style = MaterialTheme.typography.titleSmall,
                color = c.textHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onCommit != null) {
                if (editing) {
                    androidx.compose.material3.TextButton(
                        onClick = { reviewing = true },
                        enabled = draft != code,
                    ) { Text("Review ▸") }
                    androidx.compose.material3.TextButton(onClick = { editing = false; draft = code }) { Text("Cancel") }
                } else {
                    androidx.compose.material3.TextButton(onClick = { editing = true; editNote = null }) { Text("✎ Edit") }
                }
            } else {
                Text("drag the lens", style = MaterialTheme.typography.labelSmall, color = c.textDisabled)
            }
        }
        editNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = c.violet, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (editing) {
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = c.textHigh),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.violet),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
            if (reviewing) {
                val cs = dev.aarso.domain.diff.ChangeSet(
                    listOf(dev.aarso.domain.diff.FileChange(filePath ?: fileName, code, draft)),
                )
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { reviewing = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = c.ink) {
                        dev.aarso.ui.ide.ReviewSheet(
                            changeSet = cs,
                            title = "Review — $fileName",
                            commitLabel = "Commit",
                            busy = commitBusy,
                            onCancel = { reviewing = false },
                            onCommit = { approved ->
                                val nt = approved.effective.firstOrNull()?.newText
                                val commit = onCommit
                                if (nt != null && commit != null) {
                                    commitBusy = true
                                    editScope.launch {
                                        commit(nt, "Edit $fileName").fold(
                                            { editNote = "committed ($it)"; editing = false; reviewing = false },
                                            { editNote = "commit failed: ${it.message}" },
                                        )
                                        commitBusy = false
                                    }
                                } else {
                                    reviewing = false
                                }
                            },
                        )
                    }
                }
            }
            return@Column
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(viewportW, viewportH) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val maxX = (viewportW - glassWidthPx(viewportW)).coerceAtLeast(0f)
                        val maxY = (viewportH - glassHeightPx).coerceAtLeast(0f)
                        lensX = (lensX + drag.x).coerceIn(0f, maxX)
                        lensY = (lensY + drag.y).coerceIn(0f, maxY)
                    }
                }
                .onSizeChanged { viewportW = it.width; viewportH = it.height },
        ) {
            // The file, syntax-highlighted, readable everywhere except under the glass.
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp)) {
                lines.forEachIndexed { i, line ->
                    Row(
                        Modifier.fillMaxWidth().height(lineHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}".padStart(3),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = c.textDisabled,
                        )
                        Text(
                            Highlight.kotlinLike("  $line", c.violet, c.success, c.warning, c.textDisabled),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = c.textHigh,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }

            // The lens: smart glass — the meaning is shown on its own surface.
            val glassW = with(density) { glassWidthPx(viewportW).toDp() }
            val glassFill = c.violetDim.copy(alpha = 0.96f)
            Column(
                Modifier
                    .offset { IntOffset(lensX.roundToInt(), lensY.roundToInt()) }
                    .size(width = glassW, height = lineHeight * coveredLines)
                    .clip(RoundedCornerShape(12.dp))
                    .background(glassFill)
                    .border(2.dp, c.violet, RoundedCornerShape(12.dp))
                    .padding(14.dp)
                    .semantics { contentDescription = "Lens reading lines ${startLine + 1} to $endExclusive" },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "lines ${startLine + 1}–$endExclusive",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.violet,
                        modifier = Modifier.weight(1f),
                    )
                    if (watched) {
                        Text(
                            "watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.onViolet,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(c.violet)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .semantics { contentDescription = "Watched cloud — code is sent off-device to be read" },
                        )
                    }
                }
                val body = when (val s = state) {
                    // Unsettled: keep the last reading on the glass (blurred = re-focusing);
                    // on the very first read there is none, so the covered code shows through,
                    // out of focus, until the meaning resolves. Never a status word.
                    LensState.Idle, LensState.Loading ->
                        lastReading.ifBlank { under.joinToString("\n").ifBlank { " " } }
                    LensState.Empty -> "Nothing meaningful here — blank lines or boilerplate."
                    is LensState.Loaded -> s.text
                    is LensState.Error -> s.message
                }
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is LensState.Error) c.error else c.textHigh,
                    modifier = Modifier.padding(top = 6.dp).blur(focusBlur),
                )
                if (explainerLabel != null && state is LensState.Loaded) {
                    Text(
                        "via $explainerLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMid,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private fun glassWidthPx(viewportW: Int): Float = viewportW * 0.86f

private sealed interface LensState {
    data object Idle : LensState
    data object Loading : LensState
    data object Empty : LensState
    data class Loaded(val text: String) : LensState
    data class Error(val message: String) : LensState
}

/** Minimal, local, language-agnostic-ish highlighter (no model, no network). */
private object Highlight {
    private val KEYWORDS = setOf(
        "fun", "val", "var", "if", "else", "while", "for", "return", "data", "class",
        "object", "import", "package", "null", "true", "false", "when", "is", "in", "this",
        "def", "function", "const", "let", "public", "private", "static", "void", "new",
    )
    private val TOKEN = Regex("""//.*|#.*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+\b|\b[A-Za-z_]\w*\b""")

    fun kotlinLike(line: String, keyword: Color, string: Color, number: Color, comment: Color): AnnotatedString =
        buildAnnotatedString {
            var i = 0
            for (m in TOKEN.findAll(line)) {
                if (m.range.first > i) append(line.substring(i, m.range.first))
                val t = m.value
                val color = when {
                    t.startsWith("//") || t.startsWith("#") -> comment
                    t.startsWith("\"") || t.startsWith("'") -> string
                    t.first().isDigit() -> number
                    t in KEYWORDS -> keyword
                    else -> null
                }
                if (color != null) withStyle(SpanStyle(color = color)) { append(t) } else append(t)
                i = m.range.last + 1
            }
            if (i < line.length) append(line.substring(i))
        }
}

private const val SETTLE_MS = 350L
