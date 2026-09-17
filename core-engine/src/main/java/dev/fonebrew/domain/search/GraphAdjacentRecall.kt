package dev.fonebrew.domain.search

import dev.fonebrew.domain.thread.EdgeDerivation
import dev.fonebrew.domain.thread.ThreadEdgeKind
import dev.fonebrew.domain.thread.ThreadGraph
import dev.fonebrew.domain.thread.ThreadGraphEdge

/**
 * **Graph-adjacent context retrieval** — the graph-traversal-instead-of-embeddings sibling of
 * semantic recall. Given [SearchHit] node ids from the real FTS5/[LexicalSearch] pipeline and a
 * [ThreadGraph] snapshot ([dev.fonebrew.domain.thread.ThreadGraphProjector]'s output), [expand]
 * walks one hop of *recorded* graph structure out from each hit and returns the neighbours it
 * finds — each one carrying a **citable edge** (the edge's own [ThreadGraphEdge.because]) plus
 * the query term that made its originating hit match in the first place.
 *
 * This is deliberately **not** a ranking-by-similarity-score design. There is no embedder, no
 * vector, no learned notion of "relatedness" here — every recalled item is reachable by naming
 * the exact recorded fact that connects it to a hit (a reply pointer, a fork/spawn lineage
 * record, a decision's own anchor). That is the whole point: a user can ask "why is this here"
 * and get a real, structural answer, not a cosine-similarity number.
 *
 * ### Relation to the embedder-driven [dev.fonebrew.domain.scope.ContextAssembly.AssemblyMode.Recall]
 * [dev.fonebrew.domain.scope.ContextAssembly]'s `TODO(embedder)` marks the still-blocked
 * semantic-recall slot (Issue #2 — an on-device embedder is real interpretation/analysis
 * machinery, not a structural fact). This type is the *non-semantic sibling* the lane brief asks
 * for: a real graph, traversed with citable edges. It does not replace, wire into, or unblock
 * that TODO; [dev.fonebrew.domain.search.SemanticSearchProvider] and
 * `dev.fonebrew.embedding.PlaceholderEmbedder` are untouched by this file and stay exactly as
 * blocked as before.
 *
 * ### Real UI call site (lane A1)
 * Through graph-wave lane C this type was complete and tested but **callerless outside tests** —
 * its only consumer, [dev.fonebrew.domain.scope.ContextAssembly.assembleGraphAdjacent], was
 * itself test-only, and no UI ever built a [Seed] list or ran [expand]. Lane A1 closes that gap:
 * [dev.fonebrew.ui.search.SearchOverlay]'s **"Related context"** action (a search result row's
 * own tap/long-press affordance) is the first real caller — a [Seed] built from the tapped hit
 * ([seedsFrom]'s own two-field shape, read off the presented row rather than the raw hit; see
 * [dev.fonebrew.ui.search.GraphAdjacentRecallPresenter.seedsFor]'s KDoc), [expand] over a
 * [ThreadGraph] snapshot obtained through
 * [dev.fonebrew.ui.ChatViewModel.loadThreadGraph] (the same projector path `ui/graph/
 * GraphRoom.kt` already uses), presented in a sheet via
 * [dev.fonebrew.ui.search.GraphAdjacentRecallPresenter]. That sheet calls [expand] directly, not
 * `assembleGraphAdjacent` — see the presenter's own KDoc for why — so `assembleGraphAdjacent`
 * itself remains exactly as test-only as before; this lane gives [GraphAdjacentRecall] a real
 * caller, not that wrapper.
 *
 * ### Issue #2 boundary (descriptive only)
 * Every [Citation] carries structure, never judgment: a recorded edge and its own truthful
 * `because` text (verbatim — this file never rewrites or re-interprets it), plus the literal
 * query terms that matched. Nothing here scores, ranks by "relevance", or characterizes the
 * user's phrasing/intent. [expand] only ever walks [EdgeDerivation.EXTRACTED] edges — an
 * [EdgeDerivation.INFERRED] edge (reserved for a future analysis layer, per
 * [ThreadGraphEdge]'s own KDoc) is never traversed, so this stays a projection of recorded facts,
 * exactly like [dev.fonebrew.domain.thread.ThreadGraphProjector] itself.
 *
 * ### The six relations (one hop, deterministic priority)
 * In the priority order used to break ties (see [expand]'s ordering, and [Relation]'s own
 * declaration order, which *is* the priority):
 * 1. [Relation.PARENT] — the hit's own tree parent, via the backward [ThreadEdgeKind.REPLY] edge.
 * 2. [Relation.CHILDREN] — the hit's own tree children, via forward [ThreadEdgeKind.REPLY] edges.
 * 3. [Relation.ALTERNATIVES] — sibling branches: the hit's parent's *other* REPLY children
 *    (regenerated/edited alternatives under the same parent). Derived by combining the hit's own
 *    parent edge with that parent's other REPLY edges — each sibling is still cited by its own
 *    real REPLY edge, never fabricated.
 * 4. [Relation.LINEAGE_SOURCE] — [ThreadEdgeKind.FORK] and [ThreadEdgeKind.LINEAGE] edges
 *    touching the hit, either direction: the conversation this one forked from, or (for a
 *    `LINEAGE_SRC` marker/its source node) the pointer between them.
 * 5. [Relation.DECISION_ANCHOR] — [ThreadEdgeKind.DECISION_ANCHOR] edges touching the hit, either
 *    direction: a decision bookmarked against this message, or (from the decision node) the
 *    message it anchors to.
 * 6. [Relation.BRIDGE] — [ThreadEdgeKind.SPAWN] edges touching the hit, either direction. Named
 *    "bridge" rather than "lineage source" because Spawn's own domain machinery
 *    ([dev.fonebrew.domain.bridge.SpawnBridge]/`SpawnBridges`/`BridgeCodec`) already calls this
 *    relationship a *bridge* — Fork has no such name, hence the split from [LINEAGE_SOURCE].
 *
 * Deliberately **out of scope** (named follow-up, not a silent gap): [ThreadEdgeKind.MARKER_ANCHOR],
 * [ThreadEdgeKind.DELEGATION_ANCHOR] and [ThreadEdgeKind.COMMIT_ANCHOR] are not walked — the lane
 * brief names exactly the six relations above, and markers/delegations/commits already have their
 * own dedicated surfaces (`GraphRoom`, the delegation ledger). A future pass can fold them in if
 * the owner wants graph-adjacent recall to cite them too.
 *
 * Pure Kotlin (no Android, no IO, no clock, no randomness): identical inputs always produce an
 * identical, identically-ordered [Result].
 */
