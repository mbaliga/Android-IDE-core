package dev.fonebrew.ui

import dev.fonebrew.domain.Role
import dev.fonebrew.ui.chatharness.CLOUD_SPEC
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.ECHO_A
import dev.fonebrew.ui.chatharness.LOCAL_SPEC
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.ScriptedEngine
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
import java.io.File

/**
 * LOCAL_GGUF turns through the real [ChatViewModel]: the foreground-service bracket and the KV-cache
 * session paths handed to the engine. The service and the llama.cpp session files themselves are
 * owner-verified only; here the view-model's *decisions* are pinned.
 */
class ChatViewModelLocalTurnTest {
    @get:Rule val main = MainDispatcherRule()

    private fun ChatHarness.local(): ScriptedEngine = extraEngines.getValue(LOCAL_SPEC.id).also {
        it.trace = service.log
        it.traceLabel = "local"
    }

    private fun localHarness() = ChatHarness(specs = listOf(LOCAL_SPEC, ECHO_A, CLOUD_SPEC))

    @Test fun `the service starts before the model loads and stops after the reply is persisted`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.script = toks("hi")
        val vm = h.newViewModel(); observe(vm); settle()
        assertEquals(LOCAL_SPEC.id, vm.uiState.value.activeModelId)

        vm.send("hello"); settle()

        assertEquals(listOf("service:start", "local:load", "local:generate", "service:stop"), h.service.log)
        assertEquals(listOf("/models/local.gguf" to 8192), local.loads)
        assertEquals("hi", h.nodes().ofRole(Role.ASSISTANT).single().content)
    }

    @Test fun `an engine failure mid-stream still stops the service and surfaces an error`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.script = toks("par"); local.failAfterScript = IllegalStateException("native crash")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("hello"); settle()

        assertEquals(1, h.service.starts)
        assertEquals(1, h.service.stops)
        assertEquals("native crash", vm.uiState.value.error)
        assertTrue(h.nodes().ofRole(Role.ASSISTANT).isEmpty())
    }

    @Test fun `a model load failure still stops the service`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.failLoad = java.io.IOException("cannot mmap model")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("hello"); settle()

        assertEquals(listOf("service:start", "local:load", "service:stop"), h.service.log)
        assertEquals("cannot mmap model", vm.uiState.value.error)
        assertEquals(0, local.generateCalls)
    }

    @Test fun `Stop stops the service too, and the next turn brackets it again`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.script = toks("a ", "b ", "c"); local.pauseAfter = 1
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("go"); settle()
        assertEquals("service stays up while generating", 1 to 0, h.service.starts to h.service.stops)
        vm.stopGeneration(); settle()
        assertEquals(1 to 1, h.service.starts to h.service.stops)

        local.pauseAfter = null
        vm.send("again"); settle()
        assertEquals(2 to 2, h.service.starts to h.service.stops)
    }

    @Test fun `regenerate on a local model brackets the service as well`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.failLoad = java.io.IOException("first try fails")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("hello"); settle()
        local.failLoad = null; local.script = toks("recovered")

        vm.regenerate(); settle()

        assertEquals(2 to 2, h.service.starts to h.service.stops)
        assertEquals("recovered", h.nodes().ofRole(Role.ASSISTANT).single().content)
    }

    @Test fun `Echo and cloud turns never start the foreground service`() = runTest(main.dispatcher) {
        val h = localHarness()
        h.echoA.script = toks("e"); h.cloud.script = toks("c")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.switchModel(ECHO_A.id); settle()
        vm.send("echo turn"); settle()
        vm.switchModel(CLOUD_SPEC.id); settle()
        vm.send("cloud turn"); settle()

        assertEquals(2, h.nodes().ofRole(Role.ASSISTANT).size)
        assertEquals(0, h.service.starts)
        assertEquals(0, h.service.stops)
    }

    @Test fun `clearing the view-model releases the service`() = runTest(main.dispatcher) {
        val h = localHarness()
        val vm = h.newViewModel(); observe(vm); settle()

        val clear = vm.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }
        clear.invoke(vm)

        assertEquals(1, h.service.stops)
    }

    @Test fun `the first local turn has no snapshot to resume and is handed a save path keyed to its own reply`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.script = toks("one")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("first"); settle()

        val reply = h.nodes().ofRole(Role.ASSISTANT).single()
        val (load, save) = local.sessionPaths.single()
        assertNull(load)
        assertEquals(h.kvCache.pathFor(reply.id), save)
        assertTrue(save!!.endsWith("${reply.id}.session"))
    }

    @Test fun `the load path is used only when the snapshot file for the parent node exists`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.script = toks("r")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.send("one"); settle()
        val firstReply = h.nodes().ofRole(Role.ASSISTANT).single()

        // No snapshot on disk yet: the second turn must prefill from scratch, not name a missing file.
        vm.send("two"); settle()
        assertNull(local.sessionPaths[1].first)

        // Now the engine "wrote" a snapshot for the second reply; a third turn resumes from exactly it.
        val secondReply = h.nodes().ofRole(Role.ASSISTANT)[1]
        File(h.kvCache.pathFor(secondReply.id)).writeText("snapshot")
        vm.send("three"); settle()

        assertEquals(h.kvCache.pathFor(secondReply.id), local.sessionPaths[2].first)
        assertTrue("an unrelated earlier snapshot is not what is loaded", local.sessionPaths[2].first != h.kvCache.pathFor(firstReply.id))
    }

    @Test fun `each local assistant node gets its own save path`() = runTest(main.dispatcher) {
        val h = localHarness(); val local = h.local()
        local.script = toks("r")
        val vm = h.newViewModel(); observe(vm); settle()

        vm.send("one"); settle()
        vm.send("two"); settle()

        val replies = h.nodes().ofRole(Role.ASSISTANT)
        assertEquals(replies.map { h.kvCache.pathFor(it.id) }, local.sessionPaths.map { it.second })
        assertEquals(2, local.sessionPaths.map { it.second }.toSet().size)
    }

    @Test fun `Echo and cloud engines are handed no session paths at all`() = runTest(main.dispatcher) {
        val h = localHarness()
        h.echoA.script = toks("e"); h.cloud.script = toks("c")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.switchModel(ECHO_A.id); settle()
        vm.send("one"); settle()
        vm.switchModel(CLOUD_SPEC.id); settle()
        vm.send("two"); settle()

        assertEquals(listOf<Pair<String?, String?>>(null to null), h.echoA.sessionPaths)
        assertEquals(listOf<Pair<String?, String?>>(null to null), h.cloud.sessionPaths)
    }
}
