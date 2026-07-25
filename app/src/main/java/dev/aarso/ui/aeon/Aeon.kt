package dev.aarso.ui.hyle

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.aarso.ui.theme.LocalHyleColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Aeon component layer, built from the owner's Figma exports (geometry
 * measured off the selector-state SVGs and the Buttons sheet, dark-remapped):
 *
 * - Selector: label ABOVE the box (12/600), container with the Aeon signature
 *   slanted LEFT edge (~0.25 slope) and a 2dp accent bar inset at the RIGHT
 *   (present in every state), 4dp corners, a mandatory star riding the slant edge.
 * - Buttons: 6dp-radius rounded rects, 40dp tall — not M3 pills. Primary =
 *   violet fill (pressed = the darker ramp step); secondary = raised + hairline.
 *
 * Colours come from [LocalHyleColors] (runtime dark/light + accent), never raw
 * tokens, so the whole layer re-themes with the user's choice.
 */

private const val FIELD_SLANT_RATIO = 0.25f // measured: 6px run over the 24px straight edge → slope 0.25

/** Shared height for every field-like Aeon control (inputs AND dropdowns), so they
 *  line up. Matches the 32px design box. */
private val FIELD_MIN_HEIGHT = 32.dp

/** The Aeon selector silhouette now lives in CellGeometry.kt, transcribed exactly
 *  from the owner's export rather than approximated by a radius/slant formula. */

/** Mirror of HyleFieldShape: slant on the RIGHT edge (for left-side nav chips). */
internal object HyleRightSlantShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Generic(Path())
        val r = with(density) { 4.dp.toPx() }.coerceAtMost(size.height / 4f)
        val slant = (size.height * FIELD_SLANT_RATIO).coerceAtMost(with(density) { 12.dp.toPx() })
        val len = kotlin.math.sqrt(slant * slant + size.height * size.height).coerceAtLeast(1f)
        val dx = r * slant / len
        val dy = r * size.height / len
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - slant - r, 0f)
            quadraticBezierTo(w - slant, 0f, w - slant + dx, dy)
            lineTo(w - dx, h - dy)
            quadraticBezierTo(w, h, w - r, h)
            lineTo(r, h)
            quadraticBezierTo(0f, h, 0f, h - r)
            lineTo(0f, r)
            quadraticBezierTo(0f, 0f, r, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Room title: display weight, hard to the top-left, generous space beneath. */
@Composable
fun HyleTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = LocalHyleColors.current.textHigh,
        modifier = modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 16.dp),
    )
}

/** Label sits tight above the box. No asterisk here — the mandatory marker lives
 *  inside the field (right edge), so repeating it in the label is redundant. */
@Composable
private fun HyleLabelRow(label: String) {
    val c = LocalHyleColors.current
    Row(modifier = Modifier.padding(bottom = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMid)
    }
}

/**
 * The shared Aeon container. Three layers, all transcribed from the owner's export:
 * the slanted fill, a **gradient ring** whose colour encodes state (a flat stroke
 * reads dead — the ramp is what makes the edge look lit), and the marker riding the
 * slant. The marker is a single pill in every state *except* error, where the same
 * silhouette splits into a literal exclamation (stem + dot). The mandatory asterisk
 * is right-anchored, five-point.
 *
 * [ringStart]/[ringEnd] run left→right across the box. [markerColor] null hides the
 * slant marker entirely; [asteriskColor] null hides the required mark.
 */
private fun Modifier.hyleContainer(
    fieldColor: Color,
    ringStart: Color,
    ringEnd: Color,
    markerColor: Color? = null,
    splitMarker: Boolean = false,
    asteriskColor: Color? = null,
): Modifier = this
    .background(fieldColor, HyleFieldShape)
    .drawBehind {
        val s = size.height / CellPaths.FIELD_H
        // Ring: the authored 4-unit stroke, floored at 1dp so it survives on a short
        // field at low density (the source is authored 10x larger than we render).
        val ringWidth = (CellPaths.FIELD_RING_STROKE * s).coerceAtLeast(1.dp.toPx())
        val outline = HyleFieldShape.createOutline(size, layoutDirection, this)
        if (outline is Outline.Generic) {
            drawPath(
                path = outline.path,
                brush = Brush.horizontalGradient(
                    0f to ringStart,
                    1f to ringEnd,
                    startX = 0f,
                    endX = size.width,
                ),
                style = Stroke(width = ringWidth),
            )
        }
        // Slant marker, left-anchored in the source canvas.
        markerColor?.let { mc ->
            if (splitMarker) {
                drawPath(CellPaths.scaled(CellPaths.MARKER_STEM, s), mc)
                drawPath(CellPaths.scaled(CellPaths.MARKER_DOT, s), mc)
            } else {
                drawPath(CellPaths.scaled(CellPaths.MARKER_PILL, s), mc)
            }
        }
        // Required asterisk, right-anchored: shifting by (w - FIELD_W * s) keeps it the
        // same distance in from the right edge as it sits in the export.
        asteriskColor?.let { ac ->
            drawPath(
                CellPaths.scaled(
                    CellPaths.FIELD_ASTERISK,
                    s,
                    dx = size.width - CellPaths.FIELD_W * s,
                ),
                ac,
            )
        }
    }

