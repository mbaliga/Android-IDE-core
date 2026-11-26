package dev.fonebrew.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.fonebrew.FonebrewApp
import dev.fonebrew.core_engine.R
import dev.fonebrew.domain.terminal.ForegroundServiceController
import dev.fonebrew.domain.terminal.ServiceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app process at foreground priority while terminal sessions are live, so a backgrounded
 * shell is less likely to be reclaimed. It does NOT make shells durable: process death, a
 * low-memory kill, a force-stop or the Android 12+ phantom-process killer still end them, and
 * nothing reattaches afterwards (docs/design/terminal-engine.md). The ongoing notification shows
 * only a count and a Close-all action; it is started and stopped by [ServiceKeeper]. Declared in
 * the `full` flavor only (`specialUse`, EXECUTION_CONTRACT 3.2); in `play` [TerminalServiceController]
 * sees no declaration and does nothing. Owner-verified only: notification, action and self-stop.
 */
class TerminalSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val holder = (application as FonebrewApp).container.terminalSession
        if (intent?.action == ServiceKeeper.ACTION_CLOSE_ALL) {
            holder.sessions.closeAll()
            stopSelfSafely()
            return START_NOT_STICKY
        }
        ensureChannel()
        val promoted = runCatching {
            startForeground(NOTIF_ID, notification(holder.sessions.liveCount.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.isSuccess
        if (!promoted) { stopSelfSafely(); return START_NOT_STICKY }
        watcher?.cancel()
        watcher = scope.launch {
            holder.sessions.liveCount.collect { n ->
                // At zero the ServiceKeeper (driven by the same count) calls stopService; stopping
                // here too would leave the keeper believing the service still runs.
                if (n > 0) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(n))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(live: Int): Notification {
        val closeAll = PendingIntent.getService(
            this, 0,
            Intent(this, TerminalSessionService::class.java).setAction(ServiceKeeper.ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Fonebrew terminal")
            .setContentText(ServiceKeeper.notificationText(live))
            .setSmallIcon(R.drawable.ic_stat_fonebrew)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Close all", closeAll).build())
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Terminal sessions", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL_ID = "fonebrew.terminal"
        private const val NOTIF_ID = 1004
    }
}

/** Starts/stops [TerminalSessionService] for the [ServiceKeeper]; a no-op where it is not declared (play). */
class TerminalServiceController(private val context: Context) : ForegroundServiceController {
    private val declared: Boolean by lazy {
        runCatching {
            context.packageManager.getServiceInfo(ComponentName(context, TerminalSessionService::class.java), 0)
        }.isSuccess
    }

    override fun start() {
        if (!declared) return
        runCatching { context.startForegroundService(Intent(context, TerminalSessionService::class.java)) }
            .onFailure { android.util.Log.w("Fonebrew", "terminal service start refused; running without it") }
    }

    override fun stop() {
        if (!declared) return
        runCatching { context.stopService(Intent(context, TerminalSessionService::class.java)) }
    }
}
