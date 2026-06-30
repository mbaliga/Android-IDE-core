package dev.aarso.hyleprobe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Hyle dark palette (locked — canonical values) ─────────────────────────
private val Ink = Color(0xFF0E0F12)
private val Raised = Color(0xFF16181D)
private val Inset = Color(0xFF20242B)
private val OutlineColor = Color(0xFF262A31)
private val Hairline = Color(0x24ECEDEF)   // 14 % alpha white
private val Violet = Color(0xFF8E7BFF)
private val VioletPressed = Color(0xFF7262CC)
private val VioletDim = Color(0xFF2A2541)
private val OnViolet = Color(0xFF160F2E)
private val TextHigh = Color(0xFFECEDEF)
private val TextMid = Color(0xFF9CA3AF)
private val TextDisabled = Color(0xFF4A4E57)

// ── Spec constants (locked from owner pixel loop) ─────────────────────────
private const val SLANT = 0.2f        // slope (Δx / Δy) for every slanted edge
private const val CORNER_DP = 3.5f    // rounded-parallelogram radius (spec rx≈3.5)

// ── Shapes — slanted, but with ROUNDED corners (not sharp points) ─────────

private fun Offset.unit(): Offset {
    val l = getDistance()
    return if (l == 0f) this else this / l
}

/** Trace a polygon whose corners are rounded to radius [r] by pulling back [r]
 *  along each edge and sweeping a quadratic through the original vertex. The one
 *  primitive behind every slanted Hyle atom — parallelogram, candy trapezoid. */
private fun Path.roundedPolygon(pts: List<Offset>, r: Float) {
    for (i in pts.indices) {
        val curr = pts[i]
        val prev = pts[(i + pts.size - 1) % pts.size]
        val next = pts[(i + 1) % pts.size]
        val enter = curr + (prev - curr).unit() * r
        val exit = curr + (next - curr).unit() * r
        if (i == 0) moveTo(enter.x, enter.y) else lineTo(enter.x, enter.y)
        quadraticBezierTo(curr.x, curr.y, exit.x, exit.y)
    }
    close()
}

/** Parallelogram (both edges slant at SLANT), corners rounded to [cornerDp]. */
private class RoundedParallelogram(private val cornerDp: Float = CORNER_DP) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerDp.dp.toPx() }
        val s = size.height * SLANT
        val pts = listOf(
            Offset(s, 0f),
            Offset(size.width, 0f),
            Offset(size.width - s, size.height),
            Offset(0f, size.height),
        )
        return Outline.Generic(Path().apply { roundedPolygon(pts, r) })
    }
}

/** Candy/shoulder trapezoid: left edge vertical, right edge slants outward at the
 *  bottom; corners rounded so it reads as a soft sweet, not a sharp wedge. */
private class RoundedCandyBack(private val cornerDp: Float = CORNER_DP) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerDp.dp.toPx() }
        val s = size.height * SLANT
        val pts = listOf(
            Offset(0f, 0f),
            Offset(size.width - s, 0f),
            Offset(size.width, size.height),
            Offset(0f, size.height),
        )
        return Outline.Generic(Path().apply { roundedPolygon(pts, r) })
    }
}

private val ParallelogramShape: Shape = RoundedParallelogram()
private val CandyBackShape: Shape = RoundedCandyBack()

// ── Probe ─────────────────────────────────────────────────────────────────

/**
 * Side-by-side comparison of the locked Hyle atoms (spec values: slope 0.2,
 * rx=6 buttons, rx≈3.5 count chips) alongside the current app implementations
 * where they differ — so the feel can be verified on device before the Compose
 * translation lands in the main app.
 */
