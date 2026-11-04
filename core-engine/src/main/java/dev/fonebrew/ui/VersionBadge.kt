package dev.fonebrew.ui

import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Build-identity signal, visible on every launch (CI never launches the app — see
 * CLAUDE.md's "CI caveat" — so this on-device text is the one thing that proves
 * which build actually reached the phone). Lives in shared code so it renders in
 * every flavor of this app, not just one dist variant.
 *
 * Reads the SHIPPING app's own version via PackageManager rather than a BuildConfig
 * constant baked into this library: :core-engine deliberately carries no versionName/
 * versionCode of its own (that's an application-module concern), and any other :app
 * consuming this same module has its own, different version number.
 */
@Composable
fun VersionBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("?")
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.systemBarsPadding().padding(8.dp),
    )
}
