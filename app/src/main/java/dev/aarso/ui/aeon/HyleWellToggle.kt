package dev.aarso.ui.hyle

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.aarso.ui.theme.LocalHyleColors

/** Authored toggle height; the control keeps the export's 644:320 aspect. */
private val TOGGLE_HEIGHT = 44.dp

/**
 * Two-state toggle: a recessed **well** with a raised **chip** sitting in it.
 *
 * Built from the owner's Figma export rather than a formula. Three details are
 * load-bearing and were each corrected during review, so don't "simplify" them:
 *
 *  1. The well's depth comes from a **violet-tinted** inner shadow, not a black
 *     one and not a stroke — that tint bouncing inside the recess is what reads
 *     as reflectivity. Its alpha is deliberately low (see [SHADOW_ALPHA]).
 *  2. The chip's lit edge is a **gradient** stroke, dark at bottom-left running
 *     to violet at top-right. A flat stroke kills the effect entirely.
 *  3. Selecting the other side rotates the chip a full **180°**, not a
 *     horizontal mirror — that keeps the slant leaning the same way on both
 *     sides. The glint does *not* rotate with it: the light source is fixed.
 */
private const val SHADOW_ALPHA = 0.1625f // owner-set: 35% down from the export's 0.25

@Composable
fun HyleWellToggle(
    optionA: String,
    optionB: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalHyleColors.current
    val haptics = rememberHyleHaptics()
    // Animate the chip across rather than snapping — the slab has weight.
    val t by animateFloatAsState(if (selected == 1) 1f else 0f, label = "wellToggleChip")

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(TOGGLE_HEIGHT),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawWellToggle(
                progress = t,
                well = c.ink,
                chipFill = c.violetDim,
                chipEdgeLo = c.ink,
                chipEdgeHi = c.violet,
                glint = c.textHigh.copy(alpha = if (enabled) 0.85f else 0.35f),
                shadowTint = c.violet,
            )
        }
        Row(Modifier.fillMaxSize()) {
            listOf(optionA, optionB).forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(enabled = enabled) { haptics.tap(); onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            !enabled -> c.textDisabled
                            i == selected -> c.textHigh
                            else -> c.textMid
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Draws well + chip + glint at the export's proportions, scaled to fit.
 * [progress] 0 = chip on the right (the authored state), 1 = rotated 180° to the left.
 */
private fun DrawScope.drawWellToggle(
    progress: Float,
    well: Color,
    chipFill: Color,
    chipEdgeLo: Color,
    chipEdgeHi: Color,
    glint: Color,
    shadowTint: Color,
) {
    val s = size.height / CellPaths.TOGGLE_H
    val r = CellPaths.TOGGLE_RADIUS * s
    val wellPath = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = r,
                radiusY = r,
            ),
        )
    }
    drawPath(wellPath, well)
    // Inner shadow, violet-tinted: clip to the well, then stroke the rim thickly with
    // an offset so only the inside edge catches it. Cheaper than a real blur and
    // reads the same at this radius.
    clipPath(wellPath) {
        translate(4f * s, 4f * s) {
            drawPath(
                wellPath,
                color = shadowTint.copy(alpha = SHADOW_ALPHA),
                style = Stroke(width = 12f * s),
            )
        }
    }

    // Chip. The export places it on the right; progress rotates it about the centre.
    val chip = CellPaths.scaled(
        CellPaths.TOGGLE_CHIP,
        s,
        dx = size.width - CellPaths.TOGGLE_W * s,
    )
    rotate(degrees = 180f * progress, pivot = center) {
        drawPath(chip, chipFill)
        drawPath(
            chip,
            brush = Brush.linearGradient(
                0f to chipEdgeLo,
                1f to chipEdgeHi,
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
            ),
            style = Stroke(width = CellPaths.TOGGLE_STROKE * s),
        )
    }

    // Glint: NOT rotated. The light source stays put even when the chip flips, which
    // is the whole reason the chip rotates rather than mirroring.
    val dx = size.width - CellPaths.TOGGLE_W * s
    drawPath(
        CellPaths.scaled(CellPaths.TOGGLE_GLINT_BRACKET, s, dx = dx),
        color = glint,
        style = Stroke(width = 8f * s, cap = StrokeCap.Round),
    )
    drawCircle(
        color = glint,
        radius = CellPaths.GLINT_DOT_SIZE * s / 2f,
        center = Offset(
            dx + (CellPaths.GLINT_DOT_X + CellPaths.GLINT_DOT_SIZE / 2f) * s,
            (CellPaths.GLINT_DOT_Y + CellPaths.GLINT_DOT_SIZE / 2f) * s,
        ),
    )
}
