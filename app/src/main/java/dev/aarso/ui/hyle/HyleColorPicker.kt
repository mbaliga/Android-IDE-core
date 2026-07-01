package dev.aarso.ui.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * **Hyle colour picker** — a reusable saturation/value field + hue slider that emits an opaque
 * [Color] as the user drags. Part of the Hyle component family (alongside `HyleButton`,
 * `HyleChip`, …) so any surface — the appearance accent, a gradient stop, a future per-loop
 * colour — reuses one picker rather than re-rolling hue maths.
 *
 * Unidirectional: [color] is the single source of truth (the caller persists it and passes the
 * new value straight back). Hue is held locally so dragging the SV field down to black/grey —
 * where hue is mathematically undefined — doesn't lose the chosen hue; it re-syncs from [color]
 * only when the incoming colour carries a real (saturated) hue.
 *
 * HSV↔RGB uses `android.graphics.Color` (no dependency on a specific Compose colour-space API).
 * Render/gesture behaviour is owner-verified on device (CI never launches the app).
 *
 * @param color the current colour (source of truth).
 * @param onColorChange called with the new opaque colour on each drag/tap.
 * @param modifier layout modifier for the whole picker column.
 */
@Composable
fun HyleColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val argb = color.toArgb()
    val incoming = remember(argb) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    }
    var hue by remember { mutableFloatStateOf(incoming[0]) }
    var sat by remember { mutableFloatStateOf(incoming[1]) }
    var value by remember { mutableFloatStateOf(incoming[2]) }

    // Re-sync from an externally-set colour (e.g. a preset swatch tap). Preserve the local hue
    // when the incoming colour is greyscale (undefined hue), so a later value-drag keeps it.
    LaunchedEffect(argb) {
        if (incoming[1] > 0.01f) hue = incoming[0]
        sat = incoming[1]
        value = incoming[2]
    }

    fun emit() {
        val out = android.graphics.Color.HSVToColor(
            floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)),
        )
        onColorChange(Color(out))
    }

    val pureHue = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Saturation (x) × Value (y) field ─────────────────────────────────
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.horizontalGradient(listOf(Color.White, pureHue)))
                .semantics { contentDescription = "Saturation and brightness field" },
        ) {
            // Vertical black overlay: top = full brightness, bottom = black.
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
            )
            val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            fun setFromOffset(x: Float, y: Float) {
                sat = (x / wPx).coerceIn(0f, 1f)
                value = (1f - y / hPx).coerceIn(0f, 1f)
                emit()
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { setFromOffset(it.x, it.y) } }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ -> setFromOffset(change.position.x, change.position.y) }
                    },
            )
            // Thumb at (sat, value).
            val thumbX = (maxWidth - 16.dp) * sat.coerceIn(0f, 1f)
            val thumbY = (maxHeight - 16.dp) * (1f - value).coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset { IntOffset(thumbX.roundToPx(), thumbY.roundToPx()) }
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
            )
        }

        // ── Hue slider ───────────────────────────────────────────────────────
        val hueColors = remember {
            (0..360 step 60).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f))) }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(hueColors))
                .semantics { contentDescription = "Hue slider" },
        ) {
            val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            fun setHue(x: Float) {
                hue = (x / wPx).coerceIn(0f, 1f) * 360f
                emit()
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { setHue(it.x) } }
                    .pointerInput(Unit) { detectDragGestures { change, _ -> setHue(change.position.x) } },
            )
            val thumbX = (maxWidth - 8.dp) * (hue / 360f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset { IntOffset(thumbX.roundToPx(), 0) }
                    .fillMaxHeight()
                    .width(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(1.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
            )
        }
    }
}
