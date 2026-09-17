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
