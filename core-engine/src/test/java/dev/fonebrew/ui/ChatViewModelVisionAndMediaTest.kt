package dev.fonebrew.ui

import dev.fonebrew.data.Object3dNodeMeta
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.ECHO_A
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
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Pending-photo (vision) refusal and the image / 3D turn routing, at the decision level. The pixel
 * work (BitmapFactory downscale, the SD and cloud image engines, cloud 3D) cannot run on the JVM and
 * is on the owner-verified list in docs/testing/chatviewmodel-harness.md.
 */
class ChatViewModelVisionAndMediaTest {
    @get:Rule val main = MainDispatcherRule()

    private val photo = byteArrayOf(1, 2, 3)

    // ── vision refusal ───────────────────────────────────────────────────────

    @Test fun `a photo on a vision-blind model is refused with a stated reason and nothing is sent or inserted`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.addPendingAttachmentFromBytes(photo); settle()

        vm.send("what is this?"); settle()

        assertEquals("Echo A (dev) can't see images — switch model or remove the photo", vm.uiState.value.error)
        assertTrue(h.nodes().isEmpty())
        assertEquals(0, h.echoA.generateCalls)
        assertTrue("never even loaded the model", h.echoA.loads.isEmpty())
        assertEquals("the photo stays so the user can switch model", 1, vm.pendingAttachments.value.size)
    }

    @Test fun `after switching to a sighted model the same photo is attached to the user node and cleared`() = runTest(main.dispatcher) {
        val h = ChatHarness(specs = listOf(ECHO_A, VISION_SPEC))
        h.extraEngines.getValue(VISION_SPEC.id).script = toks("a cat")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.addPendingAttachmentFromBytes(photo); settle()
        vm.send("what is this?"); settle()
        assertTrue(h.nodes().isEmpty())

        vm.switchModel(VISION_SPEC.id); settle()
        vm.send("what is this?"); settle()

        val user = h.nodes().ofRole(Role.USER).single()
        val attached = user.metadata[Conversations.ATTACHMENTS_KEY]
        assertNotNull(attached)
        assertTrue("the stored photo path travels with the node", attached!!.contains("photo-1.jpg"))
        assertTrue(vm.pendingAttachments.value.isEmpty())
        assertEquals("a cat", h.nodes().ofRole(Role.ASSISTANT).single().content)
    }

    @Test fun `removing a pending photo drops it and deletes its stored file`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.addPendingAttachmentFromBytes(photo); settle()
        val pending = vm.pendingAttachments.value.single()
        assertTrue(File(pending.path).exists())

        vm.removePendingAttachment(pending.id); settle()

        assertTrue(vm.pendingAttachments.value.isEmpty())
        assertFalse(File(pending.path).exists())
    }

    @Test fun `a photo whose decode fails is not added, so nothing phantom can ride a send`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        // The real (un-seamed) downscaler: BitmapFactory is not available on the JVM, which the
        // production code treats exactly like an undecodable file (runCatching -> null).
        val vm = h.newViewModel(realPhotoDecode = true); observe(vm); settle()

        vm.addPendingAttachmentFromBytes(photo); settle()

        assertTrue(vm.pendingAttachments.value.isEmpty())
    }

    // ── image mode ───────────────────────────────────────────────────────────

    @Test fun `image mode with no image model and no keyed provider says so and inserts nothing`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.IMAGE)

        vm.send("a red fox"); settle()

        assertTrue(vm.uiState.value.error!!.startsWith("no image model"))
        assertTrue(h.nodes().isEmpty())
        assertEquals(0, h.service.starts)
    }

    @Test fun `image mode refuses a pending photo instead of silently dropping it`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.installSdModel()
        val vm = h.newViewModel(); observe(vm); settle()
        vm.setComposerMode(ComposerMode.IMAGE)
        vm.addPendingAttachmentFromBytes(photo); settle()

        vm.send("a red fox"); settle()

        assertEquals("image generation doesn't use a photo attachment — switch mode or remove the photo", vm.uiState.value.error)
        assertTrue(h.nodes().isEmpty())
        assertEquals(0, h.service.starts)
        assertEquals(1, vm.pendingAttachments.value.size)
    }

    // ── 3D object mode ───────────────────────────────────────────────────────

    private val triangleObj = "```object3d\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n```"

    @Test fun `an on-device 3D turn runs the active on-device engine and mints a 3D node under the prompt`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks(triangleObj)
        val vm = h.newViewModel(); observe(vm); settle()
        vm.enterObject3dMode(Object3dGenScope.ON_DEVICE)

        vm.send("a triangle"); settle()

        val nodes = h.nodes()
        assertEquals(listOf(Role.USER, Role.ASSISTANT), nodes.map { it.role })
        val node = nodes[1]
        assertEquals(nodes[0].id, node.parentId)
        assertEquals("object3d:on-device", node.modelId)
        assertEquals("ON_DEVICE_GENERATED", node.metadata[Object3dNodeMeta.KEY_SOURCE])
        assertEquals("a triangle", node.metadata[Object3dNodeMeta.KEY_PROMPT])
        assertTrue(File(h.context.filesDir, "objects3d/${node.metadata[Object3dNodeMeta.KEY_FILE]}").exists() ||
            h.context.filesDir.walkTopDown().any { it.name == node.metadata[Object3dNodeMeta.KEY_FILE] })
        assertFalse("the mode exits after a successful turn", vm.uiState.value.object3dMode)
        assertEquals(0, h.cloud.generateCalls)
        assertEquals(listOf("service:start", "service:stop"), h.service.log)
    }

    @Test fun `an on-device 3D failure is an error with the prompt kept and no fabricated model`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("sorry, I cannot draw")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.enterObject3dMode(Object3dGenScope.ON_DEVICE)

        vm.send("a triangle"); settle()

        assertEquals(listOf(Role.USER), h.nodes().map { it.role })
        assertTrue(vm.uiState.value.error!!.startsWith("on-device 3D generation failed"))
        assertTrue("stays in 3D mode so the user can retry", vm.uiState.value.object3dMode)
        assertEquals(GenPhase.IDLE, vm.uiState.value.genPhase)
        assertEquals(1 to 1, h.service.starts to h.service.stops)
        assertEquals("one retry with the reasons, then it gives up", 2, h.echoA.generateCalls)
    }

    @Test fun `a cloud 3D turn with no configured provider is an error and never falls back to the on-device model`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks(triangleObj)
        val vm = h.newViewModel(); observe(vm); settle()
        vm.enterObject3dMode(Object3dGenScope.CLOUD)

        vm.send("a triangle"); settle()

        assertEquals(listOf(Role.USER), h.nodes().map { it.role })
        assertTrue(vm.uiState.value.error!!.startsWith("no 3D cloud provider configured"))
        assertEquals("cloud choice never silently becomes on-device", 0, h.echoA.generateCalls)
        assertEquals(1 to 1, h.service.starts to h.service.stops)
    }

    @Test fun `leaving 3D mode through another composer mode stops routing sends to 3D`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.echoA.script = toks("plain")
        val vm = h.newViewModel(); observe(vm); settle()
        vm.enterObject3dMode(Object3dGenScope.ON_DEVICE)
        vm.setComposerMode(ComposerMode.SINGLE)

        vm.send("hello"); settle()

        assertNull(h.nodes().ofRole(Role.ASSISTANT).single().metadata[Object3dNodeMeta.KEY_FILE])
        assertEquals(0, h.service.starts)
    }
}