@Composable
fun HyleAtomsProbe() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Text("Hyle atoms", color = TextHigh, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        // Buttons — flat (today) vs candy (material direction the owner wants)
        AtomSection("Buttons — flat vs candy") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("flat")
                    PrimaryButton("Primary")
                    SecondaryButton("Secondary")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("candy")
                    CandyPrimaryButton("Primary")
                    CandySecondaryButton("Secondary")
                }
            }
        }

        // ── Chips ────────────────────────────────────────────────────
        AtomSection("Selection chips") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("current (rx=6)")
                    CurrentChip("All", selected = true)
                    CurrentChip("Running", selected = false)
                    CurrentChip("Retired", selected = false)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("spec (0.2 slant)")
                    SpecChip("All", selected = true)
                    SpecChip("Running", selected = false)
                    SpecChip("Retired", selected = false)
                }
            }
        }

        // ── Count chips ───────────────────────────────────────────────
        AtomSection("Count chips — parallelogram, rx≈3.5") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountChip("3")
                CountChip("12")
                CountChip("99+")
            }
        }

        // ── Slant separator ───────────────────────────────────────────
        AtomSection("Slant separator — slope 0.2") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loops", color = TextMid, fontSize = 14.sp)
                SlantSep()
                Text("main", color = TextMid, fontSize = 14.sp)
                SlantSep()
                Text("Running", color = Violet, fontSize = 14.sp)
            }
        }

        // ── Candy back ────────────────────────────────────────────────
        AtomSection("Back button (candy/shoulder) — spec") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CandyBack("Back")
                CandyBack("Loops")
            }
        }

        // ── Tab row ───────────────────────────────────────────────────
        AtomSection("Tab row — indicator ON divider") {
            var tab by remember { mutableIntStateOf(0) }
            SpecTabRow(
                tabs = listOf("All" to null, "Running" to "2", "Retired" to null),
                selected = tab,
                onSelect = { tab = it },
            )
        }

        // ── Log entries ───────────────────────────────────────────────
        AtomSection("Log entries — backing layer behind bullet") {
            Column {
                LogEntry("Propose a fix for the login bug", selected = false)
                LogEntry("Review the API changes", selected = true)
                LogEntry("Update the failing tests", selected = false)
                LogEntry("Ship to staging branch", selected = false)
            }
        }
    }
}

// ── Buttons ───────────────────────────────────────────────────────────────

@Composable
private fun PrimaryButton(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Violet)
            .clickable {}
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = OnViolet, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SecondaryButton(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Raised)
            .border(1.dp, Hairline, RoundedCornerShape(6.dp))
            .clickable {}
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TextHigh, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Candy/material primary: a glossy violet pill — vertical light→dark body gradient
 *  + a specular sky sheen across the top, so it reads as a dimensional sweet rather
 *  than a flat fill. Same rx=6 footprint. */
@Composable
private fun CandyPrimaryButton(label: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFA493FF), Violet, VioletPressed)))
            .clickable {}
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // specular sheen — bright at the very top, gone by the middle (the 'wet' reflection)
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x40FFFFFF), 0.45f to Color(0x00FFFFFF), 1f to Color(0x00FFFFFF),
                    ),
                ),
        )
        Text(label, color = OnViolet, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Candy/material secondary: the same gloss on the dark raised body. */
@Composable
private fun CandySecondaryButton(label: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF252932), Raised, Color(0xFF0F1115))))
            .border(1.dp, Hairline, shape)
            .clickable {}
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x1FFFFFFF), 0.45f to Color(0x00FFFFFF), 1f to Color(0x00FFFFFF),
                    ),
                ),
        )
        Text(label, color = TextHigh, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Chips ─────────────────────────────────────────────────────────────────

/** Current implementation — standard rx=6 rounded rect (no slant). */
@Composable
private fun CurrentChip(label: String, selected: Boolean) {
    val bg = if (selected) VioletDim else Color.Transparent
    val border = if (selected) Violet else Hairline
    val text = if (selected) Violet else TextMid
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable {}
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = text, fontSize = 13.sp)
    }
}

/** Spec chip — parallelogram with 0.2 slant (extra horizontal padding
 *  compensates for the shear so text isn't clipped). */
@Composable
private fun SpecChip(label: String, selected: Boolean) {
    val bg = if (selected) VioletDim else Color.Transparent
    val border = if (selected) Violet else Hairline
    val text = if (selected) Violet else TextMid
    // Clip then draw the border on top inside a Box so the border follows the shape.
    Box(
        Modifier
            .clip(ParallelogramShape)
            .background(bg)
            .border(1.dp, border, ParallelogramShape)
            .clickable {}
            .padding(horizontal = 16.dp, vertical = 6.dp),   // wider to accommodate shear
    ) {
        Text(label, color = text, fontSize = 13.sp)
    }
}

