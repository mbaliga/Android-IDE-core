package dev.fonebrew.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.fonebrew.FonebrewApp
import dev.fonebrew.ui.spatial.SpatialRoot

/**
 * Top-level shell: the two-screen stance once, then the spatial shell — the centre
 * room is home, the rooms sit off its edges and along the z-axis, and navigation
 * between them is spatial only (edge drags + pinch; the original "no room tab bar"
 * stance is back in force by owner correction). The centre room's own lenses
 * (Chat / Terminal / Tasks) are ChatScreen's tab row, scoped to the chat card —
 * there is no persistent shell-level bar (the old bottom CenterViewTabBar showed
 * chat's tabs in every room and is deleted, 2026-08-21).
 */
@Composable
fun AppRoot() {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
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
