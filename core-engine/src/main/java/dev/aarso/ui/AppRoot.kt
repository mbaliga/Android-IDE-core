package dev.aarso.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.aarso.AarsoApp
import dev.aarso.ui.spatial.SpatialRoot

/**
 * Top-level shell: the two-screen stance once, then the spatial shell — the centre
 * room is home, the rooms sit off its edges and along the z-axis, and navigation
 * between them is spatial only (edge drags + pinch; the original "no room tab bar"
 * stance is back in force by owner correction). The persistent bottom bar is
 * CenterViewTabBar.kt: the centre room's three lenses (Conversation / Terminal /
 * Background tasks) — tabs because they are views of one activity, not places.
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
