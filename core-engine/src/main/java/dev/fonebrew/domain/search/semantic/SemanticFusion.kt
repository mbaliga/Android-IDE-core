package dev.fonebrew.domain.search.semantic

import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.query.ChipKind
import dev.fonebrew.domain.search.query.ParsedQuery
import dev.fonebrew.domain.search.query.QueryCompiler

/** The fused list plus, per doc id, how it was found. */
data class FusedResult(val hits: List<SearchHit>, val provenance: Map<String, MatchSource>)

object SemanticFusion {

    /**
     * The text to embed for [parsed], or null when the query is not a plain-words query. A facet
     * (`is:starred`, `after:-7d`), regex, group or negation constrains the result set in ways a
     * nearest-neighbour hit cannot honestly satisfy, so those queries stay lexical-only.
     */
    fun eligibleText(parsed: ParsedQuery): String? {
        if (parsed.facets.isNotEmpty() || parsed.chips.isEmpty()) return null
        val plain = parsed.chips.all {
            (it.kind == ChipKind.TERM || it.kind == ChipKind.PHRASE || it.kind == ChipKind.SEMANTIC) && !it.text.startsWith("-")
        }
        if (!plain) return null
        return QueryCompiler.lexicalText(parsed.root).trim().ifEmpty { null }
    }

    /**
     * Fuses the lexical hits (order preserved as the lexical ranking) with the semantic ranking.
     * Lexical hits keep their own [SearchHit] (highlights, explanation); semantic-only docs become
     * hits with no highlights and the cosine similarity as score. Semantic ids missing from
     * [docs] are dropped, never invented.
     */
    fun fuse(lexical: List<SearchHit>, semantic: List<DocSimilarity>, docs: Map<String, SearchDoc>): FusedResult {
        val lexById = LinkedHashMap<String, SearchHit>()
        lexical.forEach { lexById.putIfAbsent(it.doc.id, it) }
        val semKnown = semantic.filter { it.docId in lexById || it.docId in docs }
        val sims = semKnown.associate { it.docId to it.score }
        val fused = RankFusion.fuse(lexById.keys.toList(), semKnown.map { it.docId })
        val provenance = LinkedHashMap<String, MatchSource>()
        val hits = fused.mapNotNull { f ->
            provenance[f.docId] = f.source
            lexById[f.docId] ?: docs[f.docId]?.let { d ->
                SearchHit(d, (sims[f.docId] ?: 0f).toDouble(), MatchedIn.CONTENT, emptyList())
            }
        }
        return FusedResult(hits, provenance)
    }
}
