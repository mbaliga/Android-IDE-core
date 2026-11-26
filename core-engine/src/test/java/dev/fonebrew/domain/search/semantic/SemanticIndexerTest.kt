package dev.fonebrew.domain.search.semantic

import dev.fonebrew.domain.search.Segmenter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticIndexerTest {
    private val chunker: (dev.fonebrew.domain.search.SearchDoc) -> DocChunker.Chunked = { DocChunker.chunk(it, Segmenter.JavaTextBoundarySource) }
    private fun indexer(store: VectorStore, e: SemanticEmbedder) = SemanticIndexer(store, e, chunker)

    @Test fun `first run embeds every doc and writes manifests`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        val out = indexer(store, e).run(listOf(doc("a"), doc("b")))
        assertEquals(SemanticIndexer.Outcome.Finished(2, 0), out)
        assertEquals(setOf("a", "b"), store.manifests().map { it.docId }.toSet())
        assertTrue(store.manifests().all { it.embedderId == e.id && it.dim == e.dim })
    }

    @Test fun `second run embeds nothing - incremental`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        val docs = listOf(doc("a"), doc("b"))
        indexer(store, e).run(docs)
        val before = e.calls
        val out = indexer(store, e).run(docs)
        assertEquals(SemanticIndexer.Outcome.Finished(0, 0), out)
        assertEquals(before, e.calls)
    }

    @Test fun `only a changed doc is re-embedded`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        indexer(store, e).run(listOf(doc("a"), doc("b")))
        val before = e.calls
        indexer(store, e).run(listOf(doc("a"), doc("b", body = "completely different text")))
        assertEquals(before + 1, e.calls)
    }

    @Test fun `deleted docs lose their vectors`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        indexer(store, e).run(listOf(doc("a"), doc("b")))
        indexer(store, e).run(listOf(doc("a")))
        assertEquals(listOf("a"), store.manifests().map { it.docId })
        assertTrue(store.entries(e.id).none { it.docId == "b" })
    }

    @Test fun `a different embedder id makes every doc stale and replaces its vectors, never mixing`() = runTest {
        val store = InMemoryVectorStore()
        val old = FakeEmbedder(id = "llama-embed:old:1:none:dim8")
        val new = FakeEmbedder(id = "llama-embed:new:2:none:dim8")
        val docs = listOf(doc("a"), doc("b"))
        indexer(store, old).run(docs)
        val plan = indexer(store, new).plan(docs)
        assertEquals(2, plan.stale); assertEquals(0, plan.upToDate)
        indexer(store, new).run(docs)
        assertTrue(store.manifests().all { it.embedderId == new.id })
        assertEquals(0, store.entries(old.id).count())
        assertTrue(store.entries(new.id).count() > 0)
        assertEquals(0, indexer(store, new).plan(docs).stale)
    }

    @Test fun `pause stops between docs and resume continues without redoing finished docs`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        val docs = (1..5).map { doc("d$it") }
        var paused = false
        val out = indexer(store, e).run(docs, isPaused = { paused }) { done, _ -> if (done == 2) paused = true }
        assertEquals(SemanticIndexer.Outcome.Paused(2), out)
        assertEquals(2, store.manifests().size)
        paused = false
        val before = e.calls
        val out2 = indexer(store, e).run(docs)
        assertEquals(SemanticIndexer.Outcome.Finished(3, 0), out2)
        assertEquals(before + 3, e.calls) // one chunk each
    }

    @Test fun `process death mid-doc leaves no manifest and the next run redoes only that doc`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        val docs = listOf(doc("a"), doc("b"), doc("c"))
        store.failNextReplace = false
        var n = 0
        val dying = object : VectorStore by store {
            override fun replaceDoc(manifest: DocManifest, vectors: List<FloatArray>) {
                if (++n == 2) throw IllegalStateException("killed")
                store.replaceDoc(manifest, vectors)
            }
        }
        try { indexer(dying, e).run(docs) } catch (_: IllegalStateException) {}
        assertEquals(listOf("a"), store.manifests().map { it.docId })
        val plan = indexer(store, e).plan(docs)
        assertEquals(listOf("b", "c"), plan.toEmbed.map { it.id })
        assertEquals(1, plan.upToDate)
        assertEquals(SemanticIndexer.Outcome.Finished(2, 0), indexer(store, e).run(docs))
    }

    @Test fun `a failing doc is skipped and left without a manifest`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        e.failOn = { it.contains("poison") }
        val out = indexer(store, e).run(listOf(doc("a"), doc("p", body = "poison"), doc("c")))
        assertEquals(SemanticIndexer.Outcome.Finished(2, 1), out)
        assertNull(store.manifests().firstOrNull { it.docId == "p" })
    }

    @Test fun `repeated failures stop the run instead of looping on a broken model`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        e.failOn = { true }
        val out = indexer(store, e).run((1..10).map { doc("d$it") })
        assertTrue(out is SemanticIndexer.Outcome.Failed)
        assertTrue(e.calls <= SemanticIndexer.MAX_CONSECUTIVE_FAILURES + 1)
    }

    @Test fun `a wrong-dimension or non-finite vector is refused, not stored`() = runTest {
        val store = InMemoryVectorStore()
        val bad = object : SemanticEmbedder {
            override val id = "llama-embed:bad:1:none:dim8"; override val dim = 8
            override suspend fun embedDocument(text: String) = floatArrayOf(1f, 2f)
            override suspend fun embedQuery(text: String) = floatArrayOf(1f)
        }
        val out = indexer(store, bad).run(listOf(doc("a")))
        assertTrue(out is SemanticIndexer.Outcome.Failed || (out as SemanticIndexer.Outcome.Finished).skipped == 1)
        assertTrue(store.manifests().isEmpty())
    }

    @Test fun `stored vectors are normalised`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        indexer(store, e).run(listOf(doc("a")))
        store.entries(e.id).forEach { assertEquals(1f, VectorMath.norm(it.vector), 1e-5f) }
    }

    @Test fun `progress reports done over total`() = runTest {
        val store = InMemoryVectorStore(); val e = FakeEmbedder()
        val seen = ArrayList<Pair<Int, Int>>()
        indexer(store, e).run((1..3).map { doc("d$it") }) { d, t -> seen.add(d to t) }
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test fun `query ranks by meaning only within the loaded embedder's vectors`() = runTest {
        val store = InMemoryVectorStore()
        val e = FakeEmbedder()
        val other = FakeEmbedder(id = "llama-embed:other:9:none:dim8")
        indexer(store, e).run(listOf(doc("cat", title = "cats", body = "cat cat cat"), doc("car", title = "cars", body = "car car car")))
        // Another embedder's vector for a doc that would otherwise win: must never appear.
        store.replaceDoc(DocManifest("intruder", other.id, "h", 1, 8), listOf(VectorMath.normalized(other.embedQuery("cat cat cat"))))
        val ranked = SemanticQuery(store, e).rank("cat", 5)!!
        assertEquals("cat", ranked.first().docId)
        assertTrue(ranked.none { it.docId == "intruder" })
    }

    @Test fun `query against an empty index is null, not an empty result set`() = runTest {
        assertNull(SemanticQuery(InMemoryVectorStore(), FakeEmbedder()).rank("anything", 5))
        assertNotNull(SemanticQuery(InMemoryVectorStore(), FakeEmbedder()).rank("", 5))
    }
}
