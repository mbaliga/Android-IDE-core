package dev.aarso.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.aarso.AarsoApp
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.data.Intake
import dev.aarso.ui.theme.AarsoTheme
import dev.aarso.ui.theme.DefaultAccent
import dev.aarso.ui.theme.ThemeMode
import dev.aarso.ui.theme.parseHexColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // If the previous run crashed (or the container failed to build during Application.onCreate,
        // which also lands a report via CrashRecovery.captureInitError), show the shared recovery
        // screen — NOT the app — instead of touching the (possibly uninitialised) container. This
        // finishes this Activity, so a device-only launch crash can't brick the install.
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Aarso")) return

        val app = application as AarsoApp
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
            dev.aarso.domain.runtime.WorkspaceHandoffContract.ACTION_OPEN_WORKSPACE -> {
                val uri = intent.data ?: return
                if (uri.scheme != "content") return
                val grantMask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val takeFlags = intent.flags and grantMask
                if (takeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return

                runCatching {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                }
                val readOnly = intent.getBooleanExtra(
                    dev.aarso.domain.runtime.WorkspaceHandoffContract.EXTRA_READ_ONLY,
                    takeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0,
                )
                val displayName = intent.getStringExtra(
                    dev.aarso.domain.runtime.WorkspaceHandoffContract.EXTRA_DISPLAY_NAME,
                )
                container.workspaceHandoffStore.set(
                    dev.aarso.domain.runtime.FylzWorkspaceRef(
                        treeUri = uri.toString(),
                        displayName = displayName,
                        readOnly = readOnly,
                    )
                )
            }
        }
    }
}
