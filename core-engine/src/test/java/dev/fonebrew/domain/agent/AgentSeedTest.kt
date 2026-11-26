package dev.fonebrew.domain.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSeedTest {
    private val files = mapOf(
        "a.txt" to "alpha", "src/b.kt" to "bravo", "src/deep/c.kt" to "charlie", "src/deep/d/e/f/g.kt" to "too deep",
        "bin.dat" to "x\u0000y", ".git/config" to "secret",
    )

    @Test fun `picked files and folders are injected and the notes say so`() = runTest {
        val ws = AgentWorkspace(FakeRepo(files))
        val r = AgentSeed.build(ws, listOf(ContextPick("a.txt", false), ContextPick("src", true)))
        assertEquals(setOf("a.txt", "src/b.kt", "src/deep/c.kt"), r.files.keys)
        assertTrue(r.notes[0].startsWith("injected 3 picked file(s)"))
        // seeded files count as fetched (searchable, no refetch)
        assertEquals(r.files.keys, ws.fetchedFiles.keys)
    }

    @Test fun `refusals are listed with their reason, never silently dropped`() = runTest {
        val ws = AgentWorkspace(FakeRepo(files))
        val r = AgentSeed.build(ws, listOf(ContextPick("bin.dat", false), ContextPick("../x", false), ContextPick(".git/config", false), ContextPick("nope.txt", false)))
        assertTrue(r.files.isEmpty())
        val n = r.notes.last()
        assertTrue(n.contains("binary"))
        assertTrue(n.contains("'..'"))
        assertTrue(n.contains(".git"))
        assertTrue(n.contains("nope.txt (not found)"))
    }

    @Test fun `no picks means no note`() = runTest {
        assertTrue(AgentSeed.build(AgentWorkspace(FakeRepo(files)), emptyList()).notes.isEmpty())
    }

    @Test fun `the seed file cap is enforced and stated`() = runTest {
        val many = (1..50).associate { "d/f$it.txt" to "x$it" }
        val r = AgentSeed.build(AgentWorkspace(FakeRepo(many)), listOf(ContextPick("d", true)))
        assertEquals(AgentSeed.MAX_FILES, r.files.size)
        assertTrue(r.notes.any { it.contains("10 more file(s) left out") })
    }
}
