package dev.fonebrew.data.watch

import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.AskRequest
import dev.fonebrew.watchbridge.ContractHello
import dev.fonebrew.watchbridge.HelloCompatibility
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.StateGlyphKeys
import dev.fonebrew.watchbridge.TaskSnapshot
import dev.fonebrew.watchbridge.TaskToggleRequest
import dev.fonebrew.watchbridge.TaskToggleResult
import dev.fonebrew.watchbridge.Usage
import dev.fonebrew.watchbridge.WatchBridge
import dev.fonebrew.watchbridge.WatchBridgeCodec
import dev.fonebrew.watchbridge.WatchMessage
import dev.fonebrew.watchbridge.WatchPaths
import dev.fonebrew.watchbridge.WatchTaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/** A turn whose events are scripted; records what it was asked. */
private class ScriptedTurns(
    private val events: List<WristTurnEvent>,
    private val thenThrow: Throwable? = null,
    private val hold: CompletableDeferred<Unit>? = null,
) : WristTurns {
    val prompts = mutableListOf<String>()
    override suspend fun run(prompt: String, emit: suspend (WristTurnEvent) -> Unit) {
        prompts += prompt
        for (e in events) emit(e)
        hold?.await()
        thenThrow?.let { throw it }
    }
}

private fun answer(engine: String = "tiny", watched: Boolean = false, usage: TurnUsage = TurnUsage.Estimated(3, 4), vararg text: String) =
    listOf<WristTurnEvent>(WristTurnEvent.Engine(engine, watched)) + text.map { WristTurnEvent.Token(it) } + WristTurnEvent.Done(usage)

@OptIn(ExperimentalCoroutinesApi::class)
class WearRelayPresenterTest {

    private val node = "watch-node-1"
    private fun askBytes(id: String = "req-1", prompt: String = "hello?") = WatchBridgeCodec.encodeToBytes(AskRequest(id, prompt))
    private fun toggleBytes(id: String, done: Boolean) = WatchBridgeCodec.encodeToBytes(TaskToggleRequest(id, done))

    private fun presenter(
        turns: WristTurns = ScriptedTurns(answer(text = arrayOf("Hi"))),
        fx: TreeFixture = TreeFixture(),
        loops: LoopRunSource = LoopRunSource { emptyList() },
        version: String = "0.3.1",
    ) = WearRelayPresenter(turns, fx.tasks, loops, version)

    private fun task(id: String, state: TaskState = TaskState.TODO, title: String = "task $id", order: Double = id.hashCode().toDouble()) =
        TaskEntity(id = id, title = title, state = state, orderKey = order, createdAt = 1, updatedAt = 1)

    private fun TreeFixture.withTasks(vararg t: TaskEntity) = apply { taskDao.rows.value = t.toList() }

    // ---- classification -------------------------------------------------------------------------------

    @Test
    fun classify_acceptsExactlyTheWatchToPhonePaths_withTheirOwnMessageTypes() {
        val p = presenter()
        assertEquals(WearRelayPresenter.Inbound.Sync, p.classify(WatchPaths.SYNC, ByteArray(0)))
        assertEquals(WearRelayPresenter.Inbound.Sync, p.classify(WatchPaths.SYNC, "anything at all".toByteArray()))
        assertEquals(WearRelayPresenter.Inbound.Ask(AskRequest("r", "q")), p.classify(WatchPaths.ASK, askBytes("r", "q")))
        assertEquals(WearRelayPresenter.Inbound.Toggle(TaskToggleRequest("t", true)), p.classify(WatchPaths.TASK_TOGGLE, toggleBytes("t", true)))
    }

    @Test
    fun classify_ignoresAMessageOnTheWrongPath() {
        val p = presenter()
        assertTrue(p.classify(WatchPaths.ASK, toggleBytes("t", true)) is WearRelayPresenter.Inbound.Ignored)
        assertTrue(p.classify(WatchPaths.TASK_TOGGLE, askBytes()) is WearRelayPresenter.Inbound.Ignored)
    }

