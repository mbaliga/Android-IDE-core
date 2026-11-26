package dev.fonebrew.domain.terminal

/** Starts and stops the platform foreground service. The Android class implements it. */
interface ForegroundServiceController {
    fun start()
    fun stop()
}

/**
 * Decides when the keep-alive foreground service should exist: running while at least one
 * session is live, stopped when the last one closes. Pure logic behind a [ForegroundServiceController]
 * so the lifecycle is JVM-tested with a fake; the Android service only mirrors it.
 */
class ServiceKeeper(private val controller: ForegroundServiceController) {
    var running = false
        private set

    /** Call whenever the live-session count changes (idempotent). */
    @Synchronized fun onLiveCount(live: Int) {
        if (live > 0 && !running) {
            running = true
            controller.start()
        } else if (live == 0 && running) {
            running = false
            controller.stop()
        }
    }

    /** The platform stopped the service on its own (task removed, OEM kill): forget it so the next live count restarts it. */
    @Synchronized fun onServiceDestroyed() { running = false }

    companion object {
        const val ACTION_CLOSE_ALL = "dev.fonebrew.terminal.CLOSE_ALL"

        /** Notification text. Counts only; never a command line, title or path (those can be private). */
        fun notificationText(live: Int): String =
            if (live == 1) "1 terminal session running" else "$live terminal sessions running"
    }
}
