package dev.fonebrew.data.search

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.ledger.InteractionModel
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Provenance
import dev.fonebrew.domain.ledger.Status
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.thread.ThreadMarker
import dev.fonebrew.domain.thread.ThreadMarkerKind
import dev.fonebrew.domain.thread.ThreadMarkerSource
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.domain.tree.TreeFork
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProjectorTest {

    private fun node(
        id: String,
        parentId: String?,
        role: Role,
        content: String,
        createdAt: Long,
        modelId: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = MessageNode(id, parentId, role, content, modelId, createdAt, metadata = metadata)

    private fun ledger(chatId: String, costMinor: Long, at: Long = 0L) = LedgerEntry(
        timestampMillis = at,
        projectId = null,
        chatId = chatId,
        nodeId = "n",
        model = "gpt",
        provider = "openai",
        provenance = Provenance.CLOUD,
        interactionModel = InteractionModel.SINGLE,
        councilMemberId = null,
        inputTokens = 10,
        outputTokens = 20,
        estCostMinor = costMinor,
        latencyMs = 100,
        tier = Tier.CLOUD,
        status = Status.COMPLETE,
        estimated = false,
    )

    @Test fun `projects title body and snippet, segmented and raw`() {
        val tree = MessageTree(
            listOf(
                node("u1", null, Role.USER, "How do I fix the gradle build cache", 1L),
                node("a1", "u1", Role.ASSISTANT, "Pin GRADLE_USER_HOME under the project root", 2L, modelId = "gpt"),
            ),
        )
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap())
        assertEquals(1, rows.size)
        val row = rows.single()

        assertEquals("u1", row.convId)
        assertEquals("How do I fix the gradle build cache", row.titleRaw)
        assertTrue(row.title.contains("gradle"))
        assertEquals("Pin GRADLE_USER_HOME under the project root", row.snippetRaw)
        assertTrue(row.bodyRaw.contains("How do I fix the gradle build cache"))
        assertTrue(row.bodyRaw.contains("Pin GRADLE_USER_HOME"))
        assertTrue(row.body.contains("gradle"))
        assertEquals(SearchProjector.PROJECTION_VERSION, row.projectionVersion)
    }

    @Test fun `snippet prefers the first assistant turn over the user turn`() {
        val tree = MessageTree(
            listOf(
                node("u1", null, Role.USER, "question", 1L),
                node("a1", "u1", Role.ASSISTANT, "the actual answer", 2L),
            ),
        )
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertEquals("the actual answer", row.snippetRaw)
    }

    @Test fun `falls back to the user turn when there is no assistant turn yet`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "question only", 1L)))
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertEquals("question only", row.snippetRaw)
    }

    @Test fun `starred archived and project facets flow through from session inputs`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "hello", 1L)))
        val row = SearchProjector.project(
            tree,
            starredRoots = setOf("u1"),
            archivedRoots = setOf("u1"),
            projects = mapOf("u1" to "Aarso"),
            ledgerByConversation = emptyMap(),
        ).single()
        assertTrue(row.starred)
        assertTrue(row.archived)
        assertEquals("Aarso", row.projectId)
    }

    @Test fun `unstarred unarchived unassigned conversation has empty facets`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "hello", 1L)))
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertFalse(row.starred)
        assertFalse(row.archived)
        assertNull(row.projectId)
    }

    @Test fun `model ids are distinct, sorted, and comma-joined`() {
        val tree = MessageTree(
            listOf(
                node("u1", null, Role.USER, "q", 1L),
                node("a1", "u1", Role.ASSISTANT, "r1", 2L, modelId = "zeta"),
                node("a2", "a1", Role.ASSISTANT, "r2", 3L, modelId = "alpha"),
                node("a3", "a2", Role.ASSISTANT, "r3", 4L, modelId = "zeta"),
            ),
        )
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertEquals("alpha,zeta", row.modelIds)
    }

    @Test fun `turn count and branch count mirror Conversations summarize`() {
        val tree = MessageTree(
            listOf(
                node("u1", null, Role.USER, "q", 1L),
                node("a1", "u1", Role.ASSISTANT, "branch A", 2L),
                node("a2", "u1", Role.ASSISTANT, "branch B", 3L),
            ),
        )
        val summary = Conversations.summarize(tree).single()
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertEquals(summary.nodeCount.toLong(), row.turnCount)
        assertEquals(summary.branchCount.toLong(), row.branchCount)
        assertEquals(2L, row.branchCount) // two leaf tips
    }

    @Test fun `has image passes through from Conversations IMAGE_KEY metadata`() {
        val tree = MessageTree(
            listOf(
                node("u1", null, Role.USER, "make me a picture", 1L),
                node("a1", "u1", Role.ASSISTANT, "here", 2L, metadata = mapOf(Conversations.IMAGE_KEY to "1")),
            ),
        )
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertTrue(row.hasImage)
    }

    @Test fun `has code detects a markdown fence in the raw body`() {
        val withCode = MessageTree(
            listOf(
                node("u1", null, Role.USER, "show me", 1L),
                node("a1", "u1", Role.ASSISTANT, "```kotlin\nfun x() {}\n```", 2L),
            ),
        )
        val withoutCode = MessageTree(listOf(node("u2", null, Role.USER, "just prose, no fences here", 1L)))

        assertTrue(SearchProjector.project(withCode, emptySet(), emptySet(), emptyMap(), emptyMap()).single().hasCode)
        assertFalse(SearchProjector.project(withoutCode, emptySet(), emptySet(), emptyMap(), emptyMap()).single().hasCode)
    }

    @Test fun `cost is summed only from this conversation's ledger entries`() {
        val tree = MessageTree(
            listOf(
                node("u1", null, Role.USER, "q1", 1L),
                node("u2", null, Role.USER, "q2", 1L),
            ),
        )
        val ledgerByConversation = mapOf(
            "u1" to listOf(ledger("u1", 100), ledger("u1", 50)),
            "u2" to listOf(ledger("u2", 9999)),
        )
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), ledgerByConversation)
            .associateBy { it.convId }

        assertEquals(150L, rows.getValue("u1").costMinor)
        assertEquals(9999L, rows.getValue("u2").costMinor)
    }

    @Test fun `conversation with no ledger entries has zero cost`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "q", 1L)))
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertEquals(0L, row.costMinor)
    }

    @Test fun `multiple roots project independently`() {
        val tree = MessageTree(
            listOf(
                node("u1", null, Role.USER, "first conversation", 1L),
                node("u2", null, Role.USER, "second conversation", 5L),
            ),
        )
        val rows = SearchProjector.project(tree, setOf("u2"), emptySet(), emptyMap(), emptyMap())
        assertEquals(2, rows.size)
        val byId = rows.associateBy { it.convId }
        assertFalse(byId.getValue("u1").starred)
        assertTrue(byId.getValue("u2").starred)
    }

    @Test fun `empty tree projects to no rows`() {
        assertTrue(SearchProjector.project(MessageTree(emptyList()), emptySet(), emptySet(), emptyMap(), emptyMap()).isEmpty())
    }

    // ---- lineage + chapter/compaction facets (THREAD_TOPOLOGY_PLAN.md WP6) -------------------

    private fun lineageMarker(rootId: String, srcRootId: String, kind: TreeFork.LineageKind = TreeFork.LineageKind.FORK) = ThreadMarker(
        id = "lineage-$rootId",
        rootId = rootId,
        kind = ThreadMarkerKind.LINEAGE_SRC,
        at = 0L,
        source = ThreadMarkerSource.SYSTEM,
        payloadJson = JSONObject().put("srcRootId", srcRootId).put("srcNodeId", "m").put("lineageKind", kind.name).toString(),
    )

    private fun chapterMarker(rootId: String, at: Long) = ThreadMarker(
        id = "chapter-$rootId-$at", rootId = rootId, anchorMsgId = "m", kind = ThreadMarkerKind.CHAPTER,
        label = "ch", at = at, source = ThreadMarkerSource.USER,
    )

    private fun compactionMarker(rootId: String, at: Long) = ThreadMarker(
        id = "compaction-$rootId-$at", rootId = rootId, anchorMsgId = "m", kind = ThreadMarkerKind.COMPACTION_RUN,
        at = at, source = ThreadMarkerSource.SYSTEM,
    )

    @Test fun `a root with no thread markers projects with default lineage and zero counts`() {
        val tree = MessageTree(listOf(node("u1", null, Role.USER, "hello", 1L)))
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap()).single()
        assertNull(row.lineageParent)
        assertNull(row.lineageKind)
        assertEquals(0L, row.chapterCount)
        assertEquals(0L, row.compactionCount)
    }

    @Test fun `a forked root's lineage_parent and lineage_kind come from its LINEAGE_SRC marker`() {
        val tree = MessageTree(listOf(node("fork-root", null, Role.SYSTEM, "spawned prose", 1L)))
        val markersByRoot = mapOf("fork-root" to listOf(lineageMarker("fork-root", "orig-root", TreeFork.LineageKind.SPAWN)))
        val row = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap(), markersByRoot).single()
        assertEquals("orig-root", row.lineageParent)
        assertEquals("SPAWN", row.lineageKind)
    }

    @Test fun `chapter and compaction counts reflect only this root's markers`() {
        val tree = MessageTree(
            listOf(
                node("r1", null, Role.USER, "hi", 1L),
                node("r2", null, Role.USER, "hey", 2L),
            ),
        )
        val markersByRoot = mapOf(
            "r1" to listOf(chapterMarker("r1", 1L), chapterMarker("r1", 2L), compactionMarker("r1", 3L)),
            "r2" to listOf(chapterMarker("r2", 1L)),
        )
        val rows = SearchProjector.project(tree, emptySet(), emptySet(), emptyMap(), emptyMap(), markersByRoot).associateBy { it.convId }
        assertEquals(2L, rows.getValue("r1").chapterCount)
        assertEquals(1L, rows.getValue("r1").compactionCount)
        assertEquals(1L, rows.getValue("r2").chapterCount)
        assertEquals(0L, rows.getValue("r2").compactionCount)
    }

    @Test fun `PROJECTION_VERSION is 2 -- WP6 changed the projection shape`() {
        assertEquals(2L, SearchProjector.PROJECTION_VERSION)
    }
}
