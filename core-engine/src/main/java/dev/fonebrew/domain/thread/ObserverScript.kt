// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.thread

/**
 * **WP9** — turns a [ThreadGraph] (or a [ThreadDeltas.Diff] between two of them) into short,
 * descriptive remark strings: what the graph *is*, structurally, never what it *means*.
 *
 * THREAD_TOPOLOGY_PLAN.md's WP9 bullet calls this "descriptive tone — Orrery's anti-'overseer'
 * ruling." That ruling isn't itself restated anywhere in this repo (grepped `docs/` — no other
 * hit), so this file applies it as the plain-language reading, consistent with every other
 * "descriptive only" boundary this plan already enforces in code (binding constraint 3 /
 * CLAUDE.md rule 4, [DelegationEvent.outcome]'s KDoc, [ThreadChains]'s counts, [DelegationCounts]):
 * a remark states a **count or a structural fact** ("3 forks this session," "1 marker added") and
 * never a judgment, a "why," an imperative, or second-person framing that casts the observer as
 * watching *over* the user rather than reporting *back to* them ("you should," "you failed to,"
 * "you strayed") — that framing is exactly what "anti-overseer" reads as ruling out. If the
 * owner's actual Orrery ruling differs, this is flagged here as an assumption to revisit, not a
 * silent guess (CLAUDE.md rule 6).
 *
 * Pure string formatting, JVM-tested for both correctness (right counts) and tone (no banned
 * words) — [ObserverScriptTest] asserts the banned-word list holds structurally, not just by eye.
 * Callers ([dev.fonebrew.data.ThreadObserver] today; a future WP10 remark card) present these **on
 * request only** — this object has no push/notification surface of its own, matching the plan's
 * "remarks presented on request, never pushed."
 */
object ObserverScript {

    /** Second-person imperative/judgment words a remark must never contain (case-insensitive
     *  substring check) — the "anti-overseer" guard [ObserverScriptTest] enforces mechanically. */
    private val BANNED_WORDS = listOf(
        "you should", "you must", "you failed", "you strayed", "you need to", "you forgot",
        "warning", "alert", "overseer", "watching you", "why did you", "should have",
    )

    /** A snapshot description: structural counts only, oldest-to-newest order implied by nothing
     *  here (this is a summary, not a timeline — [describeDelta] is the timeline-flavored one). */
    fun describe(graph: ThreadGraph): List<String> {
        if (graph.nodes.isEmpty() && graph.edges.isEmpty()) {
            return listOf("No conversation activity captured yet.")
        }
        val remarks = ArrayList<String>()
        val byKind = graph.nodes.groupingBy { it.kind }.eachCount()
        val messageCount = byKind[ThreadNodeKind.MESSAGE] ?: 0
        val forkCount = byKind[ThreadNodeKind.FORK_ROOT] ?: 0
        val spawnCount = byKind[ThreadNodeKind.SPAWN_ROOT] ?: 0
        val markerCount = byKind[ThreadNodeKind.MARKER] ?: 0
        val delegationCount = byKind[ThreadNodeKind.DELEGATION] ?: 0

        remarks += "$messageCount message${plural(messageCount)} across the captured tree."
        if (forkCount > 0) remarks += "$forkCount fork${plural(forkCount)} branched off into a new conversation."
        if (spawnCount > 0) remarks += "$spawnCount conversation${plural(spawnCount)} spawned with a summary bridge."
        if (markerCount > 0) remarks += "$markerCount marker${plural(markerCount)} placed (chapters, session starts, compaction runs, lineage)."
        if (delegationCount > 0) remarks += "$delegationCount delegation${plural(delegationCount)} recorded (\"choose for me\")."
        return remarks
    }

    /** A between-two-snapshots description: only what actually changed, degree changes folded
     *  into one summary line rather than one line per node (a remark stays short). */
    fun describeDelta(delta: ThreadDeltas.Diff): List<String> {
        if (delta.isEmpty) return listOf("No structural change since the last snapshot.")
        val remarks = ArrayList<String>()
        if (delta.addedNodes.isNotEmpty()) {
            remarks += "${delta.addedNodes.size} node${plural(delta.addedNodes.size)} added: " +
                delta.addedNodes.groupingBy { it.kind }.eachCount().entries
                    .joinToString(", ") { (kind, count) -> "$count $kind" }
        }
        if (delta.removedNodes.isNotEmpty()) {
            remarks += "${delta.removedNodes.size} node${plural(delta.removedNodes.size)} no longer present."
        }
        if (delta.addedEdges.isNotEmpty()) {
            remarks += "${delta.addedEdges.size} edge${plural(delta.addedEdges.size)} added."
        }
        if (delta.removedEdges.isNotEmpty()) {
            remarks += "${delta.removedEdges.size} edge${plural(delta.removedEdges.size)} removed."
        }
        if (delta.nodeDegreeChanges.isNotEmpty()) {
            remarks += "${delta.nodeDegreeChanges.size} node${plural(delta.nodeDegreeChanges.size)} changed connection count."
        }
        return remarks
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}
