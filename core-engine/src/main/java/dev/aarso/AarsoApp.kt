package dev.aarso

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.di.AppContainer

/** Holds the single [AppContainer] for the process. */
class AarsoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        // Install crash capture FIRST, so even a failure in container construction below (or a
        // later first-frame/Compose crash) lands a readable trace for the recovery screen.
        CrashRecovery.install(this, appLabel = "Aarso")
        super.onCreate()
        // Don't let an init failure brick the process silently — record it and let MainActivity recover.
        try {
            container = AppContainer(this)
        } catch (e: Throwable) {
            CrashRecovery.captureInitError(this, appLabel = "Aarso", e)
        }
    }
}