object GraphAdjacentRecall {

    /** The six one-hop relations [expand] walks — declaration order is the tie-break priority
     *  used when a hit has candidates in more than one relation (see [expand]). */
    enum class Relation {
        PARENT,
        CHILDREN,
        ALTERNATIVES,
        LINEAGE_SOURCE,
        DECISION_ANCHOR,
        BRIDGE,
    }

    /**
     * One FTS5/[LexicalSearch] hit to expand from.
     *
     * @property nodeId the hit's [SearchDoc.id] — a [ThreadGraph] node id (today, a conversation
     *   root, since [dev.fonebrew.data.search.SearchProjector] indexes conversation-level).
     * @property matchedQueryTerms the query terms that made this hit match, for the citation —
     *   never invented; see [seedsFrom] for how a real [SearchHit] supplies these.
     */
    data class Seed(val nodeId: String, val matchedQueryTerms: List<String>)

    /**
     * Builds [Seed]s straight from real [SearchHit]s (the FTS5 → [LexicalSearch] pipeline's own
     * output — [dev.fonebrew.data.search.SearchQuery.search] always ranks with `explain = true`,
     * so production hits already carry [MatchExplanation.termContributions]). A hit with no
     * explanation (e.g. a facet-only query, or a caller that ranked with `explain = false`)
     * honestly contributes an empty term list rather than a fabricated one — [expand] still
     * expands it, the citation just names no matched term.
     */
    fun seedsFrom(hits: List<SearchHit>): List<Seed> =
        hits.map { hit ->
            Seed(
                nodeId = hit.doc.id,
                matchedQueryTerms = hit.explanation?.termContributions?.map { it.term }?.distinct().orEmpty(),
            )
        }

    /**
     * One recorded, citable reason a node was recalled.
     *
     * @property relation which of the six [Relation]s connected it to [fromNodeId].
     * @property because the connecting [ThreadGraphEdge.because] — verbatim, never rewritten.
     * @property fromNodeId which [Seed.nodeId] this citation was discovered from.
     * @property matchedQueryTerms that seed's own [Seed.matchedQueryTerms] — the "the matched
     *   query term" half of "why was this recalled".
     */
    data class Citation(
        val relation: Relation,
        val because: String,
        val fromNodeId: String,
        val matchedQueryTerms: List<String>,
    )

    /**
     * One recalled node, with every citable reason it was reached (a node can be one hop from
     * more than one hit, or reachable from the same hit by more than one relation — every such
     * path is kept, never collapsed to just the first one found).
     */
    data class RecalledItem(val nodeId: String, val citations: List<Citation>)

    /**
     * The outcome of [expand]: a deterministically-ordered, capped recall list plus an honest
     * account of what the cap left out — never a silent truncation.
     *
     * @property included the recalled items that fit within [cap], in priority order.
     * @property cut every other candidate the traversal actually found, beyond [cap] — still
     *   fully cited, so a caller can show *what* was cut, not just how many.
     * @property cap the cap that was applied (echoed back so a caller/UI never has to remember it
     *   separately from the result).
     */
    data class Result(val included: List<RecalledItem>, val cut: List<RecalledItem>, val cap: Int) {
        /** Total distinct nodes the traversal found, before capping — `included.size + cut.size`. */
        val consideredCount: Int get() = included.size + cut.size

        /** True iff the cap actually left something out. */
        val truncated: Boolean get() = cut.isNotEmpty()
    }

