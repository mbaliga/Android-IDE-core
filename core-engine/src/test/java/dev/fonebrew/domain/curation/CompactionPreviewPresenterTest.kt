package dev.fonebrew.domain.curation

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionPreviewPresenterTest {

    private fun node(id: String, content: String) = MessageNode(
        id = id, parentId = null, role = Role.ASSISTANT, content = content, createdAt = 0L,
    )

    @Test fun `rows come back in the caller's own order, one per message`() {
        val messages = listOf(node("m1", "a"), node("m2", "b"), node("m3", "c"))
        val rows = CompactionPreviewPresenter.build(messages, emptyMap(), emptyMap(), emptySet(), emptySet())
        assertEquals(listOf("m1", "m2", "m3"), rows.map { it.msgId })
    }

    @Test fun `an ordinary message with no signals previews as GIST, same as a real run would resolve it`() {
        val rows = CompactionPreviewPresenter.build(listOf(node("m1", "hello")), emptyMap(), emptyMap(), emptySet(), emptySet())
        assertEquals(MessageFate.GIST, rows.single().fate)
        assertEquals(FidelityReason.DEFAULT_ORDINARY, rows.single().resolution.reason)
    }

    @Test fun `a plus-2 verdict message previews as KEPT_VERBATIM`() {
        val rows = CompactionPreviewPresenter.build(
            listOf(node("m1", "great answer")),
            directives = emptyMap(),
            verdicts = mapOf("m1" to Verdict("m1", 2, 0L)),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
        )
        assertEquals(MessageFate.KEPT_VERBATIM, rows.single().fate)
    }

    @Test fun `a minus-2 verdict message previews as a TOMBSTONE`() {
        val rows = CompactionPreviewPresenter.build(
            listOf(node("m1", "wrong approach")),
            directives = emptyMap(),
            verdicts = mapOf("m1" to Verdict("m1", -2, 0L)),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
        )
        assertEquals(MessageFate.TOMBSTONE, rows.single().fate)
        assertTrue(rows.single().resolution.isFailureTombstone)
    }

    @Test fun `nothing is generated -- the row carries only an excerpt of the ORIGINAL content`() {
        val row = CompactionPreviewPresenter.build(listOf(node("m1", "content")), emptyMap(), emptyMap(), emptySet(), emptySet()).single()
        assertEquals("content", row.excerpt)
    }

    @Test fun `an excerpt is truncated, never the full message`() {
        val long = "x".repeat(500)
        val row = CompactionPreviewPresenter.build(listOf(node("m1", long)), emptyMap(), emptyMap(), emptySet(), emptySet()).single()
        assertTrue(row.excerpt.length < long.length)
    }

    @Test fun `an explicit directive wins over every other signal, same as CompactionContract`() {
        val rows = CompactionPreviewPresenter.build(
            listOf(node("m1", "content")),
            directives = mapOf("m1" to CompactionDirective("m1", mustInclude = false, fidelity = Fidelity.F0)),
            verdicts = mapOf("m1" to Verdict("m1", 2, 0L)), // would otherwise floor to F3
            bookmarkedIds = setOf("m1"),
            versionSpineIds = setOf("m1"),
        )
        assertEquals(Fidelity.F0, rows.single().resolution.fidelity)
        assertEquals(MessageFate.DROPPED, rows.single().fate)
    }

    @Test fun `must-include floors an F0 directive to a GIST instead of vanishing`() {
        val rows = CompactionPreviewPresenter.build(
            listOf(node("m1", "content")),
            directives = mapOf("m1" to CompactionDirective("m1", mustInclude = true, fidelity = Fidelity.F0)),
            verdicts = emptyMap(),
            bookmarkedIds = emptySet(),
            versionSpineIds = emptySet(),
        )
        assertEquals(MessageFate.GIST, rows.single().fate)
    }
}
