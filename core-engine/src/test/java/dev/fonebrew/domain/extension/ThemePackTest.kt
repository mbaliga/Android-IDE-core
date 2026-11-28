package dev.fonebrew.domain.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemePackTest {

    private fun doc(theme: String, id: String = "dev.example.theme.t") = """
        {"format":"fonebrew-extension","formatVersion":1,"id":"$id","name":"T","version":"1.0.0",
         "kind":"theme","author":"a","license":"MIT","capabilities":["appearance.write"],"theme":$theme}
    """.trimIndent()

    private fun failure(theme: String) = (ThemePack.parse(doc(theme)) as ThemePack.Result.Failure).errors
    private fun pack(theme: String) = (ThemePack.parse(doc(theme)) as ThemePack.Result.Success).pack

    @Test fun `parses the four appearance knobs`() {
        val p = pack("""{"mode":"LIGHT","accent":"#9aa7b8","texture":0.1,"gradient":"#4DA3FF"}""")
        assertEquals(ThemePack.Apply("LIGHT", "#9AA7B8", 0.1f, "#4DA3FF"), p.toApply())
    }

    @Test fun `no gradient means the apply call clears it`() {
        assertEquals("", pack("""{"mode":"DARK","accent":"#2DD4BF","texture":0.3}""").toApply().gradient)
    }

    @Test fun `rejects bad mode, accent, texture and gradient`() {
        assertTrue(failure("""{"mode":"dark","accent":"#2DD4BF","texture":0.3}""").any { it.contains("theme.mode") })
        assertTrue(failure("""{"mode":"DARK","accent":"teal","texture":0.3}""").any { it.contains("theme.accent") })
        assertTrue(failure("""{"mode":"DARK","accent":"#2DD4BF","texture":1.5}""").any { it.contains("theme.texture") })
        assertTrue(failure("""{"mode":"DARK","accent":"#2DD4BF"}""").any { it.contains("theme.texture") })
        assertTrue(failure("""{"mode":"DARK","accent":"#2DD4BF","texture":0.2,"gradient":"blue"}""").any { it.contains("theme.gradient") })
    }

    @Test fun `isActive compares case-insensitively and tolerates float noise`() {
        val p = pack("""{"mode":"DARK","accent":"#2dd4bf","texture":0.3,"gradient":"#4DA3FF"}""")
        assertTrue(p.isActive("DARK", "#2DD4BF", 0.3f, "#4da3ff"))
        assertTrue(p.isActive("DARK", "#2DD4BF", 0.3001f, "#4DA3FF"))
        assertFalse(p.isActive("LIGHT", "#2DD4BF", 0.3f, "#4DA3FF"))
        assertFalse(p.isActive("DARK", "#2DD4BF", 0.3f, ""))
    }

    @Test fun `the catalog reports invalid and duplicate packs instead of dropping them silently`() {
        val good = doc("""{"mode":"DARK","accent":"#2DD4BF","texture":0.3}""")
        val bad = doc("""{"mode":"nope","accent":"#2DD4BF","texture":0.3}""", id = "dev.example.theme.bad")
        val loaded = ThemePackCatalog.load(mapOf("a.theme.json" to good, "b.theme.json" to good, "c.theme.json" to bad))
        assertEquals(1, loaded.packs.size)
        assertEquals(setOf("b.theme.json", "c.theme.json"), loaded.rejected.map { it.source }.toSet())
    }

    /** The packs we SHIP must all be valid — a typo in a bundled asset fails the build, not the user. */
    @Test fun `every bundled theme pack is valid, uniquely identified and Apache-2_0`() {
        val dir = File("src/main/assets/extensions/themes")
        assertTrue("bundled themes dir missing: ${dir.absolutePath}", dir.isDirectory)
        val docs = dir.listFiles { f -> f.name.endsWith(".theme.json") }!!.associate { it.name to it.readText() }
        assertTrue(docs.size >= 5)
        val loaded = ThemePackCatalog.load(docs)
        assertEquals("rejected: ${loaded.rejected}", emptyList<ThemePackCatalog.Rejected>(), loaded.rejected)
        assertEquals(docs.size, loaded.packs.size)
        assertTrue(loaded.packs.all { it.manifest.license == "Apache-2.0" })
        // the Fonebrew violet ships as a pack too (ThemePicker presets call it the Fonebrew violet)
        assertTrue(loaded.packs.any { it.manifest.id == "dev.fonebrew.theme.fonebrew" && it.accent.equals("#8E7BFF", true) })
    }
}