/** Count chip — compact parallelogram, typically shows a number. */
@Composable
private fun CountChip(count: String) {
    Box(
        Modifier
            .clip(ParallelogramShape)
            .background(VioletDim)
            .border(1.dp, Violet.copy(alpha = 0.5f), ParallelogramShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count, color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Slant separator ────────────────────────────────────────────────────────

/** "/" rendered as a slant line matching slope 0.2, used between breadcrumbs. */
@Composable
private fun SlantSep() {
    Box(
        Modifier
            .size(width = 16.dp, height = 20.dp)
            .drawBehind {
                val shear = size.height * SLANT
                drawLine(
                    color = TextDisabled,
                    start = Offset(size.width / 2f + shear / 2, 0f),
                    end = Offset(size.width / 2f - shear / 2, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            },
    )
}

// ── Candy/shoulder back button ─────────────────────────────────────────────

/** Trapezoid with a small chevron inside. Right edge slants at slope 0.2. Glossy body
 *  (gradient + top sheen) so the "candy" name is earned, not just the shoulder shape. */
@Composable
private fun CandyBack(label: String) {
    Box(
        Modifier
            .clip(CandyBackShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF252932), Raised, Color(0xFF0F1115))))
            .border(1.dp, Hairline, CandyBackShape)
            .clickable {}
            .padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CandyBackShape)
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x1FFFFFFF), 0.45f to Color(0x00FFFFFF), 1f to Color(0x00FFFFFF),
                    ),
                ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", color = TextMid, fontSize = 16.sp, fontWeight = FontWeight.Light)
            Text(label, color = TextMid, fontSize = 13.sp)
        }
    }
}

// ── Tab row with indicator ON divider ──────────────────────────────────────

/**
 * Tab row where the active indicator sits ON the 1dp grey divider line,
 * spanning both the text label and the count chip below it — not below the
 * divider as standard Material tabs do.
 *
 * [tabs]: pairs of (label, countChip?). Count chip is null for no chip.
 */
@Composable
private fun SpecTabRow(
    tabs: List<Pair<String, String?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val indicatorH = 2.dp

    Box(Modifier.fillMaxWidth()) {
        // Content: tab labels + optional count chips
        Row(Modifier.fillMaxWidth().padding(bottom = indicatorH)) {
            tabs.forEachIndexed { i, (label, count) ->
                val isActive = i == selected
                val labelColor = if (isActive) TextHigh else TextMid
                Column(
                    Modifier.weight(1f).clickable { onSelect(i) }.padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, color = labelColor, fontSize = 13.sp)
                    if (count != null) {
                        Box(Modifier.padding(top = 4.dp)) {
                            CountChip(count)
                        }
                    }
                }
            }
        }

        // Grey divider sitting below the tab content
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OutlineColor)
                .align(Alignment.BottomStart),
        )

        // Active indicator ON the divider: drawn at the selected tab's x-offset,
        // same width as one tab. The 2dp height overlaps the 1dp grey divider,
        // so the indicator visually sits on rather than below the divider.
        Box(
            Modifier
                .fillMaxWidth()
                .height(indicatorH)
                .align(Alignment.BottomStart)
                .drawBehind {
                    val w = size.width / tabs.size
                    drawRect(
                        color = Violet,
                        topLeft = Offset(selected * w, 0f),
                        size = Size(w, size.height),
                    )
                },
        )
    }
}

// ── Log entries with backing layer ────────────────────────────────────────

/**
 * A single log/run entry. Selected state shows a backing layer that wraps
 * BEHIND the white bullet (•) with balanced left and right margins — the
 * bullet appears to float in front of the selection layer.
 */
@Composable
private fun LogEntry(text: String, selected: Boolean) {
    val bulletColor = if (selected) TextHigh else TextDisabled
    Box(
        Modifier
            .fillMaxWidth()
            .clickable {},
    ) {
        // Backing layer: slightly inset from both edges so it sits behind the bullet
        if (selected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Inset),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bullet in front of the backing layer
            Text(
                "•",
                color = if (selected) Violet else bulletColor,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text,
                color = if (selected) TextHigh else TextMid,
                fontSize = 13.sp,
            )
        }
    }
}

// ── Layout helpers ────────────────────────────────────────────────────────

@Composable
private fun AtomSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = TextDisabled, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextDisabled,
        fontSize = 10.sp,
        modifier = Modifier.width(72.dp),
    )
}
