package dev.fonebrew.embedding

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlamaEmbedderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun model(name: String = "m.gguf", content: String = "GGUFxxxx"): File =
        tmp.newFile(name).apply { writeText(content) }

    private suspend fun open(native: EmbedNative, f: File) =
        LlamaEmbedder.open(native, f, dispatcher = Dispatchers.Unconfined)

    @Test fun `opens, reports dim from the model and an id carrying the file identity`() = runTest {
        val f = model("bge-small.gguf")
        val r = open(FakeEmbedNative(dim = 8), f) as EmbedderOpen.Opened
        assertEquals(8, r.embedder.dim)
        assertTrue(r.embedder.id.startsWith("llama-embed:bge-small.gguf:"))
        assertTrue(r.embedder.id.endsWith(":none:dim8"))
        assertTrue(EmbedderIdentity.isSemanticId(r.embedder.id))
    }

    @Test fun `PlaceholderEmbedder ids are never semantic ids`() {
        assertFalse(EmbedderIdentity.isSemanticId(PlaceholderEmbedder().id))
    }

    @Test fun `different file content or name gives a different id`() = runTest {
        val a = (open(FakeEmbedNative(), model("a.gguf", "GGUF-one")) as EmbedderOpen.Opened).embedder.id
        val b = (open(FakeEmbedNative(), model("b.gguf", "GGUF-two")) as EmbedderOpen.Opened).embedder.id
        assertNotEquals(a, b)
        val sameNameOtherBytes = tmp.newFolder("x").resolve("a.gguf").apply { writeText("GGUF-different") }
        assertNotEquals(a, (open(FakeEmbedNative(), sameNameOtherBytes) as EmbedderOpen.Opened).embedder.id)
    }

    @Test fun `nomic file names get the nomic prefixes and a different id tag`() = runTest {
        val e = (open(FakeEmbedNative(), model("nomic-embed-text-v1.5.Q4_K_M.gguf")) as EmbedderOpen.Opened).embedder
        assertEquals(PrefixProfile.NOMIC, e.prefixProfile)
        assertTrue(e.id.contains(":nomic-v1:"))
    }

    @Test fun `document and query prefixes reach the native layer`() = runTest {
        val native = FakeEmbedNative()
        val e = (open(native, model("nomic-embed-text-v1.5.Q4_K_M.gguf")) as EmbedderOpen.Opened).embedder
        e.embedDocument("hello"); e.embedQuery("hello")
        assertEquals(listOf("search_document: hello", "search_query: hello"), native.embedded)
    }

    @Test fun `unknown models get no prefix`() = runTest {
        val native = FakeEmbedNative()
        val e = (open(native, model("other.gguf")) as EmbedderOpen.Opened).embedder
        e.embedDocument("hello")
        assertEquals(listOf("hello"), native.embedded)
    }

    @Test fun `every native load failure is a typed Failed, never a throw`() = runTest {
        for (lc in LoadCode.entries) {
            val r = open(FakeEmbedNative(loadResult = lc.code), model("m${lc.ordinal}.gguf"))
            assertTrue(r is EmbedderOpen.Failed)
            assertEquals(lc.message, (r as EmbedderOpen.Failed).reason)
        }
    }

    @Test fun `missing file is a typed failure and the native layer is not called`() = runTest {
        val native = FakeEmbedNative()
        val r = open(native, File(tmp.root, "nope.gguf"))
        assertTrue(r is EmbedderOpen.Failed)
        assertTrue(native.loadedPaths.isEmpty())
    }

    @Test fun `a model reporting no dimension is unloaded and refused`() = runTest {
        val native = FakeEmbedNative(dim = 0)
        val r = open(native, model())
        assertTrue(r is EmbedderOpen.Failed)
        assertEquals(1, native.unloaded.size)
    }

    @Test fun `empty text is a typed error`() = runTest {
        val e = (open(FakeEmbedNative(), model()) as EmbedderOpen.Opened).embedder
        try { e.embed(""); fail() } catch (x: EmbedException) { assertEquals(EmbedCode.EMPTY, x.code) }
    }

    @Test fun `native embed failure codes surface as EmbedException`() = runTest {
        for (c in EmbedCode.entries.filter { it.code < 0 }) {
            val e = (open(FakeEmbedNative(embedCode = c.code), model("c${c.ordinal}.gguf")) as EmbedderOpen.Opened).embedder
            try { e.embed("text"); fail("expected $c") } catch (x: EmbedException) { assertEquals(c, x.code) }
        }
    }

    @Test fun `truncation is reported, vector is still returned`() = runTest {
        val e = (open(FakeEmbedNative(embedCode = EmbedCode.OK_TRUNCATED.code), model()) as EmbedderOpen.Opened).embedder
        val r = e.embedChecked("long text")
        assertTrue(r.truncated)
        assertEquals(1f, dev.fonebrew.domain.search.semantic.VectorMath.norm(r.vector), 1e-5f)
    }

    @Test fun `close unloads once and later calls fail typed`() = runTest {
        val native = FakeEmbedNative()
        val e = (open(native, model()) as EmbedderOpen.Opened).embedder
        e.close(); e.close()
        assertEquals(1, native.unloaded.size)
        try { e.embed("x"); fail() } catch (_: EmbedException) {}
    }

    @Test fun `non-ASCII text round-trips as UTF-8`() = runTest {
        val native = FakeEmbedNative()
        val e = (open(native, model()) as EmbedderOpen.Opened).embedder
        e.embed("東京駅 😀 café")
        assertEquals("東京駅 😀 café", native.embedded.single())
    }

    @Test fun `PlaceholderEmbedder is unchanged`() = runTest {
        val p = PlaceholderEmbedder()
        assertEquals("placeholder-hash-v1:dim64", p.id)
        assertEquals(64, p.embed("abc").size)
    }

    @Test fun `fingerprint is stable and sensitive to size and ends`() {
        val a = tmp.newFile("fa").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val b = tmp.newFile("fb").apply { writeBytes(a.readBytes().also { it[199_999] = 7 }) }
        assertEquals(EmbedderIdentity.fileFingerprint(a), EmbedderIdentity.fileFingerprint(a))
        assertNotEquals(EmbedderIdentity.fileFingerprint(a), EmbedderIdentity.fileFingerprint(b))
    }

    @Test fun `native return codes round trip through the enums`() {
        assertEquals(LoadCode.POOLING_UNSUPPORTED, LoadCode.of(-5))
        assertEquals(LoadCode.INTERNAL, LoadCode.of(-999))
        assertEquals(EmbedCode.NON_FINITE, EmbedCode.of(-6))
        assertEquals(EmbedCode.INTERNAL, EmbedCode.of(12345))
    }
}
