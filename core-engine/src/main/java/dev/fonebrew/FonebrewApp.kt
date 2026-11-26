package dev.fonebrew

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import dev.fonebrew.di.AppContainer

/** Holds the single [AppContainer] for the process. Open so a consumer's own thin :app can
 *  subclass it (e.g. Studio's `StudioApp : FonebrewApp()`, which installs its paid-tier seams from
 *  its own onCreate() override) — the de-fork's whole point is that :core-engine is a real
 *  dependency other apps build on, not just this repo's own :app. */
open class FonebrewApp : Application() {
    lateinit var container: AppContainer
        private set

    /** The container, or null if it failed to build — for callers outside the UI (home-screen
     *  widgets) that must draw an honest error state instead of throwing. */
    val containerOrNull: AppContainer?
        get() = if (::container.isInitialized) container else null

    override fun onCreate() {
        // Install crash capture FIRST, so even a failure in container construction below (or a
        // later first-frame/Compose crash) lands a readable trace for the recovery screen.
        CrashRecovery.install(this, appLabel = "Fonebrew")
        super.onCreate()
        // Don't let an init failure brick the process silently — record it and let MainActivity recover.
        try {
            container = AppContainer(this)
        } catch (e: Throwable) {
            CrashRecovery.captureInitError(this, appLabel = "Fonebrew", e)
        }
        // Home-screen widgets (docs/widgets.md): if any are placed, redraw them from this fresh
        // process's real state and start pushing updates. Background thread; never throws here.
        dev.fonebrew.widget.FonebrewWidgets.onProcessStart(this)
    }
}
