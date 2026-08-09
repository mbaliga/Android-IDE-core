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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * the raw ground-level transcript, [BACKGROUND] what's running underneath. Altitude
 * order left→right: the floor above, ground, basement.
 */
enum class CenterView(val label: String) {
    CONVERSATION("Conversation"),
    TERMINAL("Terminal"),
    BACKGROUND("Background tasks"),
}

/**
 * The persistent bottom bar, now the centre room's **view switcher** and nothing else
 * (owner correction to the previous six-room bar: tabs are views of the same place;
 * *rooms* are navigated spatially — edge drags for Chats/Settings/Project/Develop,
 * pinch for Tree and Loops — because a tab metaphor is weaker than the room metaphor
 * the shell is built on; the tabs inside Settings/Chats are a different, in-room kind).
 *
 * Responsive ladder (owner-set): show icon+label on every tab when they fit; else keep
 * the label only on the selected tab; else icons alone. The decision is
 * [CenterTabLayout.stage] over real measured label widths — no truncation, no marquee.
 */
@Composable
fun CenterViewTabBar(
    selected: CenterView,
    onSelect: (CenterView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ac = LocalHyleColors.current
    val views = CenterView.entries
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(ac.ink)
            .height(56.dp),
    ) {
        val density = LocalDensity.current
        val stage = with(density) {
            CenterTabLayout.stage(
                availablePx = maxWidth.toPx(),
                labelWidthsPx = views.map {
                    measurer.measure(it.label, labelStyle).size.width.toFloat()
                },
                selected = views.indexOf(selected),
                iconPx = 22.dp.toPx(),
                gapPx = 6.dp.toPx(),
                paddingPx = 14.dp.toPx(),
            )
        }
        Row(
            Modifier.fillMaxWidth().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            views.forEach { view ->
                val active = view == selected
                val tint = if (active) ac.violet else ac.textMid
                val labelled = when (stage) {
                    CenterTabStage.FULL -> true
                    CenterTabStage.SELECTED_LABEL -> active
                    CenterTabStage.ICONS_ONLY -> false
                }
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(view) }
                        .semantics { contentDescription = view.label },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ViewGlyph(view, tint, size = 22.dp)
                    if (labelled) {
                        Spacer(Modifier.width(6.dp))
                        Text(view.label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
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
