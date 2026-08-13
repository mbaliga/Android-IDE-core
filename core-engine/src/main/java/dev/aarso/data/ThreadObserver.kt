package dev.aarso.data

import dev.aarso.domain.thread.ObserverScript
import dev.aarso.domain.thread.ThreadDeltas
import dev.aarso.domain.thread.ThreadGraph
import dev.aarso.domain.thread.ThreadGraphProjector
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * **WP9** — the observer's data-layer half: builds a [ThreadGraph] from the same three Room
 * stores every other thread-topology reader uses ([MessageTreeRepository], [ThreadMarkerStore],
 * [DelegationStore]), then hands it to the pure [ObserverScript] for remarks. **Never** reads
 * [dev.aarso.domain.mirror.AarsoEventLog] (binding constraint 3 / Issue #2) — this is a plain
 * projection of already-queryable facts, not a consumer of the write-only capture log.
 *
 * Ships **inert by default**: [enabled] is a lambda (same "settings as a function, not a captured
 * snapshot" shape [dev.aarso.domain.mirror.AarsoEventLog]'s own `settings: () -> AarsoCaptureSettings`
 * constructor param uses) so a live [dev.aarso.data.SessionStore.observerEnabled] toggle can be
 * threaded in without this class caching a stale value. Every method below returns an honest
 * "nothing to show" (empty list / null) when [enabled] resolves false — never a fabricated remark,
 * matching [dev.aarso.domain.mirror.Reflection.NONE]'s "materially absent, not faked" rule.
 *
 * No caller exists yet — same "forward pointer, no consumer wired in" pattern [ThreadGraph]'s own
 * KDoc used for this exact class before it existed. A future WP10 observer remark card is the
 * first real reader; today this exists so the substrate is real and JVM-tested ahead of any UI.
 */
class ThreadObserver(
    private val repository: MessageTreeRepository,
    private val markerStore: ThreadMarkerStore,
    private val delegationStore: DelegationStore,
    private val enabled: () -> Boolean,
) {

    /** A fresh [ThreadGraph] snapshot, or `null` when the observer toggle is off. */
    suspend fun snapshot(now: Instant = Instant.now()): ThreadGraph? {
        if (!enabled()) return null
        return ThreadGraphProjector.project(
            tree = repository.tree(),
            markers = markerStore.markers.first(),
            delegations = delegationStore.delegations.first(),
            generatedAtUtc = now,
        )
    }

    /** Descriptive remarks about the current graph — "presented on request, never pushed": this
     *  is a plain suspend call a UI action invokes, not something that observes and fires on its
     *  own. Empty (not a "disabled" sentinel string) when the toggle is off, same honest-absence
     *  rule [snapshot] follows. */
    suspend fun remarks(now: Instant = Instant.now()): List<String> =
        snapshot(now)?.let(ObserverScript::describe) ?: emptyList()

    /** Descriptive remarks about what changed between [before] (an earlier [snapshot] the caller
     *  held onto) and the current graph. Null/empty the same way [remarks] is when the toggle is
     *  off — a caller mid-comparison when the owner switches the toggle off gets silence, not a
     *  half-computed diff. */
    suspend fun remarksSince(before: ThreadGraph, now: Instant = Instant.now()): List<String> {
        val after = snapshot(now) ?: return emptyList()
        return ObserverScript.describeDelta(ThreadDeltas.diff(before, after))
    }
}
