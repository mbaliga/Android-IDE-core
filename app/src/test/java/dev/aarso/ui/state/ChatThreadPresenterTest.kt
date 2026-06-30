package dev.aarso.ui.state

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.provenance.ProvenanceState
import dev.aarso.domain.state.UiState
import dev.aarso.domain.tree.MessageTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ChatThreadPresenter] — pure, deterministic, no Android.
 */
class ChatThreadPresenterTest {

    // Fixed epoch base so createdAt ordering is deterministic — no clock reads.
    private val t0 = 1_700_000_000_000L

    private fun node(
        id: String,
        parentId: String?,
        role: Role,
        content: String,
        modelId: String? = null,
        createdAt: Long = t0,
    ) = MessageNode(
        id = id,
        parentId = parentId,
        role = role,
        content = content,
        modelId = modelId,
        createdAt = createdAt,
    )

    private fun readyView(state: UiState<ChatThreadPresenter.ThreadView>): ChatThreadPresenter.ThreadView {
        assertTrue("expected Ready, was $state", state is UiState.Ready)
        return (state as UiState.Ready).value
    }

    // ---- active path projection ----

    @Test
    fun `active path projects root user assistant in order`() {
        val root = node("root", null, Role.SYSTEM, "you are aarso", createdAt = t0)
        val user = node("u1", "root", Role.USER, "hello", createdAt = t0 + 1)
        val asst = node("a1", "u1", Role.ASSISTANT, "hi there", modelId = "qwen", createdAt = t0 + 2)
        val tree = MessageTree(listOf(root, user, asst))

        val view = readyView(ChatThreadPresenter.present(tree, "a1"))

        assertEquals("leaf carried through", "a1", view.leafId)
        assertEquals("one row per path node", 3, view.rows.size)
        assertEquals(listOf("root", "u1", "a1"), view.rows.map { it.nodeId })
        assertEquals(
            listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT),
            view.rows.map { it.role },
        )
    }

    @Test
    fun `assistant row carries model and supplied provenance`() {
        val root = node("root", null, Role.USER, "ping", createdAt = t0)
        val asst = node("a1", "root", Role.ASSISTANT, "pong", modelId = "claude-cloud", createdAt = t0 + 1)
        val tree = MessageTree(listOf(root, asst))

        // Caller decides provenance: cloud for the assistant node, unknown otherwise.
        val state = ChatThreadPresenter.present(tree, "a1") { n ->
            if (n.role == Role.ASSISTANT) ProvenanceState.CLOUD else ProvenanceState.UNKNOWN
        }
        val view = readyView(state)

        val asstRow = view.rows.single { it.nodeId == "a1" }
        assertEquals("model id carried", "claude-cloud", asstRow.model)
        assertEquals("provenance from supplied fn", ProvenanceState.CLOUD, asstRow.provenance)

        val userRow = view.rows.single { it.nodeId == "root" }
        assertNull("user turn has no model", userRow.model)
        assertEquals(ProvenanceState.UNKNOWN, userRow.provenance)
    }

    @Test
    fun `provenance defaults to UNKNOWN when not supplied`() {
        val root = node("root", null, Role.USER, "ping", createdAt = t0)
        val asst = node("a1", "root", Role.ASSISTANT, "pong", modelId = "local-gguf", createdAt = t0 + 1)
        val tree = MessageTree(listOf(root, asst))

        val view = readyView(ChatThreadPresenter.present(tree, "a1"))

        assertTrue(view.rows.all { it.provenance == ProvenanceState.UNKNOWN })
    }

    // ---- streaming-safe markdown ----

    @Test
    fun `mid-stream unclosed fence yields openFence true and safe renderText`() {
        val root = node("root", null, Role.USER, "show me code", createdAt = t0)
        // An assistant turn still streaming: an opened ```kotlin fence, no closer yet.
        val partial = "Here:\n```kotlin\nfun x() {"
        val asst = node("a1", "root", Role.ASSISTANT, partial, modelId = "qwen", createdAt = t0 + 1)
        val tree = MessageTree(listOf(root, asst))

        val view = readyView(ChatThreadPresenter.present(tree, "a1"))
        val asstRow = view.rows.single { it.nodeId == "a1" }

        assertTrue("open fence detected", asstRow.streamingOpenFence)
        // The safe text must close the fence so a strict renderer does not thrash.
        assertTrue("synthetic closing fence appended", asstRow.renderText.endsWith("```"))
        assertTrue("original prefix preserved", asstRow.renderText.startsWith(partial))
    }

    @Test
    fun `well-formed markdown leaves renderText unchanged and openFence false`() {
        val root = node("root", null, Role.USER, "q", createdAt = t0)
        val complete = "Here:\n```kotlin\nfun x() {}\n```\nDone."
        val asst = node("a1", "root", Role.ASSISTANT, complete, modelId = "qwen", createdAt = t0 + 1)
        val tree = MessageTree(listOf(root, asst))

        val view = readyView(ChatThreadPresenter.present(tree, "a1"))
        val asstRow = view.rows.single { it.nodeId == "a1" }

        assertFalse("no open fence", asstRow.streamingOpenFence)
        assertEquals("complete markdown untouched", complete, asstRow.renderText)
    }

    // ---- branch sibling count ----

    @Test
    fun `branch sibling count counts other children at the parent`() {
        // user node "u1" with two assistant alternatives a1 / a2; active path follows a2.
        val root = node("root", null, Role.SYSTEM, "sys", createdAt = t0)
        val user = node("u1", "root", Role.USER, "go", createdAt = t0 + 1)
        val a1 = node("a1", "u1", Role.ASSISTANT, "first try", modelId = "qwen", createdAt = t0 + 2)
        val a2 = node("a2", "u1", Role.ASSISTANT, "second try", modelId = "qwen", createdAt = t0 + 3)
        val tree = MessageTree(listOf(root, user, a1, a2))

        val view = readyView(ChatThreadPresenter.present(tree, "a2"))

        val a2Row = view.rows.single { it.nodeId == "a2" }
        // a2 has one sibling (a1) → one OTHER alternative.
        assertEquals("one other alternative at the fork", 1, a2Row.branchSiblingCount)

        val userRow = view.rows.single { it.nodeId == "u1" }
        // u1 is the only child of root → no other alternatives.
        assertEquals("no fork at user node", 0, userRow.branchSiblingCount)
    }

    @Test
    fun `branch sibling count is zero on a linear path`() {
        val root = node("root", null, Role.USER, "hi", createdAt = t0)
        val asst = node("a1", "root", Role.ASSISTANT, "hello", modelId = "m", createdAt = t0 + 1)
        val tree = MessageTree(listOf(root, asst))

        val view = readyView(ChatThreadPresenter.present(tree, "a1"))

        assertTrue("linear path has no forks", view.rows.all { it.branchSiblingCount == 0 })
    }

    // ---- Empty matrix state ----

    @Test
    fun `unknown leaf yields Empty`() {
        val root = node("root", null, Role.USER, "hi", createdAt = t0)
        val tree = MessageTree(listOf(root))

        assertEquals(UiState.Empty, ChatThreadPresenter.present(tree, "does-not-exist"))
    }

    @Test
    fun `lone contentless root yields Empty`() {
        val root = node("root", null, Role.SYSTEM, "   ", createdAt = t0)
        val tree = MessageTree(listOf(root))

        assertEquals(UiState.Empty, ChatThreadPresenter.present(tree, "root"))
    }

    @Test
    fun `lone root with content is Ready not Empty`() {
        val root = node("root", null, Role.USER, "an actual message", createdAt = t0)
        val tree = MessageTree(listOf(root))

        val view = readyView(ChatThreadPresenter.present(tree, "root"))
        assertEquals(1, view.rows.size)
        assertEquals("root", view.rows.single().nodeId)
    }

    // ---- determinism ----

    @Test
    fun `present is deterministic for identical inputs`() {
        val root = node("root", null, Role.SYSTEM, "sys", createdAt = t0)
        val user = node("u1", "root", Role.USER, "go", createdAt = t0 + 1)
        val a1 = node("a1", "u1", Role.ASSISTANT, "```\nopen", modelId = "qwen", createdAt = t0 + 2)
        val a2 = node("a2", "u1", Role.ASSISTANT, "done", modelId = "qwen", createdAt = t0 + 3)
        val tree = MessageTree(listOf(root, user, a1, a2))

        val prov: (MessageNode) -> ProvenanceState = { ProvenanceState.LOCAL }
        val first = ChatThreadPresenter.present(tree, "a1", prov)
        val second = ChatThreadPresenter.present(tree, "a1", prov)

        assertEquals("data-class equality holds across calls", first, second)
    }

    @Test
    fun `provenance function is applied per node`() {
        val root = node("root", null, Role.USER, "ask", createdAt = t0)
        val asst = node("a1", "root", Role.ASSISTANT, "answer", modelId = "split", createdAt = t0 + 1)
        val tree = MessageTree(listOf(root, asst))

        val view = readyView(
            ChatThreadPresenter.present(tree, "a1") { n ->
                if (n.id == "a1") ProvenanceState.MIXED else ProvenanceState.LOCAL
            },
        )

        assertEquals(ProvenanceState.LOCAL, view.rows.single { it.nodeId == "root" }.provenance)
        assertEquals(ProvenanceState.MIXED, view.rows.single { it.nodeId == "a1" }.provenance)
    }
}
