package dev.fonebrew.data.search.semantic

import dev.fonebrew.embedding.PrefixProfile

/**
 * A downloadable embedding model. Every field below was read from the real host, not recalled:
 * see docs/design/semantic-search.md section 9 for how (HTTP 206 ranged GET through the proxy,
 * Hugging Face model API for size / LFS sha256 / license).
 */
data class EmbeddingCatalogEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val host: String,
    val prefixProfile: PrefixProfile,
)

object EmbeddingModelCatalog {
    val entries: List<EmbeddingCatalogEntry> = listOf(
        EmbeddingCatalogEntry(
            id = "nomic-embed-text-v1.5-q4_k_m",
            displayName = "nomic-embed-text v1.5 (Q4_K_M)",
            fileName = "nomic-embed-text-v1.5.Q4_K_M.gguf",
            url = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf",
            sizeBytes = 84_106_624L,
            sha256 = "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac",
            license = "Apache-2.0",
            host = "huggingface.co",
            prefixProfile = PrefixProfile.NOMIC,
        ),
    )

    fun byFileName(name: String): EmbeddingCatalogEntry? = entries.firstOrNull { it.fileName == name }
}
