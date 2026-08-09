package dev.aarso.ui.spatial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.ui.theme.LocalHyleColors

/**
 * The three *views* of the central room — not rooms, not depths. The centre is one
 * activity (agents doing work over the message tree); these are lenses onto it, like a
 * CLI session read three ways: [CONVERSATION] the human-readable exchange, [TERMINAL]
 * the raw ground-level transcript, [BACKGROUND] what's running underneath.
 *
 * Labels are the mockups' own words ("Chat", not "Conversation") — the enum keeps the
 * structural name, the UI shows what the owner drew.
 */
enum class CenterView(val label: String) {
    CONVERSATION("Chat"),
    TERMINAL("Terminal"),
    BACKGROUND("Background Tasks"),
}

private val BAR_HEIGHT = 52.dp

/**
 * Hyle's locked slant (rise 1, run 0.2 — the slope every slanted surface in the design
 * system leans at). Held here as a local constant rather than imported: the pinned
 * `dev.aarso:hyle` does not export the slope yet, and the app should not gain a hard
 * dependency on an unmerged design-system branch just to draw a seam. If/when the module
 * publishes it, delete this and import it — the value must not diverge.
 */
private const val HYLE_SLANT = 0.2f

/**
 * The persistent bottom bar: the centre room's **view switcher** and nothing else. Room
 * navigation is spatial (edge drags, pinch) — tabs are views of one place, never a way to
 * change place.
 *
 * Form follows the owner's detailed mockups: the selected view sits on the app surface,
 * cut out of a **dark strip** that carries the unselected views, and every seam between
 * them leans at [HYLE_SLANT] — the design system's one slope, so the bar's slashes are the
 * same gesture as the colour picker's tabs rather than a second eyeballed angle. The
 * selected view's icon and label take the **accent** (owner-set: buttons, swipe
 * affordances and selected text are all the user's chosen accent); unselected views sit
 * light-on-dark inside the strip.
 *
 * Responsive ladder (owner-set): icons+labels on every tab when they fit, else the label
 * on the selected tab only, else icons alone — decided by [CenterTabLayout.stage] over
 * real measured label widths, no truncation or marquee. Render is owner-verified.
 */
@Composable
fun CenterViewTabBar(
    selected: CenterView,
    onSelect: (CenterView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ac = LocalHyleColors.current
    // The strip is the bar's high-contrast block. On a light surface that is a near-black
    // slab (as drawn); on an already-dark surface it lifts to `raised` instead, so the
    // strip stays a distinct block rather than dissolving into the page.
    val lightSurface = ac.ink.luminance() > 0.5f
    val strip = if (lightSurface) Color(0xFF0E0F12) else ac.raised
    val onStrip = if (lightSurface) Color(0xFFECEDEF) else ac.textHigh

    val views = CenterView.entries
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(strip),
    ) {
        val density = LocalDensity.current
        val stage = with(density) {
            CenterTabLayout.stage(
                availablePx = maxWidth.toPx(),
                labelWidthsPx = views.map { measurer.measure(it.label, labelStyle).size.width.toFloat() },
                selected = views.indexOf(selected),
                iconPx = 20.dp.toPx(),
                gapPx = 8.dp.toPx(),
                paddingPx = 16.dp.toPx(),
            )
        }

        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            views.forEachIndexed { i, view ->
                val active = view == selected
                val labelled = when (stage) {
                    CenterTabStage.FULL -> true
                    CenterTabStage.SELECTED_LABEL -> active
                    CenterTabStage.ICONS_ONLY -> false
                }
                // A divider marks the seam between two neighbouring strip cells only —
                // where the selected cell abuts the strip, its own colour edge IS the seam.
                val leadingDivider = i > 0 && !active && views[i - 1] != selected

                Row(
                    Modifier
                        .fillMaxHeight()
                        .drawBehind {
                            val lean = size.height * HYLE_SLANT
                            if (active) {
                                // The surface cut-out: flush to the screen edge when this
                                // is the first cell, leaning on both sides otherwise.
                                val path = Path().apply {
                                    moveTo(if (i == 0) 0f else lean, 0f)
                                    lineTo(size.width, 0f)
                                    lineTo(size.width - lean, size.height)
                                    lineTo(0f, size.height)
                                    close()
                                }
                                drawPath(path, ac.ink)
                            } else if (leadingDivider) {
                                drawLine(
                                    onStrip.copy(alpha = 0.38f),
                                    start = Offset(lean, 0f),
                                    end = Offset(0f, size.height),
                                    strokeWidth = 1.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                        .clickable { onSelect(view) }
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = view.label },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val tint = if (active) ac.violet else onStrip
                    ViewGlyph(view, tint, size = 20.dp)
                    if (labelled) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            view.label,
                            color = tint,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
            // The strip runs on past the last view to the screen edge, as drawn.
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Simple stroked line-art, same convention as `SettingsRoom.kt`'s `TabGlyph`: fractional
 *  coordinates over the drawn size, stroke ~9% of size, no icon font. */
@Composable
private fun ViewGlyph(view: CenterView, tint: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.09f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(tint, Offset(x0, y0), Offset(x1, y1), strokeWidth = sw, cap = StrokeCap.Round)

        when (view) {
            // Speech bubble: the human-readable lens.
            CenterView.CONVERSATION -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.10f, h * 0.14f),
                    size = Size(w * 0.80f, h * 0.60f),
                    cornerRadius = CornerRadius(w * 0.18f),
                    style = stroke,
                )
                val tail = Path().apply {
                    moveTo(w * 0.30f, h * 0.74f)
                    lineTo(w * 0.24f, h * 0.92f)
                    lineTo(w * 0.46f, h * 0.74f)
                    close()
                }
                drawPath(tail, tint)
            }
            // Prompt-and-cursor in a frame: the ground-level raw transcript.
            CenterView.TERMINAL -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.08f, h * 0.14f),
                    size = Size(w * 0.84f, h * 0.72f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = stroke,
                )
                val prompt = Path().apply {
                    moveTo(w * 0.22f, h * 0.36f)
                    lineTo(w * 0.38f, h * 0.50f)
                    lineTo(w * 0.22f, h * 0.64f)
                }
                drawPath(prompt, tint, style = stroke)
                line(w * 0.48f, h * 0.66f, w * 0.72f, h * 0.66f)
            }
            // Stacked layers: what runs underneath.
            CenterView.BACKGROUND -> {
                for (i in 0..2) {
                    val y = h * (0.26f + i * 0.22f)
                    val layer = Path().apply {
                        moveTo(w * 0.14f, y)
                        lineTo(w * 0.50f, y + h * 0.12f)
                        lineTo(w * 0.86f, y)
                    }
                    drawPath(layer, tint, style = stroke)
                }
            }
        }
    }
}
