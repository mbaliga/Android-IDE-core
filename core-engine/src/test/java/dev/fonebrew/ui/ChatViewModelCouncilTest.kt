package dev.fonebrew.ui

import dev.fonebrew.data.Participant
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.ui.chatharness.CLOUD_SPEC
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.ECHO_A
import dev.fonebrew.ui.chatharness.ECHO_B
import dev.fonebrew.ui.chatharness.LOCAL_SPEC
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.VISION_SPEC
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.ofRole
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/** The real [ChatViewModel] council fan-out: roster order, per-member model + memory, addressing, failure, Stop. */
class ChatViewModelCouncilTest {
    @get:Rule val main = MainDispatcherRule()

    private val ada = Participant("p1", "Ada", "Answer tersely.", modelId = ECHO_A.id, memory = "Ada likes tea")
    private val bo = Participant("p2", "Bo", "Answer at length.", modelId = ECHO_B.id)

    private fun ChatHarness.personas(vararg members: Participant) = councilStore.setAll(members.toList())

    @Test fun `one user node then one assistant node per participant, in roster order, each on its own model`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("from ", "A")
        h.echoB.script = toks("from ", "B")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("what now?"); settle()

        val nodes = h.nodes()
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.ASSISTANT), nodes.map { it.role })
        val (user, first, second) = nodes
        assertEquals(listOf("Ada", "Bo"), listOf(first, second).map { it.metadata["agent"] })
        assertEquals(listOf("echo-a", "echo-b"), listOf(first, second).map { it.modelId })
        assertEquals("from A", first.content)
        assertEquals("from B", second.content)
        assertTrue("both replies are siblings under the user turn", listOf(first, second).all { it.parentId == user.id })
        assertTrue(listOf(first, second).all { it.metadata["council"] == user.id })
        assertEquals(1, h.echoA.generateCalls)
        assertEquals(1, h.echoB.generateCalls)
    }

    @Test fun `the roster order decides the reply order, not the model order`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(bo, ada) // reversed
        h.echoA.script = toks("A"); h.echoB.script = toks("B")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("go"); settle()

        assertEquals(listOf("Bo", "Ada"), h.nodes().ofRole(Role.ASSISTANT).map { it.metadata["agent"] })
        assertEquals(listOf("B", "A"), h.nodes().ofRole(Role.ASSISTANT).map { it.content })
    }

    @Test fun `each member runs with its own instructions and memory as a leading system message, then the shared path`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("a"); h.echoB.script = toks("b")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("shared question"); settle()

        val adaPrompt = h.echoA.prompts.single()
        assertEquals(Role.SYSTEM, adaPrompt.first().role)
        assertTrue(adaPrompt.first().content.contains("Answer tersely."))
        assertTrue("memory is folded in", adaPrompt.first().content.contains("Ada likes tea"))
        assertEquals("shared question", adaPrompt.last().content)
        val boPrompt = h.echoB.prompts.single()
        assertTrue(boPrompt.first().content.contains("Answer at length."))
        assertFalse("Bo has no memory and must not see Ada's", boPrompt.first().content.contains("tea"))
        assertEquals("shared question", boPrompt.last().content)
    }

    @Test fun `a member with no model of its own runs on the chat's active model`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(Participant("p1", "Solo", "Be solo."))
        h.echoA.script = toks("active")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("hi"); settle()

        assertEquals("echo-a", h.nodes().ofRole(Role.ASSISTANT).single().modelId)
        assertEquals(0, h.echoB.generateCalls)
    }

    @Test fun `a cloud member is recorded against the cloud spec and that spec is watched`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, Participant("p3", "Cloudy", "Be remote.", modelId = CLOUD_SPEC.id))
        h.echoA.script = toks("local"); h.cloud.script = toks("remote")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("both"); settle()

        val cloudReply = h.nodes().ofRole(Role.ASSISTANT).single { it.metadata["agent"] == "Cloudy" }
        assertEquals(CLOUD_SPEC.id, cloudReply.modelId)
        val options = vm.uiState.value.models.associateBy { it.id }
        assertTrue("the cloud model is a watched object", options.getValue(CLOUD_SPEC.id).watched)
        assertFalse("the on-device member is not", options.getValue(ECHO_A.id).watched)
    }

    @Test fun `a leading @mention narrows the turn to that one member and is recorded on the user node`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoB.script = toks("only Bo")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("@bo, thoughts?"); settle()

        val nodes = h.nodes()
        assertEquals(listOf(Role.USER, Role.ASSISTANT), nodes.map { it.role })
        assertEquals("Bo", nodes[0].metadata["addressedTo"])
        assertEquals("Bo", nodes[1].metadata["agent"])
        assertEquals(0, h.echoA.generateCalls)
    }

    @Test fun `a mention of nobody on the roster falls back to the full fan-out`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("a"); h.echoB.script = toks("b")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("@Zed hello"); settle()

        assertEquals(2, h.nodes().ofRole(Role.ASSISTANT).size)
        assertNull(h.nodes().first().metadata["addressedTo"])
    }

    @Test fun `an empty roster falls back to the default personas, never to nothing`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)
        h.echoA.script = toks("x")

        vm.send("hello"); settle()

        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertTrue(replies.size >= 2)
        assertTrue(replies.all { it.metadata.containsKey("agent") && it.modelId == "echo-a" })
    }

    @Test fun `after a council turn the leaf stays on the user node so the panel is held side by side`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("a"); h.echoB.script = toks("b")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("q"); settle()

        assertEquals(h.nodes().first().id, h.session.activeLeafId.value)
        assertEquals(listOf("Ada", "Bo"), vm.uiState.value.councilCards.map { it.agent })
        assertTrue(vm.uiState.value.councilCards.all { it.done })
        assertEquals(GenPhase.IDLE, vm.uiState.value.genPhase)
    }

    @Test fun `a member that fails after earlier members answered keeps their replies and writes none for itself`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("fine")
        h.echoB.script = toks("partial"); h.echoB.failAfterScript = IllegalStateException("model B exploded")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("q"); settle()

        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertEquals(listOf("Ada"), replies.map { it.metadata["agent"] })
        assertEquals("model B exploded", vm.uiState.value.error)
        assertEquals(GenPhase.IDLE, vm.uiState.value.genPhase)
        assertNull("no streaming panel is left behind", vm.uiState.value.councilCards.firstOrNull { !it.done })
    }

    // FINDING (P2b): sendCouncil wraps the whole voice loop in ONE try/catch, so the first member
    // that throws ends the fan-out and every later member is silently never asked. The lane brief
    // says a failing member must not poison the others. Production code is unchanged here (seam-only
    // lane); reported to the owner as a defect to fix with a per-voice try/catch.
    @Ignore("DEFECT sendCouncil: one failing voice aborts all later voices (single try around the loop); needs per-voice catch")
    @Test fun `a failing member does not stop the members after it`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("x"); h.echoA.failAfterScript = IllegalStateException("A exploded")
        h.echoB.script = toks("Bo still answers")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("q"); settle()

        assertEquals(listOf("Bo"), h.nodes().ofRole(Role.ASSISTANT).map { it.metadata["agent"] })
    }

    @Test fun `the foreground service is started once for the fan-out and stopped after, also when a member fails`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("a"); h.echoB.script = toks("b")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)
        vm.send("q"); settle()
        assertEquals(listOf("service:start", "service:stop"), h.service.log)

        h.echoB.failAfterScript = IllegalStateException("boom")
        vm.send("again"); settle()
        assertEquals(2, h.service.starts)
        assertEquals("stop is never skipped on a failure", 2, h.service.stops)
    }

    @Test fun `Stop mid-member keeps its partial text marked stopped and never starts the remaining members`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        h.echoA.script = toks("one ", "two ", "three"); h.echoA.pauseAfter = 2
        h.echoB.script = toks("never")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("q"); settle()
        vm.stopGeneration(); settle()

        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertEquals(listOf("Ada"), replies.map { it.metadata["agent"] })
        assertEquals("true", replies.single().metadata["stopped"])
        assertEquals("one two", replies.single().content)
        assertEquals(0, h.echoB.generateCalls)
        assertEquals(GenPhase.IDLE, vm.uiState.value.genPhase)
        assertEquals(1, h.service.stops)
    }

    @Test fun `a second send while the council is working is ignored`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada)
        h.echoA.script = toks("a", "b"); h.echoA.pauseAfter = 1
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)

        vm.send("first"); settle()
        vm.send("second"); settle()
        h.echoA.release(); settle()

        assertEquals(listOf("first"), h.nodes().ofRole(Role.USER).map { it.content })
    }

    @Test fun `a model-diversity council skips Echo and asks each runnable model once, with no persona prompt`() = runTest(main.dispatcher) {
        val h = ChatHarness(specs = listOf(ECHO_A, LOCAL_SPEC, CLOUD_SPEC))
        h.extraEngines.getValue(LOCAL_SPEC.id).script = toks("local says")
        h.cloud.script = toks("cloud says")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.MODELS)

        vm.send("compare"); settle()

        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertEquals(listOf(LOCAL_SPEC.id, CLOUD_SPEC.id), replies.map { it.modelId })
        assertEquals(listOf("local says", "cloud says"), replies.map { it.content })
        assertEquals(0, h.echoA.generateCalls)
        assertTrue("no persona prompt in model-diversity mode", h.cloud.prompts.single().none { it.role == Role.SYSTEM })
    }

    @Test fun `a model-diversity council with only Echo available says so and inserts nothing`() = runTest(main.dispatcher) {
        val h = ChatHarness(specs = listOf(ECHO_A, ECHO_B))
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.MODELS)

        vm.send("compare"); settle()

        assertTrue(vm.uiState.value.error!!.contains("model-diversity"))
        assertTrue(h.nodes().isEmpty())
        assertEquals(0, h.service.starts)
    }

    @Test fun `a pending photo with no sighted voice is refused with a reason and nothing is sent`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.personas(ada, bo)
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)
        vm.addPendingAttachmentFromBytes(byteArrayOf(1, 2, 3)); settle()
        assertEquals(1, vm.pendingAttachments.value.size)

        vm.send("what is this?"); settle()

        assertEquals("no voice in this council can see images — switch model or remove the photo", vm.uiState.value.error)
        assertTrue(h.nodes().isEmpty())
        assertEquals(0, h.service.starts)
        assertEquals(0, h.echoA.generateCalls + h.echoB.generateCalls)
        assertEquals("the photo is kept for the user to remove or retry", 1, vm.pendingAttachments.value.size)
    }

    @Test fun `with one sighted voice the photo is attached to the user node and the pending strip clears`() = runTest(main.dispatcher) {
        val h = ChatHarness(specs = listOf(ECHO_A, VISION_SPEC))
        h.personas(Participant("p1", "Eye", "Look.", modelId = VISION_SPEC.id))
        h.extraEngines.getValue(VISION_SPEC.id).script = toks("seen")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.PERSONAS)
        vm.addPendingAttachmentFromBytes(byteArrayOf(9)); settle()

        vm.send("look"); settle()

        val user = h.nodes().ofRole(Role.USER).single()
        assertNotNull(user.metadata[Conversations.ATTACHMENTS_KEY])
        assertTrue(vm.pendingAttachments.value.isEmpty())
        assertEquals("seen", h.nodes().ofRole(Role.ASSISTANT).single().content)
    }
}
