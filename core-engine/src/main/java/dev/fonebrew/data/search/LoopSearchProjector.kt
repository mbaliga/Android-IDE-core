package dev.fonebrew.data.search

import dev.fonebrew.domain.bpmn.BpmnArchive
import dev.fonebrew.domain.loop.Loop
import dev.fonebrew.domain.search.Segmenter
import dev.fonebrew.domain.search.Stemmer

/**
 * Flattens [Loop]s into search-index rows — the `loop:` half of the search-corpus expansion
 * (Field.kt's [dev.fonebrew.domain.search.query.Field.LOOP]). Pure Kotlin, no Room/SQLDelight/
 * Android, same "presenter" convention as [SearchProjector]: a stateless transform from a domain
 * list to a plain data class, JVM-tested without any of the plumbing that produces [Loop]s at
 * runtime.
 *
 * A loop's *definition* is its BPMN graph, not just its [Loop.name] — a loop named "Draft" whose
 * nodes all say "review the PR" should be findable by `pr`, not just `draft`. [BpmnArchive.read]
 * is the same parser [dev.fonebrew.ui.loops.LoopRoom] uses to load a saved loop back into the
 * builder, so the text this indexes is read the same way the builder reads it — `name` (the
 * graph's own BPMN `<process name>`, normally kept in step with [Loop.name]), every node's
 * `name` (its on-canvas label), and its `systemPrompt`/`role` extension attributes (the
 * instructions/role LoopRoom's node-config sheet edits). A brand-new draft has `bpmnXml == null`
 * (LoopRoom hasn't saved a graph yet) — indexed by name alone rather than skipped, so it is still
 * findable the moment it exists.
 */
object LoopSearchProjector {

    /** Bump when the projection logic changes shape — mirrors [SearchProjector.PROJECTION_VERSION]'s
     *  role, but for [dev.fonebrew.data.search.SearchIndexer.syncLoops]'s own stamp comparison.
     *  2: English stemming ([Stemmer]) was added to [title]/[body]. `loop_projection` has no
     *  separate `tokenizer_version` column the way `index_state` does for the conv corpus
     *  (`Search.sq`'s schema is frozen — see its own header comment) — this constant is loop:'s
     *  only per-row version stamp, so it does double duty as loop:'s tokenizer-version signal
     *  too: bumping it is what makes [dev.fonebrew.data.search.SearchIndexer.syncLoops] treat
     *  every already-indexed loop as stale and re-upsert it with newly-stemmed text, exactly the
     *  way a genuine shape change would. */
    const val PROJECTION_VERSION = 2L

    data class Row(
        val loopId: String,
        val title: String,
        val body: String,
        val titleRaw: String,
        val bodyRaw: String,
        /** [dev.fonebrew.domain.loop.LoopState] name (UNUSED/RUNNING/RETIRED) — what a
         *  `loop:<value>` facet value matches against. */
        val state: String,
        val updatedAt: Long,
        val createdAt: Long,
        val projectionVersion: Long = PROJECTION_VERSION,
    )

    fun project(loops: List<Loop>): List<Row> = loops.map(::projectOne)

    private fun projectOne(loop: Loop): Row {
        val graph = loop.bpmnXml?.let { xml -> runCatching { BpmnArchive.read(xml) }.getOrNull() }
        val nodeText = graph?.nodes.orEmpty().joinToString(" ") { node ->
            listOfNotNull(
                node.name.ifBlank { null },
                node.ext["systemPrompt"]?.ifBlank { null },
                node.ext["role"]?.ifBlank { null },
            ).joinToString(" ")
        }
        val bodyRaw = listOfNotNull(
            graph?.name?.takeIf { it.isNotBlank() && it != loop.name },
            nodeText.ifBlank { null },
        ).joinToString(" ").trim()

        return Row(
            loopId = loop.id,
            // Same segment-then-stem pipeline as SearchProjector — see its own comment on the
            // equivalent lines for why these are two separate passes.
            title = Stemmer.stemJoined(Segmenter.tokenizeForIndex(loop.name)),
            body = Stemmer.stemJoined(Segmenter.tokenizeForIndex(bodyRaw)),
            titleRaw = loop.name,
            bodyRaw = bodyRaw,
            state = loop.state.name,
            updatedAt = loop.updatedAt,
            createdAt = loop.createdAt,
        )
    }
}
