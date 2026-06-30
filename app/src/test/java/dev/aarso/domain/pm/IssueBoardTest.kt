package dev.aarso.domain.pm

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueBoardTest {

    private val github = GitHost(
        id = "gh", displayName = "GitHub", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "acme", repo = "mobile-llm", branch = "main",
        authorName = "x", authorEmail = "x@y.z",
    )

    // ---- column inference ----

    @Test fun `open issue with no status label is backlog`() {
        assertEquals(BoardColumn.BACKLOG, BoardColumn.of(listOf("bug", "priority:high"), isOpen = true))
    }

    @Test fun `status label pins the column and closed wins as done`() {
        assertEquals(BoardColumn.DOING, BoardColumn.of(listOf("status:doing", "bug"), isOpen = true))
        // Even with a status label, a closed issue is Done.
        assertEquals(BoardColumn.DONE, BoardColumn.of(listOf("status:doing"), isOpen = false))
    }

    // ---- move semantics ----

    @Test fun `move swaps the status label but preserves other labels`() {
        val next = Boards.labelsForMove(listOf("status:todo", "bug", "area:ui"), BoardColumn.REVIEW)
        assertTrue(next.containsAll(listOf("bug", "area:ui", "status:review")))
        assertFalse(next.contains("status:todo"))
        assertEquals(3, next.size)
    }

    @Test fun `moving to backlog drops the status label entirely`() {
        val next = Boards.labelsForMove(listOf("status:doing", "bug"), BoardColumn.BACKLOG)
        assertEquals(listOf("bug"), next)
    }

    @Test fun `done means closed every other column means open`() {
        assertFalse(Boards.isOpenAfter(BoardColumn.DONE))
        assertTrue(Boards.isOpenAfter(BoardColumn.TODO))
        assertTrue(Boards.isOpenAfter(BoardColumn.BACKLOG))
    }

    // ---- request builders ----

    @Test fun `list request targets the issues endpoint with state all`() {
        val r = IssueBoardApi.listIssues(github, "tok")
        assertEquals("GET", r.method)
        assertEquals("https://api.github.com/repos/acme/mobile-llm/issues?state=all&per_page=100", r.url)
        assertEquals("Bearer tok", r.headers["Authorization"])
    }

    @Test fun `move request patches the issue with new labels and state`() {
        val r = IssueBoardApi.moveCard(github, 42, BoardColumn.DONE, listOf("status:doing", "bug"), "tok")
        assertEquals("PATCH", r.method)
        assertEquals("https://api.github.com/repos/acme/mobile-llm/issues/42", r.url)
        val body = JSONObject(r.body!!)
        assertEquals("closed", body.getString("state"))
        val labels = body.getJSONArray("labels").let { (0 until it.length()).map(it::getString) }
        assertEquals(listOf("bug"), labels) // status:* stripped, no DONE label
    }

    @Test fun `create request applies the column label`() {
        val r = IssueBoardApi.createIssue(github, "Fix crash", "details", BoardColumn.TODO, "tok")
        assertEquals("POST", r.method)
        val body = JSONObject(r.body!!)
        assertEquals("Fix crash", body.getString("title"))
        assertEquals("status:todo", body.getJSONArray("labels").getString(0))
    }

    @Test fun `create into backlog carries no labels`() {
        val r = IssueBoardApi.createIssue(github, "Idea", "", BoardColumn.BACKLOG, "tok")
        assertFalse(JSONObject(r.body!!).has("labels"))
    }

    // ---- parsing ----

    @Test fun `parse builds cards skips pull requests and infers columns`() {
        val json = """
            [
              {"id":1,"number":7,"title":"Open w/ label","state":"open","html_url":"https://x/7",
               "updated_at":"2026-06-18T00:00:00Z",
               "labels":[{"name":"status:doing"},{"name":"bug"}],
               "assignees":[{"login":"madhav"}]},
              {"id":2,"number":8,"title":"A PR","state":"open","pull_request":{"url":"https://x/pulls/8"},
               "labels":[]},
              {"id":3,"number":9,"title":"Closed one","state":"closed","html_url":"https://x/9",
               "labels":[]}
            ]
        """.trimIndent()
        val cards = IssueBoardApi.parseIssues(json)
        assertEquals(2, cards.size) // the PR is skipped
        val doing = cards.first { it.number == 7 }
        assertEquals(BoardColumn.DOING, doing.column)
        assertEquals(listOf("madhav"), doing.assignees)
        assertEquals(BoardColumn.DONE, cards.first { it.number == 9 }.column)
    }

    @Test fun `parse tolerates gitea string labels`() {
        // Some hosts/exports serialise labels as bare strings rather than objects.
        val json = JSONArray().put(
            JSONObject().put("number", 1).put("title", "t").put("state", "open")
                .put("labels", JSONArray().put("status:review")),
        ).toString()
        assertEquals(BoardColumn.REVIEW, IssueBoardApi.parseIssues(json).single().column)
    }

    @Test fun `group lays out every column in order`() {
        val cards = listOf(
            BoardCard("1", 1, "a", "", true, listOf("status:todo"), emptyList(), "", ""),
            BoardCard("2", 2, "b", "", false, emptyList(), emptyList(), "", ""),
        )
        val board = Boards.group(cards)
        assertEquals(BoardColumn.entries, board.keys.toList()) // ordered, all columns present
        assertEquals(1, board[BoardColumn.TODO]!!.size)
        assertEquals(1, board[BoardColumn.DONE]!!.size)
        assertTrue(board[BoardColumn.DOING]!!.isEmpty())
    }

    @Test fun `summary aggregates pending, in-flight and done`() {
        val cards = listOf(
            BoardCard("1", 1, "a", "", true, emptyList(), emptyList(), "", ""),               // backlog
            BoardCard("2", 2, "b", "", true, listOf("status:todo"), emptyList(), "", ""),      // todo
            BoardCard("3", 3, "c", "", true, listOf("status:doing"), emptyList(), "", ""),     // doing
            BoardCard("4", 4, "d", "", true, listOf("status:review"), emptyList(), "", ""),    // review
            BoardCard("5", 5, "e", "", false, emptyList(), emptyList(), "", ""),               // done
        )
        val s = Boards.summary(cards)
        assertEquals(5, s.total)
        assertEquals(2, s.pending)   // backlog + todo
        assertEquals(2, s.inFlight)  // doing + review
        assertEquals(1, s.done)
        assertEquals(4, s.open)      // all but done
    }
}
