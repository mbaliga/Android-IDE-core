package dev.fonebrew.domain.loop.authoring

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.loop.ConditionGatewayPolicy

/**
 * Drives [TouchConnectionGrammar] for the **gateway condition editor** — the UI surface
 * `LoopRoom.kt` shows when `connectNodes` finds the new edge's source is a gateway
 * (`LOOP_PHONE_AUTHORING_SPEC.md` §5/§7, `FB-RAT-PHN-004`).
 *
 * Audit gap (asoc-reachability, 2026-09-15): before this, [TouchConnectionGrammar.Event
 * .DefineCondition]/[TouchConnectionGrammar.Event.ChooseLabel]/[TouchConnectionGrammar.Event
 * .RequestPreview] had **zero production senders** — the grammar existed as spec with no caller.
 * This presenter is that caller. It holds no legality logic of its own: every mutation is a real
 * [TouchConnectionGrammar.apply] call, so the grammar stays the single source of truth for what's
 * legal, exactly like [WireDragGesture] is for the drag gesture. A UI that skipped this presenter
 * and mutated the graph directly would be bypassing the state machine the spec calls normative —
 * this object exists so nothing has to.
 *
 * [previewFor] is the other half of the gap: it answers "which branch would this take?" by
 * reusing [ConditionGatewayPolicy] **verbatim** — the exact policy object
 * [dev.fonebrew.domain.loop.GraphRunner] runs a real loop with — so the preview can never show a
 * branch the runner wouldn't actually take. [ConditionGatewayPolicy] only ever recognises a
 * `condition` of literally `"approved"`/`"!approved"` (or a `name` of "approve"/"refine"/
 * "reject"/"yes"/"no"); a free-form condition the author types that isn't one of those is honestly
 * previewed as falling through to the gateway's default branch, not as if it were evaluated as an
 * expression — this engine does not have a condition-expression evaluator, and inventing one just
 * for the preview would make the preview lie about what a real run does (sovereignty stance:
 * never invent a fact the engine doesn't back).
 */
object GatewayConditionPresenter {

    /** One outgoing edge as [previewFor] needs it — deliberately not [BpmnEdge]: the presenter
     *  has no use for an edge id or a fixed targetId, only the two fields [ConditionGatewayPolicy]
     *  actually reads. [key] is caller-chosen and round-trips through [PreviewResult.chosenKey]
     *  so the caller can tell which of *its own* edges (or the one being authored) was chosen,
     *  without this object having to know anything about how the caller identifies an edge. */
    data class CandidateEdge(val key: String, val label: String?, val condition: String?)

    /** [chosenKey] is the [CandidateEdge.key] (or [DRAFT_KEY] for the edge being authored)
     *  [ConditionGatewayPolicy] would actually select for [sampleOutput] — `null` only when the
     *  gateway would have **no** outgoing edge to take at all (mirrors a real
     *  [dev.fonebrew.domain.loop.GraphRunResult.stoppedBecause] `"no outgoing edge"` stop; an
     *  empty candidate list is the only way to reach that here since [previewFor] always adds the
     *  draft edge itself). */
    data class PreviewResult(val chosenKey: String?, val sampleOutput: String)

    /** The key [previewFor] reports for the edge being authored (never a real persisted edge id,
     *  so it can't collide with one of [previewFor]'s [existingEdges] keys unless the caller
     *  deliberately reuses it — callers should not). */
    const val DRAFT_KEY: String = "__draft__"

