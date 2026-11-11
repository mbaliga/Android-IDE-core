package dev.fonebrew.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTargetTest {
    private fun h(id: String, repo: String = "r$id") = GitHost(
        id = id, displayName = "host-$id", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "me", repo = repo, branch = "main", authorName = "", authorEmail = "",
    )

    @Test fun `no hosts is NoHost`() {
        assertEquals(AgentTarget.Resolution.NoHost, AgentTarget.resolve(emptyList(), null))
    }

    @Test fun `a single host is unambiguous even with no pick or a stale pick`() {
        assertEquals(AgentTarget.Resolution.Resolved(h("a")), AgentTarget.resolve(listOf(h("a")), null))
        assertEquals(AgentTarget.Resolution.Resolved(h("a")), AgentTarget.resolve(listOf(h("a")), "gone"))
    }

    @Test fun `several hosts with no pick need a choice and never default to the first`() {
        val r = AgentTarget.resolve(listOf(h("a"), h("b")), null)
        assertTrue(r is AgentTarget.Resolution.NeedsChoice)
    }

    @Test fun `a stale pick among several hosts needs a fresh choice`() {
        assertTrue(AgentTarget.resolve(listOf(h("a"), h("b")), "removed") is AgentTarget.Resolution.NeedsChoice)
    }

    @Test fun `a valid pick among several hosts resolves to exactly that host`() {
        assertEquals(AgentTarget.Resolution.Resolved(h("b")), AgentTarget.resolve(listOf(h("a"), h("b")), "b"))
    }

    @Test fun `label names host repo and branch`() {
        assertEquals("host-a · me/ra @ main", AgentTarget.label(h("a")))
    }
}
