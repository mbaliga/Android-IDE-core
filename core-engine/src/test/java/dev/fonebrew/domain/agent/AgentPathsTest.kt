package dev.fonebrew.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPathsTest {
    private fun ok(raw: String, root: Boolean = false) = (AgentPaths.confine(raw, root) as AgentPaths.Result.Ok).path
    private fun rejected(raw: String, root: Boolean = false) = AgentPaths.confine(raw, root) is AgentPaths.Result.Rejected

    @Test fun `plain relative paths normalise`() {
        assertEquals("src/a.kt", ok("src/a.kt"))
        assertEquals("src/a.kt", ok("./src//a.kt"))
        assertEquals("", ok("", root = true))
        assertEquals("", ok(".", root = true))
    }

    @Test fun `root is only allowed when asked for`() {
        assertTrue(rejected(""))
        assertTrue(rejected("."))
    }

    @Test fun `dot-dot in any position is rejected`() {
        for (p in listOf("..", "../x", "a/../b", "a/..", "a/../../etc/passwd", "...", "a/.../b")) assertTrue(p, rejected(p))
    }

    @Test fun `absolute and home and drive paths are rejected`() {
        for (p in listOf("/etc/passwd", "~/x", "C:/x", "c:\\x", "\\\\server\\share", "/")) assertTrue(p, rejected(p, root = true))
    }

    @Test fun `git internals are rejected in any case or position`() {
        for (p in listOf(".git", ".git/config", "a/.git/hooks/x", ".GIT/config", "a/.Git", "GIT~1/config")) assertTrue(p, rejected(p))
        assertEquals(".github/workflows/ci.yml", ok(".github/workflows/ci.yml"))
        assertEquals("a/.gitignore", ok("a/.gitignore"))
    }

    @Test fun `encoded traversal, control characters, backslashes and trailing dots are rejected`() {
        for (p in listOf("%2e%2e/x", "a%2Fb", "a%5cb", "a\u0000b", "a\nb", "a\\b", "a./b", "a /b")) assertTrue(p, rejected(p))
    }

    @Test fun `over-long paths are rejected`() {
        assertTrue(rejected("a/".repeat(300)))
    }
}
