package dev.aarso

import android.app.Application
import dev.aarso.di.AppContainer

/** Holds the single [AppContainer] for the process. */
class AarsoApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Non-null if container construction failed — MainActivity then shows the recovery screen
     *  instead of touching the (uninitialised) container. */
    var initError: Throwable? = null
        private set

    override fun onCreate() {
        // Install crash capture FIRST, so even a failure in container construction below (or a
        // later first-frame/Compose crash) lands a readable trace for the recovery screen.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { CrashLog.write(this, e) }
            previous?.uncaughtException(thread, e)
        }
        super.onCreate()
        // Don't let an init failure brick the process silently — record it and let MainActivity recover.
        try {
            container = AppContainer(this)
        } catch (e: Throwable) {
            initError = e
            CrashLog.write(this, e)
        }
    }
}
