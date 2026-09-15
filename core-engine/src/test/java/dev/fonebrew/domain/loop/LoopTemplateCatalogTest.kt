package dev.fonebrew.domain.loop

import dev.fonebrew.data.LoopTemplateAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Pins [LoopTemplateCatalog] — LoopRoom's "Templates" browse list — honest: every listed entry
 * has real content and actually resolves to a bundled asset, so the catalog can't silently name
 * a template that doesn't exist. Same working-directory-hunting fallback as
 * [LocalizationDraftLoopTest] and [dev.fonebrew.ui.graph.GraphRoomAssetTest] — Gradle's default
 * unit-test working directory is the module's own project dir (`core-engine/`).
 */
class LoopTemplateCatalogTest {

    @Test
    fun `the catalog is non-empty`() {
        assertTrue(LoopTemplateCatalog.ALL.isNotEmpty())
    }

    @Test
    fun `every entry has a non-blank asset name, display name and description`() {
        for (entry in LoopTemplateCatalog.ALL) {
            assertFalse("blank assetName in $entry", entry.assetName.isBlank())
            assertFalse("blank displayName in $entry", entry.displayName.isBlank())
            assertFalse("blank description in $entry", entry.description.isBlank())
        }
    }

    @Test
    fun `asset names are unique`() {
        val names = LoopTemplateCatalog.ALL.map { it.assetName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `stays in lockstep with LoopTemplateAssets' own constant`() {
        // dev.fonebrew.data.LoopTemplateAssets.LOCALIZATION_DRAFT is a plain string constant —
        // referencing it needs no android.content.Context, so this comparison is a real JVM
        // check, not a stub.
        assertTrue(LoopTemplateCatalog.ALL.any { it.assetName == LoopTemplateAssets.LOCALIZATION_DRAFT })
    }

    @Test
    fun `every listed asset actually exists under the committed assets folder`() {
        for (entry in LoopTemplateCatalog.ALL) {
            assertTrue("missing bundled asset: ${entry.assetName}", findAsset(entry.assetName).isFile)
        }
    }

    private fun findAsset(name: String): File {
        val relative = "src/main/assets/loops/$name"
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val direct = File(dir, "core-engine/$relative")
            if (direct.isFile) return direct
            dir = dir.parentFile ?: return@repeat
        }
        fail("couldn't find core-engine/$relative from working directory '${System.getProperty("user.dir")}'.")
        error("unreachable")
    }
}
