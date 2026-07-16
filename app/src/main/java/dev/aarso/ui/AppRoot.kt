package dev.aarso.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.aarso.AarsoApp
import dev.aarso.ui.spatial.SpatialRoot

/**
 * Top-level shell: the two-screen stance once, then the spatial shell — Chat is
 * home, the rooms sit off its edges, and there is deliberately no tab bar
 * (redesign brief §1/§9).
 */
@Composable
fun AppRoot() {
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val onboarded by container.sessionStore.onboardingDone.collectAsState()
    Box(Modifier.fillMaxSize()) {
        if (!onboarded) {
            OnboardingScreen(onDone = { container.sessionStore.setOnboardingDone() })
        } else {
            SpatialRoot()
        }
        // Visible-at-launch build-identity signal (permanent — keep in every build).
        VersionBadge(modifier = Modifier.align(Alignment.BottomStart))
    }
}
