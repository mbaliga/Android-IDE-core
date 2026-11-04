package dev.fonebrew.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogMapperTest {

    private fun entry(
        id: String,
        kind: RemoteCatalogKind,
        sizeBytes: Long,
        policySafe: Boolean = true,
        downloadUrl: String? = "https://example.com/$id",
    ) = RemoteCatalogEntry(
        id = id,
        name = id,
        kind = kind,
        family = "fam",
        params = "1B",
        quant = "Q4_K_M",
        contextWindow = 8192,
        sizeBytes = sizeBytes,
        downloadUrl = downloadUrl,
        hfRepo = null,
        hfFile = null,
        sha256 = null,
        policySafe = policySafe,
        verifiedAt = "2026-07-11",
        note = null,
    )

    @Test fun `splits chat and image kinds`() {
        val catalog = RemoteModelCatalog(
            schemaVersion = 1,
            lastUpdated = "2026-07-11",
            entries = listOf(
                entry("llm-1", RemoteCatalogKind.LLM_GGUF, 1_000L),
                entry("sd-1", RemoteCatalogKind.SD_IMAGE, 2_000L),
                entry("weird", RemoteCatalogKind.UNKNOWN, 3_000L),
            ),
        )

        val chat = ModelCatalogMapper.chatModels(catalog, policySafeOnly = false)
        val sd = ModelCatalogMapper.sdModels(catalog, policySafeOnly = false)

        assertEquals(listOf("llm-1"), chat.map { it.id })
        assertEquals(listOf("sd-1"), sd.map { it.id })
    }

    @Test fun `policySafeOnly filters out community remixes`() {
        val catalog = RemoteModelCatalog(
            schemaVersion = 1,
            lastUpdated = "2026-07-11",
            entries = listOf(
                entry("official", RemoteCatalogKind.LLM_GGUF, 1_000L, policySafe = true),
                entry("abliterated", RemoteCatalogKind.LLM_GGUF, 1_000L, policySafe = false),
            ),
        )

        assertEquals(listOf("official"), ModelCatalogMapper.chatModels(catalog, policySafeOnly = true).map { it.id })
        assertEquals(
            setOf("official", "abliterated"),
            ModelCatalogMapper.chatModels(catalog, policySafeOnly = false).map { it.id }.toSet(),
        )
    }

    @Test fun `sorts by size ascending`() {
        val catalog = RemoteModelCatalog(
            schemaVersion = 1,
            lastUpdated = "",
            entries = listOf(
                entry("big", RemoteCatalogKind.LLM_GGUF, 9_000L),
                entry("small", RemoteCatalogKind.LLM_GGUF, 1_000L),
                entry("mid", RemoteCatalogKind.LLM_GGUF, 5_000L),
            ),
        )

        assertEquals(listOf("small", "mid", "big"), ModelCatalogMapper.chatModels(catalog, false).map { it.id })
    }

    @Test fun `a null downloadUrl never gets coerced into an available one`() {
        val catalog = RemoteModelCatalog(
            schemaVersion = 1,
            lastUpdated = "",
            entries = listOf(entry("no-mirror", RemoteCatalogKind.LLM_GGUF, 1_000L, downloadUrl = null)),
        )

        val model = ModelCatalogMapper.chatModels(catalog, false).single()
        assertNull(model.downloadUrl)
        assertTrue(model.fileName.isNotBlank()) // still has a usable local file name for the UI
    }
}
