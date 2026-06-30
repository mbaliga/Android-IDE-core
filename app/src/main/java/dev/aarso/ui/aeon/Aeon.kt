package dev.aarso.ui.hyle

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
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

/** The Aeon selector silhouette: rounded rect whose left edge leans right (slant on
 *  left). The bottom-left corner — where the slant meets the base — is rounded more
 *  than the other three, per the design. */
private object HyleFieldShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Generic(Path())
        val r = with(density) { 4.dp.toPx() }.coerceAtMost(size.height / 4f)
        val tlr = with(density) { 5.5.dp.toPx() }.coerceAtMost(size.height / 3f) // top-left: a touch more
        val blr = with(density) { 7.dp.toPx() }.coerceAtMost(size.height / 2f) // bottom-left: rounder
        val slant = (size.height * FIELD_SLANT_RATIO).coerceAtMost(with(density) { 12.dp.toPx() })
        val len = kotlin.math.sqrt(slant * slant + size.height * size.height).coerceAtLeast(1f)
        val tdx = tlr * slant / len
        val tdy = tlr * size.height / len
        val bdx = blr * slant / len
        val bdy = blr * size.height / len
        val path = Path().apply {
            moveTo(slant + tlr, 0f)
            lineTo(size.width - r, 0f)
            quadraticBezierTo(size.width, 0f, size.width, r)
            lineTo(size.width, size.height - r)
            quadraticBezierTo(size.width, size.height, size.width - r, size.height)
            lineTo(blr, size.height)
            quadraticBezierTo(0f, size.height, bdx, size.height - bdy)
            lineTo(slant - tdx, tdy)
            quadraticBezierTo(slant, 0f, slant + tlr, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

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

@Composable
private fun HyleLabelRow(label: String, mandatory: Boolean) {
    val c = LocalHyleColors.current
    Row(modifier = Modifier.padding(bottom = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMid)
        if (mandatory) {
            Text(" *", style = MaterialTheme.typography.labelMedium, color = c.violet)
        }
    }
}

/** The shared Aeon container: slanted shape, fill, accent bar, and (when mandatory)
 *  the 5-point asterisk. NO border — the field reads as a lighter fill on the ground,
 *  per the design. [fieldColor] is the fill (also the asterisk's feather/halo colour),
 *  passed in because a Modifier extension can't read a CompositionLocal. */
private fun Modifier.hyleContainer(
    barColor: Color,
    fieldColor: Color,
    showErrorMark: Boolean = false,
    mandatoryColor: Color? = null,
): Modifier = this
    .background(fieldColor, HyleFieldShape)
    .drawBehind {
        // Accent bar / ! mark. SVG: x = fieldRight − 4px in a 32px field → 6dp.
        val x = size.width - 6.dp.toPx()
        val inset = size.height * 0.125f  // SVG: bar inset 4px in 32px field = 12.5% of H.
        if (showErrorMark) {
            // '!' proportions: stem 21.9%–59.4%, dot at 76.6%, strokeWidth 3dp (SVG-measured).
            drawLine(
                color = barColor,
                start = Offset(x, size.height * 0.219f),
                end = Offset(x, size.height * 0.594f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = barColor, radius = 1.5.dp.toPx(), center = Offset(x, size.height * 0.766f))
        } else {
            drawLine(
                color = barColor,
                start = Offset(x, inset),
                end = Offset(x, size.height - inset),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        // Mandatory marker: a 5-POINT asterisk (five arms, point-up, gap at the bottom)
        // riding the slanted left edge. HARD (square-cut) arm tips. Each arm carries a
        // field-colour feather/halo — drawn a touch longer and wider underneath — so the
        // mark blends where it overlaps the field and reads cleanly where it overflows
        // onto the darker ground ("at home with the field").
        if (mandatoryColor != null) {
            val slant = (size.height * FIELD_SLANT_RATIO).coerceAtMost(12.dp.toPx())
            val cx = slant * 0.45f
            val cy = size.height * 0.30f
            val armLen = size.height * 0.15f
            val armW = 2.2.dp.toPx()
            val feather = 1.8.dp.toPx()
            val centre = Offset(cx, cy)
            // Feather pass: field colour, a feather longer at each tip and wider on each
            // side, hard butt caps — wraps the whole mark.
            for (i in 0 until 5) {
                val a = Math.PI / 2.0 + i * 2.0 * Math.PI / 5.0
                val r = armLen + feather
                val haloTip = Offset(cx + (r * cos(a)).toFloat(), cy - (r * sin(a)).toFloat())
                drawLine(fieldColor, centre, haloTip, armW + 2f * feather, StrokeCap.Butt)
            }
            // Arm pass: marker colour, hard butt caps → square-cut tips.
            for (i in 0 until 5) {
                val a = Math.PI / 2.0 + i * 2.0 * Math.PI / 5.0
                val tip = Offset(cx + (armLen * cos(a)).toFloat(), cy - (armLen * sin(a)).toFloat())
                drawLine(mandatoryColor, centre, tip, armW, StrokeCap.Butt)
            }
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
    val barColor = when {
        error != null -> c.error
        !enabled -> c.textDisabled
        focused -> c.violet
        else -> c.violet.copy(alpha = 0.4f)
    }
    // Mandatory marker colour, shown in every mandatory state — incl. disabled, where
    // the design greys it (NOT hidden), matching the disabled SVG/PNG export.
    val mandatoryColor = when {
        !mandatory -> null
        error != null -> c.error
        !enabled -> c.textDisabled
        else -> c.violet
    }
    Column(modifier) {
        if (label.isNotBlank()) HyleLabelRow(label, false)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FIELD_MIN_HEIGHT)
                .hyleContainer(barColor, c.inset, showErrorMark = error != null, mandatoryColor = mandatoryColor)
                // top > bottom by ~2dp: optical centering — Latin text geometrically
                // centred reads a touch high, so nudge it down.
                .padding(start = 18.dp, end = 18.dp, top = 5.dp, bottom = 3.dp),
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
        if (label.isNotBlank()) HyleLabelRow(label, mandatory)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FIELD_MIN_HEIGHT)
                    .hyleContainer(barColor = c.violet, fieldColor = c.inset)
                    .clickable { expanded = true }
                    .padding(start = 18.dp, end = 14.dp, top = 5.dp, bottom = 3.dp),
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
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
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
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) c.violetDim else Color.Transparent)
            .border(1.dp, if (selected) c.violet else c.hairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
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
    val shape = if (slantLeft) HyleFieldShape else HyleRightSlantShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (pressed) c.inset else c.raised, shape)
            .border(1.dp, c.hairline, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
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
