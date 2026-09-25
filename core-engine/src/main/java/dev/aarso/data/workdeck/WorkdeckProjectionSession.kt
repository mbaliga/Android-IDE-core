package dev.aarso.data.workdeck

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import dev.aarso.domain.workdeck.GrayFrame
import dev.aarso.domain.workdeck.GrayFrameScaler

class WorkdeckProjectionSession(private val context: Context) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val publisher = WorkdeckFramePublisher()
    private var nextFrameAtMillis = 0L
    private var lastConnectionEpoch = -1L

    fun start(resultCode: Int, resultData: Intent) {
        stop()
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val density = context.resources.displayMetrics.densityDpi
        val worker = HandlerThread("workdeck-projection").also { it.start() }
        val handler = Handler(worker.looper)
        val mediaProjection = context.getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, handler)
        imageReader.setOnImageAvailableListener({ source ->
            val now = android.os.SystemClock.elapsedRealtime()
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                if (now < nextFrameAtMillis || WorkdeckSessionHub.state.value.connectedDevice == null) return@use
                val plane = it.planes.firstOrNull() ?: return@use
                val buffer = plane.buffer
                val base = buffer.position()
                val gray = ByteArray(width * height)
                for (y in 0 until height) {
                    val row = base + y * plane.rowStride
                    for (x in 0 until width) {
                        val offset = row + x * plane.pixelStride
                        val r = buffer.get(offset).toInt() and 0xff
                        val g = buffer.get(offset + 1).toInt() and 0xff
                        val b = buffer.get(offset + 2).toInt() and 0xff
                        gray[y * width + x] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                    }
                }
                val state = WorkdeckSessionHub.state.value
                val device = state.connectedDevice ?: return@use
                if (state.connectionEpoch != lastConnectionEpoch) {
                    publisher.reset()
                    lastConnectionEpoch = state.connectionEpoch
                }
                val fitted = GrayFrameScaler.fit(GrayFrame(width, height, gray), device.displayWidth, device.displayHeight)
                val decision = publisher.publish(fitted, now)
                nextFrameAtMillis = now + 1_000L / decision.targetFramesPerSecond.coerceIn(1, 8)
            }
        }, handler)
        val virtualDisplay = mediaProjection.createVirtualDisplay(
            "Fonebrew Workdeck",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler,
        )
        projection = mediaProjection
        reader = imageReader
        display = virtualDisplay
        thread = worker
        WorkdeckSessionHub.update { it.copy(projectionActive = true) }
    }

    @Synchronized fun stop() {
        // Clear fields first: MediaProjection.stop() synchronously invokes onStop() on some
        // releases, and a callback must observe an already-stopped session instead of recursing.
        val oldReader = reader
        val oldDisplay = display
        val oldProjection = projection
        val oldThread = thread
        projection = null; display = null; reader = null; thread = null
        oldReader?.setOnImageAvailableListener(null, null)
        runCatching { oldDisplay?.release() }
        runCatching { oldReader?.close() }
        runCatching { oldProjection?.stop() }
        runCatching { oldThread?.quitSafely() }
        publisher.reset()
        WorkdeckSessionHub.update { it.copy(projectionActive = false) }
    }
}
