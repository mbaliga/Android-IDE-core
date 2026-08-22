package dev.fonebrew.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.fonebrew.domain.remote.term.Cell
import dev.fonebrew.domain.remote.term.ScreenBuffer
import dev.fonebrew.domain.remote.term.Sgr
import dev.fonebrew.domain.remote.term.TermColor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The 16 standard ANSI colors a real terminal's SGR codes select from — literal program output
 * (an `ls --color` directory, a git-diff +/- line), a **watched object** shown verbatim, exactly
 * as the machine sent it. This is NOT the app's own state-color language — CLAUDE.md's
 * colorblind-safe rule (never encode state in red/green) governs Fonebrew's own UI chrome, not a
 * remote or local shell's actual output, which this app has always promised to show unparaphrased.
 *
 * The bright half (indices 8–15, SGR 90–97 / 100–107) is new: the parser used to fold those codes
 * to nothing, so half of what `ls --color` and every modern prompt emit simply never arrived.
 */
private val AnsiPalette = listOf(
    Color(0xFF1A1A1A), Color(0xFFE5484D), Color(0xFF3DD68C), Color(0xFFE5C53D),
    Color(0xFF5B8DEF), Color(0xFFC768E0), Color(0xFF3DBDD6), Color(0xFFD8D8D8),
    Color(0xFF6E6E6E), Color(0xFFFF7B72), Color(0xFF7EE787), Color(0xFFF2CC60),
    Color(0xFF79B8FF), Color(0xFFD2A8FF), Color(0xFF76E3EA), Color(0xFFFFFFFF),
)

// `internal` (not `private`) so callers that wrap this view — e.g.
// [dev.fonebrew.ui.develop.TerminalFacet], which surrounds the grid with its own terminal-dark
// chrome — can match these exact values instead of duplicating the hex and risking drift.
internal val TerminalBg = Color(0xFF0A0A0A)
internal val TerminalFg = Color(0xFFE8E8E8)

/** Inset between the panel edge and the first glyph — subtracted when deriving the grid size. */
private val GridPadding = 10.dp

/**
 * How many scrollback lines the view actually composes. [ScreenBuffer] keeps a thousand; laying
 * out a thousand styled `Text`s on every output chunk is a phone-scale cost for lines nobody is
 * scrolling to. This is a *render* budget, not a data cap — the model still holds the rest.
 */
private const val RenderedScrollbackRows = 400

// Guards on the derived grid: a mid-animation or zero-size measurement must never be handed to
// the shell as a real window size.
private const val MinCols = 20
private const val MaxCols = 500
private const val MinRows = 4
private const val MaxRows = 200

/**
 * Debounce before a measured size becomes a SIGWINCH. Showing/hiding the soft keyboard animates
 * the panel's height across many frames; resizing the shell on each one would thrash it (and
 * make a full-screen app repaint dozens of times).
 */
private const val ResizeDebounceMs = 180L

/** Ten ems of the face that actually renders, so one cell's width isn't a rounding artefact. */
private const val CharProbe = "MMMMMMMMMM"

