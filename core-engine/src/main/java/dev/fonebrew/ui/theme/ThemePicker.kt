package dev.fonebrew.ui.theme

import dev.aarso.hyle.cells.HyleSlider
import dev.aarso.hyle.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.fonebrew.FonebrewApp
import dev.aarso.hyle.cells.HyleColorPicker3D

/** Curated quick-start accents — the "floor" of customization. Each is mid-bright so
 *  the ramp derivation + contrast clamp keep it AA-safe in both light and dark. */
private val ACCENT_PRESETS = listOf(
    "#8E7BFF", // the Fonebrew violet (default)
    "#4DA3FF", // blue
    "#2DD4BF", // teal
    "#5BD16A", // green
    "#F2B53B", // amber
    "#FF7A66", // coral
    "#FF7AB6", // pink
    "#9AA7B8", // slate
)

/**
 * The appearance engine (Arc-style): theme MODE, an ACCENT (curated presets + the
 * reusable Hyle colour picker), and a TEXTURE grain — the owner's "make it your own"
 * affordance. Self-contained: reads and writes [dev.fonebrew.data.SessionStore], which
 * the app root observes, so changes apply live across the whole UI.
 */
@Composable
fun ThemePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = (context.applicationContext as FonebrewApp).container.sessionStore
    val c = LocalHyleColors.current

    val modeStr by session.themeMode.collectAsState()
    val accentStr by session.accentColor.collectAsState()
    val texture by session.textureIntensity.collectAsState()

    val accent = parseHexColor(accentStr) ?: Violet

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

        // Free colour — Hyle's real 3D picker (hue ring + HSV/RGB/Lab/HCL slice + a live model of
        // the space), the same one the tactile kit ships, verbatim. The ramp derivation +
        // contrast clamp downstream keep any pick AA-legible in both modes.
        HyleColorPicker3D(
            color = accent,
            onColorChange = { session.setAccentColor(it.toHexRgb()) },
        )

        SectionLabel("Texture", c.textMid)
        HyleSlider(
            value = texture,
            onValueChange = { session.setTextureIntensity(it) },
            valueRange = 0f..1f,
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

