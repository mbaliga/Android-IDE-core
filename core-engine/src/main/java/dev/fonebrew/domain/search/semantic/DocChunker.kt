package dev.fonebrew.domain.search.semantic

import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.Segmenter
import java.security.MessageDigest

/**
 * Splits a [SearchDoc] into embeddable chunks by reusing [Segmenter]'s word boundaries (so a
 * chunk is always a real substring of the original text, cut between words). Each chunk carries
 * the doc title as a prefix: a chunk of a long conversation is much easier to place with it.
 *
 * Numbers here (160 words, 24 overlap, 24 chunks) are engineering choices sized to a ~512-token
 * embedding window and to bound one huge conversation; they are not tuned against retrieval
 * quality (owner-verified follow-up, docs/design/semantic-search.md).
 */
object DocChunker {

    const val MAX_WORDS = 160
    const val OVERLAP_WORDS = 24
    const val MAX_CHUNKS = 24

    data class Chunk(val index: Int, val text: String)

    data class Chunked(val docId: String, val contentHash: String, val chunks: List<Chunk>, val truncated: Boolean)

    /** Stable fingerprint of what gets embedded; a change means the doc must be re-embedded. */
    fun contentHash(doc: SearchDoc): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(doc.title.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(doc.snippet.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(doc.body.toByteArray(Charsets.UTF_8))
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    fun chunk(
        doc: SearchDoc,
        boundarySource: Segmenter.BoundarySource = Segmenter.defaultSource,
    ): Chunked {
        val hash = contentHash(doc)
        val content = listOf(doc.snippet, doc.body).filter { it.isNotBlank() }.joinToString("\n")
        val title = doc.title.trim()
        val prefix = if (title.isEmpty()) "" else "$title\n"
        val spans = Segmenter.segment(content, boundarySource = boundarySource)
        if (spans.isEmpty()) {
            val only = (prefix + content).trim()
            return Chunked(doc.id, hash, if (only.isEmpty()) emptyList() else listOf(Chunk(0, only)), false)
        }
        val chunks = ArrayList<Chunk>()
        var start = 0
        var truncated = false
        while (start < spans.size) {
            if (chunks.size == MAX_CHUNKS) { truncated = true; break }
            val end = minOf(start + MAX_WORDS, spans.size)
            val text = content.substring(spans[start].startOriginal, spans[end - 1].endOriginal)
            chunks.add(Chunk(chunks.size, prefix + text))
            if (end == spans.size) break
            start = end - OVERLAP_WORDS
        }
        return Chunked(doc.id, hash, chunks, truncated)
    }
}
