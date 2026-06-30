package dev.aarso.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRepoParseTest {

    @Test fun `parses a single file block`() {
        val raw = "<<<FILE src/A.kt>>>\nfun a() = 1\n<<<END>>>"
        val blocks = AgentRepoRunner.parseFileBlocks(raw)
        assertEquals(1, blocks.size)
        assertEquals("src/A.kt", blocks[0].first)
        assertEquals("fun a() = 1", blocks[0].second)
    }

    @Test fun `parses multiple blocks and ignores prose between them`() {
        val raw = """
            Here are the changes:
            <<<FILE a.txt>>>
            hello
            world
            <<<END>>>
            and another:
            <<<FILE dir/b.txt>>>
            line
            <<<END>>>
        """.trimIndent()
        val blocks = AgentRepoRunner.parseFileBlocks(raw)
        assertEquals(listOf("a.txt", "dir/b.txt"), blocks.map { it.first })
        assertEquals("hello\nworld", blocks[0].second)
        assertEquals("line", blocks[1].second)
    }

    @Test fun `no blocks yields empty`() {
        assertEquals(emptyList<Pair<String, String>>(), AgentRepoRunner.parseFileBlocks("just talking, no edits"))
    }

    @Test fun `trims the path and preserves inner blank lines`() {
        val raw = "<<<FILE   p.kt  >>>\na\n\nb\n<<<END>>>"
        val blocks = AgentRepoRunner.parseFileBlocks(raw)
        assertEquals("p.kt", blocks[0].first)
        assertEquals("a\n\nb", blocks[0].second)
    }
}
