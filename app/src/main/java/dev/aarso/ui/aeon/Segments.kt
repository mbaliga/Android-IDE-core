package dev.aarso.ui.hyle

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.aarso.ui.theme.LocalHyleColors
import kotlin.math.sqrt

/**
 * The **seam grammar** — the slant edge as a shared wall that adjacent cells pack
 * along, not a decoration on one control (owner direction 2026-07-24; references:
 * the Cohere nav's tessellating pills, the owner's Global/Button split action and
 * Global/Toggle). The ASOC Voronoi identity made compositional: cells fit together
 * along parallel `/` seams with a thin strip of ground between them.
 *
 * [HyleSegmentShape] generalises the two existing field silhouettes: every seam
 * leans the same way (slope [SEAM_SLANT_RATIO], same as HyleFieldShape), so a
 * segment whose END is slanted tessellates against a following segment whose START
 * is slanted. Ends of a group keep square-rounded outer corners:
 *
 *   first  = HyleSegmentShape(slantStart = false, slantEnd = true )   ▐███/
 *   middle = HyleSegmentShape(slantStart = true,  slantEnd = true )   /███/
 *   last   = HyleSegmentShape(slantStart = true,  slantEnd = false)   /███▌
 */
private const val SEAM_SLANT_RATIO = 0.25f // same slope as HyleFieldShape — all seams parallel

/** Gap of ground between packed cells — the visible "cell gap" of the identity. */
private val SEAM_GAP = 3.dp

class HyleSegmentShape(
    private val slantStart: Boolean,
    private val slantEnd: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Generic(Path())
        val r = with(density) { 4.dp.toPx() }.coerceAtMost(size.height / 4f)
        val slant = (size.height * SEAM_SLANT_RATIO).coerceAtMost(with(density) { 12.dp.toPx() })
        val len = sqrt(slant * slant + size.height * size.height).coerceAtLeast(1f)
        val dx = r * slant / len
        val dy = r * size.height / len
        val w = size.width
        val h = size.height
        val tlx = if (slantStart) slant else 0f // top-left vertex x (slant leans "/": top inset right)
        val trx = if (slantEnd) w - slant else w // top-right vertex x
        val path = Path().apply {
            moveTo(tlx + r, 0f)
            lineTo(trx - r, 0f)
            if (slantEnd) {
                quadraticBezierTo(trx, 0f, trx + dx, dy)
                lineTo(w - dx, h - dy)
                quadraticBezierTo(w, h, w - r, h)
            } else {
                quadraticBezierTo(w, 0f, w, r)
                lineTo(w, h - r)
                quadraticBezierTo(w, h, w - r, h)
            }
            lineTo(r, h)
            if (slantStart) {
                quadraticBezierTo(0f, h, dx, h - dy)
                lineTo(tlx - dx, dy)
                quadraticBezierTo(tlx, 0f, tlx + r, 0f)
            } else {
                quadraticBezierTo(0f, h, 0f, h - r)
                lineTo(0f, r)
                quadraticBezierTo(0f, 0f, r, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Split action (the owner's Global/Button; Cohere's "TRY NOW" register): one action
 * rendered as two cells packed along a seam — the label cell and a trailing
 * affordance cell ([trailing], default "+"). Both cells fire [onClick] unless
 * [onTrailingClick] gives the affordance its own action. Fill states match
 * [HyleButton] exactly (primary violet / pressed ramp step / secondary raised +
 * hairline / disabled inset).
 */
@Composable
fun HyleSplitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    trailing: String = "+",
    onTrailingClick: (() -> Unit)? = null,
) {
    val c = LocalHyleColors.current
    val haptics = rememberHyleHaptics()

    @Composable
    fun fillFor(pressed: Boolean) = when {
        !enabled -> c.inset
        secondary -> if (pressed) c.inset else c.raised
        pressed -> c.violetPressed
        else -> c.violet
    }

    val content = when {
        !enabled -> c.textDisabled
        secondary -> c.textHigh
        else -> c.onViolet
    }

    Row(modifier = modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(SEAM_GAP)) {
        val leadShape = HyleSegmentShape(slantStart = false, slantEnd = true)
        val leadInteraction = remember { MutableInteractionSource() }
        val leadPressed by leadInteraction.collectIsPressedAsState()
        Box(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(leadShape)
                .background(fillFor(leadPressed), leadShape)
                .then(if (secondary) Modifier.border(1.dp, c.hairline, leadShape) else Modifier)
                .clickable(
                    enabled = enabled,
                    interactionSource = leadInteraction,
                    indication = LocalIndication.current,
                    onClick = { haptics.tap(); onClick() },
                )
                .padding(start = 18.dp, end = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }

        val tailShape = HyleSegmentShape(slantStart = true, slantEnd = false)
        val tailInteraction = remember { MutableInteractionSource() }
        val tailPressed by tailInteraction.collectIsPressedAsState()
        Box(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .width(44.dp)
                .clip(tailShape)
                .background(fillFor(tailPressed), tailShape)
                .then(if (secondary) Modifier.border(1.dp, c.hairline, tailShape) else Modifier)
                .clickable(
                    enabled = enabled,
                    interactionSource = tailInteraction,
                    indication = LocalIndication.current,
                    onClick = { haptics.tap(); (onTrailingClick ?: onClick)() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}

/**
 * Segmented toggle (the owner's Global/Toggle): one rounded container, hairline
 * edge; the SELECTED option is a filled cell whose slant edges are themselves the
 * dividers — first selection slants only its end, a middle one both, the last only
 * its start, so the filled cell always reads as packed against its neighbours.
 * Selection is also carried by the label colour (never hue alone).
 */
@Composable
fun HyleSegmentedToggle(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalHyleColors.current
    val haptics = rememberHyleHaptics()
    val container = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(container)
            .background(c.raised, container)
            .border(1.dp, c.hairline, container),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            val cellShape = HyleSegmentShape(slantStart = i > 0, slantEnd = i < options.lastIndex)
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .then(
                        if (isSelected) {
                            Modifier.clip(cellShape).background(c.violet, cellShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(enabled = enabled && !isSelected, onClick = { haptics.tap(); onSelect(i) })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        !enabled -> c.textDisabled
                        isSelected -> c.onViolet
                        else -> c.textMid
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
