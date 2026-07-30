package dev.aarso.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import dev.aarso.ui.MainActivity
import dev.aarso.ui.ScreenCaptureActivity
import kotlin.math.abs

/**
 * A draggable "chat-head" bubble kept alive by a foreground service (handoff §7
 * fallback): always reachable, summons Aarso on tap. Dumber about context than the
 * assist gesture, but it always works and needs no default-assistant change —
 * only the overlay permission.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(
            NOTIF_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Aarso")
                .setContentText("Floating bubble active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build(),
        )
        if (bubble == null) addBubble()
        return START_STICKY
    }

    private fun addBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val size = (56 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = "A"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF8E7BFF.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false; var downTime = 0L
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragged = false; downTime = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) dragged = true
                    params.x = startX + dx; params.y = startY + dy
                    wm.updateViewLayout(view, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        val longPress = System.currentTimeMillis() - downTime > 500
                        // Tap → open Aarso; long-press → capture the screen behind the bubble (OCR).
                        val target = if (longPress) ScreenCaptureActivity::class.java else MainActivity::class.java
                        startActivity(
                            Intent(this, target)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        bubble = view
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating bubble", NotificationManager.IMPORTANCE_MIN),
            )
        }
    }

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "aarso.overlay"
        private const val NOTIF_ID = 1002

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