    /**
     * Rebuilds the draft the tap/drag gesture already implied — [TouchConnectionGrammar.Event
     * .SelectSource] + [TouchConnectionGrammar.Event.ConnectFromHere] + [TouchConnectionGrammar
     * .Event.ChooseDestination] — as **real grammar transitions**, not a hand-built
     * [TouchConnectionGrammar.ConnectionDraft] that skips the state machine. Always legal from a
     * fresh draft (same guarantee [WireDragGesture.begin] documents for its own two-event
     * collapse), so this never has to hand the caller a [TouchConnectionGrammar.Result.Rejected]
     * to branch on.
     */
    fun start(sourceNodeId: String, destinationNodeId: String, isNewDestinationNode: Boolean = false): TouchConnectionGrammar.ConnectionDraft {
        val selected = TouchConnectionGrammar.apply(TouchConnectionGrammar.start(), TouchConnectionGrammar.Event.SelectSource(sourceNodeId))
        check(selected is TouchConnectionGrammar.Result.Advanced) { "SelectSource is legal from a fresh IDLE draft; got $selected" }
        val awaiting = TouchConnectionGrammar.apply(selected.draft, TouchConnectionGrammar.Event.ConnectFromHere)
        check(awaiting is TouchConnectionGrammar.Result.Advanced) { "ConnectFromHere is legal right after SelectSource; got $awaiting" }
        val chosen = TouchConnectionGrammar.apply(awaiting.draft, TouchConnectionGrammar.Event.ChooseDestination(destinationNodeId, isNewDestinationNode))
        check(chosen is TouchConnectionGrammar.Result.Advanced) { "ChooseDestination is legal right after ConnectFromHere; got $chosen" }
        return chosen.draft
    }

    /**
     * [label] is one of the editor's quick literals ("approve"/"refine"/"else") or any other text
     * the author typed as the branch label. [condition] is the free-form condition field's text,
     * blank/null when the author left it empty. Setting [TouchConnectionGrammar.ConnectionDraft
     * .gatewayRequiresCondition] is this presenter's job — [TouchConnectionGrammar] documents it
     * as caller-set "before ChooseLabel" — set true exactly when the author actually typed a
     * condition, so the grammar only demands [defineCondition] next when there is one to define.
     */
    fun chooseLabel(draft: TouchConnectionGrammar.ConnectionDraft, label: String, condition: String?): TouchConnectionGrammar.Result {
        val requiresCondition = !condition.isNullOrBlank()
        return TouchConnectionGrammar.apply(
            draft.copy(gatewayRequiresCondition = requiresCondition),
            TouchConnectionGrammar.Event.ChooseLabel(outputPort = null, label = label),
        )
    }

    fun defineCondition(draft: TouchConnectionGrammar.ConnectionDraft, condition: String): TouchConnectionGrammar.Result =
        TouchConnectionGrammar.apply(draft, TouchConnectionGrammar.Event.DefineCondition(condition))

    fun requestPreview(draft: TouchConnectionGrammar.ConnectionDraft): TouchConnectionGrammar.Result =
        TouchConnectionGrammar.apply(draft, TouchConnectionGrammar.Event.RequestPreview)

    fun commit(draft: TouchConnectionGrammar.ConnectionDraft): TouchConnectionGrammar.Result =
        TouchConnectionGrammar.apply(draft, TouchConnectionGrammar.Event.Commit)

    fun cancel(draft: TouchConnectionGrammar.ConnectionDraft): TouchConnectionGrammar.Result =
        TouchConnectionGrammar.apply(draft, TouchConnectionGrammar.Event.Cancel)

    /**
     * What [ConditionGatewayPolicy] would actually choose among [existingEdges] plus the edge
     * [draft] is authoring (label/condition read straight off [draft], so a preview taken before
     * [defineCondition] honestly reflects "no condition yet" rather than a value that hasn't been
     * committed), for [sampleOutput] standing in for the last step's real output. [BpmnNode] isn't
     * read by [ConditionGatewayPolicy.choose] at all (only `lastOutput`/`outgoing` are), so a
     * placeholder node is enough — never a stand-in for a fact this preview is claiming to know.
     */
    fun previewFor(
        draft: TouchConnectionGrammar.ConnectionDraft,
        existingEdges: List<CandidateEdge>,
        sampleOutput: String,
    ): PreviewResult {
        val all = existingEdges + CandidateEdge(DRAFT_KEY, draft.label, draft.conditionExpression)
        val bpmnEdges = all.mapIndexed { i, c ->
            BpmnEdge(id = "preview-$i", sourceId = "gateway", targetId = "target-$i", name = c.label, condition = c.condition)
        }
        val placeholderGateway = BpmnNode(id = "gateway", kind = BpmnNodeKind.EXCLUSIVE_GATEWAY)
        val chosen = ConditionGatewayPolicy.choose(placeholderGateway, sampleOutput, bpmnEdges)
        val chosenIndex = bpmnEdges.indexOf(chosen)
        val chosenKey = if (chosenIndex >= 0) all[chosenIndex].key else null
        return PreviewResult(chosenKey, sampleOutput)
    }
}
