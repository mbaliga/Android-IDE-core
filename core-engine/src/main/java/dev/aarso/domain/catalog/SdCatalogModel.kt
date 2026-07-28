package dev.aarso.domain.catalog

/**
 * Single-file Stable Diffusion models for on-device generation (§4c). These are
 * self-contained (UNet + VAE + text encoder in one file), so the simple
 * single-path loader runs them as-is. SD3.5 / FLUX need separate text encoders and
 * are intentionally not listed here.
 *
 * Sourced from Nooz's shared `ai-catalogue/models.json` (kind `SD_IMAGE`) — see
 * [dev.aarso.data.ModelCatalogStore] / [ModelCatalogMapper].
 */
data class SdCatalogModel(
    val id: String,
    val name: String,
    val family: String,
    val sizeBytes: Long,
    val note: String,
    /** Null when the catalog has no independently-verified mirror yet — never coerce this into
     *  "available"; callers must gate the download action on it being non-null. */
    val downloadUrl: String?,
    val policySafe: Boolean = true,
) {
    val fileName: String get() = downloadUrl?.substringAfterLast('/') ?: "$id.safetensors"
}
