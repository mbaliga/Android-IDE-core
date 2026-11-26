package dev.fonebrew.data.search.semantic

import dev.fonebrew.domain.search.semantic.DocManifest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SqliteVectorStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: SqliteVectorStore
    private val file get() = java.io.File(tmp.root, "v.db")

    @Before fun open() { store = SqliteVectorStore.open(file) }
    @After fun close() { runCatching { store.close() } }

    private fun m(doc: String, emb: String = "E1", n: Int = 1, hash: String = "h") = DocManifest(doc, emb, hash, n, 3)

    @Test fun `round trip preserves the floats exactly`() {
        val v = floatArrayOf(0.1f, -2.5f, 1e-7f)
        store.replaceDoc(m("a"), listOf(v))
        val e = store.entries("E1").toList().single()
        assertEquals("a", e.docId); assertEquals(0, e.chunk)
        assertArrayEquals(v, e.vector, 0f)
        assertEquals(m("a"), store.manifests().single())
    }

    @Test fun `entries only returns the asked embedder id`() {
        store.replaceDoc(m("a", "E1"), listOf(floatArrayOf(1f, 0f, 0f)))
        store.replaceDoc(m("b", "E2"), listOf(floatArrayOf(0f, 1f, 0f)))
        assertEquals(listOf("a"), store.entries("E1").map { it.docId }.toList())
        assertEquals(listOf("b"), store.entries("E2").map { it.docId }.toList())
        assertEquals(0, store.entries("E3").count())
    }

    @Test fun `replaceDoc swaps old vectors, even from another embedder, in one step`() {
        store.replaceDoc(m("a", "E1", 2), listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)))
        store.replaceDoc(m("a", "E2", 1), listOf(floatArrayOf(0f, 0f, 1f)))
        assertEquals(0, store.entries("E1").count())
        assertEquals(1, store.entries("E2").count())
        assertEquals("E2", store.manifests().single().embedderId)
    }

    @Test fun `removeDoc and clear`() {
        store.replaceDoc(m("a"), listOf(floatArrayOf(1f, 0f, 0f)))
        store.replaceDoc(m("b"), listOf(floatArrayOf(1f, 0f, 0f)))
        store.removeDoc("a")
        assertEquals(listOf("b"), store.manifests().map { it.docId })
        store.clear()
        assertTrue(store.manifests().isEmpty())
        assertEquals(0, store.entries("E1").count())
    }

    @Test fun `empty doc has a manifest and no vectors`() {
        store.replaceDoc(m("a", n = 0), emptyList())
        assertEquals(1, store.manifests().size)
        assertEquals(0, store.entries("E1").count())
    }

    @Test fun `more than one page of entries streams completely`() {
        for (i in 1..700) store.replaceDoc(m("d$i"), listOf(floatArrayOf(i.toFloat(), 0f, 0f)))
        assertEquals(700, store.entries("E1").count())
        assertEquals(700, store.entries("E1").map { it.docId }.toSet().size)
    }

    @Test fun `data survives closing and reopening - resumable after process death`() {
        store.replaceDoc(m("a"), listOf(floatArrayOf(1f, 0f, 0f)))
        store.close()
        store = SqliteVectorStore.open(file)
        assertEquals(1, store.manifests().size)
        assertEquals(1, store.entries("E1").count())
    }

    @Test fun `a corrupt file is replaced by an empty store`() {
        store.close()
        file.writeText("this is not a sqlite database at all, just text".repeat(50))
        store = SqliteVectorStore.open(file)
        assertTrue(store.manifests().isEmpty())
        store.replaceDoc(m("a"), listOf(floatArrayOf(1f, 0f, 0f)))
        assertEquals(1, store.manifests().size)
    }

    @Test fun `bytesUsed grows with data`() {
        val empty = store.bytesUsed()
        for (i in 1..200) store.replaceDoc(m("d$i"), listOf(FloatArray(3) { it.toFloat() }))
        assertTrue(store.bytesUsed() > empty)
    }

    @Test fun `lazy store does not create a file until used`() {
        val f = java.io.File(tmp.root, "lazy.db")
        val lazy = LazyVectorStore(f)
        assertEquals(0L, lazy.bytesUsed())
        lazy.clear()
        assertTrue(!f.exists())
        lazy.replaceDoc(m("a"), listOf(floatArrayOf(1f, 0f, 0f)))
        assertTrue(f.exists())
    }
}
