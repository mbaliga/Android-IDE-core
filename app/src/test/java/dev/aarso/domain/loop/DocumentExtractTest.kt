package dev.aarso.domain.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentExtractTest {

    @Test fun `paragraph HTML extracts to clean text with paragraph breaks preserved`() {
        val html = "<html><body><h1>Title</h1><p>First paragraph.</p><p>Second paragraph.</p></body></html>"
        val text = DocumentExtract.extractText(html)
        assertEquals("Title\nFirst paragraph.\nSecond paragraph.", text)
    }

    @Test fun `script and style content is excluded even inside the body`() {
        val html = """
            <html><head><style>body { color: red; }</style></head>
            <body>
            <script>var x = "<p>fake content</p>"; alert(1);</script>
            <p>Real paragraph.</p>
            </body></html>
        """.trimIndent()
        val text = DocumentExtract.extractText(html)
        assertTrue(text.contains("Real paragraph."))
        assertFalse(text.contains("fake content"))
        assertFalse(text.contains("color: red"))
        assertFalse(text.contains("alert"))
    }

    @Test fun `common HTML entities decode correctly`() {
        val html = "<p>Fish &amp; Chips &#39;n&#39; more&nbsp;stuff</p>"
        val text = DocumentExtract.extractText(html)
        assertEquals("Fish & Chips 'n' more stuff", text)
    }

    @Test fun `long input is truncated with a marker and stays within a reasonable bound`() {
        val html = (1..2000).joinToString("") {
            "<p>Paragraph $it with some sample padding text to make it longer.</p>"
        }
        val text = DocumentExtract.extractText(html)
        assertTrue(text.length <= 20_100)
        assertTrue(text.endsWith("[truncated — article continues]"))
        assertFalse(text.contains("Paragraph 1999"))
    }
}
