package dev.fonebrew.domain.search.semantic

import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchKind

/** Test double honouring the [VectorStore] contract (atomic replace, one embedder per query). */
class InMemoryVectorStore : VectorStore {
    private val manifest = LinkedHashMap<String, DocManifest>()
    private val chunks = LinkedHashMap<String, Pair<String, List<FloatArray>>>() // docId -> (embedderId, vectors)
    var failNextReplace = false

    override fun manifests(): List<DocManifest> = manifest.values.toList()
    override fun replaceDoc(manifest: DocManifest, vectors: List<FloatArray>) {
        if (failNextReplace) { failNextReplace = false; throw IllegalStateException("simulated process death") }
        this.manifest[manifest.docId] = manifest
        chunks[manifest.docId] = manifest.embedderId to vectors
    }
    override fun removeDoc(docId: String) { manifest.remove(docId); chunks.remove(docId) }
    override fun entries(embedderId: String): Sequence<VectorEntry> = sequence {
        for ((doc, p) in chunks) if (p.first == embedderId) p.second.forEachIndexed { i, v -> yield(VectorEntry(doc, i, v)) }
    }
    override fun clear() { manifest.clear(); chunks.clear() }
    override fun bytesUsed(): Long = chunks.values.sumOf { p -> p.second.sumOf { it.size * 4L } }
}

/** Deterministic embedder: a word-hash bag so related texts share direction; counts calls. */
class FakeEmbedder(override val id: String = "llama-embed:fake:1:none:dim8", override val dim: Int = 8) : SemanticEmbedder {
    var calls = 0
    var failOn: ((String) -> Boolean)? = null
    val seen = ArrayList<String>()

    override suspend fun embedDocument(text: String): FloatArray = make(text)
    override suspend fun embedQuery(text: String): FloatArray = make(text)

    private fun make(text: String): FloatArray {
        calls++
        seen.add(text)
        if (failOn?.invoke(text) == true) throw IllegalStateException("boom")
        val v = FloatArray(dim)
        for (w in text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }) v[Math.floorMod(w.hashCode(), dim)] += 1f
        return v
    }
}

fun doc(id: String, title: String = "T$id", body: String = "body of $id", snippet: String = "") =
    SearchDoc(id, title, snippet, body, 1_000L, SearchKind.TEXT)
