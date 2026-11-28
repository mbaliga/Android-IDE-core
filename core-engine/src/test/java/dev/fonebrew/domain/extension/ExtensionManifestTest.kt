package dev.fonebrew.domain.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionManifestTest {

    private fun themeJson(
        id: String = "dev.example.theme.abyss",
        kind: String = "theme",
        caps: String = "[\"appearance.write\"]",
        extra: String = "",
    ) = """
        {"format":"fonebrew-extension","formatVersion":1,"id":"$id","name":"Abyss","version":"1.2.3",
         "kind":"$kind","author":"Someone","license":"Apache-2.0","description":"d",
         "capabilities":$caps,"theme":{"mode":"DARK","accent":"#2DD4BF","texture":0.3}$extra}
    """.trimIndent()

    private fun errors(json: String): List<String> =
        (ExtensionManifest.parse(json) as ExtensionManifest.ParseResult.Failure).errors

    @Test fun `a well-formed theme manifest parses`() {
        val r = ExtensionManifest.parse(themeJson()) as ExtensionManifest.ParseResult.Success
        assertEquals("dev.example.theme.abyss", r.manifest.id)
        assertEquals(ExtensionKind.THEME, r.manifest.kind)
        assertEquals(setOf(ExtensionCapability.APPEARANCE_WRITE), r.manifest.capabilities)
    }

    @Test fun `garbage is a failure, not a crash`() {
        assertTrue(errors("not json {").single().startsWith("not valid JSON"))
    }

    @Test fun `ids must be lowercase dotted identifiers`() {
        assertTrue(errors(themeJson(id = "Abyss")).any { it.startsWith("id must") })
        assertTrue(errors(themeJson(id = "abyss")).any { it.startsWith("id must") })
        assertTrue(errors(themeJson(id = "dev.Example.x")).any { it.startsWith("id must") })
    }

    @Test fun `the script kind is rejected until the runtime exists`() {
        val e = errors(themeJson(kind = "script"))
        assertTrue(e.any { it.contains("not supported by this build yet") })
    }

    @Test fun `unknown kinds and unknown capabilities are rejected`() {
        assertTrue(errors(themeJson(kind = "widget")).any { it.contains("unknown kind") })
        assertTrue(errors(themeJson(caps = "[\"appearance.write\",\"network\"]")).any { it.contains("unknown capability \"network\"") })
    }

    @Test fun `a theme must declare appearance write and nothing else`() {
        assertTrue(errors(themeJson(caps = "[]")).any { it.contains("must declare \"appearance.write\"") })
    }

    @Test fun `required fields are enforced`() {
        val e = errors("""{"format":"x","formatVersion":2,"id":"","name":"","version":"1","kind":"theme","author":"","license":""}""")
        assertTrue(e.any { it.startsWith("format must") })
        assertTrue(e.any { it.startsWith("formatVersion must") })
        assertTrue(e.any { it.startsWith("version must be semver") })
        assertTrue(e.any { it == "author is required" })
        assertTrue(e.any { it.startsWith("license is required") })
    }
}
