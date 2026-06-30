package dev.aarso.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLinterTest {

    private fun messages(text: String) = PromptLinter.lint(text).findings.map { it.message }

    @Test
    fun blankInput_hasNoFindings() {
        assertTrue(PromptLinter.lint("   ").isClean)
    }

    @Test
    fun shortPrompt_flagsLength() {
        assertTrue(messages("fix this").any { it.contains("short", ignoreCase = true) })
    }

    @Test
    fun vagueOpener_isFlagged() {
        assertTrue(messages("it doesn't work, help").any { it.contains("vague pronoun") })
    }

    @Test
    fun fullInstruction_withRoleFormatConstraints_hasNoMissingNotes() {
        val msgs = messages(
            "You are a precise editor. Rewrite the paragraph as 3 bullet points, under 50 words.",
        )
        assertFalse(msgs.any { it.contains("No role") })
        assertFalse(msgs.any { it.contains("No output format") })
        assertFalse(msgs.any { it.contains("No constraints") })
    }

    @Test
    fun instructionMissingPieces_getsInfoNotes() {
        val msgs = messages("summarize the attached transcript about the meeting yesterday")
        assertTrue(msgs.any { it.contains("No role") })
        assertTrue(msgs.any { it.contains("No output format") })
    }

    @Test
    fun detectsDelimitedAndAllCapsVariables() {
        val result = PromptLinter.lint("Translate {{text}} into LANGUAGE for [audience]")
        val vars = result.variables.map { it.text }.toSet()
        assertTrue(vars.contains("{{text}}"))
        assertTrue(vars.contains("LANGUAGE"))
        assertTrue(vars.contains("[audience]"))
        assertTrue(result.findings.any { it.message.contains("variable slot") })
    }

    @Test
    fun variableSpansHaveCorrectOffsets() {
        val text = "Hello {{name}}"
        val span = PromptLinter.lint(text).variables.first { it.text == "{{name}}" }
        assertEquals("{{name}}", text.substring(span.start, span.end))
    }
}
