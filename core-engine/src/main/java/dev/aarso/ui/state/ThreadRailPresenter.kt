package dev.aarso.ui.state

import dev.aarso.domain.MessageNode
import dev.aarso.domain.curation.CompactionContract
import dev.aarso.domain.curation.CompactionDirective
import dev.aarso.domain.curation.Fidelity
import dev.aarso.domain.curation.MessageBookmark
import dev.aarso.domain.curation.ResolvedFidelity
import dev.aarso.domain.curation.Verdict
import dev.aarso.domain.curation.Version
import dev.aarso.domain.provenance.ProvenanceState
import dev.aarso.domain.thread.ThreadMarker
import dev.aarso.domain.thread.ThreadMarkerKind
import dev.aarso.domain.tree.PathView
import dev.aarso.domain.tree.TreeFork
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, JVM-testable presenter for **ThreadRail** (THREAD_TOPOLOGY_PLAN.md's dash minimap,
 * naming rule 9 — "spine" was already taken by [dev.aarso.domain.curation.VersionSpines]).
 *
 * One [Dash] per [PathView.Step] on the *active* path — the same path [dev.aarso.ui.ChatScreen]
 * already renders as the message list, so ThreadRail is a compressed side-view of exactly what's
 * on screen, not a separate projection. Every input is a plain-class snapshot (map/list/set) the
 * caller already holds as `StateFlow`s on [dev.aarso.ui.ChatViewModel] — [ChatThreadPresenter] and
 * [dev.aarso.domain.curation.CompactionPreviewPresenter] set the precedent for this shape (data in,
 * render-ready rows out, no ViewModel/Android dependency).
 *
 * See `ui/components/ThreadRail.kt` for the Compose renderer, which drives
 * [dev.aarso.hyle.cells.hylePulse] off [Dash.pulseWatched].
 *
 * **Binding constraint 6 (WCAG 1.4.1)**: colour is never the sole carrier. [Dash.thicknessDp]
 * (fidelity) and [Dash.provenance] (which already carries a machine-enforced, distinct
 * [ProvenanceState.iconKey]/[ProvenanceState.label] pair per state — see
 * `domain/provenance/ProvenanceTest`) are both non-colour channels a renderer can key off before
 * ever touching colour; the accent booleans below are a *third*, independent channel again. A UI
 * may add colour on top of any of these, never in place of them.
 *
 * **Binding constraint 6 (motion)**: [Dash.pulseWatched] is true only for the transient "live"
 * dash appended while a *cloud* generation is in flight ([LiveGeneration.watched]) — motion is
 * reserved for that one real, watched-cloud state, never decorative.
 *
 * **Version-spine simplification specific to a single active path.** The general
 * [dev.aarso.domain.curation.CompactionContract]'s `isOnVersionSpine` means "on the ancestor path
 * of any [Version]'s tip," computed elsewhere ([dev.aarso.domain.curation.VersionSpines]) by
 * walking the whole tree. ThreadRail only ever renders one already-root-to-leaf-ordered path
 * ([steps]), so for THIS path the same fact reduces to "some node at or after me, on this same
 * path, is a marked Version tip" — computable from [steps] + [versionsByTip] alone, no tree walk
 * needed. [present] uses that reduction rather than requiring a
 * [dev.aarso.domain.tree.MessageTree] as an input.
 */
object ThreadRailPresenter {

    /** How many leading characters of a turn's content become the "Jump to…" sheet's row label. */
    private const val LABEL_EXCERPT_LENGTH = 60

    // Thickness ladder (dp) — THREAD_TOPOLOGY_PLAN.md's ThreadRail section, verbatim.
    const val THICKNESS_F3_DP = 3f
    const val THICKNESS_F2_DP = 2.25f
    const val THICKNESS_F1_DP = 1.5f
    const val THICKNESS_F0_DP = 1f
    const val THICKNESS_TOMBSTONE_DP = 1f

    /** Sentinel [Dash.nodeId] for the transient in-flight-generation dash — never a real message id (message ids are UUIDs, never this literal), so callers can branch on it without a separate boolean threading through every call site. */
    const val LIVE_DASH_ID = "__thread_rail_live__"

    /** One render-ready mark on the rail. */
    data class Dash(
        val nodeId: String,
        /** Short label for the "Jump to…" sheet row + TalkBack content description. */
        val label: String,
        val thicknessDp: Float,
        /** A −2-verdict failure tombstone (F1 fidelity, but semantically "this path failed") — the accent's own strike-through cue, independent of [thicknessDp] already bottoming out. */
        val tombstone: Boolean,
        val provenance: ProvenanceState,
        val bookmarked: Boolean,
        /** A named [Version] is marked with its tip exactly at this node. */
        val versionFlag: Boolean,
        val chapterBracket: Boolean,
        val sessionHairline: Boolean,
        val compactionDiamond: Boolean,
        /** Set only on the root step of a forked/spawned conversation (`TreeFork.LINEAGE_KIND_KEY` metadata) — null everywhere else, including every other step of the same path. */
        val lineageBranch: TreeFork.LineageKind?,
        /** [dev.aarso.hyle.Pulse.WATCHED] applies only when true — see the class doc's motion note. */
        val pulseWatched: Boolean,
        val isBranchPoint: Boolean,
        /** True only for the single transient dash appended by [LiveGeneration] — see [LIVE_DASH_ID]. */
        val live: Boolean = false,
    )

    data class RailView(val dashes: List<Dash>) {
        val isEmpty: Boolean get() = dashes.isEmpty()
    }

    /** The in-flight generation, if any — appended as one transient trailing [Dash]. */
    data class LiveGeneration(
        /** Same truth [dev.aarso.ui.ChatViewModel.ModelOption.watched] already carries for the active model — true means this turn is reaching a cloud provider (binding rule 2's watched object). */
        val watched: Boolean,
    )

