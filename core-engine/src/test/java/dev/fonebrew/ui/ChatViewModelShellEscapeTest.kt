package dev.fonebrew.ui

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

/**
 * The `!cmd` shell escape through the real [ChatViewModel], with the one-shot runner replaced by a
 * recording fake. The runner's own 30 s timeout and 20 000-char cap live inside `LocalExec`
 * (hard-wired to `/system/bin/sh`) and cannot run on a Linux JVM — see
 * docs/testing/chatviewmodel-harness.md; what is pinned here is what the view-model does with the
 * runner and what it never does.
 */
class ChatViewModelShellEscapeTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `bang-command makes a user node then an assistant node carrying the shell metadata`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.shell.output = "total 0\nfile.txt\n"
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("!ls -la"); settle()

        val nodes = h.nodes()
        assertEquals(listOf(Role.USER, Role.ASSISTANT), nodes.map { it.role })
        assertEquals("!ls -la", nodes[0].content)
        assertEquals("total 0\nfile.txt\n", nodes[1].content)
        assertEquals("true", nodes[1].metadata["shell"])
        assertEquals(nodes[0].id, nodes[1].parentId)
        assertNull("no model produced it, so no model is credited", nodes[1].modelId)
        assertEquals(nodes[1].id, h.session.activeLeafId.value)
    }

    @Test fun `the runner gets the command with the sigil stripped, in the app files dir`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("   !  echo hi  "); settle()

        assertEquals(listOf("echo hi"), h.shell.calls.map { it.first })
        assertEquals(h.context.filesDir, h.shell.calls.single().second)
    }

    @Test fun `empty output is shown as an honest placeholder, not a blank reply`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.shell.output = "   \n"
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("!true"); settle()

        assertEquals("(no output)", h.nodes().ofRole(Role.ASSISTANT).single().content)
    }

    @Test fun `a runner failure is an error with the user turn kept and no fabricated answer`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.shell.failure = java.io.IOException("exec failed")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("!boom"); settle()

        assertEquals(listOf(Role.USER), h.nodes().map { it.role })
        assertEquals("exec failed", vm.uiState.value.error)
        assertEquals(GenPhase.IDLE, vm.uiState.value.genPhase)
    }

    @Test fun `text that is not a shell escape never reaches the runner`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("ok")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("hello !world"); settle()
        vm.send("ls -la"); settle()
        vm.send("what does ! mean in bash?"); settle()

        assertTrue(h.shell.calls.isEmpty())
        assertEquals(3, h.echoA.generateCalls)
    }

    @Test fun `a lone bang is not a command - nothing is inserted and nothing runs`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("!"); settle()
        vm.send("!   "); settle()

        assertTrue(h.shell.calls.isEmpty())
        assertTrue(h.nodes().isEmpty())
    }

    @Test fun `a shell escape involves no model, no foreground service and no ledger entry`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.shell.output = "x"
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("!echo x"); settle()

        assertEquals(0, h.echoA.generateCalls + h.echoB.generateCalls + h.cloud.generateCalls)
        assertTrue(h.echoA.loads.isEmpty())
        assertEquals(0, h.service.starts)
        assertTrue(h.ledgerDao.rows.value.isEmpty())
    }

    @Test fun `the escape wins over council and image modes - it never fans out to models`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)
        vm.send("!pwd"); settle()
        vm.setComposerMode(ComposerMode.IMAGE)
        vm.send("!pwd"); settle()

        assertEquals(2, h.shell.calls.size)
        assertEquals(0, h.echoA.generateCalls)
        assertEquals(listOf(true, true), h.nodes().ofRole(Role.ASSISTANT).map { it.metadata["shell"] == "true" })
    }

    @Test fun `the shell exchange is part of the path, so the next model turn sees it`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.shell.output = "42"
        h.echoA.script = toks("fine")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("!echo 42"); settle()
        vm.send("what was that?"); settle()

        assertEquals(listOf("!echo 42", "42", "what was that?"), h.echoA.prompts.single().map { it.content })
    }
}