/**
 * Renders a [ScreenBuffer] as an actual terminal grid: real per-cell SGR colors/bold/underline,
 * a solid near-black panel distinct from the app's own surfaces, and a cursor block. Shared by
 * the local PTY terminal ([dev.fonebrew.ui.develop.TerminalFacet]) and Remote's SSH shell, so
 * both read as the same real terminal instead of two different levels of polish — and, since
 * this is the one renderer, so a fix here lands on both.
 *
 * Three things this view now does that it did not, each of which made ordinary use impossible:
 *
 * 1. **It scrolls, in both directions, and shows the scrollback.** [ScreenBuffer] has always
 *    kept a thousand lines of transcript and exposed them, with zero callers: only the live 24
 *    rows were ever drawn, so anything that scrolled past the top was gone from the UI forever.
 *    The vertical scroller now renders scrollback above the live grid and stays pinned to the
 *    bottom while new output arrives, releasing the pin the moment the reader scrolls up.
 * 2. **It measures itself and reports the real grid size** via [onGridMeasured]. The session was
 *    permanently 24×80 against a phone showing forty-odd monospace columns, so the right half of
 *    every line — `ls -l`, `git status` — was cut off with no way to reach it. The full resize
 *    path (`ShellSession.resize` → TIOCSWINSZ / SSH window-change) already existed and had no
 *    caller; this is the caller.
 * 3. **Horizontal scroll as the safety net.** With a truthful width the shell wraps instead of
 *    overflowing, but scrollback captured at an older width — and any program that ignores its
 *    window size — still needs somewhere to go besides off the edge.
 *
 * [version] is the caller's output counter: the screen is a plain mutable model, not Compose
 * state, so bumping this is what tells the view to re-read it. Pass it rather than wrapping the
 * call in `key(version)` — re-keying would rebuild the subtree and throw away the scroll
 * position on every chunk of output.
 *
 * Render/gesture behaviour here is owner-verified: there is no device or emulator in this build
 * environment.
 */
