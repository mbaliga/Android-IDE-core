package dev.aarso.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.aarso.AarsoApp
import dev.aarso.ui.spatial.SpatialRoot

/**
 * Top-level shell: the two-screen stance once, then the spatial shell — Chat is
 * home, the rooms sit off its edges, and a persistent bottom bar (BottomNavBar.kt)
 * gives every room a direct, thumb-reachable destination. This supersedes the
 * original redesign brief §1/§9 "no tab bar" stance — the edge-drag/pinch
 * gestures that stance was protecting are unchanged and still work, the bar is
 * an additional, discoverable entry point onto the same navigation.
 */
@Composable
fun AppRoot() {
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val onboarded by container.sessionStore.onboardingDone.collectAsState()
    if (!onboarded) {
        OnboardingScreen(onDone = { container.sessionStore.setOnboardingDone() })
        return
    }
    SpatialRoot()
}
