package dev.aarso.domain.codelens

import dev.aarso.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLensTest {

    private val echo = Generator { _, user -> "ECHO: $user" }

    @Test fun `blank or whitespace-only lines return null without calling the model`() = runTest {
        var calls = 0
        val gen = Generator { _, _ -> calls++; "x" }
        assertNull(CodeLens.explain(emptyList(), "kt", gen))
        assertNull(CodeLens.explain(listOf("   ", "\t", ""), "kt", gen))
        assertEquals(0, calls)
    }

    @Test fun `non-blank lines produce a non-null explanation`() = runTest {
        val result = CodeLens.explain(listOf("fun main() {", "  println(\"hi\")", "}"), "kt", echo)
        assertNotNull(result)
        assertTrue(result!!.isNotBlank())
    }

    @Test fun `the language extension is mapped correctly and reaches the system prompt`() {
        assertEquals("Kotlin", CodeLens.languageLabel("kt"))
        assertEquals("Kotlin", CodeLens.languageLabel(".kt"))
        assertEquals("Python", CodeLens.languageLabel("py"))
        assertEquals("TypeScript", CodeLens.languageLabel("tsx"))
        assertEquals("shell script", CodeLens.languageLabel("sh"))
        assertEquals("YAML configuration", CodeLens.languageLabel("yml"))
        assertEquals("Gradle build script", CodeLens.languageLabel("gradle"))
        assertEquals("code", CodeLens.languageLabel("xyz"))
    }

    @Test fun `the system prompt names the language and forbids jargon and code`() {
        val p = CodeLens.systemPrompt("Kotlin")
        assertTrue(p.contains("Kotlin"))
        assertTrue(p.contains("not a programmer"))
        assertTrue(p.contains("no code"))
    }

    @Test fun `caps at MAX_LINES lines, trimming excess silently`() = runTest {
        val manyLines = (1..CodeLens.MAX_LINES + 10).map { "line $it" }
        var received = ""
        val gen = Generator { _, user -> received = user; "ok" }
        CodeLens.explain(manyLines, "kt", gen)
        assertEquals(CodeLens.MAX_LINES, received.lines().size)
    }

    @Test fun `blank lines inside the window are stripped before sending`() = runTest {
        val lines = listOf("val x = 1", "", "   ", "val y = 2")
        var received = ""
        val gen = Generator { _, user -> received = user; "ok" }
        CodeLens.explain(lines, "kt", gen)
        assertTrue(received.lines().none { it.isBlank() })
        assertEquals(2, received.lines().size)
    }

    @Test fun `a blank model response is returned as null`() = runTest {
        val res = CodeLens.explain(listOf("val x = 1"), "kt", Generator { _, _ -> "   " })
        assertNull(res)
    }
}
