package dev.aarso.ui.spatial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.ui.theme.LocalHyleColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * The persistent bottom bar (redesign brief §1/§9 update — this supersedes the earlier
 * "deliberately no tab bar" stance in [AppRoot]/[SpatialRoot]'s own doc comments): six
 * slots mapped 1:1 onto [SpatialTarget], driven through [SpatialController.open] — the
 * same settle animation an edge-drag already produces, so the bar is a second entry point
 * onto existing, tested navigation rather than a parallel implementation of it.
 *
 * The edge-drag/pinch gestures in [SpatialRoot] are left intact rather than removed: the
 * bar makes navigation discoverable and thumb-reachable, but nothing about a working,
 * hand-tuned gesture system needed to go for that to be true. Loops (pinch OUT) has no
 * slot here by design — six rooms fill six slots exactly; Loops stays a nested/pinch
 * destination reached from the thread or Tree, like today.
 *
 * Icon choice: the reference mockup specified the bar's LAYOUT precisely (slot order,
 * spacing, divider style, label-only-on-the-active-tab convention) but its icon glyphs
 * were too low-resolution to read unambiguously — so glyphs here are drawn fresh in this
 * app's own established idiom (`SettingsRoom.kt`'s `TabGlyph`: simple stroked Canvas line
 * art, no icon font, no Material Icons dependency — this app has neither), chosen for
 * their SEMANTIC fit to each [SpatialTarget] rather than pixel-matched to the reference.
 */
@Composable
fun BottomNavBar(controller: SpatialController, modifier: Modifier = Modifier) {
    val ac = LocalHyleColors.current
    val active = controller.openRoom

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(ac.ink)
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(BarGlyph.CONVERSATIONS, active = active == SpatialTarget.CHATS, ac = ac) {
            controller.open(SpatialTarget.CHATS)
        }
        NavDivider(ac.hairline)
        NavTab(
            label = "Chat",
            glyph = BarGlyph.CHAT,
            active = active == SpatialTarget.HOME,
            ac = ac,
            modifier = Modifier.weight(1f),
        ) { controller.open(SpatialTarget.HOME) }
        NavDivider(ac.hairline)
        NavIcon(BarGlyph.DEVELOP, active = active == SpatialTarget.DEVELOP, ac = ac) {
            controller.open(SpatialTarget.DEVELOP)
        }
        NavDivider(ac.hairline)
        NavIcon(BarGlyph.TREE, active = active == SpatialTarget.TREE, ac = ac) {
            controller.open(SpatialTarget.TREE)
        }
        NavDivider(ac.hairline)
        NavIcon(BarGlyph.PROJECT, active = active == SpatialTarget.PROJECT, ac = ac) {
            controller.open(SpatialTarget.PROJECT)
        }
        Spacer(Modifier.width(10.dp))
        NavIcon(BarGlyph.SETTINGS, active = active == SpatialTarget.SETTINGS, ac = ac) {
            controller.open(SpatialTarget.SETTINGS)
        }
    }
}

@Composable
private fun NavDivider(color: Color) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .background(color),
    )
}

/** Icon-only slot: [BarGlyph.CONVERSATIONS] never reads as "active" itself (it opens a
 *  room rather than being one), everything else tints violet while its room is open. */
@Composable
private fun NavIcon(
    glyph: BarGlyph,
    active: Boolean,
    ac: dev.aarso.ui.theme.HyleColors,
    onClick: () -> Unit,
) {
    val tint = if (active) ac.violet else ac.textMid
    Box(
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BarGlyphCanvas(glyph, tint, size = 22.dp)
    }
}

/** The one labelled slot — Chat, the spatial home. Label shown only while active, same
 *  "label appears on the active tab only" convention the reference mockup uses. */
@Composable
private fun NavTab(
    label: String,
    glyph: BarGlyph,
    active: Boolean,
    ac: dev.aarso.ui.theme.HyleColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (active) ac.violet else ac.textMid
    Row(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        BarGlyphCanvas(glyph, tint, size = 20.dp)
        if (active) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = tint, fontSize = 14.sp, fontWeight = MaterialTheme.typography.labelLarge.fontWeight)
        }
    }
}

private enum class BarGlyph { CONVERSATIONS, CHAT, DEVELOP, TREE, PROJECT, SETTINGS }

/** Simple stroked line-art, matching `SettingsRoom.kt`'s `TabGlyph` convention: fractional
 *  coordinates over the drawn size, stroke width ~9% of size, no filled icon font. */
@Composable
private fun BarGlyphCanvas(glyph: BarGlyph, tint: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.09f
        val stroke = Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(tint, Offset(x0, y0), Offset(x1, y1), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)

        when (glyph) {
            // Three stacked bars — "open the list" (Conversations slides in from the left).
            BarGlyph.CONVERSATIONS -> {
                line(w * 0.14f, h * 0.28f, w * 0.86f, h * 0.28f)
                line(w * 0.14f, h * 0.50f, w * 0.62f, h * 0.50f)
                line(w * 0.14f, h * 0.72f, w * 0.86f, h * 0.72f)
            }
            // Speech bubble: rounded-rect body + a small tail.
            BarGlyph.CHAT -> {
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
            // Code brackets: < >.
            BarGlyph.DEVELOP -> {
                val left = Path().apply {
                    moveTo(w * 0.40f, h * 0.20f)
                    lineTo(w * 0.14f, h * 0.50f)
                    lineTo(w * 0.40f, h * 0.80f)
                }
                val right = Path().apply {
                    moveTo(w * 0.60f, h * 0.20f)
                    lineTo(w * 0.86f, h * 0.50f)
                    lineTo(w * 0.60f, h * 0.80f)
                }
                drawPath(left, tint, style = stroke)
                drawPath(right, tint, style = stroke)
            }
            // Branching tree: root node, two branches, two leaves.
            BarGlyph.TREE -> {
                line(w * 0.50f, h * 0.20f, w * 0.50f, h * 0.42f)
                line(w * 0.50f, h * 0.42f, w * 0.24f, h * 0.62f)
                line(w * 0.50f, h * 0.42f, w * 0.76f, h * 0.62f)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.16f))
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.24f, h * 0.78f))
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.76f, h * 0.78f))
            }
            // Task list: three rows, each with a small leading checkbox square.
            BarGlyph.PROJECT -> {
                for (i in 0..2) {
                    val y = h * (0.24f + i * 0.26f)
                    drawRoundRect(
                        tint,
                        topLeft = Offset(w * 0.12f, y - h * 0.06f),
                        size = Size(w * 0.14f, h * 0.14f),
                        cornerRadius = CornerRadius(w * 0.03f),
                        style = stroke,
                    )
                    line(w * 0.36f, y, w * 0.86f, y)
                }
            }
            // Gear: outer ring + short radiating spokes + inner hub.
            BarGlyph.SETTINGS -> {
                val cx = w * 0.5f; val cy = h * 0.5f
                val rOuter = w * 0.30f
                val rInner = w * 0.14f
                drawCircle(tint, radius = rOuter, center = Offset(cx, cy), style = stroke)
                drawCircle(tint, radius = rInner, center = Offset(cx, cy), style = stroke)
                val spokeInner = rOuter + w * 0.03f
                val spokeOuter = rOuter + w * 0.14f
                for (i in 0 until 6) {
                    val angle = (i * 60.0) * (Math.PI / 180.0)
                    val cos = cos(angle).toFloat(); val sin = sin(angle).toFloat()
                    line(
                        cx + spokeInner * cos, cy + spokeInner * sin,
                        cx + spokeOuter * cos, cy + spokeOuter * sin,
                    )
                }
            }
        }
    }
}
