package dev.aarso.embedding

/**
 * Produces a fixed-length vector for a piece of text. A small local embedder
 * (~100–300M params, handoff §5b) lands in Phase 2; this interface lets the
 * cold-start logging pipeline exist now and swap the real model in later without
 * touching call sites.
 */
interface Embedder {
    /** Stable provenance id stored alongside every vector. */
    val id: String

    /** Vector dimensionality. */
    val dim: Int

    suspend fun embed(text: String): FloatArray
}
