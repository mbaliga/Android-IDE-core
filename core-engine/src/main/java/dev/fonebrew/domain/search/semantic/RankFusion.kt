package dev.fonebrew.domain.search.semantic

/** How a fused hit was found. Carried to the UI as a glyph + label, never colour alone. */
enum class MatchSource { WORDS, MEANING, BOTH }

data class FusedHit(
    val docId: String,
    val score: Double,
    val source: MatchSource,
    /** 1-based rank in each input list, null when absent from it. */
    val lexicalRank: Int?,
    val semanticRank: Int?,
)

/**
 * Reciprocal-rank fusion of the lexical ranking and the semantic ranking. Rank-based on purpose:
 * lexical scores and cosine similarities live on incomparable scales, and inventing a blend
 * weight would be an invented number. Per-hit provenance is preserved: a doc present in both
 * lists is [MatchSource.BOTH].
 */
object RankFusion {

    const val RRF_K = 60

    /** Semantic-only additions are capped; see the design note on why there is no similarity floor. */
    const val MAX_SEMANTIC_ONLY = 10

    fun fuse(lexicalIds: List<String>, semanticIds: List<String>, semanticOnlyCap: Int = MAX_SEMANTIC_ONLY): List<FusedHit> {
        val lex = HashMap<String, Int>()
        lexicalIds.forEachIndexed { i, id -> lex.putIfAbsent(id, i + 1) }
        val sem = LinkedHashMap<String, Int>()
        var semOnly = 0
        semanticIds.forEachIndexed { i, id ->
            if (id in sem) return@forEachIndexed
            if (id !in lex) {
                if (semOnly >= semanticOnlyCap) return@forEachIndexed
                semOnly++
            }
            sem[id] = i + 1
        }
        val ids = LinkedHashSet<String>().apply { addAll(lex.keys.sortedBy { lex[it] }); addAll(sem.keys) }
        return ids.map { id ->
            val lr = lex[id]
            val sr = sem[id]
            val score = (lr?.let { 1.0 / (RRF_K + it) } ?: 0.0) + (sr?.let { 1.0 / (RRF_K + it) } ?: 0.0)
            val source = when {
                lr != null && sr != null -> MatchSource.BOTH
                lr != null -> MatchSource.WORDS
                else -> MatchSource.MEANING
            }
            FusedHit(id, score, source, lr, sr)
        }.sortedWith(
            compareByDescending<FusedHit> { it.score }
                .thenBy { it.lexicalRank ?: Int.MAX_VALUE }
                .thenBy { it.semanticRank ?: Int.MAX_VALUE }
                .thenBy { it.docId },
        )
    }
}
