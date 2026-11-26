package dev.fonebrew.data.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcInspectorTest {
    private fun fakeProc(vararg procs: Triple<Int, Int, Int>): File {
        val root = Files.createTempDirectory("proc").toFile()
        for ((pid, ppid, pgrp) in procs) {
            File(root, "$pid").mkdirs()
            File(root, "$pid/stat").writeText("$pid (we ird) name) S $ppid $pgrp 1 0 -1 0")
            File(root, "$pid/comm").writeText("proc$pid\n")
        }
        return root
    }

    @Test fun `finds direct children with their process group`() {
        val p = ProcInspector(fakeProc(Triple(100, 1, 100), Triple(101, 100, 101), Triple(102, 101, 101), Triple(200, 1, 200)))
        assertEquals(listOf(101 to 101), p.children(100))
        assertEquals(emptyList<Pair<Int, Int>>(), p.children(200))
    }

    @Test fun `unreadable shell stat means unknown, not no children`() {
        assertNull(ProcInspector(fakeProc(Triple(5, 1, 5))).children(100))
    }

    @Test fun `comm is read and trimmed, missing is null`() {
        val p = ProcInspector(fakeProc(Triple(7, 1, 7)))
        assertEquals("proc7", p.comm(7)); assertNull(p.comm(8))
    }
}
