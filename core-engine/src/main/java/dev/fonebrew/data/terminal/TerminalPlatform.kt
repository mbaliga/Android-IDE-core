package dev.fonebrew.data.terminal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.io.File

/**
 * The Android-side seams the session facade needs, so the facade (and its tests) stay free of
 * Context. [Headless] is the default: no lifecycle hook, the real process environment.
 */
interface TerminalPlatform {
    /** Call [onBackground] each time the app's last visible activity stops. */
    fun registerBackgroundHook(onBackground: () -> Unit) {}
    fun processEnvironment(): Map<String, String> = System.getenv()

    object Headless : TerminalPlatform
}

class AndroidTerminalPlatform(private val app: Application) : TerminalPlatform {
    override fun registerBackgroundHook(onBackground: () -> Unit) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) onBackground()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        fun filesRoot(context: Context): File = File(context.applicationContext.filesDir, "terminal")
    }
}
