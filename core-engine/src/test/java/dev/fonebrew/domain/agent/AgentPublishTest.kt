package dev.fonebrew.domain.agent

import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPublishTest {
    private fun host(kind: GitHostKind = GitHostKind.GITHUB) = GitHost(
        "h1", "mine", kind, if (kind == GitHostKind.GITEA) "https://g.example" else "", "me", "proj", "work", "Me", "me@example.com",
    )
    private val approved = ChangeSet(listOf(FileChange("a.txt", "a", "b")))
    private val empty = ChangeSet(listOf(FileChange("a.txt", "a", "a")))

    @Test fun `the sheet preselects nothing and shows host repo branch and base`() {
        val m = PublishPlanner.sheet(host(), "Fix the README typo", "abc123")
        assertNull(m.selected)
        assertTrue(m.direct.title.contains("directly"))
        assertTrue(m.direct.detail.contains("direct commit"))
        assertTrue(m.direct.detail.contains("me/proj @ work"))
        assertEquals("work", m.baseBranch)
        assertTrue(m.suggestedBranch.startsWith("fonebrew/agent/fix-the-readme-typo-"))
        assertTrue(m.draft)
        assertTrue(m.branchPr.title.contains("draft pull request"))
    }

    @Test fun `gitea has no draft flag and the sheet says so`() {
        val m = PublishPlanner.sheet(host(GitHostKind.GITEA), "x", "id")
        assertFalse(m.draft)
        assertTrue(m.branchPr.detail.contains("not a draft"))
    }

    @Test fun `no choice means no plan`() {
        val r = PublishPlanner.plan(null, approved, host())
        assertTrue(r is PublishPlanner.PlanResult.NotReady)
    }

    @Test fun `nothing is committed without an approved subset`() {
        for (c in listOf(PublishChoice.Direct, PublishChoice.BranchAndPr("fonebrew/agent/x-1"))) {
            assertTrue(PublishPlanner.plan(c, ChangeSet(emptyList()), host()) is PublishPlanner.PlanResult.NotReady)
            assertTrue(PublishPlanner.plan(c, empty, host()) is PublishPlanner.PlanResult.NotReady)
        }
    }

    @Test fun `direct plan targets the host branch and is labelled a direct commit`() {
        val p = (PublishPlanner.plan(PublishChoice.Direct, approved, host()) as PublishPlanner.PlanResult.Ready).plan
        assertTrue(p.isDirect)
        assertEquals("work", p.targetBranch)
        assertTrue(p.describe().startsWith("Direct commit"))
    }

    @Test fun `branch plan validates the editable name and keeps the base`() {
        val ok = (PublishPlanner.plan(PublishChoice.BranchAndPr(" fonebrew/agent/x-1 "), approved, host()) as PublishPlanner.PlanResult.Ready).plan
        assertEquals("fonebrew/agent/x-1", ok.targetBranch)
        assertEquals("work", ok.baseBranch)
        assertTrue(ok.draft)
        for (bad in listOf("", "has space", "a..b", "work", "-x")) {
            assertTrue(bad, PublishPlanner.plan(PublishChoice.BranchAndPr(bad), approved, host()) is PublishPlanner.PlanResult.NotReady)
        }
        val g = (PublishPlanner.plan(PublishChoice.BranchAndPr("b1"), approved, host(GitHostKind.GITEA)) as PublishPlanner.PlanResult.Ready).plan
        assertFalse(g.draft)
    }

    @Test fun `context meter uses glyph and label and flags the token figure as estimated`() {
        val fits = ContextMeter.assess(4_000, 0, 8_000)
        assertEquals(ContextMeter.Level.FITS, fits.level)
        assertTrue(fits.text.contains("estimated"))
        assertEquals(ContextMeter.Level.NEAR, ContextMeter.assess(20_000, 0, 8_000).level) // 5000 of 8000 tokens
        val over = ContextMeter.assess(40_000, 2, 8_000)
        assertEquals(ContextMeter.Level.OVER, over.level)
        assertTrue(over.text.startsWith("✗"))
        assertTrue(over.text.contains("2 folder(s) of unknown size"))
        assertEquals(ContextMeter.Level.OVER, ContextMeter.assess(400_000, 0, 1_000_000).level) // over the byte budget
    }

    @Test fun `dispatch must name a workflow file and a valid ref`() {
        assertTrue(DispatchPlanner.prepare(host(), "", "work") is DispatchPlanner.Result.Invalid)
        assertTrue(DispatchPlanner.prepare(host(), ".github/workflows/ci.yml", "work") is DispatchPlanner.Result.Invalid)
        assertTrue(DispatchPlanner.prepare(host(), "ci.yml", "bad name") is DispatchPlanner.Result.Invalid)
        val r = (DispatchPlanner.prepare(host(), "ci.yml", "work") as DispatchPlanner.Result.Ready).request
        assertTrue(r.confirmationText.contains("ci.yml"))
        assertTrue(r.confirmationText.contains("work"))
        assertTrue(r.confirmationText.contains("me/proj"))
    }

    @Test fun `step log presenter carries state in glyph and label and flags estimates`() {
        val cloud = EngineBadge("Claude X", cloud = true)
        assertEquals("☁ Claude X (cloud - watched object)", StepLogPresenter.header(cloud))
        assertEquals("⌂ Local (on-device)", StepLogPresenter.header(EngineBadge("Local", false)))
        val call = StepCall("read_file", "path=a.kt", CallStatus.ERROR, "no such file: a.kt")
        assertEquals("✗ error read_file(path=a.kt) - no such file: a.kt", StepLogPresenter.call(call))
        assertTrue(StepLogPresenter.call(call.copy(status = CallStatus.REFUSED)).startsWith("⊘ refused"))
        assertTrue(StepLogPresenter.call(call.copy(status = CallStatus.OK)).startsWith("✓ ok"))

        val est = RunTotals().plus(TurnUsage(100, 20, estimated = true, costMinor = 5))
        assertEquals("tokens: ≈ in 100 / out 20 (estimated)", StepLogPresenter.tokens(est))
        assertEquals("cost: ≈ 5c (estimated from your price book)", StepLogPresenter.cost(est, true) { "${it}c" })
        assertEquals("cost: 0c (on-device)", StepLogPresenter.cost(est, false) { "${it}c" })

        val unknown = RunTotals().plus(null)
        assertEquals("tokens: unknown", StepLogPresenter.tokens(unknown))
        assertEquals("cost: unknown", StepLogPresenter.cost(unknown, true) { "${it}c" })
        val partial = est.plus(null)
        assertTrue(StepLogPresenter.tokens(partial).contains("1 step(s) unknown"))
        assertTrue(StepLogPresenter.cost(partial, true) { "${it}c" }.startsWith("cost: ≥ 5c"))
        val exact = RunTotals().plus(TurnUsage(10, 5, estimated = false, costMinor = 3))
        assertEquals("tokens: in 10 / out 5", StepLogPresenter.tokens(exact))
    }

    @Test fun `log lines state the engine, the seed, every step and the stop reason`() {
        val outcome = SessionOutcome(
            StopReason.STEP_LIMIT, "used all 2 steps without finishing",
            listOf(AgentStep(1, listOf(StepCall("list_dir", "path=", CallStatus.OK, "3 entries")), null, null), AgentStep(2, emptyList(), null, "reply cut at 10 bytes")),
            ChangeSet(emptyList()), null, emptyList(), RunTotals().plus(null).plus(null),
        )
        val lines = StepLogPresenter.lines(AgentRunLog(EngineBadge("M", true), "obj", listOf("injected 1 picked file(s), 5 bytes: a.txt"), outcome)) { "$it" }
        assertEquals("☁ M (cloud - watched object)", lines.first())
        assertTrue(lines.any { it.startsWith("Context: injected 1") })
        assertTrue(lines.contains("Step 1"))
        assertTrue(lines.contains("Step 2 - reply cut at 10 bytes"))
        assertTrue(lines.contains("  (no tool call)"))
        assertTrue(lines.any { it.startsWith("■ Step limit reached: used all 2") })
        assertTrue(lines.last().contains("tokens: unknown"))
    }
}
