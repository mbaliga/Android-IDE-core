package dev.aarso.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.BuildConfig

/**
 * Build-identity signal, visible on every launch (CI never launches the app — see
 * CLAUDE.md's "CI caveat" — so this on-device text is the one thing that proves
 * which build actually reached the phone). Lives in shared code so it renders in
 * every flavor of this app, not just one dist variant.
 */
@Composable
fun VersionBadge(modifier: Modifier = Modifier) {
    Text(
        BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.systemBarsPadding().padding(8.dp),
    )
}
