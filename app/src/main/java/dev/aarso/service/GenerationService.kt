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

/**
 * A minimal foreground service whose only job is to keep the process alive while
 * an on-device model is loading or generating, so work continues when the app is
 * minimized and the OS is less likely to reclaim the (large, model-holding)
 * process. It does not run the work itself — that stays in the ViewModel — it just
 * elevates process priority for the duration.
 */
class GenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Aarso")
                .setContentText("Running a model on-device…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
        // API 34+ requires the type to be passed; minSdk 31 always has the 3-arg form.
        // If promotion is refused (OEM policy, background-start races), stop the
        // service instead of crashing — generation runs in the ViewModel either way,
        // this service only elevates process priority.
        // Try the typed promotion; if an OEM/policy rejects the type, fall back to the untyped
        // call (still satisfies the start-foreground contract so the system's "did not start in
        // time" watchdog can't crash us). Only give up if BOTH fail.
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.recoverCatching {
            startForeground(NOTIF_ID, notification)
        }.isSuccess
        if (!promoted) {
            android.util.Log.w("Aarso", "foreground promotion refused; running without priority boost")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "On-device inference", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "aarso.generation"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, GenerationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // Background-start restriction: proceed without the priority boost.
                android.util.Log.w("Aarso", "could not start generation FGS: $t")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationService::class.java))
        }
    }
}
