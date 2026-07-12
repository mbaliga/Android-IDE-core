package dev.aarso.domain.catalog

/**
 * Turns the shared remote catalog (Nooz's `ai-catalogue/models.json`) into the app's two curated
 * lists — chat GGUFs and single-file SD checkpoints — applying the flavor's policy-safety filter
 * (a policy-restricted storefront build, `play`, only ever shows `policySafe` entries; `full`
 * shows everything). Pure, JVM-tested; no Android deps.
 */
object ModelCatalogMapper {

    fun chatModels(catalog: RemoteModelCatalog, policySafeOnly: Boolean): List<CatalogModel> =
        catalog.entries
            .asSequence()
            .filter { it.kind == RemoteCatalogKind.LLM_GGUF }
            .filter { !policySafeOnly || it.policySafe }
            .map {
                CatalogModel(
                    id = it.id,
                    name = it.name,
                    family = it.family ?: "unknown",
                    params = it.params ?: "?",
                    quant = it.quant ?: "?",
                    sizeBytes = it.sizeBytes,
                    contextWindow = it.contextWindow ?: 8192,
                    hfRepo = it.hfRepo,
                    hfFile = it.hfFile,
                    explicitDownloadUrl = it.downloadUrl,
                    sha256 = it.sha256,
                    policySafe = it.policySafe,
                    verifiedAt = it.verifiedAt,
                    note = it.note,
                )
            }
            .sortedBy { it.sizeBytes }
            .toList()

    fun sdModels(catalog: RemoteModelCatalog, policySafeOnly: Boolean): List<SdCatalogModel> =
        catalog.entries
            .asSequence()
            .filter { it.kind == RemoteCatalogKind.SD_IMAGE }
            .filter { !policySafeOnly || it.policySafe }
            .map {
                SdCatalogModel(
                    id = it.id,
                    name = it.name,
                    family = it.family ?: "unknown",
                    sizeBytes = it.sizeBytes,
                    note = it.note ?: "",
                    downloadUrl = it.downloadUrl,
                    policySafe = it.policySafe,
                )
            }
            .sortedBy { it.sizeBytes }
            .toList()
}
