package dev.fonebrew.ui

import dev.fonebrew.domain.Role
import dev.fonebrew.domain.thread.ThreadMarkerKind
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.ofRole
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The compaction run through the real [ChatViewModel]: a real model call per message (scripted
 * engine), the real contract/verifier, a persisted receipt + COMPACTION_RUN marker, and the
 * boundary that routes the next prompt. The tree itself is append-only: compaction never edits or
 * adds a message node.
 */
class ChatViewModelCompactionTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `a successful run persists one receipt and one boundary marker anchored at the leaf, and adds no message`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("reply one"); vm.send("question one"); settle()
        h.echoA.script = toks("reply two"); vm.send("question two"); settle()
        val before = h.nodes()
        val leaf = h.session.activeLeafId.value!!
        h.echoA.script = toks("squeezed")

        vm.openCompactionPreview(); settle()
        assertNotNull(vm.compactionPreview.value)
        vm.runCompaction(); settle()

        assertEquals("compaction never edits or adds a message", before, h.nodes())
        assertEquals(1, h.receipts.all().first().size)
        val marker = h.markerStore.forRoot(before.first().id).single()
        assertEquals(ThreadMarkerKind.COMPACTION_RUN, marker.kind)
        assertEquals(leaf, marker.anchorMsgId)
        assertNull("the preview closes on success", vm.compactionPreview.value)
        assertFalse(vm.compactionRunning.value)
        assertNull(vm.compactionFailure.value)
        assertNull(vm.uiState.value.error)
    }

    @Test fun `the model is really called for the compacted messages and never loaded through a different engine`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("reply one"); vm.send("question one"); settle()
        val callsBefore = h.echoA.generateCalls
        h.echoA.script = toks("squeezed")

        vm.openCompactionPreview(); settle()
        vm.runCompaction(); settle()

        assertTrue("at least one per-message model call happened", h.echoA.generateCalls > callsBefore)
        assertEquals(0, h.echoB.generateCalls + h.cloud.generateCalls)
        val compactionPrompts = h.echoA.prompts.drop(callsBefore)
        assertTrue(compactionPrompts.all { it.first().role == Role.SYSTEM })
    }

    @Test fun `after a run the next turn is prompted through the compacted boundary, not the verbatim history`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("reply one"); vm.send("question one"); settle()
        h.echoA.script = toks("reply two"); vm.send("question two"); settle()
        h.echoA.script = toks("squeezed")
        vm.openCompactionPreview(); settle()
        vm.runCompaction(); settle()
        val priorCalls = h.echoA.generateCalls

        h.echoA.script = toks("reply three")
        vm.send("question three"); settle()

        val prompt = h.echoA.prompts[priorCalls].map { it.content }
        assertEquals("the newest turn is always present, verbatim", "question three", prompt.last())
        assertTrue("older messages were routed through their receipt entries", prompt != listOf("question one", "reply one", "question two", "reply two", "question three"))
        // The persisted tree still holds every original, verbatim.
        assertEquals(
            listOf("question one", "reply one", "question two", "reply two", "question three", "reply three"),
            h.nodes().map { it.content },
        )
    }

    @Test fun `a model that cannot load fails the run loudly - no receipt, no marker, running flag cleared`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("reply one"); vm.send("question one"); settle()
        h.echoA.failLoad = java.io.IOException("out of memory")

        vm.openCompactionPreview(); settle()
        vm.runCompaction(); settle()

        assertEquals("out of memory", vm.uiState.value.error)
        assertTrue(h.receipts.all().first().isEmpty())
        assertTrue(h.markerStore.forRoot(h.nodes().first().id).isEmpty())
        assertFalse(vm.compactionRunning.value)
    }

    @Test fun `running with no preview anchor does nothing at all`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("reply one"); vm.send("question one"); settle()
        val calls = h.echoA.generateCalls

        vm.runCompaction(); settle()

        assertEquals(calls, h.echoA.generateCalls)
        assertTrue(h.receipts.all().first().isEmpty())
        assertFalse(vm.compactionRunning.value)
    }

    @Test fun `closing the preview forgets the anchor, so a later bare run is a no-op`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        h.echoA.script = toks("reply one"); vm.send("question one"); settle()
        vm.openCompactionPreview(); settle()
        vm.closeCompactionPreview()
        val calls = h.echoA.generateCalls

        vm.runCompaction(); settle()

        assertNull(vm.compactionPreview.value)
        assertEquals(calls, h.echoA.generateCalls)
        assertTrue(h.receipts.all().first().isEmpty())
    }

    @Test fun `with no active model a run does nothing`() = runTest(main.dispatcher) {
        val h = ChatHarness(specs = listOf(dev.fonebrew.ui.chatharness.CLOUD_SPEC))
        val vm = h.newViewModel(); observe(vm); settle()
        h.seed(dev.fonebrew.ui.chatharness.seedNode("u", null, Role.USER, "q", 1L))

        vm.openCompactionPreview("u"); settle()
        vm.runCompaction("u"); settle()

        assertEquals(0, h.cloud.generateCalls)
        assertTrue(h.receipts.all().first().isEmpty())
        assertTrue(h.nodes().ofRole(Role.ASSISTANT).isEmpty())
    }
}