    /**
     * Expand [seeds] one hop through [graph]'s [EdgeDerivation.EXTRACTED] edges, per the six
     * [Relation]s documented on this object.
     *
     * ### Ordering (fully deterministic)
     * [seeds] are walked in the order given — that order **is** hit rank, caller-supplied (e.g.
     * [SearchHit] ranking order). Within one seed, relations are walked in [Relation]'s own
     * declaration order (the documented priority), and candidates within one relation are ordered
     * by neighbour node id ascending. A node's position in the result is fixed by the *first* time
     * it is discovered under that walk order; every later discovery of the same node (a different
     * seed, or a different relation from the same seed) only adds another [Citation] to its
     * existing [RecalledItem] — it never moves or duplicates the item.
     *
     * ### What is excluded
     * - Any edge that is not [EdgeDerivation.EXTRACTED] (in particular, [EdgeDerivation.INFERRED]
     *   is never traversed — see this object's class KDoc).
     * - A neighbour that is itself one of [seeds]' own node ids — already a direct hit, not new
     *   recall.
     *
     * @param cap the hard cap on [Result.included] size; must be `>= 0`. Never a silent cut —
     *   [Result.cut] and [Result.truncated] always show what the cap left out.
     */
    fun expand(seeds: List<Seed>, graph: ThreadGraph, cap: Int): Result {
        require(cap >= 0) { "cap must be >= 0 (got $cap)" }
        val seedIds = seeds.mapTo(HashSet()) { it.nodeId }

        // Adjacency indices over EXTRACTED edges only — built once, walked per seed below.
        val outgoing = HashMap<String, MutableList<ThreadGraphEdge>>()
        val incoming = HashMap<String, MutableList<ThreadGraphEdge>>()
        for (edge in graph.edges) {
            if (edge.derivation != EdgeDerivation.EXTRACTED || edge.because == null) continue
            outgoing.getOrPut(edge.from) { mutableListOf() }.add(edge)
            incoming.getOrPut(edge.to) { mutableListOf() }.add(edge)
        }

        /** Neighbours reached from [nodeId] by an edge of [kind] in EITHER direction, paired with
         *  the connecting edge, deduped by neighbour id and sorted by neighbour id ascending. */
        fun touching(nodeId: String, kind: ThreadEdgeKind): List<Pair<String, ThreadGraphEdge>> =
            (incoming[nodeId].orEmpty() + outgoing[nodeId].orEmpty())
                .filter { it.kind == kind }
                .map { edge -> (if (edge.from == nodeId) edge.to else edge.from) to edge }
                .distinctBy { it.first }
                .sortedBy { it.first }

        val byNode = LinkedHashMap<String, MutableList<Citation>>()
        fun record(neighborId: String, relation: Relation, because: String, seed: Seed) {
            if (neighborId in seedIds) return // already a direct hit, not new recall
            byNode.getOrPut(neighborId) { mutableListOf() }
                .add(Citation(relation, because, seed.nodeId, seed.matchedQueryTerms))
        }

        for (seed in seeds) {
            val hitId = seed.nodeId

            // 1. PARENT — the hit's own backward REPLY edge(s).
            incoming[hitId].orEmpty()
                .filter { it.kind == ThreadEdgeKind.REPLY }
                .sortedBy { it.from }
                .forEach { record(it.from, Relation.PARENT, it.because!!, seed) }

            // 2. CHILDREN — the hit's own forward REPLY edges.
            outgoing[hitId].orEmpty()
                .filter { it.kind == ThreadEdgeKind.REPLY }
                .sortedBy { it.to }
                .forEach { record(it.to, Relation.CHILDREN, it.because!!, seed) }

            // 3. ALTERNATIVES — the hit's parent's other REPLY children (sibling branches).
            val parentId = incoming[hitId].orEmpty().firstOrNull { it.kind == ThreadEdgeKind.REPLY }?.from
            if (parentId != null) {
                outgoing[parentId].orEmpty()
                    .filter { it.kind == ThreadEdgeKind.REPLY && it.to != hitId }
                    .sortedBy { it.to }
                    .forEach { record(it.to, Relation.ALTERNATIVES, it.because!!, seed) }
            }

            // 4. LINEAGE_SOURCE — FORK + LINEAGE edges, either direction.
            (touching(hitId, ThreadEdgeKind.FORK) + touching(hitId, ThreadEdgeKind.LINEAGE))
                .sortedBy { it.first }
                .forEach { (neighborId, edge) -> record(neighborId, Relation.LINEAGE_SOURCE, edge.because!!, seed) }

            // 5. DECISION_ANCHOR — either direction.
            touching(hitId, ThreadEdgeKind.DECISION_ANCHOR)
                .forEach { (neighborId, edge) -> record(neighborId, Relation.DECISION_ANCHOR, edge.because!!, seed) }

            // 6. BRIDGE — SPAWN edges, either direction.
            touching(hitId, ThreadEdgeKind.SPAWN)
                .forEach { (neighborId, edge) -> record(neighborId, Relation.BRIDGE, edge.because!!, seed) }
        }

        val allItems = byNode.map { (nodeId, citations) -> RecalledItem(nodeId, citations.toList()) }
        val included = allItems.take(cap)
        return Result(included = included, cut = allItems.drop(included.size), cap = cap)
    }
}
