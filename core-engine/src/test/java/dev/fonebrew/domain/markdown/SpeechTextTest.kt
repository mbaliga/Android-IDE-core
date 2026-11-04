package dev.fonebrew.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    private fun plain(s: String) = SpeechText.plain(s)

    // ---- blank / plain passthrough ----

    @Test fun `empty string yields empty string`() {
        assertEquals("", plain(""))
    }

    @Test fun `whitespace-only string yields empty string`() {
        assertEquals("", plain("   \n\t  \n  "))
    }

    @Test fun `plain prose with no markdown passes through unchanged`() {
        assertEquals("Hello, this is plain prose.", plain("Hello, this is plain prose."))
    }

    @Test fun `multi-line prose is joined with a single space, not left with raw newlines`() {
        assertEquals("Hello world", plain("Hello\nworld"))
    }

    @Test fun `blank lines between paragraphs collapse rather than doubling the join space`() {
        assertEquals("First paragraph. Second paragraph.", plain("First paragraph.\n\n\nSecond paragraph."))
    }

    // ---- headings ----

    @Test fun `level-1 heading marker is stripped`() {
        assertEquals("Title", plain("# Title"))
    }

    @Test fun `level-3 heading marker is stripped`() {
        assertEquals("Sub-section", plain("### Sub-section"))
    }

    @Test fun `level-6 heading marker is stripped`() {
        assertEquals("Deep", plain("###### Deep"))
    }

    // ---- blockquote ----

    @Test fun `blockquote marker is stripped`() {
        assertEquals("quoted text", plain("> quoted text"))
    }

    @Test fun `doubled blockquote marker (nested quote) is stripped entirely`() {
        assertEquals("nested", plain("> > nested"))
    }

    // ---- lists ----

    @Test fun `dash bullet marker is stripped`() {
        assertEquals("item one", plain("- item one"))
    }

    @Test fun `star bullet marker is stripped`() {
        assertEquals("item two", plain("* item two"))
    }

    @Test fun `plus bullet marker is stripped`() {
        assertEquals("item three", plain("+ item three"))
    }

    @Test fun `ordered list marker with dot is stripped`() {
        assertEquals("First step", plain("1. First step"))
    }

    @Test fun `ordered list marker with paren is stripped`() {
        assertEquals("Second step", plain("2) Second step"))
    }

    @Test fun `multi-digit ordered marker is stripped`() {
        assertEquals("Twelfth", plain("12. Twelfth"))
    }

    // ---- horizontal rule ----

    @Test fun `a bare horizontal rule line contributes nothing`() {
        assertEquals("", plain("---"))
    }

    @Test fun `a horizontal rule between paragraphs is dropped, not read as dashes`() {
        assertEquals("Before After", plain("Before\n---\nAfter"))
    }

    // ---- inline code ----

    @Test fun `inline code backticks are unwrapped to their literal text`() {
        assertEquals("Run ls -la now", plain("Run `ls -la` now"))
    }

    // ---- emphasis ----

    @Test fun `single-star italic is unwrapped`() {
        assertEquals("This is italic text", plain("This is *italic* text"))
    }

    @Test fun `single-underscore italic is unwrapped`() {
        assertEquals("This is italic text", plain("This is _italic_ text"))
    }

    @Test fun `double-star bold is unwrapped`() {
        assertEquals("This is bold text", plain("This is **bold** text"))
    }

    @Test fun `double-underscore bold is unwrapped`() {
        assertEquals("This is bold text", plain("This is __bold__ text"))
    }

    @Test fun `triple-star bold-italic is unwrapped`() {
        assertEquals("This is both text", plain("This is ***both*** text"))
    }

    // ---- links and images ----

    @Test fun `a markdown link is unwrapped to its visible text, url dropped`() {
        assertEquals("See the docs for more", plain("See [the docs](https://example.com) for more"))
    }

    @Test fun `an image with alt text is unwrapped to the alt text`() {
        assertEquals("a cat", plain("![a cat](cat.png)"))
    }

    @Test fun `an image with empty alt text contributes nothing, not a stray line`() {
        assertEquals("Before After", plain("Before\n![](cat.png)\nAfter"))
    }

    // ---- fenced code blocks ----

    @Test fun `a fenced code block collapses to a single spoken marker`() {
        val src = "Before\n```kotlin\nval x = 1\nprintln(x)\n```\nAfter"
        assertEquals("Before Code block. After", plain(src))
    }

    @Test fun `fenced code content is never read literally`() {
        val src = "```\nfun undeclaredIdentifier(): Nothing = TODO()\n```"
        val out = plain(src)
        assertFalse(out.contains("undeclaredIdentifier"))
        assertEquals("Code block.", out)
    }

    @Test fun `an unclosed fence never leaks the fence marker or trailing code`() {
        val out = plain("Before\n```\ncode without a closing fence")
        assertEquals("Before Code block.", out)
        assertFalse(out.contains("`"))
    }

    @Test fun `tilde fences are recognized the same as backtick fences`() {
        assertEquals("Code block.", plain("~~~\nsome code\n~~~"))
    }

    // ---- GFM tables ----

    @Test fun `a table separator row contributes nothing`() {
        assertEquals("", plain("| --- | --- |"))
    }

    @Test fun `table data rows read their cells as a comma list`() {
        val src = "| Name | Age |\n| --- | --- |\n| Alice | 30 |"
        assertEquals("Name, Age Alice, 30", plain(src))
    }

    // ---- lineToSpeech / unwrapInline (single-line internals TaskFromTurn.title also relies on) ----

    @Test fun `lineToSpeech strips structural markers from one already-trimmed line`() {
        assertEquals("Fix the login bug", SpeechText.lineToSpeech("## Fix the login bug"))
    }

    @Test fun `lineToSpeech on a horizontal-rule line returns empty`() {
        assertEquals("", SpeechText.lineToSpeech("***"))
    }

    @Test fun `unwrapInline composes image, link, code, and emphasis unwrapping`() {
        assertEquals(
            "See a cat and read the docs, run ls now, in bold",
            SpeechText.unwrapInline(
                "See ![a cat](cat.png) and read [the docs](https://x), run `ls` now, in **bold**",
            ),
        )
    }

    @Test fun `never throws on adversarial input`() {
        val inputs = listOf(
            "`".repeat(50),
            "*".repeat(50),
            "|".repeat(50),
            "#".repeat(200) + " heading",
            "😀 emoji and CJK 你好 mixed with *markdown*",
            "[unclosed link(",
            "![unclosed image(",
        )
        for (input in inputs) {
            plain(input) // must not throw
        }
        assertTrue(true)
    }
}
