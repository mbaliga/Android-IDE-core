package dev.fonebrew.ui

import dev.fonebrew.domain.Role
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The interaction model (Single / Council) is immutable once a chat starts; changing it branches with a summary. */
class ChatViewModelInteractionModeTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `before a chat starts the mode switches directly`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()

        vm.requestComposerMode(ComposerMode.PERSONAS); settle()

        assertNull(vm.pendingInteractionChange.value)
        assertEquals(ComposerMode.PERSONAS, vm.uiState.value.composerMode)
        assertTrue(h.nodes().isEmpty())
    }

    @Test fun `once a chat has started a change is held pending and the model is not changed in place`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("hello")
        vm.send("start"); settle()
        val before = h.nodes()

        vm.requestComposerMode(ComposerMode.PERSONAS); settle()

        assertEquals(ComposerMode.PERSONAS, vm.pendingInteractionChange.value)
        assertEquals("still single until confirmed", ComposerMode.SINGLE, vm.uiState.value.composerMode)
        assertEquals(before, h.nodes())
    }

    @Test fun `cancelling the pending change leaves everything as it was`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("hello")
        vm.send("start"); settle()
        vm.requestComposerMode(ComposerMode.MODELS)

        vm.cancelInteractionChange(); settle()

        assertNull(vm.pendingInteractionChange.value)
        assertEquals(ComposerMode.SINGLE, vm.uiState.value.composerMode)
        assertEquals(2, h.nodes().size)
    }

    @Test fun `confirming appends a summary branch node and only then switches the interaction model`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("hello")
        vm.send("start"); settle()
        val before = h.nodes()
        vm.requestComposerMode(ComposerMode.PERSONAS)
        h.echoA.script = toks("A short brief.") // what the summary call will stream

        vm.confirmInteractionChange(); settle()

        val after = h.nodes()
        assertEquals("history is untouched; the switch is one appended node", before, after.take(before.size))
        assertEquals(before.size + 1, after.size)
        val bridge = after.last()
        assertEquals(Role.SYSTEM, bridge.role)
        assertEquals(before.last().id, bridge.parentId)
        assertEquals("PERSONAS", bridge.metadata["interactionSwitch"])
        assertEquals("true", bridge.metadata["summary"])
        assertEquals("interaction_switch", bridge.metadata["bridge"])
        assertTrue(bridge.content.startsWith("Interaction model → Council · personas."))
        assertTrue(bridge.content.endsWith("A short brief."))
        assertEquals(bridge.id, h.session.activeLeafId.value)
        assertEquals(ComposerMode.PERSONAS, vm.uiState.value.composerMode)
        assertNull(vm.pendingInteractionChange.value)
    }

    @Test fun `when the summary model fails the bridge falls back to an honest excerpt, not an invented summary`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("hello back")
        vm.send("hello there"); settle()
        vm.requestComposerMode(ComposerMode.MODELS)
        h.echoA.failLoad = java.io.IOException("no memory")

        vm.confirmInteractionChange(); settle()

        val bridge = h.nodes().last()
        assertTrue(bridge.content.contains("User: hello there"))
        assertTrue(bridge.content.contains("Assistant: hello back"))
        assertEquals("MODELS", bridge.metadata["interactionSwitch"])
    }

    @Test fun `re-requesting the current mode or entering image mode never asks to branch`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("hello")
        vm.send("start"); settle()

        vm.requestComposerMode(ComposerMode.SINGLE)
        assertNull(vm.pendingInteractionChange.value)
        vm.requestComposerMode(ComposerMode.IMAGE)
        assertNull(vm.pendingInteractionChange.value)
        settle()
        assertTrue(vm.uiState.value.imageMode)
    }

    @Test fun `confirming with nothing pending does nothing`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("hello")
        vm.send("start"); settle()

        vm.confirmInteractionChange(); settle()

        assertEquals(2, h.nodes().size)
        assertFalse(vm.uiState.value.councilEnabled)
    }
}