    @Test
    fun classify_ignoresEveryPhoneToWatchPath_andEveryMessageTheWatchMustNotSend() {
        val p = presenter()
        val phoneToWatch = listOf(WatchPaths.HELLO, WatchPaths.TASKS, WatchPaths.TASK_TOGGLE_RESULT, WatchPaths.LOOPS, WatchPaths.ASK_REPLY)
        val forbidden: List<WatchMessage> = listOf(
            ContractHello.forThisBuild("9.9"), AskChunk.token("r", "x", "e", false), AskError("r", "no"),
            TaskSnapshot(emptyList(), 0), TaskToggleResult.Applied("t", true), LoopRunSnapshot("r", "n", StateGlyphKeys.RUNNING, null, null),
        )
        for (path in phoneToWatch + WatchPaths.ASK + WatchPaths.TASK_TOGGLE) {
            for (m in forbidden) {
                assertTrue("$path accepted ${m.javaClass.simpleName}", p.classify(path, WatchBridgeCodec.encodeToBytes(m)) is WearRelayPresenter.Inbound.Ignored)
            }
        }
    }

    @Test
    fun classify_ignoresForeignPaths_malformedBytes_andOversizedPayloads() {
        val p = presenter()
        assertTrue(p.classify("/other/app/ask", askBytes()) is WearRelayPresenter.Inbound.Ignored)
        assertTrue(p.classify("/watchbrew/unknown", askBytes()) is WearRelayPresenter.Inbound.Ignored)
        assertTrue(p.classify("", askBytes()) is WearRelayPresenter.Inbound.Ignored)
        assertTrue(p.classify(WatchPaths.ASK, ByteArray(0)) is WearRelayPresenter.Inbound.Ignored)
        assertTrue(p.classify(WatchPaths.ASK, byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)) is WearRelayPresenter.Inbound.Ignored)
        assertTrue(p.classify(WatchPaths.ASK, "{\"not\":\"ours\"}".toByteArray()) is WearRelayPresenter.Inbound.Ignored)
        val whole = askBytes()
        assertTrue("a truncated envelope is never partly acted on", p.classify(WatchPaths.ASK, whole.copyOf(whole.size - 3)) is WearRelayPresenter.Inbound.Ignored)
        assertTrue(p.classify(WatchPaths.ASK, askBytes(prompt = "x".repeat(WearRelayPresenter.MAX_INBOUND_BYTES))) is WearRelayPresenter.Inbound.Ignored)
    }

    @Test
    fun classify_aNewerContractVersion_isAnIncompatibility_notAnAsk() {
        val v2 = """{"contract":"watchbrew","v":2,"type":"AskRequest","payload":{"anything":"else"}}""".toByteArray()
        assertEquals(WearRelayPresenter.Inbound.IncompatibleVersion, presenter().classify(WatchPaths.ASK, v2))
    }

    // ---- version handshake ----------------------------------------------------------------------------------

    @Test
    fun aNewerWatch_isAnsweredWithTheHello_andNothingElseHappens() = runTest {
        val turns = ScriptedTurns(answer())
        val fx = TreeFixture().withTasks(task("t1"))
        val transport = FakeTransport()
        val v2 = """{"contract":"watchbrew","v":2,"type":"AskRequest","payload":{"requestId":"r","prompt":"hi"}}""".toByteArray()

        presenter(turns, fx, version = "0.3.1").onMessage(node, WatchPaths.ASK, v2, transport)

        assertTrue("the newer payload must never be run", turns.prompts.isEmpty())
        assertEquals(0, transport.channelOpenAttempts)
        val hello = transport.sent.single()
        assertEquals(node, hello.nodeId)
        assertEquals(WatchPaths.HELLO, hello.path)
        val msg = hello.message() as ContractHello
        assertEquals(WatchBridge.CONTRACT_VERSION, msg.contractVersion)
        assertEquals("0.3.1", msg.phoneAppVersion)
        assertEquals("the watch can tell which side is old from this alone", HelloCompatibility.Compatible, msg.compatibility())
        assertEquals("no task was touched", TaskState.TODO, fx.taskDao.rows.value.single().state)
    }

    @Test
    fun theHelloIsWireSafe_evenForAnOddVersionString() = runTest {
        val transport = FakeTransport()
        presenter(version = "").onMessage(node, WatchPaths.SYNC, ByteArray(0), transport)
        assertEquals("unknown", (transport.sent.first { it.path == WatchPaths.HELLO }.message() as ContractHello).phoneAppVersion)
        val t2 = FakeTransport()
        presenter(version = "1.0\uD83D").onMessage(node, WatchPaths.SYNC, ByteArray(0), t2)
        assertEquals("1.0�", (t2.sent.first { it.path == WatchPaths.HELLO }.message() as ContractHello).phoneAppVersion)
    }

    // ---- ask: the reply stream ----------------------------------------------------------------------------------

    @Test
    fun anAsk_streamsChunksThenATerminalChunk_onAChannelOpenedToTheAskingWatch() = runTest {
        val turns = ScriptedTurns(answer("tiny", false, TurnUsage.Estimated(7, 9), "Hel", "lo"))
        val transport = FakeTransport()

        presenter(turns).onMessage(node, WatchPaths.ASK, askBytes("req-9", "say hi"), transport)

        assertEquals(listOf("say hi"), turns.prompts)
        assertEquals(listOf(WatchPaths.ASK_REPLY), transport.channelPaths)
        val frames = transport.channels.single().decoded()
        assertEquals(listOf("Hel", "lo", ""), frames.map { (it as AskChunk).text })
        assertEquals(listOf(false, false, true), frames.map { (it as AskChunk).terminal })
        assertTrue(frames.all { (it as AskChunk).requestId == "req-9" && it.engineId == "tiny" && !it.watchedObject })
        assertEquals(Usage.estimate(7, 9), (frames.last() as AskChunk).usage)
        assertEquals(1, transport.channels.single().closed)
        assertTrue("an ask uses the channel only, never a message", transport.sent.isEmpty())
    }

    @Test
    fun aCloudAnswer_carriesTheWatchedObjectFlagOnEveryChunk() = runTest {
        val transport = FakeTransport()
        presenter(ScriptedTurns(answer("Acme · big", true, TurnUsage.EngineReported(11, 22), "a", "b"))).onMessage(node, WatchPaths.ASK, askBytes(), transport)
        val chunks = transport.channels.single().decoded().map { it as AskChunk }
        assertEquals(3, chunks.size)
        assertTrue("the wrist never hides who answered", chunks.all { it.watchedObject })
        assertEquals(Usage.engineReported(11, 22), chunks.last().usage)
        assertFalse(chunks.last().usage!!.estimated)
    }

    @Test
    fun everyUsageCase_reachesTheTerminalChunk_asItsOwnHonestWireForm() = runTest {
        val cases = mapOf(
            TurnUsage.EngineReported(1, 2) to Usage.engineReported(1, 2),
            TurnUsage.Estimated(3, null) to Usage.estimate(3, null),
            TurnUsage.Estimated(null, 4) to Usage.estimate(null, 4),
            TurnUsage.Unknown to Usage.unknown(),
        )
        for ((given, wire) in cases) {
            val t = FakeTransport()
            presenter(ScriptedTurns(answer(usage = given))).onMessage(node, WatchPaths.ASK, askBytes(), t)
            assertEquals(wire, (t.channels.single().decoded().last() as AskChunk).usage)
        }
    }

    @Test
    fun usageNeverAppearsOnANonTerminalChunk() = runTest {
        val t = FakeTransport()
        presenter(ScriptedTurns(answer(text = arrayOf("a", "b", "c")))).onMessage(node, WatchPaths.ASK, askBytes(), t)
        val chunks = t.channels.single().decoded().map { it as AskChunk }
        assertTrue(chunks.dropLast(1).all { it.usage == null })
        assertNull(chunks.dropLast(1).firstOrNull()?.usage)
    }

    @Test
    fun anEmojiSplitAcrossTokens_arrivesWhole() = runTest {
        val t = FakeTransport()
        presenter(ScriptedTurns(answer(text = arrayOf("ok \uD83D", "\uDE42 done")))).onMessage(node, WatchPaths.ASK, askBytes(), t)
        val text = t.channels.single().decoded().joinToString("") { (it as AskChunk).text }
        assertEquals("ok 🙂 done", text)
    }

    @Test
    fun aFailureEvent_becomesOneAskError_withTheRequestId_andTheChannelIsClosed() = runTest {
        val t = FakeTransport()
        presenter(ScriptedTurns(listOf(WristTurnEvent.Failed("No model is set up on the phone yet.")))).onMessage(node, WatchPaths.ASK, askBytes("req-5"), t)
        val frames = t.channels.single().decoded()
        assertEquals(listOf<WatchMessage>(AskError("req-5", "No model is set up on the phone yet.")), frames)
        assertEquals(1, t.channels.single().closed)
    }

    @Test
    fun aFailureAfterSomeText_endsTheStreamWithAnAskError_andNoTerminalChunk() = runTest {
        val t = FakeTransport()
        val events = listOf(WristTurnEvent.Engine("tiny", false), WristTurnEvent.Token("part"), WristTurnEvent.Failed("The model could not finish on the phone."))
        presenter(ScriptedTurns(events)).onMessage(node, WatchPaths.ASK, askBytes(), t)
        val frames = t.channels.single().decoded()
        assertEquals(2, frames.size)
        assertEquals("part", (frames[0] as AskChunk).text)
        assertTrue(frames[1] is AskError)
        assertTrue("an error is not a finished answer", frames.none { it is AskChunk && it.terminal })
    }

    @Test
    fun theWatchUnreachable_meansTheQuestionIsNeverRun() = runTest {
        val turns = ScriptedTurns(answer())
        val t = FakeTransport(channelFactory = null)
        presenter(turns).onMessage(node, WatchPaths.ASK, askBytes(), t)
        assertEquals(1, t.channelOpenAttempts)
        assertTrue("no orphan turn, no wasted compute", turns.prompts.isEmpty())
    }

    @Test
    fun aLinkThatDropsMidStream_endsQuietly_andClosesTheChannel_withoutAnythingEscaping() = runTest {
        val t = FakeTransport(channelFactory = { FakeChannel(failAfterWrites = 1) })
        val turns = ScriptedTurns(answer(text = arrayOf("one", "two", "three")))
        presenter(turns).onMessage(node, WatchPaths.ASK, askBytes(), t) // must not throw
        val ch = t.channels.single()
        assertEquals(1, ch.written.size)
        assertEquals(1, ch.closed)
        assertTrue("a cut stream has no terminal chunk, so the wrist shows 'unreachable'", ch.decoded().none { it is AskChunk && it.terminal })
    }

    @Test
    fun aTurnThatThrowsAnUnexpectedError_neverEscapesIntoTheService() = runTest {
        val t = FakeTransport()
        presenter(ScriptedTurns(emptyList(), thenThrow = IllegalStateException("boom"))).onMessage(node, WatchPaths.ASK, askBytes(), t)
        assertEquals(1, t.channels.single().closed)
    }

    @Test
    fun aRunnerThatBreaksTheEventGrammar_ClosesTheStream_insteadOfInventingAnEngine() = runTest {
        val t = FakeTransport()
        presenter(ScriptedTurns(listOf(WristTurnEvent.Token("no engine announced yet")))).onMessage(node, WatchPaths.ASK, askBytes(), t)
        assertTrue(t.channels.single().written.isEmpty())
        assertEquals(1, t.channels.single().closed)
    }

    @Test
    fun aBlankEngineName_isReplacedWithAPlaceholder_notCrashedAndNotLeftEmpty() = runTest {
        val t = FakeTransport()
        presenter(ScriptedTurns(answer(engine = "  ", text = arrayOf("x")))).onMessage(node, WatchPaths.ASK, askBytes(), t)
        assertEquals("model", (t.channels.single().decoded().first() as AskChunk).engineId)
    }

    // ---- ask: refusals ----------------------------------------------------------------------------------------------

    @Test
    fun aPromptTooLongForAWrist_isRefusedInWords_andNotRun() = runTest {
        val turns = ScriptedTurns(answer())
        val t = FakeTransport()
        val long = "x".repeat(WearRelayPresenter.MAX_PROMPT_CHARS + 1)
        presenter(turns).onMessage(node, WatchPaths.ASK, askBytes("r", long), t)
        assertTrue(turns.prompts.isEmpty())
        assertEquals(listOf<WatchMessage>(AskError("r", WearRelayPresenter.PROMPT_TOO_LONG_REASON)), t.channels.single().decoded())
        assertEquals(1, t.channels.single().closed)
    }

    @Test
    fun aPromptAtTheLimit_isStillRun() = runTest {
        val turns = ScriptedTurns(answer())
        presenter(turns).onMessage(node, WatchPaths.ASK, askBytes("r", "x".repeat(WearRelayPresenter.MAX_PROMPT_CHARS)), FakeTransport())
        assertEquals(1, turns.prompts.size)
    }

    @Test
    fun aSecondQuestionWhileOneIsBeingAnswered_isRefusedAtOnce_notQueuedSilently_andTheFirstIsUnharmed() = runTest {
        val hold = CompletableDeferred<Unit>()
        val firstTurns = ScriptedTurns(answer(text = arrayOf("slow")), hold = hold)
        val p = presenter(firstTurns)
        val t = FakeTransport()

        val first = launch { p.onMessage(node, WatchPaths.ASK, askBytes("first", "q1"), t) }
        advanceUntilIdle() // first is now parked inside its turn, holding the gate
        p.onMessage(node, WatchPaths.ASK, askBytes("second", "q2"), t)

        assertEquals("only the first question ever ran", listOf("q1"), firstTurns.prompts)
        assertEquals(listOf<WatchMessage>(AskError("second", WearRelayPresenter.BUSY_REASON)), t.channels[1].decoded())

        hold.complete(Unit)
        first.join()
        val firstFrames = t.channels[0].decoded()
        assertTrue((firstFrames.last() as AskChunk).terminal)
        assertEquals("first", (firstFrames.first() as AskChunk).requestId)

        // And the gate is free again afterwards.
        p.onMessage(node, WatchPaths.ASK, askBytes("third", "q3"), FakeTransport())
        assertEquals(listOf("q1", "q3"), firstTurns.prompts)
    }

    @Test
    fun theGateIsReleasedWhenATurnFails() = runTest {
        val turns = ScriptedTurns(emptyList(), thenThrow = IllegalStateException("x"))
        val p = presenter(turns)
        p.onMessage(node, WatchPaths.ASK, askBytes("a", "q1"), FakeTransport())
        p.onMessage(node, WatchPaths.ASK, askBytes("b", "q2"), FakeTransport())
        assertEquals("a failed turn must not leave the phone refusing everything as busy", listOf("q1", "q2"), turns.prompts)
    }

    @Test
    fun theGateIsReleasedWhenATurnIsCancelled() = runTest {
        val hold = CompletableDeferred<Unit>()
        val turns = ScriptedTurns(answer(), hold = hold)
        val p = presenter(turns)
        val job = launch { p.onMessage(node, WatchPaths.ASK, askBytes("a", "q1"), FakeTransport()) }
        advanceUntilIdle()
        job.cancelAndJoin()

        hold.complete(Unit)
        val t = FakeTransport()
        p.onMessage(node, WatchPaths.ASK, askBytes("b", "q2"), t)
        assertEquals(listOf("q1", "q2"), turns.prompts)
        assertTrue("not a busy refusal", t.channels.single().decoded().none { it is AskError })
    }

    // ---- tasks -----------------------------------------------------------------------------------------------------------

    @Test
    fun checkingATask_setsItDoneThroughTheStore_thenReportsWhatTheStoreHolds_thenRefreshesTheList() = runTest {
        val fx = TreeFixture().withTasks(task("t1"), task("t2"))
        val t = FakeTransport()
        presenter(fx = fx).onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("t1", true), t)

        val stored = fx.taskDao.rows.value.first { it.id == "t1" }
        assertEquals(TaskState.DONE, stored.state)
        assertNotNullDoneAt(stored)
        assertEquals("the other task is untouched", TaskState.TODO, fx.taskDao.rows.value.first { it.id == "t2" }.state)

        assertEquals(listOf(WatchPaths.TASK_TOGGLE_RESULT, WatchPaths.TASKS), t.sent.map { it.path })
        assertEquals(TaskToggleResult.Applied("t1", true), t.sent[0].message())
        val snapshot = t.sent[1].message() as TaskSnapshot
        assertEquals(WatchTaskState.DONE, snapshot.tasks.first { it.id == "t1" }.state)
        assertEquals(1, snapshot.pendingCount)
    }

    private fun assertNotNullDoneAt(task: TaskEntity) = assertTrue("doneAt must be minted by the store", task.doneAt != null)

    @Test
    fun uncheckingADoneTask_returnsItToTodo_andClearsDoneAt() = runTest {
        val fx = TreeFixture().withTasks(task("t1", TaskState.DONE).copy(doneAt = 5))
        val t = FakeTransport()
        presenter(fx = fx).onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("t1", false), t)
        val stored = fx.taskDao.rows.value.single()
        assertEquals(TaskState.TODO, stored.state)
        assertNull(stored.doneAt)
        assertEquals(TaskToggleResult.Applied("t1", false), t.sent[0].message())
    }

    @Test
    fun aRetriedOrDuplicatedToggle_isHarmless_theRequestIsADesiredStateNotAFlip() = runTest {
        val fx = TreeFixture().withTasks(task("t1"))
        val p = presenter(fx = fx)
        repeat(3) { p.onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("t1", true), FakeTransport()) }
        assertEquals(TaskState.DONE, fx.taskDao.rows.value.single().state)
        repeat(2) { p.onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("t1", false), FakeTransport()) }
        assertEquals(TaskState.TODO, fx.taskDao.rows.value.single().state)
    }

    @Test
    fun aWristCheckboxNeverDemotesADoingOrBlockedTask_andReportsTheTruth() = runTest {
        for (state in listOf(TaskState.DOING, TaskState.BLOCKED)) {
            val fx = TreeFixture().withTasks(task("t1", state))
            val t = FakeTransport()
            presenter(fx = fx).onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("t1", false), t)
            assertEquals("$state must be left exactly as it is", state, fx.taskDao.rows.value.single().state)
            assertEquals(TaskToggleResult.Applied("t1", false), t.sent[0].message())
        }
    }

    @Test
    fun anUnknownTask_isRejectedInWords_nothingIsWritten_andTheListIsStillRefreshed() = runTest {
        val fx = TreeFixture().withTasks(task("t1"))
        val before = fx.taskDao.rows.value
        val t = FakeTransport()
        presenter(fx = fx).onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("ghost", true), t)
        assertEquals(TaskToggleResult.Rejected("ghost", WearRelayPresenter.TASK_GONE_REASON), t.sent[0].message())
        assertEquals(before, fx.taskDao.rows.value)
        assertEquals(WatchPaths.TASKS, t.sent[1].path) // so the watch reconciles away the vanished row
    }

    @Test
    fun aStoreFailure_isRejectedWithAFixedSentence_neverTheDatabasesMessage() = runTest {
        val fx = TreeFixture().withTasks(task("t1"))
        fx.taskDao.failUpdates = true
        val t = FakeTransport()
        presenter(fx = fx).onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("t1", true), t)
        val result = t.sent[0].message() as TaskToggleResult.Rejected
        assertEquals(WearRelayPresenter.TASK_SAVE_FAILED_REASON, result.reason)
        assertFalse(String(t.everyByteOnTheWire()).contains("db locked"))
        assertEquals(TaskState.TODO, fx.taskDao.rows.value.single().state)
    }

    // ---- sync ----------------------------------------------------------------------------------------------------------------

    @Test
    fun aSyncPushesHello_thenTasks_thenEveryLoopRun_toTheAskingNode() = runTest {
        val fx = TreeFixture().withTasks(task("t1"), task("t2", TaskState.DONE).copy(doneAt = 3))
        val runs = listOf(
            LoopRunSnapshot("run-2", "Refine", StateGlyphKeys.RUNNING, null, null),
            LoopRunSnapshot("run-1", "Debate", StateGlyphKeys.COMPLETE, "critic", null),
        )
        val t = FakeTransport()
        presenter(fx = fx, loops = LoopRunSource { runs }).onMessage(node, WatchPaths.SYNC, ByteArray(0), t)

        assertEquals(listOf(WatchPaths.HELLO, WatchPaths.TASKS, WatchPaths.LOOPS, WatchPaths.LOOPS), t.sent.map { it.path })
        assertTrue(t.sent.all { it.nodeId == node })
        assertTrue(t.sent[0].message() is ContractHello)
        val snapshot = t.sent[1].message() as TaskSnapshot
        assertEquals(1, snapshot.pendingCount)
        assertEquals(setOf("t1", "t2"), snapshot.tasks.map { it.id }.toSet())
        assertEquals(runs, t.sent.drop(2).map { it.message() })
        assertEquals("a sync opens no reply channel", 0, t.channelOpenAttempts)
    }

    @Test
    fun aSyncWithNothingToShow_stillSaysHelloAndSendsAnEmptyTaskList() = runTest {
        val t = FakeTransport()
        presenter().onMessage(node, WatchPaths.SYNC, ByteArray(0), t)
        assertEquals(listOf(WatchPaths.HELLO, WatchPaths.TASKS), t.sent.map { it.path })
        assertEquals(TaskSnapshot(emptyList(), 0), t.sent[1].message())
    }

    @Test
    fun aLoopSourceThatFails_doesNotCostTheWristItsHelloAndTasks() = runTest {
        val t = FakeTransport()
        presenter(loops = LoopRunSource { throw IllegalStateException("tree unreadable") }).onMessage(node, WatchPaths.SYNC, ByteArray(0), t)
        assertEquals(listOf(WatchPaths.HELLO, WatchPaths.TASKS), t.sent.map { it.path })
    }

    @Test
    fun anUnreachableWatch_isBestEffort_nothingEscapes() = runTest {
        val t = FakeTransport(failSends = true)
        val p = presenter(fx = TreeFixture().withTasks(task("t1")), loops = LoopRunSource { listOf(LoopRunSnapshot("r", "n", StateGlyphKeys.RUNNING, null, null)) })
        p.onMessage(node, WatchPaths.SYNC, ByteArray(0), t)
        p.onMessage(node, WatchPaths.TASK_TOGGLE, toggleBytes("t1", true), t)
        assertTrue(t.sent.isEmpty())
    }

    @Test
    fun ignoredMessages_causeNoSideEffectsAtAll() = runTest {
        val turns = ScriptedTurns(answer())
        val fx = TreeFixture().withTasks(task("t1"))
        val t = FakeTransport()
        val p = presenter(turns, fx)
        p.onMessage(node, "/other/path", askBytes(), t)
        p.onMessage(node, WatchPaths.HELLO, WatchBridgeCodec.encodeToBytes(ContractHello.forThisBuild("1")), t)
        p.onMessage(node, WatchPaths.ASK, "garbage".toByteArray(), t)
        assertTrue(t.sent.isEmpty())
        assertEquals(0, t.channelOpenAttempts)
        assertTrue(turns.prompts.isEmpty())
        assertEquals(TaskState.TODO, fx.taskDao.rows.value.single().state)
    }

    @Test
    fun cancellationPropagates_itIsNotSwallowedAsAFailure() = runTest {
        val p = presenter(loops = LoopRunSource { throw CancellationException("cancelled") })
        try {
            p.onMessage(node, WatchPaths.SYNC, ByteArray(0), FakeTransport())
            org.junit.Assert.fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    // ---- end to end: presenter + the real runner, nothing secret on the wire ---------------------------------------------------

    @Test
    fun endToEnd_anEngineErrorCarryingKeyMaterial_isNeverOnTheWire_inAnyByte() = runTest {
        val secret = "sk-live-TOP-SECRET-0123456789"
        val fx = TreeFixture()
        val runner = WristTurnRunner(
            repository = fx.repository,
            resolver = { EngineResolution.Ready(cloudSpec(), FakeEngine(tokens("par"), failAfter = IllegalStateException("401: Incorrect API key provided: $secret"))) },
            appendLedger = { fx.ledger.append(it) },
        )
        val t = FakeTransport()
        WearRelayPresenter(runner, fx.tasks, LoopRunSource { emptyList() }, "0.3.1").onMessage(node, WatchPaths.ASK, askBytes("e2e", "my question"), t)

        val wire = String(t.everyByteOnTheWire(), Charsets.UTF_8)
        assertFalse("key material crossed the bridge", wire.contains(secret))
        assertFalse(wire.contains("Incorrect API key"))
        val frames = t.channels.single().decoded()
        assertEquals("par", (frames[0] as AskChunk).text)
        assertTrue((frames[0] as AskChunk).watchedObject)
        assertEquals(AskError("e2e", WristTurnRunner.CLOUD_FAILED_REASON), frames.last())
    }

    @Test
    fun endToEnd_aRelayedQuestionLandsInTheTreeAsANormalTurn_andTheWristGetsTheSameText() = runTest {
        val fx = TreeFixture()
        val runner = WristTurnRunner(
            repository = fx.repository,
            resolver = { EngineResolution.Ready(localSpec(), FakeEngine(tokens("It is ", "four."))) },
            appendLedger = { fx.ledger.append(it) },
        )
        val t = FakeTransport()
        WearRelayPresenter(runner, fx.tasks, LoopRunSource { emptyList() }, "0.3.1").onMessage(node, WatchPaths.ASK, askBytes("e2e", "2+2?"), t)

        val onWrist = t.channels.single().decoded().joinToString("") { (it as AskChunk).text }
        val inTree = fx.nodes().first { it.role == dev.fonebrew.domain.Role.ASSISTANT }.content
        assertEquals("It is four.", onWrist)
        assertEquals(onWrist, inTree)
        val terminal = t.channels.single().decoded().last() as AskChunk
        assertTrue(terminal.terminal)
        assertTrue("an on-device engine reports no usage of its own: estimated", terminal.usage!!.estimated)
        assertFalse(terminal.watchedObject)
    }
}
