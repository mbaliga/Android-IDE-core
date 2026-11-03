package dev.fonebrew.data.watch

import dev.fonebrew.data.BackgroundJobs
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.ledger.LedgerCapture
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.loop.GraphRunLog
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.StateGlyphKeys
import dev.fonebrew.watchbridge.WatchBridgeCodec
import dev.fonebrew.watchbridge.WatchTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WristTaskSnapshotTest {

    private var n = 0
    private fun task(
        id: String = "t${n++}", title: String = "task $id", state: TaskState = TaskState.TODO,
        order: Double = n.toDouble(), doneAt: Long? = null, updatedAt: Long = 1L,
    ) = TaskEntity(id = id, title = title, state = state, orderKey = order, createdAt = 1L, updatedAt = updatedAt, doneAt = doneAt)

    @Test
    fun emptyStore_isAnEmptySnapshotWithZeroPending() {
        val s = WristSnapshots.tasks(emptyList())
        assertTrue(s.tasks.isEmpty())
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun allFourStatesMapToTheirOwnWireState_andPendingCountsEverythingNotDone() {
        val s = WristSnapshots.tasks(
            listOf(
                task("a", state = TaskState.TODO), task("b", state = TaskState.DOING),
                task("c", state = TaskState.BLOCKED), task("d", state = TaskState.DONE, doneAt = 5),
            ),
        )
        assertEquals(
            listOf("a" to WatchTaskState.TODO, "b" to WatchTaskState.DOING, "c" to WatchTaskState.BLOCKED, "d" to WatchTaskState.DONE),
            s.tasks.map { it.id to it.state },
        )
        assertEquals(3, s.pendingCount)
    }

    @Test
    fun pendingComeFirstInStoreOrder_thenRecentlyDone_newestFirst() {
        val s = WristSnapshots.tasks(
            listOf(
                task("done-old", state = TaskState.DONE, doneAt = 10, order = 1.0),
                task("p1", order = 2.0),
                task("done-new", state = TaskState.DONE, doneAt = 99, order = 3.0),
                task("p2", order = 4.0),
            ),
        )
        assertEquals(listOf("p1", "p2", "done-new", "done-old"), s.tasks.map { it.id })
    }

    @Test
    fun aLongList_isWindowed_butPendingCountStaysTheTrueTotal() {
        val rows = (1..120).map { task("p$it", order = it.toDouble()) } +
            (1..20).map { task("d$it", state = TaskState.DONE, doneAt = it.toLong(), order = 1000.0 + it) }
        val s = WristSnapshots.tasks(rows)
        assertEquals(WristSnapshots.MAX_LISTED_TASKS, s.tasks.size)
        assertEquals("the complication's count must be the phone's real total", 120, s.pendingCount)
        assertTrue("pending fill the window first", s.tasks.all { it.state == WatchTaskState.TODO })
    }

    @Test
    fun doneRows_areCappedAndOnlyFillRoomPendingLeftOver() {
        val rows = (1..3).map { task("p$it", order = it.toDouble()) } +
            (1..30).map { task("d$it", state = TaskState.DONE, doneAt = it.toLong(), order = 100.0 + it) }
        val s = WristSnapshots.tasks(rows)
        assertEquals(3 + WristSnapshots.MAX_LISTED_DONE, s.tasks.size)
        assertEquals(3, s.pendingCount)
    }

    @Test
    fun longTitle_isTruncatedWithAVisibleEllipsis_andShortOnesAreUntouched() {
        val s = WristSnapshots.tasks(listOf(task("a", title = "x".repeat(500)), task("b", title = "buy milk"), task("c", title = "")))
        assertEquals(WristSnapshots.MAX_TITLE_CHARS, s.tasks[0].title.length)
        assertTrue(s.tasks[0].title.endsWith("…"))
        assertEquals("buy milk", s.tasks[1].title)
        assertEquals("", s.tasks[2].title) // the phone's store allows empty; shown as-is
    }

    @Test
    fun aTitleWithAnUnpairedSurrogate_doesNotCrashTheSnapshot() {
        val s = WristSnapshots.tasks(listOf(task("a", title = "bad\uD83Dtitle")))
        assertEquals("bad�title", s.tasks.single().title)
    }

    @Test
    fun blankIdRows_areSkipped_notFatal() {
        val s = WristSnapshots.tasks(listOf(task("", title = "ghost"), task("ok")))
        assertEquals(listOf("ok"), s.tasks.map { it.id })
    }

    @Test
    fun theWorstCaseSnapshot_staysWellUnderTheDataLayerMessageLimit() {
        val rows = (1..200).map { task("t".repeat(36) + it, title = "\"\\\n\u0001".repeat(40), order = it.toDouble()) }
        val bytes = WatchBridgeCodec.encodeToBytes(WristSnapshots.tasks(rows))
        assertTrue("snapshot was ${bytes.size} bytes", bytes.size < 100 * 1024)
    }
}

class WristLoopSnapshotTest {

    private fun root(runId: String, loopId: String?, stopped: String?, createdAt: Long): MessageNode =
        MessageNode(
            id = "root-$runId", parentId = null, role = Role.USER, content = "objective", createdAt = createdAt,
            metadata = buildMap {
                put(GraphRunLog.TAG_RUN, runId)
                if (loopId != null) put(GraphRunLog.TAG_LOOP, loopId)
                put(GraphRunLog.TAG_ROLE, "objective")
                if (stopped != null) put(GraphRunLog.TAG_STOPPED, stopped)
            },
        )

    private fun step(runId: String, parent: String, role: String, i: Int, createdAt: Long) =
        MessageNode(
            id = "step-$runId-$i", parentId = parent, role = Role.ASSISTANT, content = "out", createdAt = createdAt,
            metadata = mapOf(GraphRunLog.TAG_RUN to runId, GraphRunLog.TAG_ROLE to role),
        )

    private fun entry(runId: String, cost: Long, tier: Tier, estimated: Boolean): LedgerEntry =
        LedgerCapture.loopStep(
            timestampMillis = 1L, runId = runId, loopId = "loop-1", nodeId = "n-$runId-$cost", projectId = null,
            model = "m", tier = tier, inputTokens = 10, outputTokens = 5, latencyMs = 1, estimated = estimated, estCostMinor = cost,
        )

    private fun tree(vararg nodes: MessageNode) = MessageTree(nodes.toList())

    private fun runs(
        tree: MessageTree, ledger: List<LedgerEntry> = emptyList(), jobs: List<BackgroundJobs.Job> = emptyList(),
        names: Map<String, String> = mapOf("loop-1" to "Refine"), currency: String = "USD", max: Int = WristSnapshots.MAX_LOOP_RUNS,
    ) = WristSnapshots.loopRuns(tree, ledger, jobs, { id -> id?.let(names::get) }, currency, max)

    @Test
    fun aFinishedRun_readsItsStateFromTheRunLog_itsStepFromTheLastStep_itsSpendFromTheLedger() {
        val r = root("run-1", "loop-1", "reached end", 10)
        val s1 = step("run-1", r.id, "proposer", 1, 11)
        val s2 = step("run-1", s1.id, "critic", 2, 12)
        val out = runs(
            tree(r, s1, s2),
            listOf(entry("run-1", 30, Tier.CLOUD, estimated = false), entry("run-1", 12, Tier.CLOUD, estimated = false)),
        ).single()
        assertEquals("run-1", out.runId)
        assertEquals("Refine", out.loopName)
        assertEquals(StateGlyphKeys.COMPLETE, out.stateGlyphKey)
        assertEquals("critic", out.stepName)
        assertEquals(Spend(42, "USD", estimated = false), out.spend)
    }

    @Test
    fun stopReasons_mapToHonestGlyphs_andAnUnknownReasonIsNotForcedIntoComplete() {
        fun glyph(reason: String?) = WristSnapshots.glyphForStop(reason)
        assertEquals(StateGlyphKeys.COMPLETE, glyph("reached end"))
        for (r in listOf("cancelled", "hit step cap (40)", "budget:tokens", "budget:steps", "budget:wall")) assertEquals(r, StateGlyphKeys.STOPPED, glyph(r))
        for (r in listOf("missing params: topic", "no start event", "no outgoing edge")) assertEquals(r, StateGlyphKeys.FAILED, glyph(r))
        assertEquals(WristSnapshots.ENDED_GLYPH_KEY, glyph("something new the runner invented"))
        assertEquals(WristSnapshots.ENDED_GLYPH_KEY, glyph(null))
        assertTrue(StateGlyphKeys.isValid(WristSnapshots.ENDED_GLYPH_KEY))
    }

    @Test
    fun aRunWithNoLedgerRows_hasNullSpend_notZero() {
        val out = runs(tree(root("run-1", "loop-1", "reached end", 10))).single()
        assertNull(out.spend)
    }

    @Test
    fun anAllOnDeviceRun_isARealZero_notAnEstimate() {
        val out = runs(
            tree(root("run-1", "loop-1", "reached end", 10)),
            listOf(entry("run-1", 0, Tier.ON_DEVICE, estimated = true)), // the ledger flags on-device token counts as estimated
        ).single()
        assertEquals(Spend(0, "USD", estimated = false), out.spend)
    }

    @Test
    fun aRunWithAnEstimatedCloudStep_isFlaggedEstimated() {
        val out = runs(
            tree(root("run-1", "loop-1", "reached end", 10)),
            listOf(entry("run-1", 5, Tier.CLOUD, estimated = false), entry("run-1", 7, Tier.CLOUD, estimated = true)),
        ).single()
        assertEquals(Spend(12, "USD", estimated = true), out.spend)
    }

    @Test
    fun ledgerRowsOfOtherRunsAndOfChatTurns_arenotCounted() {
        val chat = LedgerCapture.singleTurn(1, "run-1", "n", null, "m", "p", Tier.CLOUD, 1, 1, 99, 1, dev.fonebrew.domain.ledger.Status.COMPLETE, false)
        val out = runs(
            tree(root("run-1", "loop-1", "reached end", 10)),
            listOf(entry("run-1", 5, Tier.CLOUD, false), entry("run-2", 1000, Tier.CLOUD, false), chat),
        ).single()
        assertEquals(5L, out.spend!!.amountMinor)
    }

    @Test
    fun currencyIsTheUsersDisplayLabel_andABadCodeFallsBackNeverCrashes() {
        val t = tree(root("run-1", "loop-1", "reached end", 10))
        val e = listOf(entry("run-1", 5, Tier.CLOUD, false))
        assertEquals("INR", runs(t, e, currency = "inr").single().spend!!.currency)
        assertEquals("USD", runs(t, e, currency = "not-a-code").single().spend!!.currency)
    }

    @Test
    fun aRunWithNoSavedLoop_getsAnHonestPlaceholderName() {
        val out = runs(tree(root("run-1", null, "reached end", 10)), names = emptyMap()).single()
        assertEquals(WristSnapshots.UNSAVED_LOOP_NAME, out.loopName)
    }

    @Test
    fun aRunWithNoSteps_hasNoStepName() {
        assertNull(runs(tree(root("run-1", "loop-1", "cancelled", 10))).single().stepName)
    }

    @Test
    fun liveJobs_areRunning_withNoStepAndNoSpend_andComeFirst() {
        val finished = root("run-9", "loop-1", "reached end", 10)
        val live = BackgroundJobs.Job(id = "job-1", label = "Debate", kind = "loop", startedAt = 100)
        val notALoop = BackgroundJobs.Job(id = "job-2", label = "Agent", kind = "agent", startedAt = 100)
        val out = runs(tree(finished), jobs = listOf(notALoop, live))
        assertEquals(listOf("job-1", "run-9"), out.map { it.runId })
        assertEquals(StateGlyphKeys.RUNNING, out[0].stateGlyphKey)
        assertEquals("Debate", out[0].loopName)
        assertNull(out[0].stepName)
        assertNull("a live run has no ledger figure yet: unknown, not 0", out[0].spend)
    }

    @Test
    fun aJobThatDiedBeforeWritingAnything_isFailed_andASettledOneIsNotDoubleCounted() {
        val died = BackgroundJobs.Job(id = "job-d", label = "Boom", kind = "loop", startedAt = 1, finishedAt = 5, failed = true)
        val settledOk = BackgroundJobs.Job(id = "job-ok", label = "Fine", kind = "loop", startedAt = 1, finishedAt = 5, failed = false)
        val out = runs(tree(), jobs = listOf(died, settledOk))
        assertEquals(listOf("job-d"), out.map { it.runId })
        assertEquals(StateGlyphKeys.FAILED, out.single().stateGlyphKey)
    }

    @Test
    fun finishedRunsAreNewestFirst_andTheWholeListIsCapped() {
        val roots = (1..9).map { root("run-$it", "loop-1", "reached end", it.toLong()) }
        val out = runs(MessageTree(roots), max = 3)
        assertEquals(listOf("run-9", "run-8", "run-7"), out.map { it.runId })
        assertEquals(5, runs(MessageTree(roots)).size)
    }

    @Test
    fun ordinaryChatRoots_areNeverTreatedAsLoopRuns() {
        val chat = MessageNode("c1", null, Role.USER, "hello", createdAt = 1)
        assertTrue(runs(MessageTree(listOf(chat))).isEmpty())
    }

    @Test
    fun everySnapshotProduced_encodesWithTheContractCodec() {
        val r = root("run-1", "loop-1", "reached end", 10)
        val out = runs(tree(r, step("run-1", r.id, "critic", 1, 11)), listOf(entry("run-1", 3, Tier.CLOUD, true)))
        for (snap in out) assertEquals(snap, (WatchBridgeCodec.decode(WatchBridgeCodec.encode(snap)) as dev.fonebrew.watchbridge.DecodeResult.Decoded).message)
    }
}
