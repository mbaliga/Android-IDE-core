package dev.aarso.data

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import dev.aarso.domain.pm.BoardCard
import dev.aarso.domain.pm.BoardColumn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM behaviour of [IssueBoardRepo] — same stub-transport approach as [BuildsRepoTest];
 * network paths are owner-verified (no host in CI).
 */
class IssueBoardRepoTest {

    private val host = GitHost(
        id = "gh1", displayName = "My GitHub", kind = GitHostKind.GITHUB,
        baseUrl = "", owner = "acme", repo = "mobile-llm", branch = "main",
        authorName = "Dev", authorEmail = "dev@example.com",
    )

    private class BombTransport : GitTransport() {
        override suspend fun execute(req: GitRequest): Resp =
            error("transport must not be called without a token")
    }

    /** Captures the last request and returns a fixed response. */
    private class CaptureTransport(private val code: Int, private val body: String = "") : GitTransport() {
        var last: GitRequest? = null
        override suspend fun execute(req: GitRequest): Resp { last = req; return Resp(code, body) }
    }

    @Test fun `no token means empty board and no transport call`() = runTest {
        val repo = IssueBoardRepo(BombTransport()) { null }
        assertTrue(repo.listCards(host).isEmpty())
        assertFalse(repo.move(host, card(1, BoardColumn.TODO), BoardColumn.DONE))
        assertFalse(repo.create(host, "t", "", BoardColumn.TODO))
    }

    @Test fun `http error yields an empty board`() = runTest {
        val repo = IssueBoardRepo(CaptureTransport(404, """{"message":"Not Found"}""")) { "tok" }
        assertTrue(repo.listCards(host).isEmpty())
        assertTrue(repo.loadBoard(host).getValue(BoardColumn.TODO).isEmpty())
    }

    @Test fun `happy path parses and groups cards`() = runTest {
        val json = """
            [
              {"number":7,"title":"Doing it","state":"open","labels":[{"name":"status:doing"}]},
              {"number":9,"title":"Done it","state":"closed","labels":[]}
            ]
        """.trimIndent()
        val repo = IssueBoardRepo(CaptureTransport(200, json)) { "tok" }
        val board = repo.loadBoard(host)
        assertEquals(1, board.getValue(BoardColumn.DOING).size)
        assertEquals(1, board.getValue(BoardColumn.DONE).size)
        assertTrue(board.getValue(BoardColumn.BACKLOG).isEmpty())
    }

    @Test fun `move issues a PATCH and reports 2xx success`() = runTest {
        val t = CaptureTransport(200, "{}")
        val repo = IssueBoardRepo(t) { "tok" }
        assertTrue(repo.move(host, card(7, BoardColumn.DOING), BoardColumn.DONE))
        assertEquals("PATCH", t.last!!.method)
        assertTrue(t.last!!.url.endsWith("/issues/7"))
    }

    @Test fun `create posts a new issue`() = runTest {
        val t = CaptureTransport(201, "{}")
        val repo = IssueBoardRepo(t) { "tok" }
        assertTrue(repo.create(host, "New", "body", BoardColumn.TODO))
        assertEquals("POST", t.last!!.method)
    }

    private fun card(number: Int, col: BoardColumn) = BoardCard(
        id = number.toString(), number = number, title = "t", body = "",
        isOpen = col != BoardColumn.DONE, labels = listOfNotNull(col.label),
        assignees = emptyList(), updatedAt = "", url = "",
    )
}