@Composable
fun TerminalView(
    screen: ScreenBuffer,
    modifier: Modifier = Modifier,
    version: Int = 0,
    onGridMeasured: ((rows: Int, cols: Int) -> Unit)? = null,
) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val padPx = with(density) { GridPadding.roundToPx() }
    val bottomSlopPx = with(density) { 8.dp.roundToPx() }

    // One cell's box, taken from the face Compose will actually draw with — not a guess.
    val cellPx = remember(measurer, style) {
        val probe = measurer.measure(AnnotatedString(CharProbe), style)
        IntSize(
            (probe.size.width.toFloat() / CharProbe.length).roundToInt().coerceAtLeast(1),
            probe.size.height.coerceAtLeast(1),
        )
    }
    var panelPx by remember { mutableStateOf(IntSize.Zero) }

    // Re-read the (mutable, non-Compose) model whenever the caller says output landed.
    val liveRows = remember(screen, version) { screen.rowsSnapshot() }
    val backRows = remember(screen, version) { screen.scrollbackSnapshot(RenderedScrollbackRows) }
    val cursor = screen.cursor
    val cursorVisible = screen.cursorVisible

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    // "Pinned" = the reader is at the bottom watching live output, so new lines should follow
    // them down. Scrolling up releases the pin (and reading old output is precisely when being
    // yanked back to the bottom is worst); scrolling back to the bottom re-takes it. Evaluated
    // when a gesture *settles* rather than continuously, so the pin can't be lost to the
    // momentary gap between new content arriving and our own scroll catching up.
    var pinned by remember { mutableStateOf(true) }
    LaunchedEffect(vScroll) {
        snapshotFlow { vScroll.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) pinned = vScroll.value >= vScroll.maxValue - bottomSlopPx
        }
    }
    // maxValue only updates once the new rows have actually been laid out, so that — not the
    // moment the output arrived — is the honest point at which to follow them down. (Once the
    // content stops growing, staying at maxValue is staying at the bottom, so nothing more is
    // needed for a steady stream.)
    LaunchedEffect(vScroll) {
        snapshotFlow { vScroll.maxValue }.collect { max ->
            if (!pinned) return@collect
            try {
                vScroll.scrollTo(max)
            } catch (_: CancellationException) {
                // A user drag (or a newer auto-scroll) takes the scroll mutex and interrupts this
                // one with a MutationInterruptedException — a CancellationException. Letting it
                // escape would tear this collector down for good, so auto-follow would silently
                // stop working the first time the reader ever touched the grid. Our own scope
                // going away must still cancel, hence the ensureActive.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    // Held via rememberUpdatedState so the debounce isn't restarted by the callback merely being
    // a fresh lambda each recomposition — with output streaming, that would reset the timer
    // forever and the resize would never fire.
    val reportSize by rememberUpdatedState(onGridMeasured)
    LaunchedEffect(panelPx, cellPx) {
        val report = reportSize ?: return@LaunchedEffect
        if (panelPx.width <= 0 || panelPx.height <= 0) return@LaunchedEffect
        delay(ResizeDebounceMs)
        val cols = ((panelPx.width - 2 * padPx) / cellPx.width).coerceIn(MinCols, MaxCols)
        val rows = ((panelPx.height - 2 * padPx) / cellPx.height).coerceIn(MinRows, MaxRows)
        report(rows, cols)
    }

    Box(
        modifier
            .background(TerminalBg, RoundedCornerShape(8.dp))
            .onSizeChanged { panelPx = it }
            .padding(GridPadding),
    ) {
        SelectionContainer {
            Column(
                Modifier
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll),
            ) {
                backRows.forEach { row ->
                    Text(
                        text = rowAnnotatedString(row, -1),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalFg,
                        softWrap = false,
                    )
                }
                liveRows.forEachIndexed { r, row ->
                    Text(
                        text = rowAnnotatedString(
                            row,
                            if (cursorVisible && r == cursor.row) cursor.col else -1,
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalFg,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** An xterm palette index → a drawable colour: 0–15 the named ramp above, 16–231 the 6×6×6
 *  cube, 232–255 the grey ramp. */
private fun ansiColor(index: Int): Color = when (index) {
    in AnsiPalette.indices -> AnsiPalette[index]
    in 16..231 -> {
        val i = index - 16
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        Color(steps[(i / 36) % 6], steps[(i / 6) % 6], steps[i % 6])
    }
    in 232..255 -> { val v = 8 + (index - 232) * 10; Color(v, v, v) }
    else -> TerminalFg
}

private fun TermColor.toColor(): Color = when (this) {
    is TermColor.Indexed -> ansiColor(index)
    is TermColor.Rgb -> Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))
}

private fun spanFor(sgr: Sgr, inverted: Boolean): SpanStyle {
    var fg = sgr.fg?.toColor() ?: TerminalFg
    var bg = sgr.bg?.toColor()
    if (sgr.inverse != inverted) {   // the cursor cell inverts too; both inverting cancels out
        val t = fg; fg = bg ?: TerminalBg; bg = t
    }
    return SpanStyle(
        color = fg,
        background = bg ?: Color.Unspecified,
        fontWeight = if (sgr.bold) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (sgr.underline) TextDecoration.Underline else null,
    )
}

/**
 * One grid row as styled text. Runs of identical attributes collapse into a single span: the
 * previous renderer opened one per *cell*, which was already 1920 spans a frame for a bare 24×80
 * screen and would be tens of thousands now that scrollback is drawn. A terminal line is a
 * handful of runs (a coloured prompt, then plain text), so this is both cheaper and closer to
 * what the line actually is.
 *
 * Trailing untouched cells are dropped so a row is only as wide as its content — otherwise every
 * line would pad itself out to the full grid width and give the horizontal scroller a range even
 * when nothing overflows.
 */
private fun rowAnnotatedString(row: List<Cell>, cursorCol: Int): AnnotatedString = buildAnnotatedString {
    val lastInk = row.indexOfLast { it.char != ' ' || it.sgr != Sgr.DEFAULT }
    val end = maxOf(lastInk, cursorCol) + 1
    if (end <= 0) {
        // A blank line still has to occupy a line box, or the grid would silently close up.
        append(" ")
        return@buildAnnotatedString
    }
    var i = 0
    while (i < end) {
        val sgr = row[i].sgr
        val atCursor = i == cursorCol
        var runEnd = i + 1
        if (!atCursor) {
            while (runEnd < end && runEnd != cursorCol && row[runEnd].sgr == sgr) runEnd++
        }
        val text = buildString { for (k in i until runEnd) append(row[k].char) }
        withStyle(spanFor(sgr, inverted = atCursor)) { append(text) }
        i = runEnd
    }
}
