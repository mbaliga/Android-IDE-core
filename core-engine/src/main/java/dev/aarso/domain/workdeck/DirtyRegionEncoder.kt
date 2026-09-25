package dev.aarso.domain.workdeck

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

data class GrayFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Invalid grayscale frame." }
    }

    fun unsignedAt(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff
}

data class WorkdeckRect(val left: Int, val top: Int, val rightExclusive: Int, val bottomExclusive: Int) {
    init {
        require(left >= 0 && top >= 0 && rightExclusive > left && bottomExclusive > top) { "Invalid dirty rectangle." }
    }
    val width: Int get() = rightExclusive - left
    val height: Int get() = bottomExclusive - top
    val area: Int get() = width * height
}

data class DirtyRegionResult(val fullFrame: Boolean, val rectangles: List<WorkdeckRect>, val changedPixels: Int)

class DirtyRegionDetector(
    private val tileSize: Int = 32,
    private val pixelThreshold: Int = 6,
    private val fullFrameRatio: Float = 0.55f,
) {
    init {
        require(tileSize in 4..256 && pixelThreshold in 0..255 && fullFrameRatio in 0f..1f)
    }

    fun detect(previous: GrayFrame?, current: GrayFrame): DirtyRegionResult {
        if (previous == null || previous.width != current.width || previous.height != current.height) {
            return DirtyRegionResult(true, listOf(WorkdeckRect(0, 0, current.width, current.height)), current.pixels.size)
        }
        val columns = (current.width + tileSize - 1) / tileSize
        val rows = (current.height + tileSize - 1) / tileSize
        val changedTiles = BooleanArray(columns * rows)
        var changedPixels = 0
        for (y in 0 until current.height) {
            for (x in 0 until current.width) {
                if (kotlin.math.abs(current.unsignedAt(x, y) - previous.unsignedAt(x, y)) > pixelThreshold) {
                    changedPixels++
                    changedTiles[(y / tileSize) * columns + x / tileSize] = true
                }
            }
        }
        if (changedPixels == 0) return DirtyRegionResult(false, emptyList(), 0)
        if (changedPixels.toFloat() / current.pixels.size >= fullFrameRatio) {
            return DirtyRegionResult(true, listOf(WorkdeckRect(0, 0, current.width, current.height)), changedPixels)
        }

        val visited = BooleanArray(changedTiles.size)
        val rectangles = mutableListOf<WorkdeckRect>()
        for (start in changedTiles.indices) {
            if (!changedTiles[start] || visited[start]) continue
            val queue = ArrayDeque<Int>()
            queue += start
            visited[start] = true
            var minColumn = start % columns
            var maxColumn = minColumn
            var minRow = start / columns
            var maxRow = minRow
            while (queue.isNotEmpty()) {
                val value = queue.removeFirst()
                val column = value % columns
                val row = value / columns
                minColumn = minOf(minColumn, column); maxColumn = maxOf(maxColumn, column)
                minRow = minOf(minRow, row); maxRow = maxOf(maxRow, row)
                val neighbors = intArrayOf(value - 1, value + 1, value - columns, value + columns)
                for (neighbor in neighbors) {
                    if (neighbor !in changedTiles.indices || visited[neighbor] || !changedTiles[neighbor]) continue
                    val neighborColumn = neighbor % columns
                    val neighborRow = neighbor / columns
                    if (kotlin.math.abs(neighborColumn - column) + kotlin.math.abs(neighborRow - row) != 1) continue
                    visited[neighbor] = true
                    queue += neighbor
                }
            }
            rectangles += WorkdeckRect(
                minColumn * tileSize,
                minRow * tileSize,
                minOf(current.width, (maxColumn + 1) * tileSize),
                minOf(current.height, (maxRow + 1) * tileSize),
            )
        }
        return DirtyRegionResult(false, rectangles.sortedWith(compareBy(WorkdeckRect::top, WorkdeckRect::left)), changedPixels)
    }
}

enum class WorkdeckQuality(val levels: Int) { TEXT(2), GENERAL(16), FAST_SCROLL(4) }

object WorkdeckRegionEncoder {
    private val BAYER_4 = arrayOf(
        intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5),
    )

    fun encode(frame: GrayFrame, rect: WorkdeckRect, quality: WorkdeckQuality): ByteArray {
        require(rect.rightExclusive <= frame.width && rect.bottomExclusive <= frame.height) { "Rectangle exceeds frame." }
        val raw = ByteArray(rect.area)
        var index = 0
        val step = 255f / (quality.levels - 1)
        for (y in rect.top until rect.bottomExclusive) {
            for (x in rect.left until rect.rightExclusive) {
                val source = frame.unsignedAt(x, y)
                val dither = if (quality == WorkdeckQuality.TEXT) 0f else (BAYER_4[y and 3][x and 3] - 7.5f) * step / 16f
                val level = ((source + dither).coerceIn(0f, 255f) / step).toInt().coerceIn(0, quality.levels - 1)
                raw[index++] = (level * 255 / (quality.levels - 1)).toByte()
            }
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val output = ByteArrayOutputStream(raw.size / 2)
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return output.toByteArray()
    }
}

