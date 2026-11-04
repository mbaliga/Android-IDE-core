package dev.fonebrew.data

import dev.aarso.interactionmode.InteractionMode
import dev.aarso.interactionmode.InteractionModeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-side, Compose-observable wrapper around [InteractionModeStore]. `dev.aarso:interaction-mode`
 * deliberately ships [InteractionModeStore.mode] as a plain getter rather than a `Flow`, to stay
 * dependency-free (see that interface's own KDoc, which names a reactive wrapper as a NAMED
 * FOLLOW-UP for "once a real consumer needs to react to changes instead of re-reading" — this is
 * that consumer). [AppRoot][dev.fonebrew.ui.AppRoot] needs the shell switch to recompose the
 * moment Settings → General's "Interaction style" picker changes the mode, with no restart — a
 * plain getter alone can't drive that, so this bridge is the app layer's own thin reactive
 * seam on top of the shared, dependency-free module — never a change to that module itself.
 *
 * Every write goes through [InteractionModeStore.setMode] first, so persistence and
 * `hasExplicitChoice` stay single-sourced there; [mode] only ever mirrors what the store now
 * reports, never invents a value of its own.
 */
class InteractionModeBridge(private val store: InteractionModeStore) {

    private val _mode = MutableStateFlow(store.mode)
    val mode: StateFlow<InteractionMode> = _mode.asStateFlow()

    val hasExplicitChoice: Boolean get() = store.hasExplicitChoice

    fun setMode(mode: InteractionMode) {
        store.setMode(mode)
        _mode.value = store.mode
    }
}
