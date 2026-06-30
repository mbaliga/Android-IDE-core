package dev.aarso.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.aarso.AarsoApp
import dev.aarso.data.Intake
import dev.aarso.ui.theme.AarsoTheme
import dev.aarso.ui.theme.DefaultAccent
import dev.aarso.ui.theme.ThemeMode
import dev.aarso.ui.theme.parseHexColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AarsoApp

        // If the previous run crashed (or the container failed to build), show the recovery screen
        // — NOT the app — so a device-only launch crash can't brick the install, and its trace is
        // visible/shareable. The flag is cleared only after a clean first frame (below).
        val crash = app.initError?.let { stackString(it) } ?: dev.aarso.CrashLog.read(this)
        if (crash != null) {
            setContent {
                dev.aarso.ui.RecoveryScreen(
                    trace = crash,
                    onContinue = { dev.aarso.CrashLog.clear(this); recreate() },
                    onShare = { shareReport(crash) },
                    onReset = {
                        dev.aarso.CrashLog.clear(this)
                        runCatching { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData() }
                    },
                )
            }
            return
        }

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
            AarsoTheme(mode = mode, accent = accent, texture = texture, gradient = gradient) {
                AppRoot()
            }
            // Reached only if the theme + AppRoot composed without throwing → clear the crash flag
            // so the next launch is normal. A composition crash skips this, keeping the flag set.
            androidx.compose.runtime.LaunchedEffect(Unit) { dev.aarso.CrashLog.clear(applicationContext) }
        }
    }

    private fun stackString(t: Throwable): String =
        java.io.StringWriter().also { t.printStackTrace(java.io.PrintWriter(it)) }.toString()

    private fun shareReport(text: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Aarso crash report")
                putExtra(Intent.EXTRA_TEXT, text.take(60_000))
            }
            startActivity(Intent.createChooser(send, "Share crash report"))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntake(intent)
    }

    /** Route shared / selected text (and shared images) into the app (§7). */
    private fun handleIntake(intent: Intent?) {
        intent ?: return
        val container = (application as AarsoApp).container
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