/**
 * Aeon selector: label above the box, slanted-edge container, accent bar at the
 * right, error caption beneath. States: empty/filled/disabled/error.
 */
@Composable
fun HyleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mandatory: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = LocalHyleColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Ring runs left→right; the state lives in the ramp, not a flat colour.
    val ringStart = when {
        error != null -> c.error
        !enabled -> c.textHigh.copy(alpha = 0.14f)
        focused -> c.violet
        else -> c.textHigh.copy(alpha = 0.30f)
    }
    val ringEnd = when {
        error != null -> c.error.copy(alpha = 0.35f)
        !enabled -> c.textHigh.copy(alpha = 0.06f)
        focused -> c.violetHover
        else -> c.textHigh.copy(alpha = 0.08f)
    }
    // The slant marker is present in EVERY state — the default just mutes it rather
    // than dropping it. Error is the only state that splits it into an exclamation.
    val markerColor = when {
        error != null -> c.error
        !enabled -> c.textDisabled
        focused -> c.violet
        else -> c.textMid
    }
    val asteriskColor = when {
        !mandatory -> null
        error != null -> c.error
        !enabled -> c.textDisabled
        else -> c.violet
    }
    Column(modifier) {
        if (label.isNotBlank()) HyleLabelRow(label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FIELD_MIN_HEIGHT)
                .hyleContainer(
                    fieldColor = c.inset,
                    ringStart = ringStart,
                    ringEnd = ringEnd,
                    markerColor = markerColor,
                    splitMarker = error != null,
                    asteriskColor = asteriskColor,
                )
                // Start clears the slant; end clears the asterisk when one is shown.
                // top > bottom: optical centering — Latin text geometrically centred
                // reads high, and the export sets its baseline low in the box.
                .padding(
                    start = 22.dp,
                    end = if (mandatory) 36.dp else 18.dp,
                    top = 7.dp,
                    bottom = 3.dp,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 5,
                // Input text reads at bodyLarge (16sp) — the export shows text ~half the
                // field height, much larger than the 14sp body default.
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) c.textHigh else c.textDisabled,
                ),
                cursorBrush = SolidColor(c.violet),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.textMid.copy(alpha = 0.75f),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = c.error,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp),
            )
        }
    }
}

