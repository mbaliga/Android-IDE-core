package dev.fonebrew.domain.search.query

import dev.fonebrew.domain.tree.Conversations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFacetFilterTest {

    private fun summary(id: String, hasImage: Boolean = false) = Conversations.Summary(
        rootId = id,
        title = "conv $id",
        modelIds = emptyList(),
        lastUpdatedAt = 0L,
        nodeCount = 1,
        latestLeafId = id,
        hasImage = hasImage,
    )

    private fun matches(node: QueryNode?, starred: Boolean = false, projectId: String? = null, hasImage: Boolean = false) =
        ConversationFacetFilter.matches(summary("c1", hasImage), starred, projectId, node)

    @Test fun `null node always matches`() {
        assertTrue(matches(null))
    }

    @Test fun `is starred matches only starred conversations`() {
        val node = QueryParser.parse("is:starred").root
        assertTrue(matches(node, starred = true))
        assertFalse(matches(node, starred = false))
    }

    @Test fun `is orphan matches only conversations with no project`() {
        val node = QueryParser.parse("is:orphan").root
        assertTrue(matches(node, projectId = null))
        assertFalse(matches(node, projectId = "Aarso"))
    }

    @Test fun `has image matches only image conversations`() {
        val node = QueryParser.parse("has:image").root
        assertTrue(matches(node, hasImage = true))
        assertFalse(matches(node, hasImage = false))
    }

    @Test fun `negated has image is the TEXT preset`() {
        val node = QueryParser.parse("-has:image").root
        assertTrue(matches(node, hasImage = false))
        assertFalse(matches(node, hasImage = true))
    }

    @Test fun `negated is orphan is the PROJECTS preset`() {
        val node = QueryParser.parse("-is:orphan").root
        assertTrue(matches(node, projectId = "Aarso"))
        assertFalse(matches(node, projectId = null))
    }

    @Test fun `unbacked is value never matches`() {
        val node = QueryParser.parse("is:archived").root
        assertFalse(matches(node))
    }

    // ---- every ChatsPreset parses, and behaves as documented ----

    @Test fun `every preset query parses without diagnostics that would break evaluation`() {
        ChatsPreset.entries.forEach { preset ->
            val parsed = QueryParser.parse(preset.query)
            // ALL's blank query is the one exception with no root; everything else must parse
            // to a real node for the filter to do anything.
            if (preset != ChatsPreset.ALL) {
                assertTrue("expected a root for preset '${preset.name}' ('${preset.query}')", parsed.root != null)
            }
        }
    }

    @Test fun `ALL preset matches everything`() {
        val node = QueryParser.parse(ChatsPreset.ALL.query).root
        assertTrue(matches(node, starred = false, projectId = null, hasImage = false))
        assertTrue(matches(node, starred = true, projectId = "x", hasImage = true))
    }

    @Test fun `TEXT preset matches exactly conversations without images`() {
        val node = QueryParser.parse(ChatsPreset.TEXT.query).root
        assertTrue(matches(node, hasImage = false))
        assertFalse(matches(node, hasImage = true))
    }

    @Test fun `STARRED preset matches exactly starred conversations`() {
        val node = QueryParser.parse(ChatsPreset.STARRED.query).root
        assertTrue(matches(node, starred = true))
        assertFalse(matches(node, starred = false))
    }

    @Test fun `filtering a mixed list with TEXT preset behaves like the legacy hasImage filter`() {
        val list = listOf(summary("a", hasImage = false), summary("b", hasImage = true), summary("c", hasImage = false))
        val node = QueryParser.parse(ChatsPreset.TEXT.query).root
        val viaPreset = list.filter { ConversationFacetFilter.matches(it, false, null, node) }
        val legacy = list.filter { !it.hasImage }
        assertEquals(legacy.map { it.rootId }, viaPreset.map { it.rootId })
    }

    @Test fun `filtering with STARRED preset behaves like the legacy Bookmarks filter`() {
        val list = listOf(summary("a"), summary("b"), summary("c"))
        val bookmarked = setOf("b")
        val node = QueryParser.parse(ChatsPreset.STARRED.query).root
        val viaPreset = list.filter { ConversationFacetFilter.matches(it, it.rootId in bookmarked, null, node) }
        val legacy = dev.fonebrew.domain.tree.Bookmarks.filter(list, bookmarked)
        assertEquals(legacy.map { it.rootId }, viaPreset.map { it.rootId })
    }
}
