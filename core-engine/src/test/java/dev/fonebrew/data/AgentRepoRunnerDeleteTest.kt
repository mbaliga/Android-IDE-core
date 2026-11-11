package dev.fonebrew.data

import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.git.GitRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reviewed deletion must be applied, or the commit must fail naming the file - never dropped. */
class AgentRepoRunnerDeleteTest {

    private class FakeTransport(private val respond: (GitRequest) -> GitTransport.Resp) : GitTransport() {
        val seen = mutableListOf<GitRequest>()
        override suspend fun execute(req: GitRequest): GitTransport.Resp {
            seen += req
            return respond(req)
        }
    }

    private val host = GitHost(
        id = "h1", displayName = "mine", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "me", repo = "proj", branch = "work", authorName = "Me", authorEmail = "me@example.com",
    )

    private fun ok(o: JSONObject) = GitTransport.Resp(200, o.toString())

    private fun treeFlow(req: GitRequest): GitTransport.Resp = when {
        req.method == "GET" && req.url.contains("/git/ref/") -> ok(JSONObject().put("object", JSONObject().put("sha", "head1")))
        req.method == "GET" && req.url.contains("/git/commits/") -> ok(JSONObject().put("tree", JSONObject().put("sha", "tree1")))
        req.method == "POST" && req.url.endsWith("/git/trees") -> ok(JSONObject().put("sha", "tree2"))
        req.method == "POST" && req.url.endsWith("/git/commits") -> ok(JSONObject().put("sha", "commit1"))
        else -> GitTransport.Resp(200, "{}")
    }

    @Test fun `squashed commit sends a deletion as a tree entry with null sha and keeps the other files`() = runTest {
        val transport = FakeTransport(::treeFlow)
        val cs = ChangeSet(
            listOf(
                FileChange("gone.txt", oldText = "bye", newText = ""),
                FileChange("edit.txt", oldText = "a", newText = "b"),
            ),
        )
        val ids = AgentRepoRunner.squashedCommit(transport, host, "tok", cs, "msg").getOrThrow()
        assertEquals(listOf("commit1"), ids)
        val tree = org.json.JSONObject(transport.seen.first { it.url.endsWith("/git/trees") }.body!!).getJSONArray("tree")
        assertEquals(2, tree.length())
        val byPath = (0 until tree.length()).associate { tree.getJSONObject(it).getString("path") to tree.getJSONObject(it) }
        assertTrue(byPath.getValue("gone.txt").isNull("sha"))
        assertTrue(byPath.getValue("gone.txt").has("sha"))
        assertFalse(byPath.getValue("gone.txt").has("content"))
        assertEquals("b", byPath.getValue("edit.txt").getString("content"))
    }

    @Test fun `a delete-only change set is committed rather than rejected as nothing to commit`() = runTest {
        val transport = FakeTransport(::treeFlow)
        val cs = ChangeSet(listOf(FileChange("gone.txt", oldText = "bye", newText = "")))
        val ids = AgentRepoRunner.squashedCommit(transport, host, "tok", cs, "msg").getOrThrow()
        assertEquals(listOf("commit1"), ids)
        assertTrue(transport.seen.any { it.method == "PATCH" })
    }

    @Test fun `a failing tree request fails the squashed commit instead of succeeding without the delete`() = runTest {
        val transport = FakeTransport { req ->
            if (req.url.endsWith("/git/trees")) GitTransport.Resp(422, "{\"message\":\"bad path\"}") else treeFlow(req)
        }
        val cs = ChangeSet(listOf(FileChange("gone.txt", oldText = "bye", newText = "")))
        val r = AgentRepoRunner.squashedCommit(transport, host, "tok", cs, "msg")
        assertTrue(r.isFailure)
        assertTrue(transport.seen.none { it.method == "PATCH" }) // ref never moved
    }

    @Test fun `per-file fallback deletes through the Contents API using the blob sha`() = runTest {
        val transport = FakeTransport { req ->
            when (req.method) {
                "GET" -> ok(JSONObject().put("sha", "blob9").put("content", ""))
                "DELETE" -> ok(JSONObject().put("commit", JSONObject().put("sha", "cdel")))
                else -> GitTransport.Resp(500, "")
            }
        }
        val cs = ChangeSet(listOf(FileChange("dir/gone.txt", oldText = "bye", newText = "")))
        val ids = AgentRepoRunner.perFileCommit(transport, host, "tok", cs, "msg").getOrThrow()
        assertEquals(listOf("cdel"), ids)
        val del = transport.seen.last()
        assertEquals("DELETE", del.method)
        assertTrue(del.url.endsWith("/contents/dir/gone.txt"))
        val body = JSONObject(del.body!!)
        assertEquals("blob9", body.getString("sha"))
        assertEquals("work", body.getString("branch"))
    }

    @Test fun `per-file fallback fails loudly naming the file when the delete cannot be applied`() = runTest {
        val transport = FakeTransport { req ->
            if (req.method == "GET") GitTransport.Resp(404, "") else GitTransport.Resp(500, "")
        }
        val cs = ChangeSet(listOf(FileChange("gone.txt", oldText = "bye", newText = "")))
        val r = AgentRepoRunner.perFileCommit(transport, host, "tok", cs, "msg")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("gone.txt"))
    }

    @Test fun `per-file delete rejected by the host fails naming the file`() = runTest {
        val transport = FakeTransport { req ->
            if (req.method == "GET") ok(JSONObject().put("sha", "blob9")) else GitTransport.Resp(409, "")
        }
        val cs = ChangeSet(listOf(FileChange("gone.txt", oldText = "bye", newText = "")))
        val r = AgentRepoRunner.perFileCommit(transport, host, "tok", cs, "msg")
        assertTrue(r.exceptionOrNull()!!.message!!.contains("gone.txt"))
    }

    @Test fun `describeCommit states adds changes and deletes separately`() {
        val cs = ChangeSet(
            listOf(
                FileChange("n.txt", "", "new"),
                FileChange("m1.txt", "a", "b"),
                FileChange("m2.txt", "a", "c"),
                FileChange("d.txt", "x", ""),
                FileChange("same.txt", "s", "s"), // no-op, not counted
            ),
        )
        assertEquals("committed: 1 added, 2 changed, 1 deleted (1 commit)", AgentRepoRunner.describeCommit(cs, listOf("c1")))
        assertEquals(
            "committed: 0 added, 0 changed, 1 deleted (2 commits)",
            AgentRepoRunner.describeCommit(ChangeSet(listOf(FileChange("d.txt", "x", ""))), listOf("a", "b")),
        )
    }
}
