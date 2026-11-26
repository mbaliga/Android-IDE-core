package dev.fonebrew.data.workspace

import dev.fonebrew.domain.workspace.fuzzy.FrecencyEntry
import dev.fonebrew.domain.workspace.roots.RootSettings
import dev.fonebrew.domain.workspace.roots.RootUiState
import dev.fonebrew.domain.workspace.roots.WorkspaceRoot
import dev.fonebrew.domain.workspace.roots.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceFileStoreTest {
    private fun tmp(prefix: String = "ws"): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    private val snap = WorkspaceSnapshot(
        roots = listOf(
            WorkspaceRoot("r1", "Alpha", "/data/a", settings = RootSettings(listOf("*.bak", "tmp/"), useDefaultIgnores = false, useGitignore = true)),
            WorkspaceRoot("r2", "Beta", "/data/b"),
        ),
        ui = mapOf("r1" to RootUiState(listOf("src", "src/main"), listOf("src/A.kt"), listOf(FrecencyEntry("src/A.kt", 2.5, 1234L)))),
    )

    @Test fun `store saves loads and starts fresh`() {
        val f = File(tmp(), "state/workspace.json"); val s = WorkspaceFileStore(f)
        assertTrue(s.load() is WorkspaceLoad.Fresh)
        s.save(snap)
        assertEquals(snap, (s.load() as WorkspaceLoad.Loaded).snapshot)
    }

    @Test fun `corrupt file is quarantined not deleted and an empty workspace returned`() {
        val dir = tmp(); val f = File(dir, "workspace.json"); f.writeText("{ truncated")
        val r = WorkspaceFileStore(f).load() as WorkspaceLoad.Recovered
        assertTrue(r.snapshot.roots.isEmpty()); assertNotNull(r.quarantined)
        assertEquals("{ truncated", r.quarantined!!.readText()); assertFalse(f.exists())
        f.writeText("again bad")
        val r2 = WorkspaceFileStore(f).load() as WorkspaceLoad.Recovered
        assertTrue(r2.quarantined!!.name.endsWith("corrupt-2"))
    }

    @Test fun `newer version file is quarantined not misread`() {
        val f = File(tmp(), "workspace.json"); f.writeText("""{"version":2,"roots":[{"id":"a","name":"A","path":"/p"}]}""")
        val r = WorkspaceFileStore(f).load() as WorkspaceLoad.Recovered
        assertTrue(r.reason.contains("version"))
    }
}
