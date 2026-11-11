package dev.fonebrew.ui

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.Role
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.ofRole
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real [ChatViewModel] over the chat harness: the send path and the append-only tree. */
class ChatViewModelSendTest {
    @get:Rule val main = dev.fonebrew.ui.chatharness.MainDispatcherRule()

    @Test fun `send appends a user node then an assistant node and moves the leaf to the reply`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("Hel", "lo")
        val vm = h.newViewModel(); observe(vm)
        settle()

        vm.send("  hi there  ")
        settle()

        val nodes = h.nodes()
        assertEquals(listOf(Role.USER, Role.ASSISTANT), nodes.map { it.role })
        assertEquals("hi there", nodes[0].content)
        assertNull(nodes[0].parentId)
        assertEquals("Hello", nodes[1].content)
        assertEquals(nodes[0].id, nodes[1].parentId)
        assertEquals("echo-a", nodes[1].modelId)
        assertEquals(nodes[1].id, h.session.activeLeafId.value)
        assertEquals(listOf("hi there", "Hello"), vm.uiState.value.steps.map { it.node.content })
    }
}
