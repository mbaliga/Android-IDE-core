package dev.fonebrew.ui

import dev.fonebrew.domain.Role
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.ui.chatharness.CLOUD_SPEC
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.ECHO_A
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.ofRole
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Binding rule 2: cloud is a visibly marked, explicit opt-in and never a silent fallback. */
class ChatViewModelWatchedObjectTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `a cloud turn is recorded as a cloud-tier turn against its provider and a local turn names no provider`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("local")
        vm.send("on device"); settle()

        vm.switchModel(CLOUD_SPEC.id); settle()
        h.cloud.script = toks("remote")
        vm.send("explicitly cloud"); settle()

        val (local, cloud) = h.ledgerDao.rows.value
        // (local.tier is deliberately not asserted here: see the @Ignore'd tier test in ChatViewModelStreamingTest.)
        assertEquals("on-device", local.provider)
        assertEquals(Tier.CLOUD.name, cloud.tier)
        assertEquals("acme", cloud.provider)
        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertEquals(listOf("echo-a", "cloud:acme"), replies.map { it.modelId })
    }

    @Test fun `the cloud model is flagged watched in the picker and the active label names it`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()

        vm.switchModel(CLOUD_SPEC.id); settle()

        val ui = vm.uiState.value
        assertTrue(ui.models.single { it.id == CLOUD_SPEC.id }.watched)
        assertFalse(ui.models.single { it.id == ECHO_A.id }.watched)
        assertEquals(CLOUD_SPEC.displayName, ui.activeModelLabel)
    }

    @Test fun `an on-device failure is an error, never a silent retry on the cloud engine`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.failLoad = java.io.IOException("out of memory")
        h.cloud.script = toks("a cloud answer that must never appear")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("private question"); settle()

        assertEquals("out of memory", vm.uiState.value.error)
        assertEquals(0, h.cloud.generateCalls)
        assertTrue(h.cloud.loads.isEmpty())
        assertEquals(ECHO_A.id, vm.uiState.value.activeModelId)
        assertEquals(listOf(Role.USER), h.nodes().map { it.role })
        assertTrue(h.ledgerDao.rows.value.none { it.tier == Tier.CLOUD.name })
    }

    @Test fun `an on-device model with no runnable engine does not fall through to cloud`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.engines.bySpecId.remove(ECHO_A.id)
        h.engines.unavailableReasons[ECHO_A.id] = "native engine unavailable on this device"
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("hello"); settle()

        assertTrue("nothing sent, nothing stored", h.nodes().isEmpty())
        assertEquals(0, h.cloud.generateCalls)
        assertFalse(vm.uiState.value.engineAvailable)
        assertEquals("native engine unavailable on this device", vm.uiState.value.unavailableReason)
        assertFalse(h.engines.requestedSpecIds.contains(CLOUD_SPEC.id) && h.cloud.loads.isNotEmpty())
    }

    @Test fun `retrying after an on-device failure still never touches the cloud engine`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.failAfterScript = IllegalStateException("decode error")
        h.echoA.script = toks("x")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("q"); settle()

        vm.regenerate(); settle()

        assertEquals(2, h.echoA.generateCalls)
        assertEquals(0, h.cloud.generateCalls)
        assertNotNull(vm.uiState.value.error)
    }

    @Test fun `a cloud failure stays a cloud failure - no quiet fallback to the on-device engine`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.switchModel(CLOUD_SPEC.id); settle()
        h.cloud.failLoad = java.io.IOException("401 unauthorized")

        vm.send("to the cloud"); settle()

        assertEquals("401 unauthorized", vm.uiState.value.error)
        assertEquals(0, h.echoA.generateCalls)
        assertEquals(CLOUD_SPEC.id, vm.uiState.value.activeModelId)
    }
}
