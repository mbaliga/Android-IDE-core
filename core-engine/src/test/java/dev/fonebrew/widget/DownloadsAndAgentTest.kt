package dev.fonebrew.widget

import dev.fonebrew.data.AgentRepoRunner
import dev.fonebrew.data.AgentReviewHolder
import dev.fonebrew.data.BackgroundJobs
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsTest {

    private fun input(
        id: String,
        name: String = "$id.gguf",
        done: Long = 0,
        total: Long = 100,
        finished: Boolean = false,
        failed: Boolean = false,
        paused: Boolean = false,
    ) = DownloadInput(id, name, done, total, finished, failed, paused)

    @Test
    fun nothingInFlight_isTheEmptyState() {
        val m = Downloads.build(emptyList())
        assertTrue(m.rows.isEmpty())
        assertEquals(0, m.runningCount)
        assertNull(m.overallPercent)
    }

    @Test
    fun runningComeFirst_thenPaused_thenFailed_andFinishedOnesAreNotListed() {
        val m = Downloads.build(
            listOf(
                input("f", failed = true),
                input("p", paused = true),
                input("done", done = 100, finished = true),
                input("r"),
            ),
        )
        assertEquals(listOf("r", "p", "f"), m.rows.map { it.id })
        assertEquals(listOf(DownloadPhase.RUNNING, DownloadPhase.PAUSED, DownloadPhase.FAILED), m.rows.map { it.phase })
        assertEquals(1, m.runningCount)
    }

    @Test
    fun aPausedDownloadThatAlsoCarriesAnError_readsAsPaused_likeTheApp() {
        // DownloadCenter: failed = error != null && !paused.
        assertEquals(DownloadPhase.PAUSED, Downloads.build(listOf(input("x", paused = true, failed = false))).rows.single().phase)
    }

    @Test
    fun percents_areWholeAndClamped_andUnknownWhenTheServerSentNoLength() {
        assertEquals(42, Downloads.percent(420, 1000))
        assertEquals(100, Downloads.percent(2000, 1000))
        assertEquals(0, Downloads.percent(-5, 1000))
        assertNull(Downloads.percent(10, 0))
        assertNull(Downloads.percent(10, -1))
    }

    @Test
    fun overallPercent_isAcrossRunningDownloadsWithAKnownLength_likeTheTopDock() {
        val m = Downloads.build(
            listOf(
                input("a", done = 50, total = 100),
                input("b", done = 150, total = 300),
                input("c", done = 999, total = 0),
                input("p", done = 0, total = 1000, paused = true),
            ),
        )
        assertEquals(50, m.overallPercent)
        assertEquals(3, m.runningCount)
    }

    @Test
    fun theRedrawSignature_changesWithWholePercents_notWithEveryChunk() {
        val a = Downloads.build(listOf(input("a", done = 4200, total = 10000))).signature
        val b = Downloads.build(listOf(input("a", done = 4299, total = 10000))).signature
        val c = Downloads.build(listOf(input("a", done = 4300, total = 10000))).signature
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun aDownloadWithNoLength_stillRedraws_asItsBytesGrow() {
        val step = Downloads.UNKNOWN_LENGTH_STEP_BYTES
        val a = Downloads.build(listOf(input("a", done = 1, total = 0))).signature
        val b = Downloads.build(listOf(input("a", done = step - 1, total = 0))).signature
        val c = Downloads.build(listOf(input("a", done = step, total = 0))).signature
        assertEquals("within one step: no redraw", a, b)
        assertTrue("a whole step later the 'so far' line moves", a != c)
    }

    @Test
    fun namesAreTheFileName_only_onOneLine() {
        val m = Downloads.build(listOf(input("a", name = "/data/user/0/x/models/llama\n.gguf")))
        assertEquals("llama .gguf", m.rows.single().name)
    }
}

class AgentReviewTest {

    private fun job(kind: String, started: Long, finished: Long? = null, failed: Boolean = false, label: String = "Agent: secret objective") =
        BackgroundJobs.Job(id = "$kind$started", label = label, kind = kind, startedAt = started, finishedAt = finished, failed = failed)

    private val ready = PendingSummary(changeCount = 3, filesRead = 2, linesAdded = 10, linesRemoved = 4, onDevice = false, proposedAt = 50L)

    @Test
    fun noHost_andNothingElse_saysSo() {
        assertEquals(AgentPhase.NO_HOST, AgentReview.build(hasHost = false, pending = null, jobs = emptyList()).phase)
        assertEquals(AgentPhase.IDLE, AgentReview.build(hasHost = true, pending = null, jobs = emptyList()).phase)
    }

    @Test
    fun aRunInProgress_winsOverAnOlderProposal() {
        val m = AgentReview.build(true, ready, listOf(job("agent", started = 70)))
        assertEquals(AgentPhase.WORKING, m.phase)
        assertEquals(70L, m.at)
    }

    @Test
    fun provenance_isTheRunningModelWhileWorking_andTheProposingModelOnceReady() {
        val working = AgentReview.build(true, null, listOf(job("agent", started = 70)), runningOnDevice = false)
        assertEquals("a run on a cloud model is marked while it works", false, working.onDevice)
        val proposed = AgentReview.build(true, ready.copy(onDevice = true), emptyList(), runningOnDevice = null)
        assertEquals(true, proposed.onDevice)
        assertNull("no model behind idle", AgentReview.build(true, null, emptyList()).onDevice)
    }

    @Test
    fun aWaitingProposal_isReady_withItsCounts() {
        val m = AgentReview.build(true, ready, listOf(job("agent", started = 10, finished = 50)))
        assertEquals(AgentPhase.READY, m.phase)
        assertEquals(ready, m.pending)
        assertEquals(50L, m.at)
        val empty = AgentReview.build(true, ready.copy(changeCount = 0), emptyList())
        assertEquals(AgentPhase.NOTHING_PROPOSED, empty.phase)
    }

    @Test
    fun aFailure_showsOnlyWhileItIsTheNewestFinishedRun() {
        val failedLast = listOf(job("agent", 1, finished = 5), job("agent", 10, finished = 20, failed = true))
        assertEquals(AgentPhase.FAILED, AgentReview.build(true, null, failedLast).phase)
        assertEquals(20L, AgentReview.build(true, null, failedLast).at)
        val succeededLast = listOf(job("agent", 1, finished = 5, failed = true), job("agent", 10, finished = 20))
        assertEquals(AgentPhase.IDLE, AgentReview.build(true, null, succeededLast).phase)
    }

    @Test
    fun otherKindsOfJob_areIgnored() {
        val jobs = listOf(job("loop", 1), job("distill", 2, finished = 3, failed = true))
        assertEquals(AgentPhase.IDLE, AgentReview.build(true, null, jobs).phase)
    }

    @Test
    fun theModelCarriesNoText_atAll() {
        // The job label carries the typed objective; nothing in the model is a string.
        val m = AgentReview.build(true, null, listOf(job("agent", 1, label = "Agent: rotate the prod keys")))
        val strings = listOf(m, m.pending).flatMap { it?.toString()?.let(::listOf).orEmpty() }
        assertTrue(strings.none { it.contains("rotate") })
    }

    @Test
    fun pendingSummary_isCountsAndProvenanceFromTheHolder_neverPathsOrTheObjective() {
        val holder = AgentReviewHolder { 1234L }
        val proposal = AgentRepoRunner.Proposal(
            changeSet = ChangeSet(
                listOf(
                    FileChange("src/secret/Plan.kt", "a\nb\n", "a\nc\nd\n"),
                    FileChange("README.md", "same", "same"),
                    FileChange("new.txt", "", "x\n"),
                ),
            ),
            filesRead = listOf("src/secret/Plan.kt", "README.md"),
            rawModelOutput = "<<<FILE ...",
        )
        holder.started(onDevice = false)
        assertEquals(false, holder.runningOnDevice.value)
        holder.offer(proposal, onDevice = false)
        assertNull("the run is over once it proposed", holder.runningOnDevice.value)
        val summary = PendingSummary.of(holder.pending.value!!)
        assertEquals(2, summary.changeCount)
        assertEquals(2, summary.filesRead)
        assertEquals(proposal.changeSet.stat.added, summary.linesAdded)
        assertEquals(proposal.changeSet.stat.removed, summary.linesRemoved)
        assertEquals(false, summary.onDevice)
        assertEquals(1234L, summary.proposedAt)
        assertTrue(summary.toString().let { !it.contains("secret") && !it.contains("private") })

        holder.started(onDevice = true)
        assertNull("a new run replaces the old proposal", holder.pending.value)
        holder.finished()
        assertNull(holder.runningOnDevice.value)

        holder.offer(proposal, onDevice = true)
        holder.clear()
        assertNull(holder.pending.value)
        assertNull(holder.runningOnDevice.value)
    }
}
