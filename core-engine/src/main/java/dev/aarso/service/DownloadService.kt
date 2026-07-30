package dev.aarso.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.aarso.AarsoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that holds process priority while [dev.aarso.data.DownloadCenter]
 * has model downloads in flight, and mirrors their progress into its notification
 * so multi-GB fetches stay glanceable with the app minimized. The work itself
 * lives in the center (the [GenerationService] division of labour).
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification("Downloading model…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification("Downloading model…", 0, 0))
            }
        } catch (t: Throwable) {
            // DownloadCenter keeps downloading in its own scope; never crash for
            // a notification/priority formality.
            android.util.Log.w("Aarso", "foreground promotion refused: $t")
            stopSelf()
            return START_NOT_STICKY
        }
        val center = (application as AarsoApp).container.downloadCenter
        scope.launch {
            center.active.collect { active ->
                val running = active.values.filter { it.running }
                if (running.isNotEmpty()) {
                    val first = running.first()
                    val pct = (first.progress.fraction * 100).toInt()
                    val title = if (running.size == 1) {
                        "Downloading ${first.request.fileName}"
                    } else {
                        "Downloading ${running.size} models"
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, notification(title, pct, 100))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(title: String, progress: Int, max: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .apply { if (max > 0) setProgress(max, progress, false) }
            .build()

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "aarso.downloads"
        private const val NOTIF_ID = 1002

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (t: Throwable) {
                // Background-start restriction: the download itself continues in
                // DownloadCenter's scope; only the notification is lost.
                android.util.Log.w("Aarso", "could not start download FGS: $t")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
