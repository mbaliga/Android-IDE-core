package dev.fonebrew.embedding

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Provenance for vectors: what produced them. The id is stored with every vector, and a vector
 * made under a different id is stale and re-embedded, never mixed into one ranking.
 */
object EmbedderIdentity {

    private const val FINGERPRINT_BYTES = 64 * 1024

    /**
     * Cheap content fingerprint of a model file: SHA-256 over size + the first and last 64 KiB.
     * Not a full-file hash (that would read ~80+ MB every launch); it distinguishes different
     * models and re-quantised files, which is what staleness needs.
     */
    fun fileFingerprint(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            md.update(len.toString().toByteArray())
            val buf = ByteArray(minOf(FINGERPRINT_BYTES.toLong(), len).toInt())
            raf.seek(0); raf.readFully(buf); md.update(buf)
            if (len > FINGERPRINT_BYTES) {
                val tail = ByteArray(minOf(FINGERPRINT_BYTES.toLong(), len - FINGERPRINT_BYTES).toInt())
                raf.seek(len - tail.size); raf.readFully(tail); md.update(tail)
            }
        }
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    fun id(fileName: String, fingerprint: String, prefixProfile: String, dim: Int): String =
        "llama-embed:$fileName:$fingerprint:$prefixProfile:dim$dim"

    /** True for ids this semantic store may hold; the cold-start placeholder can never match. */
    fun isSemanticId(id: String): Boolean = id.startsWith("llama-embed:")
}

/**
 * Task prefixes some embedding models require (the model card is the source). Part of the
 * embedder id: the same file embedded with different prefixes gives different vectors.
 */
enum class PrefixProfile(val tag: String, val query: String, val document: String) {
    NONE("none", "", ""),
    NOMIC("nomic-v1", "search_query: ", "search_document: "),
    ;

    companion object {
        /** Only a file name that says it is a nomic-embed-text model gets the nomic prefixes; everything else gets none. */
        fun forFileName(name: String): PrefixProfile =
            if (name.lowercase().contains("nomic-embed-text")) NOMIC else NONE
    }
}
