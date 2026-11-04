package dev.fonebrew.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.hyle.theme.DefaultAccent
import dev.aarso.hyle.theme.parseHexColor
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.Intake
import dev.fonebrew.ui.theme.FonebrewCrashRecoveryStyle
import dev.fonebrew.ui.theme.FonebrewTheme
import dev.fonebrew.ui.theme.ThemeMode
import dev.fonebrew.ui.theme.resolveDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // If the previous run crashed (or the container failed to build during Application.onCreate,
        // which also lands a report via CrashRecovery.captureInitError), show the shared recovery
        // screen — NOT the app — instead of touching the (possibly uninitialised) container. This
        // finishes this Activity, so a device-only launch crash can't brick the install.
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fonebrew", style = FonebrewCrashRecoveryStyle)) return

        val app = application as FonebrewApp
        handleIntake(intent)
        // A recreated activity (rotation, theme switch) is handed its original intent again; a
        // widget tap must act once, or "New chat" would reset the chat on every rotation.
        if (savedInstanceState == null) handleWidgetTap(intent)
        val session = app.container.sessionStore
        setContent {
            val modeStr by session.themeMode.collectAsState()
            val accentStr by session.accentColor.collectAsState()
            val texture by session.textureIntensity.collectAsState()
            val gradientStr by session.gradientColor.collectAsState()
            val mode = runCatching { ThemeMode.valueOf(modeStr) }.getOrDefault(ThemeMode.DARK)
            val accent = parseHexColor(accentStr) ?: DefaultAccent
            val gradient = gradientStr.takeIf { it.isNotBlank() }?.let { parseHexColor(it) }
            // Resolved once here (not re-derived inside the splash) so the splash's asset
            // pair can never disagree with the theme FonebrewTheme itself lands on.
            val dark = mode.resolveDark()
            FonebrewTheme(mode = mode, accent = accent, texture = texture, gradient = gradient) {
                AppRoot(dark = dark)
            }
            // Reached only if the theme + AppRoot composed without throwing → clear the crash flag
            // so the next launch is normal. A composition crash skips this, keeping the flag set.
            androidx.compose.runtime.LaunchedEffect(Unit) { CrashRecovery.clear(applicationContext) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntake(intent)
        handleWidgetTap(intent)
    }

    /** A home-screen widget tap (docs/widgets.md): navigation only. MainActivity is exported, so the
     *  extras are validated ([dev.fonebrew.widget.WidgetRoutes.parse]) and anything unknown is ignored. */
    private fun handleWidgetTap(intent: Intent?) {
        if (intent?.action != dev.fonebrew.widget.WidgetRoutes.ACTION_OPEN) return
        // Reopened from Recents, the task's first intent comes back too: that tap was already acted on.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val route = dev.fonebrew.widget.WidgetRoutes.parse(
            intent.getStringExtra(dev.fonebrew.widget.WidgetRoutes.EXTRA_DESTINATION),
            intent.getStringExtra(dev.fonebrew.widget.WidgetRoutes.EXTRA_TASK_ID),
        ) ?: return
        (application as FonebrewApp).container.widgetRoutes.offer(route)
    }

    /** Route shared / selected text (and shared images) into the app (§7). */
    private fun handleIntake(intent: Intent?) {
        intent ?: return
        val container = (application as FonebrewApp).container
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val image = if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)?.toString()
                } else {
                    null
                }
                if (!text.isNullOrBlank() || image != null) {
                    container.sharedIntake.offer(Intake(text = text, imageUri = image, source = "share"))
                }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
                if (!text.isNullOrBlank()) {
                    container.sharedIntake.offer(Intake(text = text, source = "selection"))
                }
            }
        }
    }
}