    /**
     * @param steps the active path, root-first — exactly [dev.aarso.ui.ChatViewModel.ChatUiState.steps].
     * @param verdicts keyed by message id ([dev.aarso.ui.ChatViewModel.verdicts]'s shape).
     * @param bookmarks keyed by message id ([dev.aarso.ui.ChatViewModel.messageBookmarks]'s shape).
     * @param versionsByTip keyed by [Version.branchTipMsgId] ([dev.aarso.ui.ChatViewModel.versionsByTip]'s shape).
     * @param directives keyed by message id ([dev.aarso.ui.ChatViewModel.compactionDirectives]'s shape).
     * @param markersByAnchor keyed by [ThreadMarker.anchorMsgId] ([dev.aarso.ui.ChatViewModel.threadMarkers]'s shape — already pre-filtered to non-null anchors there).
     * @param provenanceOf resolves each node's provenance; caller-supplied so the routing decision
     *   stays the caller's (mirrors [ChatThreadPresenter.present]'s own `provenanceOf` parameter).
     *   Defaults to [ProvenanceState.UNKNOWN] — surfaced honestly rather than guessed.
     * @param live non-null while a turn is generating — appends one trailing transient dash.
     */
    fun present(
        steps: List<PathView.Step>,
        verdicts: Map<String, Verdict> = emptyMap(),
        bookmarks: Map<String, List<MessageBookmark>> = emptyMap(),
        versionsByTip: Map<String, Version> = emptyMap(),
        directives: Map<String, CompactionDirective> = emptyMap(),
        markersByAnchor: Map<String, List<ThreadMarker>> = emptyMap(),
        provenanceOf: (MessageNode) -> ProvenanceState = { ProvenanceState.UNKNOWN },
        live: LiveGeneration? = null,
    ): RailView {
        // "on or after me, on THIS path, is a version tip" — a single backward pass.
        val onOrAfterTip = BooleanArray(steps.size)
        var seenTip = false
        for (i in steps.indices.reversed()) {
            if (versionsByTip.containsKey(steps[i].node.id)) seenTip = true
            onOrAfterTip[i] = seenTip
        }

        val dashes = steps.mapIndexed { index, step ->
            val node = step.node
            val isBookmarked = bookmarks[node.id]?.isNotEmpty() == true
            val resolution = CompactionContract.resolve(
                msgId = node.id,
                directive = directives[node.id],
                verdict = verdicts[node.id],
                isBookmarked = isBookmarked,
                isOnVersionSpine = onOrAfterTip[index],
            )
            val markers = markersByAnchor[node.id].orEmpty()
            Dash(
                nodeId = node.id,
                label = railLabel(node),
                thicknessDp = thicknessFor(resolution),
                tombstone = resolution.isFailureTombstone,
                provenance = provenanceOf(node),
                bookmarked = isBookmarked,
                versionFlag = versionsByTip.containsKey(node.id),
                chapterBracket = markers.any { it.kind == ThreadMarkerKind.CHAPTER },
                sessionHairline = markers.any { it.kind == ThreadMarkerKind.SESSION_START },
                compactionDiamond = markers.any { it.kind == ThreadMarkerKind.COMPACTION_RUN },
                lineageBranch = lineageBranchOf(node),
                pulseWatched = false,
                isBranchPoint = step.isBranchPoint,
            )
        }

        val liveDash = live?.let {
            Dash(
                nodeId = LIVE_DASH_ID,
                label = "Generating…",
                thicknessDp = THICKNESS_F1_DP,
                tombstone = false,
                provenance = if (it.watched) ProvenanceState.CLOUD else ProvenanceState.LOCAL,
                bookmarked = false,
                versionFlag = false,
                chapterBracket = false,
                sessionHairline = false,
                compactionDiamond = false,
                lineageBranch = null,
                pulseWatched = it.watched,
                isBranchPoint = false,
                live = true,
            )
        }

        return RailView(if (liveDash != null) dashes + liveDash else dashes)
    }

    private fun thicknessFor(resolution: ResolvedFidelity): Float = when {
        resolution.isFailureTombstone -> THICKNESS_TOMBSTONE_DP
        else -> when (resolution.fidelity) {
            Fidelity.F3 -> THICKNESS_F3_DP
            Fidelity.F2 -> THICKNESS_F2_DP
            Fidelity.F1 -> THICKNESS_F1_DP
            Fidelity.F0 -> THICKNESS_F0_DP
        }
    }

    private fun lineageBranchOf(node: MessageNode): TreeFork.LineageKind? =
        node.metadata[TreeFork.LINEAGE_KIND_KEY]?.let { raw ->
            runCatching { TreeFork.LineageKind.valueOf(raw) }.getOrNull()
        }

    private fun railLabel(node: MessageNode): String =
        node.content.trim().take(LABEL_EXCERPT_LENGTH).ifBlank { node.role.name.lowercase() }

    // ---- Pure gesture math (tap-to-jump + vertical scrub) — Canvas/pointer code stays a thin,
    // owner-verified wrapper over this; see ui/components/ThreadRail.kt. ----

    /** Nearest dash index for a 0f..1f fraction down the strip (0 = first turn, 1 = latest); -1 when there are no dashes to target. */
    fun indexForFraction(dashCount: Int, fraction: Float): Int {
        if (dashCount <= 0) return -1
        if (dashCount == 1) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped * (dashCount - 1)).roundToInt().coerceIn(0, dashCount - 1)
    }

    /** How many distinct dash indices a scrub crossed between two touch samples — the ratchet-haptic tick count for one drag step. */
    fun ticksCrossed(fromIndex: Int, toIndex: Int): Int = abs(toIndex - fromIndex)
}
