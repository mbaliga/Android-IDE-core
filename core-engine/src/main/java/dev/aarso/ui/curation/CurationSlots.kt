package dev.aarso.ui.curation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What starts a Roundtable race: the message being re-run from, and (optionally) which model ids
 * to pit against each other — an empty list means "let the installed layer pick its own default
 * set." Kept intentionally minimal since the Roundtable orchestration itself (STUDIO_UX_SPEC.md
 * §5.3 — blind mode, consensus map, scorecards) is Studio-side, PC-B, not built by this core-repo
 * change; this is only the request shape core needs to hand off through the seam.
 */
data class RoundtableRequest(
    val originMsgId: String,
    val candidateModelIds: List<String> = emptyList(),
)

/**
 * S-new seam (STUDIO_UX_SPEC.md §5.3/§13 S14): "Cast to models…" / rewind's "Re-run with N
 * models" hand off to whatever an above-core layer installs here. In the bare open core there is
 * no installed content, so those entry points simply don't offer the multi-model option — core
 * never references a concrete Roundtable implementation.
 *
 * Modeled directly on [dev.aarso.ui.spatial.ProjectRoomSlot] (the only *real* precedent for this
 * idiom in the codebase — the "CallSlot" name referenced in some docs does not correspond to any
 * actual class): Compose snapshot state ([mutableStateOf], not a plain `var`) with a
 * private-set backing field and an `install`-only mutator, so a mid-session Studio-entitlement
 * unlock recomposes in place rather than needing a restart.
 */
object RoundtableSlot {
    /** Installed by the Studio layer; null in the bare open core. */
    var content: (@Composable (request: RoundtableRequest, onClose: () -> Unit) -> Unit)? by mutableStateOf(null)
        private set

    fun install(content: @Composable (request: RoundtableRequest, onClose: () -> Unit) -> Unit) {
        this.content = content
    }

    val isInstalled: Boolean get() = content != null
}

/**
 * S-new seam (STUDIO_UX_SPEC.md §4.4/§13 S13): the version-suggestion engine ("Mark this branch
 * as *auth-flow v2*?" chip above the composer) is Studio-side heuristics over verdict/build/
 * bookmark signals — core only renders whatever this slot's installed content decides to show
 * for a given branch tip, and shows nothing in the bare open core. Manual "Mark branch as
 * Version" (the curation sheet action) is unaffected by whether this slot is installed — that
 * path is core, always available.
 *
 * Same [ProjectRoomSlot]-modeled shape as [RoundtableSlot].
 */
object VersionSuggestSlot {
    /** Installed by the Studio layer; null in the bare open core. [branchTipMsgId] is the tip the host is currently rendering, so the installed content can decide per-tip whether a chip is warranted. */
    var content: (@Composable (branchTipMsgId: String) -> Unit)? by mutableStateOf(null)
        private set

    fun install(content: @Composable (branchTipMsgId: String) -> Unit) {
        this.content = content
    }

    val isInstalled: Boolean get() = content != null
}
