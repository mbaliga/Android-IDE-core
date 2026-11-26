package dev.fonebrew.ui.semantic

import dev.fonebrew.data.search.semantic.EmbeddingModelCatalog
import dev.fonebrew.data.search.semantic.EmbeddingModelFile
import dev.fonebrew.data.search.semantic.SemanticStatus
import dev.fonebrew.domain.search.semantic.SemanticIndexState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSettingsPresenterTest {
    private fun ui(
        index: SemanticIndexState, enabled: Boolean = true, paused: Boolean = false,
        installed: List<EmbeddingModelFile> = emptyList(), selected: String? = null,
        status: SemanticStatus = SemanticStatus(index), downloads: Map<String, Float> = emptyMap(),
    ) = SemanticSettingsPresenter.present(enabled, selected, paused, installed, status, EmbeddingModelCatalog.entries, downloads)

    @Test fun `off state`() {
        val u = ui(SemanticIndexState.NotEnabled, enabled = false)
        assertEquals("○", u.glyph); assertEquals("Off", u.statusLabel)
        assertFalse(u.canPause || u.canResume || u.canRebuild)
        assertFalse(u.enabled)
    }

    @Test fun `no model and load error are different, both carry a glyph and a label`() {
        val none = ui(SemanticIndexState.NoModel)
        assertEquals("No embedding model chosen", none.statusLabel)
        val bad = ui(SemanticIndexState.NoModel, status = SemanticStatus(SemanticIndexState.NoModel, loadError = "bad file"))
        assertEquals("Model could not be loaded", bad.statusLabel)
        assertTrue(bad.errorLine!!.contains("bad file"))
        assertTrue(none.glyph != bad.glyph)
    }

    @Test fun `building shows counts, a fraction and Pause`() {
        val u = ui(SemanticIndexState.Building(30, 120, false), status = SemanticStatus(SemanticIndexState.Building(30, 120, false), embedderId = "x"))
        assertEquals("Building 30 of 120", u.statusLabel)
        assertEquals(0.25f, u.progress!!, 1e-6f)
        assertTrue(u.canPause); assertFalse(u.canResume)
    }

    @Test fun `paused shows Resume not Pause`() {
        val s = SemanticIndexState.Building(30, 120, true)
        val u = ui(s, paused = true, status = SemanticStatus(s, embedderId = "x"))
        assertEquals("Paused at 30 of 120", u.statusLabel)
        assertTrue(u.canResume); assertFalse(u.canPause)
    }

    @Test fun `ready and stale have distinct glyphs`() {
        val r = ui(SemanticIndexState.Ready); val s = ui(SemanticIndexState.Stale(4))
        assertEquals("Ready", r.statusLabel)
        assertTrue(s.statusLabel.startsWith("4 items"))
        assertTrue(r.glyph != s.glyph)
        assertTrue(r.canRebuild && s.canRebuild)
    }

    @Test fun `states are distinguishable without colour - every state has a unique glyph or label`() {
        val states = listOf(
            ui(SemanticIndexState.NotEnabled, enabled = false), ui(SemanticIndexState.NoModel),
            ui(SemanticIndexState.Building(1, 2, false), status = SemanticStatus(SemanticIndexState.Building(1, 2, false), embedderId = "x")),
            ui(SemanticIndexState.Ready), ui(SemanticIndexState.Stale(1)),
        )
        assertEquals(states.size, states.map { it.glyph + it.statusLabel }.toSet().size)
        assertTrue(states.all { it.glyph.isNotBlank() && it.statusLabel.isNotBlank() })
    }

    @Test fun `storage and privacy lines are always honest`() {
        val u = ui(SemanticIndexState.Ready, status = SemanticStatus(SemanticIndexState.Ready, storeBytes = 3L * 1024 * 1024))
        assertEquals("Index storage: 3.0 MiB", u.storageLine)
        assertTrue(u.privacyLine.contains("on this phone only"))
        assertTrue(u.privacyLine.contains("never uploaded"))
    }

    @Test fun `installed models list selection and license honesty`() {
        val f = EmbeddingModelFile(File("nomic-embed-text-v1.5.Q4_K_M.gguf"))
        val other = EmbeddingModelFile(File("custom.gguf"))
        val u = SemanticSettingsPresenter.present(
            true, "custom.gguf", false, listOf(f, other), SemanticStatus(SemanticIndexState.NoModel), EmptyList,
            knownLicenseByFile = mapOf("nomic-embed-text-v1.5.Q4_K_M.gguf" to "Apache-2.0"),
        )
        assertTrue(u.models.single { it.name == "custom.gguf" }.selected)
        assertEquals("License: Apache-2.0", u.models.first { it.name.startsWith("nomic") }.licenseText)
        assertTrue(u.models.single { it.name == "custom.gguf" }.licenseText.startsWith("License unknown"))
        assertNull(u.noModelHint)
    }

    @Test fun `no installed models shows a hint and the catalog entry as not downloaded with size, license, host`() {
        val u = ui(SemanticIndexState.NoModel)
        assertNotNull(u.noModelHint)
        val row = u.catalog.single()
        assertEquals(SemanticSettingsPresenter.DownloadState.NOT_DOWNLOADED, row.state)
        assertEquals("80.2 MiB", row.sizeText)
        assertEquals("License: Apache-2.0", row.licenseText)
        assertTrue(row.hostLine.contains("huggingface.co"))
        assertTrue(row.hostLine.contains("when you tap Download"))
    }

    @Test fun `catalog row reflects an in-flight download and an installed file`() {
        val e = EmbeddingModelCatalog.entries.single()
        assertEquals(SemanticSettingsPresenter.DownloadState.DOWNLOADING, ui(SemanticIndexState.NoModel, downloads = mapOf(e.id to 0.4f)).catalog.single().state)
        assertEquals(SemanticSettingsPresenter.DownloadState.INSTALLED, ui(SemanticIndexState.NoModel, installed = listOf(EmbeddingModelFile(File(e.fileName)))).catalog.single().state)
    }

    @Test fun `byte formatting`() {
        assertEquals("0 B", SemanticSettingsPresenter.formatBytes(0))
        assertEquals("1.0 KiB", SemanticSettingsPresenter.formatBytes(1024))
        assertEquals("80.2 MiB", SemanticSettingsPresenter.formatBytes(84_106_624))
    }

    private val EmptyList = emptyList<dev.fonebrew.data.search.semantic.EmbeddingCatalogEntry>()
}
