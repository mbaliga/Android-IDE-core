package dev.aarso.domain.catalog

/**
 * Single-file Stable Diffusion models for on-device generation (§4c). These are
 * self-contained (UNet + VAE + text encoder in one file), so the simple
 * single-path loader runs them as-is. SD3.5 / FLUX need separate text encoders and
 * are intentionally not listed here. URLs verified to resolve at build time.
 */
data class SdCatalogModel(
    val id: String,
    val name: String,
    val family: String,
    val sizeBytes: Long,
    val note: String,
    val url: String,
) {
    val fileName: String get() = url.substringAfterLast('/')
}

object SdCatalog {
    val models: List<SdCatalogModel> = listOf(
        SdCatalogModel(
            id = "dreamshaper-8",
            name = "DreamShaper 8 (SD 1.5)",
            family = "sd1.5",
            sizeBytes = 2_100_000_000L,
            note = "Fast, versatile — best starting point on a phone",
            url = "https://huggingface.co/Lykon/DreamShaper/resolve/main/DreamShaper_8_pruned.safetensors",
        ),
        SdCatalogModel(
            id = "sd15-emaonly",
            name = "Stable Diffusion 1.5 (base)",
            family = "sd1.5",
            sizeBytes = 2_000_000_000L,
            note = "The classic baseline",
            url = "https://huggingface.co/Comfy-Org/stable-diffusion-v1-5-archive/resolve/main/v1-5-pruned-emaonly-fp16.safetensors",
        ),
        SdCatalogModel(
            id = "sdxl-turbo",
            name = "SDXL-Turbo",
            family = "sdxl",
            sizeBytes = 6_900_000_000L,
            note = "Higher quality, ~1–4 steps — but SDXL is heavy on CPU (slow)",
            url = "https://huggingface.co/stabilityai/sdxl-turbo/resolve/main/sd_xl_turbo_1.0_fp16.safetensors",
        ),
    )
}
