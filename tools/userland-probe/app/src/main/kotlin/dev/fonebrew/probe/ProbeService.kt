package dev.fonebrew.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Foreground service held only while check (vi) runs, so the fork-survival question is asked under
 * the same condition a real terminal session would have. It does nothing else.
 */
class ProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); running = false; return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Userland probe", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH).setContentTitle("Userland probe running").setContentText("Fork survival check")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            running = true
        } catch (t: Throwable) { startError = "$t" }
        started.countDown()
        return START_NOT_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    companion object {
        const val ACTION_STOP = "dev.fonebrew.probe.STOP"
        private const val CH = "probe"
        @Volatile var running = false
        @Volatile var startError: String? = null
        @Volatile var started = CountDownLatch(1)

        fun resetLatch() { started = CountDownLatch(1); startError = null }
        fun awaitStart(ms: Long): Boolean = started.await(ms, TimeUnit.MILLISECONDS) && running
    }
}
