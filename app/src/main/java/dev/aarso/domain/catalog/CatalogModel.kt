package dev.aarso.domain.catalog

/**
 * A downloadable GGUF model (handoff §3). The app is model-agnostic and loads any
 * GGUF; the curated list is a convenience, and a custom Hugging Face URL is also
 * accepted — model selection is the user's, per §3.
 *
 * The curated list itself comes from Nooz's shared `ai-catalogue/models.json` (see
 * [dev.aarso.data.ModelCatalogStore] / [ModelCatalogMapper]) — Aarso does not hand-maintain its
 * own model list anymore; the per-flavor split is now a runtime [policySafe] filter rather than
 * two separate hardcoded lists.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val family: String,
    val params: String,
    val quant: String,
    val sizeBytes: Long,
    val contextWindow: Int,
    val hfRepo: String? = null,
    val hfFile: String? = null,
    /** The catalog's own resolved URL, when it has one — preferred over deriving from hfRepo/hfFile. */
    val explicitDownloadUrl: String? = null,
    val sha256: String? = null,
    val policySafe: Boolean = true,
    val verifiedAt: String? = null,
    val note: String? = null,
) {
    /** Null when the catalog has no independently-verified mirror yet — never coerce this into
     *  "available"; callers must gate the download action on it being non-null. */
    val downloadUrl: String?
        get() = explicitDownloadUrl
            ?: hfRepo?.let { repo -> hfFile?.let { file -> "https://huggingface.co/$repo/resolve/main/$file" } }

    /** Best-effort local file name — from the catalog's hfFile, else the URL tail, else an id-based guess. */
    val fileName: String
        get() = hfFile ?: downloadUrl?.substringAfterLast('/') ?: "$id.gguf"
}
