package dev.fonebrew.domain.search.semantic

/** One doc's manifest row: the doc is complete for [embedderId] iff this row exists. */
data class DocManifest(val docId: String, val embedderId: String, val contentHash: String, val chunkCount: Int, val dim: Int)

/**
 * Durable vector storage. Contract (tested against every implementation):
 * - [replaceDoc] is atomic: the doc's old vectors (any embedder) are removed and the new chunks
 *   plus manifest row are written together, or nothing changes. A doc is "indexed" only through
 *   its manifest row, which is what makes indexing resumable after process death.
 * - [entries] returns vectors of exactly one embedder id; vectors from another embedder are never
 *   returned, so a ranking can never mix embedders.
 */
interface VectorStore {
    fun manifests(): List<DocManifest>
    fun replaceDoc(manifest: DocManifest, vectors: List<FloatArray>)
    fun removeDoc(docId: String)
    fun entries(embedderId: String): Sequence<VectorEntry>
    fun clear()
    fun bytesUsed(): Long
}
