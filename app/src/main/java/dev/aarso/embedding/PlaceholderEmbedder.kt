package dev.aarso.embedding

import kotlin.math.sqrt

/**
 * A stand-in embedder so the cold-start logging pipeline is live in Phase 0
 * (handoff §5c) before the real model exists.
 *
 * HONEST LIMITATION: these are NOT meaningful embeddings — they are a
 * deterministic hashed bag-of-characters projection, useful only to exercise and
 * verify the storage path end to end. They are tagged with this embedder's [id]
 * so they are never silently mixed with real vectors once the Phase 2 embedder
 * lands; vectors from different [id]s live in incomparable spaces and must not be
 * compared. No drift/convergence metric (§5b/§5c) should run against these.
 */
class PlaceholderEmbedder(override val dim: Int = 64) : Embedder {

    override val id: String = "placeholder-hash-v1:dim$dim"

    override suspend fun embed(text: String): FloatArray {
        val v = FloatArray(dim)
        for (ch in text) {
            v[(ch.code and 0x7fffffff) % dim] += 1f
        }
        // L2-normalise so magnitudes are comparable across lengths.
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}
