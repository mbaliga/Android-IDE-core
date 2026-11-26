package dev.fonebrew.ui

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.ui.chatharness.ChatHarness
import dev.fonebrew.ui.chatharness.MainDispatcherRule
import dev.fonebrew.ui.chatharness.observe
import dev.fonebrew.ui.chatharness.ofRole
import dev.fonebrew.ui.chatharness.seedNode
import dev.fonebrew.ui.chatharness.settle
import dev.fonebrew.ui.chatharness.toks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The append-only message tree through the real [ChatViewModel]: send, restore, branch, rewind and
 * relaunch only ever add nodes or move the active leaf. [ChatHarness.nodes] is the insertion-ordered
 * log, so "nothing mutated or deleted" is a prefix check against a snapshot taken before the action.
 */
class ChatViewModelTreeTest {
    @get:Rule val main = MainDispatcherRule()

    /** Two finished turns: u1 -> a1 -> u2 -> a2. */
    private suspend fun kotlinx.coroutines.test.TestScope.twoTurns(h: ChatHarness, vm: ChatViewModel): List<MessageNode> {
        h.echoA.script = toks("first reply")
        vm.send("question one"); settle()
        h.echoA.script = toks("second reply")
        vm.send("question two"); settle()
        return h.nodes()
    }

    @Test fun `the second turn continues from the first reply and the engine is handed the whole path in order`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()

        val (u1, a1, u2, a2) = twoTurns(h, vm)

        assertEquals(a1.id, u2.parentId)
        assertEquals(u2.id, a2.parentId)
        assertEquals(listOf(u1.id, a1.id, u2.id), h.echoA.prompts.last().map { it.id })
        assertEquals(listOf("question one", "first reply", "question two"), h.echoA.prompts.last().map { it.content })
    }

    @Test fun `restoring an earlier node makes it the active leaf and deletes nothing`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)

        vm.branchFrom(before[1].id) // restore to the first reply
        settle()

        assertEquals(before[1].id, h.session.activeLeafId.value)
        assertEquals(listOf("question one", "first reply"), vm.uiState.value.steps.map { it.node.content })
        assertEquals("the later turns are still in the tree", before, h.nodes())
    }

    @Test fun `sending from a restored node branches - the later history survives beside the new branch`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)
        val (_, a1, u2, a2) = before

        vm.branchFrom(a1.id); settle()
        h.echoA.script = toks("a different follow-up reply")
        vm.send("question two, reworded"); settle()

        val after = h.nodes()
        assertEquals("every prior node is untouched and in the same order", before, after.take(before.size))
        assertEquals(before.size + 2, after.size)
        val u3 = after[before.size]
        assertEquals(Role.USER, u3.role)
        assertEquals(a1.id, u3.parentId)
        assertEquals(setOf(u2.id, u3.id), after.filter { it.parentId == a1.id }.map { it.id }.toSet())
        assertEquals(u2.id, after.first { it.id == a2.id }.parentId)
        val fork = vm.uiState.value.steps.first { it.node.id == a1.id }
        assertEquals(2, fork.alternativeCount)
        assertTrue(fork.isBranchPoint)
        assertEquals("the active path follows the NEW branch", 2, fork.activeAlternative)
    }

    @Test fun `the engine for a branched turn sees only the ancestors of the restore point`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)

        vm.branchFrom(before[1].id); settle()
        vm.send("branch question"); settle()

        assertEquals(
            listOf("question one", "first reply", "branch question"),
            h.echoA.prompts.last().map { it.content },
        )
    }

    @Test fun `regenerating from a restored user turn adds a sibling reply, not a replacement`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)

        vm.branchFrom(before[0].id); settle() // back to the first user turn
        assertTrue(vm.uiState.value.canRegenerate)
        h.echoA.script = toks("an alternative first reply")
        vm.regenerate(); settle()

        val after = h.nodes()
        assertEquals(before, after.take(before.size))
        assertEquals(listOf(before[1].id, after.last().id), after.filter { it.parentId == before[0].id }.map { it.id })
        assertEquals("an alternative first reply", after.last().content)
    }

    @Test fun `no navigation action ever mutates or removes a persisted node`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)
        val order = h.nodeDao.insertOrder.toList()

        vm.branchFrom(before[0].id); settle()
        vm.rewindFrom(before[1].id); settle()
        vm.newChat(); settle()
        vm.openConversation(before[0].id); settle()
        vm.switchAlternative(before[0].id, 1); settle()

        assertEquals(before, h.nodes())
        assertEquals(order, h.nodeDao.insertOrder)
    }

    @Test fun `rewinding annotates the abandoned tip as a ghost and keeps it reachable`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)

        vm.rewindFrom(before[0].id); settle()

        assertEquals(before[0].id, h.session.activeLeafId.value)
        assertEquals(setOf(before[3].id), h.ghostedTips)
        assertEquals(before, h.nodes())
    }

    @Test fun `switching between alternatives walks the branches by their tips, wrapping at the ends`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        // u1 has two replies; each reply continues. Explicit createdAt keeps branch order off the wall clock.
        h.seed(
            seedNode("u1", null, Role.USER, "q", at = 1),
            seedNode("a1", "u1", Role.ASSISTANT, "reply one", at = 2),
            seedNode("a1b", "u1", Role.ASSISTANT, "reply two", at = 3),
            seedNode("u2", "a1b", Role.USER, "follow up", at = 4),
        )
        val vm = h.newViewModel(); observe(vm); settle()
        vm.openConversation("u1"); settle()
        assertEquals("u2", h.session.activeLeafId.value) // newest branch's tip

        vm.switchAlternative("u1", 1); settle()
        assertEquals("wraps forward to the first alternative", "a1", h.session.activeLeafId.value)

        vm.switchAlternative("u1", -1); settle()
        assertEquals("and back, landing on that branch's tip", "u2", h.session.activeLeafId.value)
        assertEquals(4, h.nodes().size)
    }

    @Test fun `a new chat clears the leaf and the next send mints a fresh root beside the old tree`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)

        vm.newChat(); settle()
        assertNull(h.session.activeLeafId.value)
        assertTrue(vm.uiState.value.steps.isEmpty())
        h.echoA.script = toks("hello again")
        vm.send("fresh start"); settle()

        val after = h.nodes()
        assertEquals(before, after.take(before.size))
        val roots = after.filter { it.parentId == null }
        assertEquals(2, roots.size)
        assertEquals("fresh start", roots.last().content)
        assertFalse("the new conversation does not inherit the old path", h.echoA.prompts.last().any { it.content == "question one" })
    }

    @Test fun `a relaunch reopens on the same path`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        val vm = h.newViewModel(); observe(vm); settle()
        val before = twoTurns(h, vm)

        val relaunched = h.newViewModel(); observe(relaunched); settle()

        assertEquals(before.map { it.id }, relaunched.uiState.value.steps.map { it.node.id })
    }

    @Test fun `a persisted leaf that no longer exists falls back to an empty chat rather than a broken path`() = runTest(main.dispatcher) {
        val h = ChatHarness()
        h.session.setActiveLeafId("vanished-node")
        val vm = h.newViewModel(); observe(vm); settle()

        assertNull(h.session.activeLeafId.value)
        assertTrue(vm.uiState.value.steps.isEmpty())
    }
}