/** Aeon selector with a dropdown: same container, caret in the accent. */
@Composable
fun HyleDropdownField(
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mandatory: Boolean = false,
) {
    val c = LocalHyleColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotBlank()) HyleLabelRow(label)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FIELD_MIN_HEIGHT)
                    .hyleContainer(
                        fieldColor = c.inset,
                        ringStart = c.textHigh.copy(alpha = 0.30f),
                        ringEnd = c.textHigh.copy(alpha = 0.08f),
                        markerColor = c.textMid,
                        asteriskColor = if (mandatory) c.violet else null,
                    )
                    .clickable { expanded = true }
                    .padding(start = 22.dp, end = 14.dp, top = 7.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.textHigh,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", style = MaterialTheme.typography.bodyLarge, color = c.violet)
                Spacer(Modifier.width(8.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { onSelect(i); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * Aeon button (Buttons sheet): 6dp corners, 40dp tall. Primary = violet fill,
 * pressed steps down the ramp; secondary = raised fill with a hairline edge.
 */
@Composable
fun HyleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
) {
    val c = LocalHyleColors.current
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    val fill = when {
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
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(fill)
            .then(if (secondary) Modifier.border(1.dp, c.hairline, shape) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = { haptics.tap(); onClick() },
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * Aeon card: raised fill, hairline edge, 10dp corners — the box register for grouped content.
 * Optionally clickable/selectable (e.g. a picker row) — [onClick] makes it tappable, and
 * [selected] swaps the fill to a violet-dim tint with a violet border so a selected card reads
 * distinctly without touching hue outside the violet/cyan axis.
 */
@Composable
fun HyleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHyleColors.current
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.violetDim else c.raised, shape)
            .border(1.dp, if (selected) c.violet else c.hairline, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = { haptics.tap(); onClick() })
                } else {
                    Modifier
                },
            )
            .padding(14.dp),
        content = content,
    )
}

/** One entry in a [HyleTabBar]: a label and a small hand-drawn Canvas glyph, tinted by the bar. */
class HyleTabSpec(val label: String, val glyph: DrawScope.(tint: Color) -> Unit)

/**
 * The app's one fixed-width tab bar, in the **seam grammar** (the owner's designs are the
 * canonical reference, 2026-07-24): EVERY tab is its own cell, packed along parallel `/`
 * seams with a thin strip of ground between — no slash dividers, no strip; the cells
 * themselves are the structure. Selection is carried by the cell's fill (the violet-on-dim-
 * violet selection register, as chips/cards) AND the label/glyph tint — never hue alone; the
 * old 2dp underline is superseded by the fill. Tabs stay equal-weight and never scroll —
 * first built for [dev.aarso.ui.rooms.SettingsRoom]'s SettingsTabBar, consolidated here so
 * every screen with a small fixed set of top-level categories uses the same widget.
 * [trailing] is an optional fixed-width slot after the tabs (e.g. a filter-toggle icon) —
 * always reserve its space in the caller rather than conditionally including it, or the tabs
 * will visibly resize when it appears/disappears (the exact "tab bar jitter" bug this bar's
 * callers have already hit once).
 *
 * [position] is `"TOP"` or `"BOTTOM"` (matching every other string-enum preference in
 * [dev.aarso.data.SessionStore], e.g. `themeMode`) — it only flips the divider to the edge that
 * faces away from this bar's own content (top: divider below the tabs; bottom: divider above
 * them), so the bar reads correctly wherever it's placed. It does NOT move the bar within the
 * caller's layout — a room wanting its tab bar to sit at the screen's bottom edge, not just have
 * a bottom-facing divider, must place this composable last in its own Column (see
 * [dev.aarso.data.SessionStore.tabBarPositionFor] for the per-room-overridable preference each
 * caller should read to decide that placement).
 */
@Composable
fun HyleTabBar(
    tabs: List<HyleTabSpec>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    position: String = "TOP",
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalHyleColors.current
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    val tabRow: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight(),
                // Boxes overlap by (slant − seam gap) so the parallel `/` seams read as a
                // constant 3dp of ground; at tab-bar height the slant run caps at 12dp.
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((-9).dp),
            ) {
                tabs.forEachIndexed { i, tab ->
                    val on = i == selected
                    val tint = if (on) c.violet else c.textMid
                    // Every tab is a cell; outer edges of the group stay square-rounded,
                    // every seam toward a neighbour (or the trailing slot) is slanted.
                    val cellShape = HyleSegmentShape(
                        slantStart = i > 0,
                        slantEnd = i < tabs.lastIndex || trailing != null,
                    )
                    Column(
                        modifier = Modifier.weight(1f)
                            .fillMaxHeight()
                            .clip(cellShape)
                            .background(if (on) c.violetDim else c.raised, cellShape)
                            .clickable { haptics.tap(); onSelect(i) }
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Canvas(Modifier.size(22.dp)) { tab.glyph(this, tint) }
                        Spacer(Modifier.height(5.dp))
                        Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                    }
                }
            }
            trailing?.invoke()
        }
    }
    Column(modifier) {
        if (position == "BOTTOM") {
            HorizontalDivider()
            tabRow()
        } else {
            tabRow()
            HorizontalDivider()
        }
    }
}

/** Aeon chip: the selection register — violet on dim violet, 6dp corners. */
@Composable
fun HyleChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalHyleColors.current
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) c.violetDim else Color.Transparent)
            .border(1.dp, if (selected) c.violet else c.hairline, shape)
            .clickable(enabled = enabled, onClick = { haptics.tap(); onClick() })
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> c.textDisabled
                selected -> c.violet
                else -> c.textMid
            },
            maxLines = 1,
        )
    }
}

/**
 * Aeon "candy" navigation chip: compact slant-edged button for the chat header.
 * [slantLeft] = slant on the left edge (Settings, right side of header — HyleFieldShape).
 * [slantLeft] = false → slant on the RIGHT edge (Chats/Back, left side — HyleRightSlantShape).
 */
@Composable
fun HyleNavChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slantLeft: Boolean = true,
    contentDescription: String? = null,
) {
    val c = LocalHyleColors.current
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    val shape = if (slantLeft) HyleFieldShape else HyleRightSlantShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (pressed) c.inset else c.raised, shape)
            .border(1.dp, c.hairline, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = { haptics.tap(); onClick() })
            .padding(horizontal = 14.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMid, maxLines = 1)
    }
}

/**
 * Generated monogram tile (brief §10): the clean-room family mark. Every model
 * gets one — bundling third-party brand art is deliberately avoided, so
 * unbranded models look exactly as finished as branded ones.
 */
@Composable
fun MonogramTile(name: String, modifier: Modifier = Modifier) {
    val c = LocalHyleColors.current
    val letter = name.firstOrNull { it.isLetter() }?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(40.dp)
            .background(c.violetDim, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleMedium,
            color = c.violet,
        )
    }
}

/** Decodes a file to a bitmap off the main thread (no image library dependency). */
@Composable
fun FileImage(path: String, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            runCatching { BitmapFactory.decodeFile(path, opts)?.asImageBitmap() }.getOrNull()
        }
    }
    val image = bmp
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier) { Text("…", modifier = Modifier.padding(8.dp)) }
    }
}
