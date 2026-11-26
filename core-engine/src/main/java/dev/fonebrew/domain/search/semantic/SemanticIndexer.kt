package dev.fonebrew.domain.search.semantic

import dev.fonebrew.domain.search.SearchDoc
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** What the indexer and query path need from an embedder; documents and queries may be prefixed differently. */
interface SemanticEmbedder {
    /** Provenance id stored with every vector; a different id means incomparable vectors. */
    val id: String
    val dim: Int
    suspend fun embedDocument(text: String): FloatArray
    suspend fun embedQuery(text: String): FloatArray
}

/**
 * Incremental, pausable, resumable indexing (docs/design/semantic-search.md section 7).
 *
 * Progress lives only in committed manifest rows ([VectorStore.replaceDoc] is atomic), so a
 * process kill mid-doc loses at most that doc's work: it has no manifest, [plan] lists it again.
 */
class SemanticIndexer(
    private val store: VectorStore,
    private val embedder: SemanticEmbedder,
    private val chunker: (SearchDoc) -> DocChunker.Chunked = { DocChunker.chunk(it) },
) {

    data class Plan(
        val toEmbed: List<SearchDoc>,
        val upToDate: Int,
        /** Docs holding vectors from a different embedder id (they are in [toEmbed] too). */
        val stale: Int,
        /** Doc ids in the store but no longer in the corpus. */
        val removed: List<String>,
    ) {
        val total: Int get() = toEmbed.size + upToDate
    }

    sealed interface Outcome {
        data class Finished(val embedded: Int, val skipped: Int) : Outcome
        data class Paused(val embedded: Int) : Outcome
        /** Repeated embed failures; indexing stopped rather than looping on a broken model. */
        data class Failed(val embedded: Int, val message: String) : Outcome
    }

    fun plan(corpus: List<SearchDoc>): Plan {
        val byDoc = store.manifests().associateBy { it.docId }
        val ids = corpus.mapTo(HashSet()) { it.id }
        val todo = ArrayList<SearchDoc>()
        var upToDate = 0
        var stale = 0
        for (doc in corpus) {
            val m = byDoc[doc.id]
            when {
                m != null && m.embedderId == embedder.id && m.contentHash == DocChunker.contentHash(doc) -> upToDate++
                else -> {
                    if (m != null && m.embedderId != embedder.id) stale++
                    todo.add(doc)
                }
            }
        }
        return Plan(todo, upToDate, stale, byDoc.keys.filter { it !in ids })
    }

    suspend fun run(
        corpus: List<SearchDoc>,
        isPaused: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome {
        val plan = plan(corpus)
        plan.removed.forEach(store::removeDoc)
        var done = plan.upToDate
        onProgress(done, plan.total)
        var embedded = 0
        var skipped = 0
        var consecutiveFailures = 0
        for (doc in plan.toEmbed) {
            coroutineContext.ensureActive()
            if (isPaused()) return Outcome.Paused(embedded)
            val chunked = chunker(doc)
            val vectors = ArrayList<FloatArray>(chunked.chunks.size)
            var failure: String? = null
            for (c in chunked.chunks) {
                coroutineContext.ensureActive()
                val v = try {
                    embedder.embedDocument(c.text)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                    break
                }
                if (v.size != embedder.dim || !VectorMath.isFinite(v)) {
                    failure = "embedder returned an unusable vector (dim ${v.size}, expected ${embedder.dim})"
                    break
                }
                vectors.add(VectorMath.normalized(v))
            }
            if (failure != null) {
                skipped++
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return Outcome.Failed(embedded, failure)
                continue
            }
            consecutiveFailures = 0
            store.replaceDoc(DocManifest(doc.id, embedder.id, chunked.contentHash, vectors.size, embedder.dim), vectors)
            embedded++
            done++
            onProgress(done, plan.total)
        }
        return Outcome.Finished(embedded, skipped)
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

/**
 * Ranks the corpus by meaning for one query against the vectors of exactly [embedder]'s id.
 * Returns null when the store holds no vectors for this embedder (the caller reports that as
 * unavailable rather than as "no results").
 */
class SemanticQuery(private val store: VectorStore, private val embedder: SemanticEmbedder) {
    suspend fun rank(query: String, k: Int): List<DocSimilarity>? {
        if (query.isBlank() || k <= 0) return emptyList()
        val q = VectorMath.normalized(embedder.embedQuery(query))
        if (!VectorMath.isFinite(q) || q.size != embedder.dim) return null
        var any = false
        val entries = store.entries(embedder.id).onEach { any = true }
        val ranked = TopK.docs(q, entries, k)
        return if (any) ranked else null
    }
}
