package dev.fonebrew.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.aarso.interactionmode.InteractionMode
import dev.fonebrew.FonebrewApp
import dev.fonebrew.ui.regular.RegularShell
import dev.fonebrew.ui.spatial.SpatialRoot

/**
 * Top-level shell: the brand splash once, then the two-screen stance, then the interaction-mode
 * shell — ASOC → [SpatialRoot] (the centre room is home, the rooms sit off its edges and along
 * the z-axis, navigation is spatial only: edge drags + pinch), REGULAR → [RegularShell] (a
 * conventional bottom-tab Scaffold over the same room composables — bifurcation wave 1, owner
 * ruling 2026-09-15: "the rooms model remains somewhat experimental while we perfect it; users
 * choose between a traditional 'Regular' layout ... and an 'asoc' mode"). The centre room's own
 * lenses (Chat / Terminal / Tasks) stay ChatScreen's own tab row in both shells — neither shell
 * mounts a persistent bar of its own for those (the old bottom CenterViewTabBar showed chat's
 * tabs in every room and is deleted, 2026-08-21).
 *
 * The mode switch takes effect immediately, no restart: [container.interactionMode][
 * dev.fonebrew.data.InteractionModeBridge] is a `StateFlow`, so Settings → General's
 * "Interaction style" picker calling `setMode` recomposes this `when` on the next frame.
 *
 * [dark] is resolved once in [MainActivity] (via [dev.fonebrew.ui.theme.resolveDark])
 * and threaded down rather than re-derived here, so [FonebrewSplash]'s asset pair can
 * never land on a different mode than [dev.fonebrew.ui.theme.FonebrewTheme] itself.
 */
@Composable
fun AppRoot(dark: Boolean) {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val onboarded by container.sessionStore.onboardingDone.collectAsState()
    val interactionMode by container.interactionMode.mode.collectAsState()
    var showSplash by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        when {
            showSplash -> FonebrewSplash(dark = dark, onFinished = { showSplash = false })
            !onboarded -> OnboardingScreen(onDone = { container.sessionStore.setOnboardingDone() })
            interactionMode == InteractionMode.REGULAR -> RegularShell()
            else -> SpatialRoot()
        }
        // Visible-at-launch build-identity signal (permanent — keep in every build).
        VersionBadge(modifier = Modifier.align(Alignment.BottomStart))
    }
}
