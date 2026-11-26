package dev.fonebrew.domain.editor.lex

import dev.fonebrew.domain.editor.buffer.Rope
import dev.fonebrew.domain.editor.buffer.TextBuffer
import dev.fonebrew.domain.editor.edit.CommentSyntax
import dev.fonebrew.domain.editor.edit.Editor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LexerInvariantTest {

    private fun check(line: String, tokens: List<Token>, where: String) {
        var pos = 0
        for (t in tokens) {
            assertEquals("gap/overlap in $where", pos, t.start)
            assertTrue("empty token in $where", t.end > t.start)
            pos = t.end
        }
        assertEquals("tokens must cover the whole line ($where)", line.length, pos)
    }

    private fun lexDoc(lang: Language, lines: List<String>): List<List<Token>> {
        var st = LexState.INITIAL
        return lines.map { l ->
            val sink = TokenSink(l.length)
            st = lang.lexer.lexLine(l, st, sink)
            sink.build().also { check(l, it, lang.id) }
        }
    }

    @Test fun `random garbage never loops, throws, or leaves a gap in any language`() {
        val rnd = Random(11)
        val frag = listOf("\"", "'", "`", "\\", "/*", "*/", "//", "#", "<", ">", "<!--", "-->", "{", "}", "[", "]", "(", ")", "$", "\${", "\"\"\"", "'''",
            " ", "\t", "a", "bc", "0x", "1.5e", "&", ";", ":", "=", "-", "---", "|", ">", "r#\"", "\"#", "<<", "EOF", "😀", "́", "\u0000", "@")
        for (lang in Languages.ALL) {
            repeat(300) {
                val lines = (0 until 1 + rnd.nextInt(6)).map { (0 until rnd.nextInt(30)).joinToString("") { frag[rnd.nextInt(frag.size)] } }
                lexDoc(lang, lines)
            }
        }
    }

    @Test fun `pathological lines stay bounded in every language`() {
        val big = 200_000
        val lines = listOf(
            "\"" + "x".repeat(big),                                  // unterminated string
            "/*".repeat(big / 2),                                     // nested comment openers
            "\\".repeat(big),                                         // escapes
            "(".repeat(big),                                          // deep nesting characters
            "a".repeat(1_000_000),                                    // one huge identifier
            "<a ".repeat(big / 3),                                    // tag soup
            "{".repeat(big) + "}".repeat(big),
            "\"\"\"" + "'".repeat(big),
        )
        val start = System.nanoTime()
        for (lang in Languages.ALL) for (l in lines) {
            val inc = IncrementalLexer(lang.lexer)
            inc.reset(object : LineSource { override val lineCount = 1; override fun lineText(line: Int) = l })
            check(l, inc.tokens(0), "${lang.id} pathological")
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("pathological lines took ${ms}ms", ms < 30_000)
    }

    @Test fun `an unterminated string or comment ends at its line or carries a state, never spins`() {
        val kt = Languages.byId("kotlin").lexer
        val sink = TokenSink(8)
        assertEquals(LexState.INITIAL, kt.lexLine("\"abc def", LexState.INITIAL, sink))
        val s2 = TokenSink(8)
        assertEquals(LexState(1, 1), kt.lexLine("/* open ", LexState.INITIAL, s2))
    }

    @Test fun `over-long lines are lexed up to the cap and the rest is one token`() {
        val line = "val x = 1 " + " ".repeat(IncrementalLexer.MAX_LINE) + "val y = 2"
        val inc = IncrementalLexer(Languages.byId("kotlin").lexer)
        inc.reset(object : LineSource { override val lineCount = 1; override fun lineText(line0: Int) = line })
        val toks = inc.tokens(0)
        check(line, toks, "long")
        assertTrue(toks.any { it.category == TokenCategory.KEYWORD && it.start == 0 })
        assertFalse("past the cap nothing is lexed as a keyword", toks.any { it.category == TokenCategory.KEYWORD && it.start > IncrementalLexer.MAX_LINE })
    }

    // ---- incremental -----------------------------------------------------------------------------

    private fun fullTokens(lang: Language, rope: Rope): List<List<Token>> {
        val inc = IncrementalLexer(lang.lexer)
        inc.reset(RopeLineSource(rope))
        return (0 until inc.lineCount).map { inc.tokens(it) }
    }

    @Test fun `incremental update equals a full re-lex after random edits (kotlin and markup and yaml)`() {
        for (id in listOf("kotlin", "markup", "yaml", "css", "python", "markdown")) {
            val lang = Languages.byId(id)
            val rnd = Random(id.hashCode().toLong())
            val src = "val a = \"x\"\n/* c\n  d */ fun f() {\n  <b class=\"k\">t</b> # y: 1\n}\n```\ncode\n```\n\"\"\"doc\nmore\"\"\"\n"
            val editor = Editor(TextBuffer(src), CommentSyntax.NONE)
            val inc = IncrementalLexer(lang.lexer)
            inc.reset(RopeLineSource(editor.rope))
            editor.addListener { before, after, tx, _ -> inc.update(before, after, tx) }
            val frag = listOf("\"", "/*", "*/", "\n", "a", " ", "{", "}", "<!--", "-->", "```", "'''", "\"\"\"", "#", "|", "\r\n", "x: ")
            repeat(400) { step ->
                val len = editor.buffer.length
                val s = rnd.nextInt(len + 1)
                val e = minOf(len, s + rnd.nextInt(6))
                editor.replaceRange(s, e, if (rnd.nextInt(4) == 0) "" else frag[rnd.nextInt(frag.size)])
                if (step % 7 == 0 && editor.history.canUndo) editor.undo()
                val full = fullTokens(lang, editor.rope)
                assertEquals("$id: line count at step $step", full.size, inc.lineCount)
                for (i in full.indices) assertEquals("$id: line $i at step $step", full[i], inc.tokens(i))
            }
        }
    }

    @Test fun `a local edit re-lexes only a few lines`() {
        val src = (0 until 2000).joinToString("\n") { "val v$it = $it" }
        val editor = Editor(TextBuffer(src), CommentSyntax.NONE)
        val inc = IncrementalLexer(Languages.byId("kotlin").lexer)
        inc.reset(RopeLineSource(editor.rope))
        var changed: IncrementalLexer.Changed? = null
        editor.addListener { b, a, tx, _ -> changed = inc.update(b, a, tx) }
        editor.setCaret(editor.rope.lineStart(1000) + 3)
        editor.typeText("Z", 0)
        assertTrue("re-lexed ${changed!!.last - changed!!.first}", changed!!.last - changed!!.first <= 2)
    }

    @Test fun `opening a block comment re-lexes down to where the state settles`() {
        val src = (0 until 50).joinToString("\n") { "x$it" } + "\n*/ y\nz"
        val editor = Editor(TextBuffer(src), CommentSyntax.NONE)
        val inc = IncrementalLexer(Languages.byId("kotlin").lexer)
        inc.reset(RopeLineSource(editor.rope))
        var changed: IncrementalLexer.Changed? = null
        editor.addListener { b, a, tx, _ -> changed = inc.update(b, a, tx) }
        editor.setCaret(0)
        editor.typeText("/*", 0)
        assertEquals(TokenCategory.COMMENT, inc.tokens(10).first().category)
        assertTrue(changed!!.last >= 51)
        assertEquals(TokenCategory.PLAIN, inc.tokens(51).first().category)   // "z" after the comment closed
    }

    // ---- the colour-independent contract ---------------------------------------------------------------

    @Test fun `every category has a style hint or is glyph-intrinsic, and hints are distinct`() {
        val hinted = TokenCategory.values().filter { !TokenStyle.defaultFor(it).isNone }
        for (c in TokenCategory.values()) {
            assertTrue("$c has neither a style hint nor a glyph cue", c in hinted || c in TokenStyle.glyphIntrinsic)
        }
        assertEquals("two categories share a hint", hinted.size, hinted.map { TokenStyle.defaultFor(it).bits }.toSet().size)
        assertTrue(TokenStyle.defaultFor(TokenCategory.KEYWORD).has(StyleHint.BOLD))
        assertTrue(TokenStyle.defaultFor(TokenCategory.COMMENT).has(StyleHint.ITALIC))
        assertTrue(TokenStyle.defaultFor(TokenCategory.INVALID).has(StyleHint.UNDERLINE))
        for (c in TokenStyle.glyphIntrinsic) assertTrue(TokenStyle.defaultFor(c).isNone || c == TokenCategory.PLAIN)
    }

    @Test fun `tokens emitted by lexers carry the default style hint of their category`() {
        val line = "fun x() { /* c */ val t: Int = \"s\" }"
        val sink = TokenSink(line.length)
        Languages.byId("kotlin").lexer.lexLine(line, LexState.INITIAL, sink)
        for (t in sink.build()) assertEquals(TokenStyle.defaultFor(t.category), t.style)
        assertTrue(sink.build().any { it.category == TokenCategory.KEYWORD && it.style.has(StyleHint.BOLD) })
    }
}
