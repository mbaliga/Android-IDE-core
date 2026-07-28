package dev.aarso.domain.sync

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.loop.Loop
import dev.aarso.domain.loop.LoopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopSyncTest {

    private val host = GitHost(
        id = "gh1", displayName = "My GitHub", kind = GitHostKind.GITHUB,
        baseUrl = "", owner = "acme", repo = "x", branch = "main",
        authorName = "Dev", authorEmail = "dev@x.y",
    )

    private fun loop(id: String, bpmn: String? = "<bpmn/>") =
        Loop(id = id, name = "Loop $id", bpmnXml = bpmn, state = LoopState.UNUSED)

    @Test fun `path conventions round-trip`() {
        assertEquals("loops/abc.bpmn", LoopSync.pathFor("abc"))
        assertEquals("abc", LoopSync.idForPath("loops/abc.bpmn"))
        assertNull(LoopSync.idForPath("loops/readme.md"))
        assertNull(LoopSync.idForPath("other/abc.bpmn"))
    }

    @Test fun `push builds a put to the conventional path carrying the BPMN`() {
        val req = LoopSync.push(host, loop("l1"), sha = null, token = "t")
        assertEquals("PUT", req.method)
        assertTrue(req.url.contains("loops/l1.bpmn"))
        assertTrue(req.body!!.isNotEmpty()) // base64 content payload
    }

    @Test fun `push without BPMN is an error`() {
        var threw = false
        try { LoopSync.push(host, loop("l1", bpmn = null), null, "t") } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test fun `fetch and list target the right paths`() {
        assertTrue(LoopSync.fetch(host, "l1", "t").url.contains("loops/l1.bpmn"))
        assertTrue(LoopSync.list(host, "t").url.contains("loops"))
    }

    @Test fun `parseLoopIds keeps only bpmn files`() {
        val listing = """
            [
              {"name":"a.bpmn","type":"file"},
              {"name":"b.bpmn","type":"file"},
              {"name":"README.md","type":"file"}
            ]
        """.trimIndent()
        assertEquals(listOf("a", "b"), LoopSync.parseLoopIds(listing))
        assertTrue(LoopSync.parseLoopIds("garbage").isEmpty())
    }

    @Test fun `toPush picks local loops with BPMN that are missing remotely`() {
        val local = listOf(loop("a"), loop("b"), loop("c", bpmn = null))
        val push = LoopSync.toPush(local, remoteIds = setOf("a"))
        assertEquals(listOf("b"), push.map { it.id }) // a exists remotely; c has no BPMN
    }

    @Test fun `toPull picks remote ids not held locally`() {
        assertEquals(listOf("x"), LoopSync.toPull(localIds = setOf("a"), remoteIds = listOf("a", "x")))
    }
}
