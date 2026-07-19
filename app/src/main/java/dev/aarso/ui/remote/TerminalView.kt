package dev.aarso.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.aarso.domain.remote.term.Cell
import dev.aarso.domain.remote.term.ScreenBuffer

/**
 * The 8 standard ANSI colors a real terminal's SGR codes select from — literal program output
 * (an `ls --color` directory, a git-diff +/- line), a **watched object** shown verbatim, exactly
 * as the machine sent it. This is NOT the app's own state-color language — CLAUDE.md's
 * colorblind-safe rule (never encode state in red/green) governs Fonebrew's own UI chrome, not a
 * remote or local shell's actual output, which this app has always promised to show unparaphrased.
 */
private val AnsiPalette = listOf(
    Color(0xFF1A1A1A), Color(0xFFE5484D), Color(0xFF3DD68C), Color(0xFFE5C53D),
    Color(0xFF5B8DEF), Color(0xFFC768E0), Color(0xFF3DBDD6), Color(0xFFD8D8D8),
)

private val TerminalBg = Color(0xFF0A0A0A)
private val TerminalFg = Color(0xFFE8E8E8)

/**
 * Renders a [ScreenBuffer] as an actual terminal grid: real per-cell SGR colors/bold/underline
 * (VtParser has always parsed these — the earlier renderer flattened every row to plain trimmed
 * text via `lineText()`, which discarded them; that's why output never looked like a real
 * terminal even once the shell side was real), a solid near-black panel distinct from the app's
 * own surfaces, and a cursor block. Shared by the local PTY terminal
 * ([dev.aarso.ui.develop.TerminalFacet]) and this file's SSH interactive shell, so both read as
 * the same real terminal instead of two different levels of polish.
 */
@Composable
fun TerminalView(screen: ScreenBuffer, modifier: Modifier = Modifier) {
    val rows = screen.rowsSnapshot()
    val cursor = screen.cursor
    Box(
        modifier
            .background(TerminalBg, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        SelectionContainer {
            Column {
                rows.forEachIndexed { r, row ->
                    Text(
                        text = rowAnnotatedString(row, if (r == cursor.row) cursor.col else -1),
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

private fun rowAnnotatedString(row: List<Cell>, cursorCol: Int): AnnotatedString = buildAnnotatedString {
    row.forEachIndexed { c, cell ->
        var fg = cell.sgr.fg?.let { AnsiPalette[it] } ?: TerminalFg
        var bg = cell.sgr.bg?.let { AnsiPalette[it] }
        if (cell.sgr.inverse) {
            val t = fg; fg = bg ?: TerminalBg; bg = t
        }
        if (c == cursorCol) {
            val t = fg; fg = bg ?: TerminalBg; bg = t
        }
        withStyle(
            SpanStyle(
                color = fg,
                background = bg ?: Color.Unspecified,
                fontWeight = if (cell.sgr.bold) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (cell.sgr.underline) TextDecoration.Underline else null,
            ),
        ) { append(cell.char) }
    }
}
