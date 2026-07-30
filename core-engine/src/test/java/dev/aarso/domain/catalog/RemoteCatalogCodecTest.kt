package dev.aarso.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCatalogCodecTest {

    @Test fun `decodes a Nooz-shaped catalog`() {
        val json = """
            {
              "schemaVersion": 1,
              "lastUpdated": "2026-07-11",
              "models": [
                {
                  "id": "qwen2.5-1.5b-instruct-q4",
                  "name": "Qwen2.5-1.5B Instruct",
                  "kind": "LLM_GGUF",
                  "family": "qwen2.5",
                  "params": "1.5B",
                  "quant": "Q4_K_M",
                  "contextWindow": 8192,
                  "sizeBytes": 1100000000,
                  "downloadUrl": "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/f.gguf",
                  "hfRepo": "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
                  "hfFile": "f.gguf",
                  "sha256": null,
                  "policySafe": true,
                  "verifiedAt": "2026-07-11",
                  "note": "Official release."
                },
                {
                  "id": "sdxl-turbo",
                  "name": "SDXL-Turbo",
                  "kind": "SD_IMAGE",
                  "family": "sdxl",
                  "params": null,
                  "quant": null,
                  "contextWindow": null,
                  "sizeBytes": 6900000000,
                  "downloadUrl": "https://huggingface.co/stabilityai/sdxl-turbo/resolve/main/f.safetensors",
                  "hfRepo": "stabilityai/sdxl-turbo",
                  "hfFile": "f.safetensors",
                  "sha256": null,
                  "policySafe": true,
                  "verifiedAt": "2026-07-11",
                  "note": "Heavy on CPU."
                },
                {
                  "id": "qwen3-4b-instruct-q4",
                  "name": "Qwen3 4B Instruct (Q4)",
                  "kind": "LLM_GGUF",
                  "family": "qwen3",
                  "params": "4B",
                  "quant": "Q4",
                  "contextWindow": null,
                  "sizeBytes": 2600000000,
                  "downloadUrl": null,
                  "hfRepo": null,
                  "hfFile": null,
                  "sha256": null,
                  "policySafe": true,
                  "verifiedAt": null,
                  "note": "No verified mirror chosen yet."
                }
              ]
            }
        """.trimIndent()

        val catalog = RemoteCatalogCodec.decode(json)

        assertEquals(1, catalog.schemaVersion)
        assertEquals("2026-07-11", catalog.lastUpdated)
        assertEquals(3, catalog.entries.size)

        val llm = catalog.entries.first { it.id == "qwen2.5-1.5b-instruct-q4" }
        assertEquals(RemoteCatalogKind.LLM_GGUF, llm.kind)
        assertEquals(1_100_000_000L, llm.sizeBytes)
        assertEquals(8192, llm.contextWindow)
        assertTrue(llm.policySafe)
        assertEquals("f.gguf", llm.hfFile)

        val sd = catalog.entries.first { it.id == "sdxl-turbo" }
        assertEquals(RemoteCatalogKind.SD_IMAGE, sd.kind)
        assertNull(sd.contextWindow)
        assertNull(sd.params)

        val unverified = catalog.entries.first { it.id == "qwen3-4b-instruct-q4" }
        assertNull(unverified.downloadUrl)
        assertNull(unverified.hfRepo)
        assertNull(unverified.verifiedAt)
    }

    @Test fun `tolerates empty and unknown kind`() {
        val empty = RemoteCatalogCodec.decode("{}")
        assertTrue(empty.entries.isEmpty())

        val unknownKind = RemoteCatalogCodec.decode(
            """{"models":[{"id":"x","name":"X","kind":"SOMETHING_NEW","sizeBytes":1}]}""",
        )
        assertEquals(RemoteCatalogKind.UNKNOWN, unknownKind.entries.single().kind)
    }

    @Test fun `defaults policySafe true and schemaVersion 1 when absent`() {
        val catalog = RemoteCatalogCodec.decode(
            """{"models":[{"id":"x","name":"X","kind":"LLM_GGUF","sizeBytes":1}]}""",
        )
        assertEquals(1, catalog.schemaVersion)
        assertTrue(catalog.entries.single().policySafe)
        assertNull(catalog.entries.single().hfFile)
    }
}
