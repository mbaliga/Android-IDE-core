package dev.aarso.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.aarso.AarsoApp
import dev.aarso.data.Intake
import dev.aarso.ui.MainActivity

/**
 * Captures one screen frame via MediaProjection and OCRs it on-device (ML Kit,
 * offline), then routes the recognized text into Aarso (handoff §7, tier 2 — for
 * when the Assist API's text is thin). Costs the system consent prompt and yields
 * a screenshot's text, not the source file.
 */
class ScreenCaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var captured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(
            NOTIF_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Aarso")
                .setContentText("Reading the screen…")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build(),
        )
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getProjectionData(it) }
        if (code == 0 || data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        // Let the app behind the (now-finished) consent activity return to front.
        handler.postDelayed({ capture(code, data) }, 700)
        return START_NOT_STICKY
    }

    private fun capture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull() ?: run { stopSelf(); return }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {}, handler)

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = ir
        mp.createVirtualDisplay(
            "aarso-capture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler,
        )
        ir.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            captured = true
            val bmp = runCatching { toBitmap(image, w, h) }.getOrNull()
            image.close()
            if (bmp == null) { finishUp(""); return@setOnImageAvailableListener }
            ocr(bmp)
        }, handler)
    }

    private fun toBitmap(image: android.media.Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        return if (full.width != w) Bitmap.createBitmap(full, 0, 0, w, h) else full
    }

    private fun ocr(bmp: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { finishUp(it.text) }
            .addOnFailureListener { finishUp("") }
    }

    private fun finishUp(text: String) {
        if (text.isNotBlank()) {
            (applicationContext as AarsoApp).container.sharedIntake.offer(Intake(text = text, source = "screen"))
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        reader = null
        projection = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen reading", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private object IntentCompat {
        fun getProjectionData(intent: Intent): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            }
    }

    companion object {
        private const val CHANNEL_ID = "aarso.capture"
        private const val NOTIF_ID = 1003
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
