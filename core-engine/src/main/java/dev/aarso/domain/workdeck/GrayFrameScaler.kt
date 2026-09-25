package dev.aarso.domain.workdeck

import kotlin.math.min

/** Aspect-preserving e-ink viewport fit with white letterboxing and bilinear sampling. */
object GrayFrameScaler {
    fun fit(source: GrayFrame, targetWidth: Int, targetHeight: Int): GrayFrame {
        require(targetWidth > 0 && targetHeight > 0)
        if (source.width == targetWidth && source.height == targetHeight) return source
        val scale = min(targetWidth.toDouble() / source.width, targetHeight.toDouble() / source.height)
        val fittedWidth = (source.width * scale).toInt().coerceIn(1, targetWidth)
        val fittedHeight = (source.height * scale).toInt().coerceIn(1, targetHeight)
        val left = (targetWidth - fittedWidth) / 2
        val top = (targetHeight - fittedHeight) / 2
        val output = ByteArray(targetWidth * targetHeight) { 0xff.toByte() }
        for (targetY in 0 until fittedHeight) {
            val sourceY = if (fittedHeight == 1) 0.0 else targetY.toDouble() * (source.height - 1) / (fittedHeight - 1)
            val y0 = sourceY.toInt()
            val y1 = min(y0 + 1, source.height - 1)
            val fy = sourceY - y0
            for (targetX in 0 until fittedWidth) {
                val sourceX = if (fittedWidth == 1) 0.0 else targetX.toDouble() * (source.width - 1) / (fittedWidth - 1)
                val x0 = sourceX.toInt()
                val x1 = min(x0 + 1, source.width - 1)
                val fx = sourceX - x0
                val topValue = source.unsignedAt(x0, y0) * (1.0 - fx) + source.unsignedAt(x1, y0) * fx
                val bottomValue = source.unsignedAt(x0, y1) * (1.0 - fx) + source.unsignedAt(x1, y1) * fx
                output[(top + targetY) * targetWidth + left + targetX] =
                    (topValue * (1.0 - fy) + bottomValue * fy).toInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayFrame(targetWidth, targetHeight, output)
    }
}
