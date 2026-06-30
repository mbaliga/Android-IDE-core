package dev.aarso.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.random.Random

/**
 * A faint ambient grain laid over the base surface — the "texture" half of the
 * owner's appearance-sovereignty ask. Procedural (a small tiled noise bitmap, so
 * it works on every API and ships no asset), drawn on top of content at very low
 * alpha and hard-capped so it never approaches a contrast hazard. [intensity] 0f
 * disables it entirely. Keep this on the base background only.
 */
fun Modifier.hyleTexture(intensity: Float, tint: Color): Modifier {
    if (intensity <= 0f) return this
    val strength = intensity.coerceIn(0f, 1f)
    return this.drawWithCache {
        val shader = BitmapShader(noiseTile(96, tint), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val paint = Paint().apply {
            this.shader = shader
            // Cap at ~6% so the grain stays ambient and AA-safe.
            alpha = (strength * 0.06f * 255f).toInt().coerceIn(0, 16)
        }
        onDrawWithContent {
            drawContent()
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        }
    }
}

/** Build a small grayscale-noise tile centred on [tint]'s brightness. */
private fun noiseTile(size: Int, tint: Color): Bitmap {
    val px = IntArray(size * size)
    val rnd = Random(42)
    val base = (((tint.red + tint.green + tint.blue) / 3f) * 255f).toInt().coerceIn(0, 255)
    for (i in px.indices) {
        val v = (base + (rnd.nextInt(48) - 24)).coerceIn(0, 255)
        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
