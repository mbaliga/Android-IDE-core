package dev.fonebrew.data.search.semantic

import dev.fonebrew.contracts.search.SemanticUnavailableReason
import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.SemanticSearchOutcome
import dev.fonebrew.domain.search.query.QueryParser
import dev.fonebrew.domain.search.semantic.InMemoryVectorStore
import dev.fonebrew.domain.search.semantic.MatchSource
import dev.fonebrew.domain.search.semantic.SemanticIndexState
import dev.fonebrew.domain.search.semantic.doc
import dev.fonebrew.embedding.EmbedCode
import dev.fonebrew.embedding.FakeEmbedNative
import dev.fonebrew.embedding.LlamaEmbedder
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SemanticSearchServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val job = Job()
    private lateinit var models: EmbeddingModelStore
    private lateinit var settings: InMemorySemanticSettings
    private lateinit var store: InMemoryVectorStore
    private lateinit var native: FakeEmbedNative
    private var docs: List<SearchDoc> = listOf(
        doc("cat", title = "cats", body = "cat cat cat feline"),
        doc("car", title = "cars", body = "car car car engine"),
        doc("tax", title = "taxes", body = "tax tax return"),
    )
    private lateinit var svc: SemanticSearchService

    @Before fun setUp() {
        models = EmbeddingModelStore(java.io.File(tmp.root, "em"))
        settings = InMemorySemanticSettings()
        store = InMemoryVectorStore()
        native = FakeEmbedNative()
        svc = SemanticSearchService(
            settings, models, store, SemanticCorpus { docs }, native,
            CoroutineScope(Dispatchers.Unconfined + job),
            openEmbedder = { LlamaEmbedder.open(native, it, dispatcher = Dispatchers.Unconfined) },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After fun tearDown() { job.cancel() }

    private fun install(name: String, content: String = "payload") {
        models.importFrom(name, ByteArrayInputStream(("GGUF$content").toByteArray()))
    }

    private val provider get() = EmbeddingSemanticSearchProvider(svc)

    @Test fun `default is off - nothing loaded, typed disabled, no fusion`() = runTest {
        assertEquals(SemanticIndexState.NotEnabled, svc.status.value.index)
        assertTrue(native.loadedPaths.isEmpty())
        assertFalse(svc.isActive); assertFalse(provider.enabled)
        val o = provider.search("cat", docs)
        assertEquals(SemanticUnavailableReason.FEATURE_DISABLED, (o as SemanticSearchOutcome.Unavailable).reason)
        assertNull(svc.fuse(QueryParser.parse("cat"), emptyList()))
    }

    @Test fun `enabled without a chosen model is NoModel and typed unavailable`() = runTest {
        settings.setEnabled(true)
        assertEquals(SemanticIndexState.NoModel, svc.status.value.index)
        val o = provider.search("cat", docs) as SemanticSearchOutcome.Unavailable
        assertEquals(SemanticUnavailableReason.NO_EMBEDDER_CONFIGURED, o.reason)
        assertTrue(native.loadedPaths.isEmpty())
    }

    @Test fun `a chosen model alone does not load while disabled`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf")
        assertTrue(native.loadedPaths.isEmpty())
        assertEquals(SemanticIndexState.NotEnabled, svc.status.value.index)
    }

    @Test fun `enable plus installed model loads, indexes, and answers by meaning`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        assertEquals(1, native.loaded.size)
        assertEquals(SemanticIndexState.Ready, svc.status.value.index)
        assertTrue(svc.isActive && provider.enabled)
        val o = provider.search("cat", docs) as SemanticSearchOutcome.Results
        assertEquals("cat", o.hits.first().doc.id)
        assertTrue(o.hits.all { it.matchedIn == MatchedIn.CONTENT })
        assertNotNull(svc.status.value.embedderId)
    }

    @Test fun `provider maps only ids present in the supplied corpus`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        val o = provider.search("cat", listOf(docs[1])) as SemanticSearchOutcome.Results
        assertEquals(listOf("car"), o.hits.map { it.doc.id })
    }

    @Test fun `disabling unloads the embedder`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        settings.setEnabled(false)
        assertTrue(native.loaded.isEmpty())
        assertEquals(1, native.unloaded.size)
        assertEquals(SemanticIndexState.NotEnabled, svc.status.value.index)
        assertFalse(svc.isActive)
    }

    @Test fun `deleting the chosen model while enabled unloads it and reports no embedder`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        models.delete("m.gguf")
        assertTrue(native.loaded.isEmpty())
        val o = provider.search("cat", docs) as SemanticSearchOutcome.Unavailable
        assertEquals(SemanticUnavailableReason.NO_EMBEDDER_CONFIGURED, o.reason)
    }

    @Test fun `a model that fails to load is a typed error, search stays unavailable, nothing crashes`() = runTest {
        native.loadResult = -5
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        assertNotNull(svc.status.value.loadError)
        assertFalse(svc.isActive)
        val o = provider.search("cat", docs) as SemanticSearchOutcome.Unavailable
        assertEquals(SemanticUnavailableReason.PROVIDER_ERROR, o.reason)
    }

    @Test fun `switching model re-embeds everything and never mixes the two models' vectors`() = runTest {
        install("a.gguf", "aaaa"); install("b.gguf", "bbbb")
        settings.setModelFile("a.gguf"); settings.setEnabled(true)
        val idA = svc.status.value.embedderId!!
        settings.setModelFile("b.gguf")
        val idB = svc.status.value.embedderId!!
        assertTrue(idA != idB)
        assertEquals(SemanticIndexState.Ready, svc.status.value.index)
        assertEquals(0, store.entries(idA).count())
        assertTrue(store.manifests().all { it.embedderId == idB })
        assertEquals(1, native.unloaded.size)
    }

    @Test fun `pause stops building, resume completes it`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setPaused(true); settings.setEnabled(true)
        assertEquals(SemanticIndexState.Building(0, 3, true), svc.status.value.index.let { it })
        assertTrue(store.manifests().isEmpty())
        settings.setPaused(false)
        assertEquals(SemanticIndexState.Ready, svc.status.value.index)
        assertEquals(3, store.manifests().size)
    }

    @Test fun `new docs are picked up by requestSync, unchanged ones are not re-embedded`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        val before = native.embedded.size
        docs = docs + doc("new", body = "something fresh")
        svc.requestSync()
        assertEquals(before + 1, native.embedded.size)
        assertEquals(4, store.manifests().size)
    }

    @Test fun `clear empties the store and leaves indexing paused, rebuild refills it`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        svc.clear()
        assertTrue(store.manifests().isEmpty())
        assertTrue(settings.paused.value)
        svc.rebuild()
        assertFalse(settings.paused.value)
        assertEquals(3, store.manifests().size)
        assertEquals(SemanticIndexState.Ready, svc.status.value.index)
    }

    @Test fun `fuse returns provenance for a plain query and nothing for a facet query`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        val lexical = listOf(SearchHit(docs[0], 1.0, MatchedIn.TITLE, listOf(0..2)))
        val fused = svc.fuse(QueryParser.parse("cat"), lexical)!!
        assertEquals(MatchSource.BOTH, fused.provenance["cat"])
        assertTrue(fused.hits.first { it.doc.id == "cat" } === lexical[0])
        assertNull(svc.fuse(QueryParser.parse("cat is:starred"), lexical))
        assertNull(svc.fuse(QueryParser.parse("is:starred"), lexical))
    }

    @Test fun `a native embed failure while querying is typed unavailable`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setEnabled(true)
        native.embedCode = EmbedCode.DECODE.code
        val o = provider.search("cat", docs) as SemanticSearchOutcome.Unavailable
        assertEquals(SemanticUnavailableReason.PROVIDER_ERROR, o.reason)
    }

    @Test fun `an index with no vectors yet is not reported as an empty result set`() = runTest {
        install("m.gguf"); settings.setModelFile("m.gguf"); settings.setPaused(true); settings.setEnabled(true)
        val o = provider.search("cat", docs)
        assertTrue(o is SemanticSearchOutcome.Unavailable)
    }
}
