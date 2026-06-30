package dev.aarso.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.aarso.AarsoApp
import kotlin.math.roundToInt

/** Curated quick-start accents — the "floor" of customization. Each is mid-bright so
 *  the ramp derivation + contrast clamp keep it AA-safe in both light and dark. */
private val ACCENT_PRESETS = listOf(
    "#8E7BFF", // the Aarso violet (default)
    "#4DA3FF", // blue
    "#2DD4BF", // teal
    "#5BD16A", // green
    "#F2B53B", // amber
    "#FF7A66", // coral
    "#FF7AB6", // pink
    "#9AA7B8", // slate
)

/**
 * The appearance engine (Arc-style): theme MODE, an ACCENT (curated presets + a
 * free hue/lightness picker), and a TEXTURE grain — the owner's "make it your own"
 * affordance. Self-contained: reads and writes [dev.aarso.data.SessionStore], which
 * the app root observes, so changes apply live across the whole UI.
 */
@Composable
fun ThemePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = (context.applicationContext as AarsoApp).container.sessionStore
    val c = LocalHyleColors.current

    val modeStr by session.themeMode.collectAsState()
    val accentStr by session.accentColor.collectAsState()
    val texture by session.textureIntensity.collectAsState()

    val accent = parseHexColor(accentStr) ?: Violet
    val hsl = accent.toHsl()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel("Mode", c.textMid)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("System", modeStr == "SYSTEM", c) { session.setThemeMode("SYSTEM") }
            ModeChip("Light", modeStr == "LIGHT", c) { session.setThemeMode("LIGHT") }
            ModeChip("Dark", modeStr == "DARK", c) { session.setThemeMode("DARK") }
        }

        SectionLabel("Accent", c.textMid)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ACCENT_PRESETS.forEach { hex ->
                val selected = accentStr.equals(hex, ignoreCase = true)
                Swatch(parseHexColor(hex)!!, selected, c) { session.setAccentColor(hex) }
            }
        }

        // Free hue + lightness — saturation held high so the bars stay vivid; the ramp
        // clamps for contrast, so any pick is legible.
        GradientBar(
            colors = listOf(0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000).map { Color(it) },
            position = (hsl[0] / 360f),
            onPosition = { p -> session.setAccentColor(hslColor(p * 360f, 0.72f, hsl[2].coerceIn(0.35f, 0.75f)).toHexRgb()) },
        )
        GradientBar(
            colors = listOf(hslColor(hsl[0], 0.72f, 0.15f), hslColor(hsl[0], 0.72f, 0.55f), hslColor(hsl[0], 0.72f, 0.9f)),
            position = ((hsl[2] - 0.15f) / 0.75f).coerceIn(0f, 1f),
            onPosition = { p -> session.setAccentColor(hslColor(hsl[0], 0.72f, 0.15f + p * 0.75f).toHexRgb()) },
        )

        SectionLabel("Texture", c.textMid)
        Slider(
            value = texture,
            onValueChange = { session.setTextureIntensity(it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = c.violet,
                activeTrackColor = c.violet,
                inactiveTrackColor = c.inset,
            ),
        )

        SectionLabel("Ambient gradient", c.textMid)
        val gradientStr by session.gradientColor.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip("Off", gradientStr.isBlank(), c) { session.setGradientColor("") }
            // The accent is the first stop; offer the rest as the second stop.
            ACCENT_PRESETS.drop(1).forEach { hex ->
                Swatch(parseHexColor(hex)!!, gradientStr.equals(hex, ignoreCase = true), c) {
                    session.setGradientColor(hex)
                }
            }
        }
        if (gradientStr.isNotBlank()) {
            parseHexColor(gradientStr)?.let { stop2 ->
                Box(
                    Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(listOf(accent, stop2))),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun ModeChip(label: String, selected: Boolean, c: HyleColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.violetDim else Color.Transparent)
            .border(1.dp, if (selected) c.violet else c.hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.violet else c.textMid,
        )
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, c: HyleColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color, CircleShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.textHigh else c.hairline, CircleShape)
            .clickable(onClick = onClick),
    )
}

/** A draggable gradient bar (hue or lightness). Tap or drag anywhere to set [position]. */
@Composable
private fun GradientBar(
    colors: List<Color>,
    position: Float,
    onPosition: (Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(colors))
            .pointerInput(Unit) {
                detectTapGestures { offset -> onPosition((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onPosition((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val thumbX = (maxWidth - 6.dp) * position.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .fillMaxHeight()
                .width(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
                .border(1.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
        )
    }
}
