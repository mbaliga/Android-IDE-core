package dev.aarso.hyleprobe

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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The **Lens** probe: a draggable piece of "smart glass" you pass over code. Where
 * the glass sits, the code beneath it is replaced — on the lens surface itself — by a
 * plain-English reading of those lines. Outside the glass the code stays fully
 * readable (syntax-highlighted). Legibility for someone who doesn't read code.
 *
 * Self-contained *feel* probe: a small sample file + a canned reading (no model, no
 * network) so the interaction can be tried on device now. In the real app the reading
 * on the glass is produced by an on-device or watched-cloud model (`CodeLens.explain`
 * in `:app`); here it is a hand-written sample, labelled as such.
 */

private val Ink = Color(0xFF08090C)
private val TextHigh = Color(0xFFEDEFF3)
private val TextMid = Color(0xFF9AA1AD)
private val TextDim = Color(0xFF5B626E)
private val Violet = Color(0xFF8E7BFF)
private val GlassFill = Color(0xF2120E1F)   // ~95% opaque dark violet — obscures the code behind

// Syntax colours (local, no model — Q1: highlighting is just rendering).
private val SynKeyword = Color(0xFFC7B8FF)
private val SynString = Color(0xFF9ECE9E)
private val SynNumber = Color(0xFFE0B080)
private val SynComment = Color(0xFF5B626E)

private val SAMPLE = """
    import okhttp3.OkHttpClient
    import okhttp3.Request

    data class Download(
        val url: String,
        val attempts: Int,
    )

    // Fetch a file, retrying a few times if the network fails.
    fun fetch(client: OkHttpClient, job: Download): ByteArray? {
        var tries = 0
        while (tries < job.attempts) {
            val request = Request.Builder().url(job.url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                return response.body?.bytes()
            }
            tries = tries + 1
        }
        return null
    }
""".trimIndent()

/** A canned plain-English reading for whichever region the glass covers. */
private fun sampleReading(startLine: Int): String = when (startLine) {
    in 0..2 -> "This pulls in a networking toolkit so the program can talk to the web."
    in 3..7 -> "It describes a download job: which web address to fetch, and how many times to retry if it fails."
    in 8..11 -> "A routine begins that fetches the file, and sets a counter to track how many tries it has made."
    in 12..14 -> "It keeps asking the server for the file, over and over, up to the allowed number of attempts."
    in 15..17 -> "When the server finally answers successfully, it hands back the downloaded contents and stops."
    else -> "If a try fails it counts it and goes round again; if every attempt fails, it gives back nothing."
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "if", "else", "while", "for", "return", "data", "class",
    "object", "import", "package", "null", "true", "false", "when", "is", "in", "this",
)
private val TOKEN = Regex("""//.*|"(?:\\.|[^"\\])*"|\b\d+\b|\b[A-Za-z_]\w*\b""")

private fun highlight(line: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in TOKEN.findAll(line)) {
        if (m.range.first > i) append(line.substring(i, m.range.first))
        val t = m.value
        val color = when {
            t.startsWith("//") -> SynComment
            t.startsWith("\"") -> SynString
            t.first().isDigit() -> SynNumber
            t in KEYWORDS -> SynKeyword
            else -> null
        }
        if (color != null) withStyle(SpanStyle(color = color)) { append(t) } else append(t)
        i = m.range.last + 1
    }
    if (i < line.length) append(line.substring(i))
}

@Composable
fun LensProbe() {
    val density = LocalDensity.current
    val lines = remember { SAMPLE.split('\n') }

    val lineHeight = 20.dp
    val lineHeightPx = with(density) { lineHeight.toPx() }
    val coveredLines = 6
    val glassHeightPx = lineHeightPx * coveredLines

    val scroll = rememberScrollState()
    var lensX by remember { mutableStateOf(0f) }
    var lensY by remember { mutableStateOf(lineHeightPx * 3) } // start over the data class
    var viewportW by remember { mutableStateOf(0) }
    var viewportH by remember { mutableStateOf(0) }

    val startLine by remember {
        derivedStateOf {
            floor((scroll.value + lensY) / lineHeightPx).toInt()
                .coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        }
    }
    val endExclusive = (startLine + coveredLines).coerceAtMost(lines.size)

    // The meaning is always known to the glass; the *material* shows whether it is
    // legible yet. While the lens moves it is out of focus (blurred); when it settles
    // the lens focuses and the reading sharpens. No status word — the glass does the
    // talking, which is the whole thesis.
    val reading = remember(startLine) { sampleReading(startLine) }
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(startLine) {
        settling = true            // the lens is moving → meaning out of focus
        delay(300)                 // hold to settle, as a model call would
        settling = false           // the lens focuses → meaning sharpens into legibility
    }
    val focusBlur by animateDpAsState(
        targetValue = if (settling) 11.dp else 0.dp,
        animationSpec = tween(if (settling) 90 else 300, easing = FastOutSlowInEasing),
        label = "lensFocus",
    )
    val readingInk by animateFloatAsState(
        targetValue = if (settling) 0.5f else 1f,
        animationSpec = tween(if (settling) 90 else 320, easing = FastOutSlowInEasing),
        label = "lensInk",
    )

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Download.kt", color = TextHigh, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("drag the lens", color = TextDim, fontSize = 12.sp)
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
            // The code — syntax-highlighted, fully readable everywhere except under the glass.
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp)) {
                lines.forEachIndexed { i, line ->
                    Row(
                        Modifier.fillMaxWidth().height(lineHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}".padStart(2), color = TextDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(
                            highlight("  $line"),
                            color = TextHigh,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }

            // The lens: smart glass that shows the meaning on its own surface.
            val glassW = with(density) { glassWidthPx(viewportW).toDp() }
            Column(
                Modifier
                    .offset { IntOffset(lensX.roundToInt(), lensY.roundToInt()) }
                    .size(width = glassW, height = lineHeight * coveredLines)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassFill)
                    .border(2.dp, Violet, RoundedCornerShape(12.dp))
                    .padding(14.dp)
                    .semantics { contentDescription = "Lens reading lines ${startLine + 1} to $endExclusive: $reading" },
            ) {
                Text("lines ${startLine + 1}–$endExclusive", color = Violet, fontSize = 11.sp)
                Text(
                    reading,
                    color = TextHigh,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .blur(focusBlur)
                        .alpha(readingInk),
                )
            }
        }
    }
}

private fun glassWidthPx(viewportW: Int): Float = viewportW * 0.86f
