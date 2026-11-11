package dev.fonebrew.ui

import dev.fonebrew.domain.Role
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.ui.chatharness.CLOUD_SPEC
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.ECHO_A
import dev.fonebrew.ui.chatharness.ECHO_B
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.ofRole
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Switching the model that continues a conversation, and which model a session starts with. */
class ChatViewModelModelSwitchTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `the first on-device model is active at launch`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        assertEquals("echo-a", vm.uiState.value.activeModelId)
        assertFalse(vm.uiState.value.noModelActive)
    }

    @Test fun `cloud is never adopted as the default - a persisted cloud choice resolves back to on-device`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.session.setActiveModelId(CLOUD_SPEC.id)
        val vm = h.newViewModel(); observe(vm); settle()
        assertEquals(ECHO_A.id, vm.uiState.value.activeModelId)
    }

    @Test fun `with only cloud models configured nothing is active and a send does nothing`() = runTest(main.dispatcher) {
        val h = ChatHarness(specs = listOf(CLOUD_SPEC))
        val vm = h.newViewModel(); observe(vm); settle()

        assertTrue(vm.uiState.value.noModelActive)
        vm.send("anyone there?"); settle()

        assertTrue(h.nodes().isEmpty())
        assertEquals("the cloud engine was never touched", 0, h.cloud.generateCalls)
        assertTrue(h.cloud.loads.isEmpty())
    }

    @Test fun `switching model mid-conversation continues the same path under the new model`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("from A")
        vm.send("one"); settle()

        vm.switchModel(ECHO_B.id); settle()
        h.echoB.script = toks("from B")
        vm.send("two"); settle()

        val (u1, a1, u2, a2) = h.nodes()
        assertEquals("echo-a", a1.modelId)
        assertEquals("echo-b", a2.modelId)
        assertEquals(a1.id, u2.parentId)
        assertEquals(listOf("one", "from A", "two"), h.echoB.prompts.single().map { it.content })
        assertEquals("echo:b", h.tokenDao.counts.single { it.nodeId == a2.id }.tokenizerId)
        assertEquals(ECHO_B.id, h.session.activeModelId.value)
        assertEquals("A generated only turn one", 1, h.echoA.generateCalls)
        assertEquals("A's reply is untouched", "from A", a1.content)
        assertEquals(Role.USER, u1.role)
    }

    @Test fun `a switch raises the prefill reminder and the next turn clears it`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("x")
        vm.send("one"); settle()
        assertFalse(vm.uiState.value.prefillPending)

        vm.switchModel(ECHO_B.id); settle()
        assertTrue(vm.uiState.value.prefillPending)

        h.echoB.script = toks("y")
        vm.send("two"); settle()
        assertFalse(vm.uiState.value.prefillPending)
    }

    @Test fun `switching to an unknown model is ignored`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.switchModel("no-such-model"); settle()
        assertEquals(ECHO_A.id, vm.uiState.value.activeModelId)
        assertFalse(vm.uiState.value.prefillPending)
    }

    @Test fun `switching is ignored while a turn is generating`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("a", "b")
        h.echoA.pauseAfter = 1
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("go"); settle()

        vm.switchModel(ECHO_B.id); settle()

        assertEquals(ECHO_A.id, vm.uiState.value.activeModelId)
        h.echoA.release(); settle()
        assertEquals("echo-a", h.nodes().ofRole(Role.ASSISTANT).single().modelId)
    }

    @Test fun `the model list marks every runtime honestly`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val byId = vm.uiState.value.models.associateBy { it.id }
        assertEquals(setOf("echo-a", "echo-b", "cloud:acme"), byId.keys)
        assertTrue(byId.getValue("cloud:acme").watched)
        assertFalse(byId.getValue("echo-a").watched)
        assertEquals(Runtime.CLOUD, CLOUD_SPEC.runtime)
    }
}
