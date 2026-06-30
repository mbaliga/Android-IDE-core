package dev.aarso.domain.catalog

/**
 * A downloadable GGUF model (handoff §3). The app is model-agnostic and loads any
 * GGUF; the curated list is a convenience, and a custom Hugging Face URL is also
 * accepted — model selection is the user's, per §3.
 *
 * The curated list itself ([ModelCatalog]) is per distribution flavor:
 * src/full carries the original catalog, src/play a Play-policy-safe one.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val family: String,
    val params: String,
    val quant: String,
    val sizeBytes: Long,
    val contextWindow: Int,
    val hfRepo: String,
    val hfFile: String,
) {
    val downloadUrl: String get() = "https://huggingface.co/$hfRepo/resolve/main/$hfFile"
}
