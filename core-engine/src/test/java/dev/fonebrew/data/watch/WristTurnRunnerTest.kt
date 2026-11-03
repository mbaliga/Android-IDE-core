package dev.fonebrew.data.watch

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.ProviderKind
import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.cost.UsageReport
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Provenance
import dev.fonebrew.domain.ledger.Status
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.thread.MessageConfidence
import dev.fonebrew.inference.InferenceEngine
import dev.fonebrew.inference.cloud.OpenAiCompatEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The relay's engine bridge against fake DAOs and fake engines (plus, for the cloud shape, a real
 * [OpenAiCompatEngine] talking to a loopback SSE server so provider-reported usage is captured by the
 * production code, not by a stand-in). Every parity claim in [WristTurnRunner]'s KDoc is pinned here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WristTurnRunnerTest {

    private var idCounter = 0
    private var clockNow = 1_000L
    private val servers = mutableListOf<LoopbackServer>()

    @After
    fun stopServers() { servers.forEach { it.stop() } }

    private fun runner(
        fx: TreeFixture,
        spec: ModelSpec = localSpec(),
        engine: InferenceEngine = FakeEngine(tokens("Hel", "lo ", "there")),
        userNodes: MutableList<MessageNode> = mutableListOf(),
        free: MutableList<Triple<String, Long, Long>> = mutableListOf(),
        priceFor: (String) -> UsagePricing = { UsagePricing.ON_DEVICE },
        kv: KvSnapshots? = null,
        onUserNode: suspend (MessageNode) -> Unit = { userNodes += it },
        appendLedger: suspend (dev.fonebrew.domain.ledger.LedgerEntry) -> Unit = { fx.ledger.append(it) },
        keepAlive: ProcessKeepAlive = ProcessKeepAlive.NONE,
    ) = WristTurnRunner(
        repository = fx.repository,
        resolver = WristEngineResolver { EngineResolution.Ready(spec, engine) },
        appendLedger = appendLedger,
        onUserNode = onUserNode,
        priceFor = priceFor,
        recordFreeTier = { id, i, o -> free += Triple(id, i, o) },
        projectOf = { null },
        kv = kv,
        keepAlive = keepAlive,
        clock = { clockNow++ },
        idGen = { "n${idCounter++}" },
    )

    private suspend fun WristTurnRunner.collect(prompt: String): List<WristTurnEvent> {
        val events = mutableListOf<WristTurnEvent>()
        run(prompt) { events += it }
        return events
    }

    private suspend fun TreeFixture.ledgerEntries(): List<LedgerEntry> = ledger.entries().first()

    // ---- the happy path, on-device ----------------------------------------------------------------

    @Test
    fun anOnDeviceAnswer_isEngineThenTokensThenDone_notWatched_withEstimatedCounts() = runTest {
        val fx = TreeFixture()
        val events = runner(fx).collect("what is 2+2")

        assertEquals(WristTurnEvent.Engine("tiny", watchedObject = false), events.first())
        assertEquals(listOf("Hel", "lo ", "there"), events.filterIsInstance<WristTurnEvent.Token>().map { it.text })
        val done = events.last() as WristTurnEvent.Done
        val usage = done.usage as TurnUsage.Estimated
        assertNotNull("both tokenizer counts were produced", usage.inputTokens)
        assertNotNull(usage.outputTokens)
        assertEquals("grammar: Engine, Token*, Done", 1 + 3 + 1, events.size)
    }

    @Test
    fun theAnswer_isAppendedToTheTreeAsNormalTurns_underTheWristConversation_only() = runTest {
        val fx = TreeFixture()
        runner(fx).collect("what is 2+2")

        val nodes = fx.nodes()
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT), nodes.map { it.role })
        val (root, user, assistant) = nodes
        assertNull(root.parentId)
        assertEquals(root.id, user.parentId)
        assertEquals(user.id, assistant.parentId)
        assertEquals("what is 2+2", user.content)
        assertEquals("Hello there", assistant.content)
        assertEquals("local:tiny.gguf", assistant.modelId)
        assertNull("the user turn is not a model's", user.modelId)
        for (n in listOf(user, assistant)) assertEquals(WristConversation.VIA_WATCHBREW, n.metadata[WristConversation.VIA_KEY])
        assertEquals("a relayed turn mints no search/sources keys", emptySet<String>(), assistant.metadata.keys intersect setOf("webSearch", "sources", "searchPaused"))
    }

    @Test
    fun appendOnly_theRelayOnlyEverInsertsNewIds_neverUpdatesOrBackdates() = runTest {
        val fx = TreeFixture()
        val r = runner(fx)
        r.collect("one")
        val firstSnapshot = fx.nodes().associateBy { it.id }
        r.collect("two")
        val after = fx.nodes().associateBy { it.id }

        for ((id, node) in firstSnapshot) assertEquals("node $id was changed after insert", node, after[id])
        assertEquals(firstSnapshot.size + 2, after.size)
        assertEquals("no id was inserted twice", fx.nodeDao.insertOrder, fx.nodeDao.insertOrder.distinct())
        val times = fx.nodeDao.insertOrder.map { after.getValue(it).createdAt }
        assertEquals("nothing is back-dated", times.sorted(), times)
    }

    @Test
    fun aSecondQuestion_continuesTheSameConversation_andTheEngineSeesTheWholePath() = runTest {
        val fx = TreeFixture()
        val engine = FakeEngine(tokens("ok"))
        val r = runner(fx, engine = engine)
        r.collect("first")
        r.collect("second")

        val roots = fx.nodes().filter { it.parentId == null }
        assertEquals("one wrist conversation, not one per question", 1, roots.size)
        assertEquals(
            listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER),
            engine.generatedFrom!!.map { it.role },
        )
        assertEquals(listOf(WristConversation.ROOT_TEXT, "first", "ok", "second"), engine.generatedFrom!!.map { it.content })
    }

    @Test
    fun theEngineIsLoadedTheSameWayAChatTurnLoadsIt() = runTest {
        val fx = TreeFixture()
        val engine = FakeEngine(tokens("x"))
        runner(fx, spec = localSpec(), engine = engine).collect("q")
        assertEquals("/models/tiny.gguf", engine.loadedPath)
        assertEquals(4096, engine.loadedContext)
        assertEquals(1, engine.generateCalls)
    }

    @Test
    fun tokenCountsAreRecordedPerTokenizer_forBothTurns() = runTest {
        val fx = TreeFixture()
        runner(fx).collect("hello world")
        val user = fx.nodes().first { it.role == Role.USER }
        val assistant = fx.nodes().first { it.role == Role.ASSISTANT }
        assertEquals(setOf("llama.cpp:tiny"), fx.repository.tokenCounts(user.id).keys)
        assertEquals(setOf("llama.cpp:tiny"), fx.repository.tokenCounts(assistant.id).keys)
        assertTrue(fx.repository.totalTokens(assistant.id) > 0)
    }

    @Test
    fun theUserTurnIsEmbeddedForTheColdStartLog_once() = runTest {
        val fx = TreeFixture()
        val seen = mutableListOf<MessageNode>()
        runner(fx, userNodes = seen).collect("embed me")
        assertEquals(listOf("embed me"), seen.map { it.content })
        assertEquals(Role.USER, seen.single().role)
    }

    // ---- ledger -------------------------------------------------------------------------------------

    @Test
    fun oneLedgerEntryPerTurn_onDevice_costsNothing_andIsFlaggedEstimated() = runTest {
        val fx = TreeFixture()
        runner(fx).collect("q")
        val entry = fx.ledgerEntries().single()
        val assistant = fx.nodes().first { it.role == Role.ASSISTANT }
        val root = fx.nodes().first { it.parentId == null }
        assertEquals(Tier.ON_DEVICE, entry.tier)
        assertEquals(Provenance.LOCAL, entry.provenance)
        assertEquals("on-device", entry.provider)
        assertEquals("local:tiny.gguf", entry.model)
        assertEquals(0L, entry.estCostMinor)
        assertTrue("on-device counts approximate the templated prompt", entry.estimated)
        assertEquals(Status.COMPLETE, entry.status)
        assertEquals(assistant.id, entry.nodeId)
        assertEquals("filed under the wrist conversation's true root", root.id, entry.chatId)
        assertEquals("chat", entry.surface)
    }

    @Test
    fun anOnDeviceNonGgufModel_isStillOnDeviceInTheLedger() = runTest {
        // The phone chat records anything but a local GGUF as CLOUD tier (so on-device Gemini Nano
        // under-counts the sovereignty ratio). The relay follows ModelSpec.isOnDevice instead.
        for (runtime in listOf(Runtime.AICORE_NANO, Runtime.ECHO_DEV)) {
            val fx = TreeFixture()
            runner(fx, spec = localSpec(id = "x:$runtime", runtime = runtime)).collect("q")
            assertEquals(runtime.name, Tier.ON_DEVICE, fx.ledgerEntries().single().tier)
        }
    }

    @Test
    fun aFailingLedgerOrEmbedding_neverFailsTheAnswer() = runTest {
        val fx = TreeFixture()
        val events = runner(
            fx,
            onUserNode = { throw IllegalStateException("embedder down") },
            appendLedger = { throw IllegalStateException("ledger down") },
        ).collect("q")
        assertTrue(events.last() is WristTurnEvent.Done)
        assertEquals("the turn was still filed", 3, fx.nodes().size)
    }

    // ---- confidence, kv, keep-alive --------------------------------------------------------------------

    @Test
    fun confidenceIsMintedAtInsertFromRealEntropies_andAbsentWhenThereAreNone() = runTest {
        val withEntropy = TreeFixture()
        runner(withEntropy, engine = FakeEngine(listOf(GeneratedToken("a", entropy = 0.5f), GeneratedToken("b", entropy = 1.5f)))).collect("q")
        assertNotNull(withEntropy.nodes().first { it.role == Role.ASSISTANT }.metadata[MessageConfidence.METADATA_KEY])

        val without = TreeFixture()
        runner(without).collect("q")
        assertNull("never a faked 0", without.nodes().first { it.role == Role.ASSISTANT }.metadata[MessageConfidence.METADATA_KEY])
    }

    @Test
    fun aLocalGguf_resumesFromAndSavesToTheKvSnapshots_otherRuntimesPassNone() = runTest {
        val kv = KvSnapshots(loadPathFor = { if (it == "root-has-snapshot") "/kv/$it" else null }, savePathFor = { "/kv/save-$it" })

        val local = TreeFixture()
        val engine = FakeEngine(tokens("x"))
        runner(local, spec = localSpec(), engine = engine, kv = kv).collect("q")
        assertNull("no snapshot for the wrist root", engine.sessionLoad)
        assertTrue("saves to a path keyed by the new assistant node", engine.sessionSave!!.startsWith("/kv/save-n"))

        val echo = TreeFixture()
        val echoEngine = FakeEngine(tokens("x"))
        runner(echo, spec = localSpec(id = "echo", runtime = Runtime.ECHO_DEV), engine = echoEngine, kv = kv).collect("q")
        assertNull(echoEngine.sessionLoad)
        assertNull(echoEngine.sessionSave)
    }

    @Test
    fun keepAlive_isHeldForTheWholeGeneration_andReleasedEvenOnFailure() = runTest {
        val log = mutableListOf<String>()
        val hold = ProcessKeepAlive { log += "hold"; AutoCloseable { log += "release" } }

        val ok = TreeFixture()
        runner(ok, engine = object : FakeEngine(tokens("a")) {
            override fun generate(messages: List<MessageNode>, params: dev.fonebrew.domain.SamplingParams, sessionLoadPath: String?, sessionSavePath: String?): Flow<GeneratedToken> =
                super.generate(messages, params, sessionLoadPath, sessionSavePath).onCompletion { log += "generated" }
        }, keepAlive = hold).collect("q")
        assertEquals(listOf("hold", "generated", "release"), log)

        log.clear()
        runner(TreeFixture(), engine = FakeEngine(failAfter = IllegalStateException("x")), keepAlive = hold).collect("q")
        assertEquals(listOf("hold", "release"), log)
    }

    // ---- unavailable / save failures -------------------------------------------------------------------

    @Test
    fun noRunnableModel_isOneFailedEvent_andNothingTouchesTheTree() = runTest {
        val fx = TreeFixture()
        val r = WristTurnRunner(fx.repository, { EngineResolution.Unavailable(WristEngineResolver.NO_MODEL_REASON) }, { fx.ledger.append(it) })
        val events = mutableListOf<WristTurnEvent>()
        r.run("q") { events += it }
        assertEquals(listOf<WristTurnEvent>(WristTurnEvent.Failed(WristEngineResolver.NO_MODEL_REASON)), events)
        assertTrue("not even the wrist root is created for a question that never ran", fx.nodes().isEmpty())
        assertTrue(fx.ledgerEntries().isEmpty())
    }

    @Test
    fun ifTheQuestionCannotBeSaved_itIsNotRun() = runTest {
        val fx = TreeFixture()
        fx.nodeDao.failInserts = true
        val engine = FakeEngine(tokens("never"))
        val events = runner(fx, engine = engine).collect("q")
        assertEquals(WristTurnEvent.Failed(WristTurnRunner.SAVE_FAILED_REASON), events.last())
        assertEquals(0, engine.generateCalls)
    }

    @Test
    fun aReplyThatCannotBeSaved_isReportedAsUnsent_notAsFinished() = runTest {
        val fx = TreeFixture()
        val engine = object : FakeEngine(tokens("answer")) {
            override fun generate(messages: List<MessageNode>, params: dev.fonebrew.domain.SamplingParams, sessionLoadPath: String?, sessionSavePath: String?): Flow<GeneratedToken> =
                super.generate(messages, params, sessionLoadPath, sessionSavePath).onCompletion { fx.nodeDao.failInserts = true }
        }
        val events = runner(fx, engine = engine).collect("q")
        assertEquals(WristTurnEvent.Failed(WristTurnRunner.REPLY_NOT_SAVED_REASON), events.last())
        assertFalse(events.any { it is WristTurnEvent.Done })
        assertTrue("no ledger row for a reply that is not in the tree", fx.ledgerEntries().isEmpty())
    }

    // ---- engine failures never leak ----------------------------------------------------------------------

    @Test
    fun aLoadFailure_isAFixedSentence_neverTheExceptionMessage_andNoAssistantNode() = runTest {
        val fx = TreeFixture()
        val secret = "Incorrect API key provided: sk-live-ABCDEF123456"
        val events = runner(fx, engine = FakeEngine(failLoad = IllegalStateException(secret))).collect("q")
        assertEquals(WristTurnEvent.Failed(WristTurnRunner.ON_DEVICE_FAILED_REASON), events.last())
        assertFalse(events.toString().contains("sk-live"))
        assertEquals(listOf(Role.SYSTEM, Role.USER), fx.nodes().map { it.role })
        assertTrue(fx.ledgerEntries().isEmpty())
    }

    @Test
    fun aMidStreamFailure_keepsWhatTheWristAlreadySaw_asAStoppedTurn_andSaysSoWithAFixedSentence() = runTest {
        val fx = TreeFixture()
        val secret = "401 {\"error\":\"bad key sk-live-ZZZ\"}"
        val events = runner(fx, engine = FakeEngine(tokens("par", "tial"), failAfter = IllegalStateException(secret))).collect("q")

        assertEquals(listOf("par", "tial"), events.filterIsInstance<WristTurnEvent.Token>().map { it.text })
        assertEquals(WristTurnEvent.Failed(WristTurnRunner.ON_DEVICE_FAILED_REASON), events.last())
        assertFalse(events.toString().contains("sk-live"))

        val assistant = fx.nodes().first { it.role == Role.ASSISTANT }
        assertEquals("partial", assistant.content)
        assertEquals("true", assistant.metadata["stopped"])
        assertEquals(Status.ERROR, fx.ledgerEntries().single().status)
    }

    // ---- the wrist link goes away --------------------------------------------------------------------------

    @Test
    fun ifTheLinkDropsMidStream_generationStops_thePartialIsFiledAsStopped_andTheFailureIsRethrown() = runTest {
        val fx = TreeFixture()
        val engine = FakeEngine(tokens("one ", "two ", "three ", "four ", "five"))
        var seen = 0
        try {
            runner(fx, engine = engine).run("q") { e ->
                if (e is WristTurnEvent.Token && ++seen == 2) throw IOException("link lost")
            }
            fail("the link failure must reach the caller")
        } catch (e: IOException) {
            assertEquals("link lost", e.message)
        }
        assertEquals("the engine was not drained after the link died", 2, engine.emitted)

        val assistant = fx.nodes().first { it.role == Role.ASSISTANT }
        assertEquals("one two", assistant.content)
        assertEquals("true", assistant.metadata["stopped"])
        assertEquals(Status.STOPPED, fx.ledgerEntries().single().status)
    }

    @Test
    fun aLinkThatDiesOnTheVeryFirstToken_stillFilesThatRealToken() = runTest {
        val fx = TreeFixture()
        try {
            runner(fx, engine = FakeEngine(tokens("a"))).run("q") { e -> if (e is WristTurnEvent.Token) throw IOException("gone") }
            fail("expected the IOException")
        } catch (e: IOException) { /* expected */ }
        // "a" was really produced before the sink died on it, so it is filed (and nothing is invented beyond it).
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT), fx.nodes().map { it.role })
    }

    @Test
    fun theLinkDyingOnTheEngineAnnouncement_runsNothing_andFilesNothing() = runTest {
        val fx = TreeFixture()
        val engine = FakeEngine(tokens("a"))
        try {
            runner(fx, engine = engine).run("q") { e -> if (e is WristTurnEvent.Engine) throw IOException("gone") }
            fail("expected the IOException")
        } catch (e: IOException) { /* expected */ }
        assertEquals(0, engine.generateCalls)
        assertTrue(fx.nodes().isEmpty())
    }

    @Test
    fun cancellation_stillFilesThePartial_thenPropagates() = runTest {
        val fx = TreeFixture()
        val started = CompletableDeferred<Unit>()
        val hanging = object : FakeEngine() {
            override fun generate(messages: List<MessageNode>, params: dev.fonebrew.domain.SamplingParams, sessionLoadPath: String?, sessionSavePath: String?): Flow<GeneratedToken> = flow {
                emit(GeneratedToken("half an ans"))
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { runner(fx, engine = hanging).run("q") { } }
        started.await()
        advanceUntilIdle()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        val assistant = fx.nodes().first { it.role == Role.ASSISTANT }
        assertEquals("half an ans", assistant.content)
        assertEquals("true", assistant.metadata["stopped"])
        assertEquals(Status.STOPPED, fx.ledgerEntries().single().status)
    }

    // ---- honest usage ----------------------------------------------------------------------------------------

    @Test
    fun whenTheTokenizerFails_usageIsUnknown_notZero_onTheWire_whileTheLedgerKeepsItsSchemasZero() = runTest {
        val fx = TreeFixture()
        val events = runner(fx, engine = FakeEngine(tokens("x"), countFails = true)).collect("q")
        assertEquals(TurnUsage.Unknown, (events.last() as WristTurnEvent.Done).usage)
        assertEquals(0L, fx.ledgerEntries().single().outputTokens) // schema has no null; the wire does
    }

    @Test
    fun theWireUsageMapping_keepsTheThreeCasesDistinct() {
        assertEquals(dev.fonebrew.watchbridge.Usage.engineReported(5, 6), TurnUsage.EngineReported(5, 6).toWire())
        assertEquals(dev.fonebrew.watchbridge.Usage.estimate(5, null), TurnUsage.Estimated(5, null).toWire())
        assertEquals(dev.fonebrew.watchbridge.Usage.unknown(), TurnUsage.Unknown.toWire())
        assertTrue(TurnUsage.Estimated(1, 2).toWire().estimated)
        assertFalse(TurnUsage.EngineReported(1, 2).toWire().estimated)
    }

    // ---- the cloud shape (real engine, loopback provider) ---------------------------------------------------------

    private fun provider(port: Int) = CloudProvider(
        id = "acme", displayName = "Acme", kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "http://127.0.0.1:$port/v1", model = "big-model", contextWindow = 8192,
    )

    private fun serve(status: Int, contentType: String, body: String, hits: AtomicInteger = AtomicInteger()): Int {
        val server = LoopbackServer(status, contentType, body, hits)
        servers += server
        return server.start()
    }

    private val sseWithUsage =
        "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":2000}}\n\n" +
            "data: [DONE]\n\n"

    @Test
    fun aCloudAnswer_isWatched_carriesProviderReportedUsage_isPriced_andCountsAgainstTheFreeTier() = runBlocking {
        val port = serve(200, "text/event-stream", sseWithUsage)
        val fx = TreeFixture()
        val free = mutableListOf<Triple<String, Long, Long>>()
        val pricing = UsagePricing(centsPer1kInput = 10, centsPer1kOutput = 20)
        val expectedCost = UsageReport(1000, 2000).toAdviceCost(pricing).moneyMinor
        assertTrue(expectedCost > 0)

        val events = runner(
            fx, spec = cloudSpec(), engine = OpenAiCompatEngine(provider(port), apiKey = "test-key-NOT-ON-THE-WIRE"),
            free = free, priceFor = { id -> if (id == "cloud:big-model") pricing else UsagePricing.ON_DEVICE },
        ).collect("q")

        assertEquals("the watched-object fact, with the cloud marker dropped from the label", WristTurnEvent.Engine("Acme · big-model", watchedObject = true), events.first())
        assertEquals("Hello", events.filterIsInstance<WristTurnEvent.Token>().joinToString("") { it.text })
        assertEquals(TurnUsage.EngineReported(1000, 2000), (events.last() as WristTurnEvent.Done).usage)
        assertFalse((events.last() as WristTurnEvent.Done).usage.toWire().estimated)

        val assistant = fx.nodes().first { it.role == Role.ASSISTANT }
        assertEquals("cloud:acme", assistant.modelId)
        assertEquals(expectedCost.toString(), assistant.metadata["costMinor"])
        assertEquals("1000", assistant.metadata["tokensIn"])
        assertEquals("2000", assistant.metadata["tokensOut"])
        assertEquals(listOf(Triple("acme", 1000L, 2000L)), free)

        val entry = fx.ledgerEntries().single()
        assertEquals(Tier.CLOUD, entry.tier)
        assertEquals(Provenance.CLOUD, entry.provenance)
        assertEquals("acme", entry.provider)
        assertEquals(expectedCost, entry.estCostMinor)
        assertFalse("provider-reported usage is not an estimate", entry.estimated)
        assertEquals(1000L, entry.inputTokens)
        assertEquals(2000L, entry.outputTokens)
    }

    @Test
    fun aCloudAnswerWithNoReportedUsage_isEstimated_notPassedOffAsReported() = runBlocking {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"twelve chars\"}}]}\n\ndata: [DONE]\n\n"
        val port = serve(200, "text/event-stream", body)
        val fx = TreeFixture()
        val events = runner(fx, spec = cloudSpec(), engine = OpenAiCompatEngine(provider(port), "k")).collect("q")
        val usage = (events.last() as WristTurnEvent.Done).usage
        assertTrue("no provider usage: must be flagged estimated", usage is TurnUsage.Estimated)
        assertTrue(usage.toWire().estimated)
        assertNull(fx.nodes().first { it.role == Role.ASSISTANT }.metadata["costMinor"])
        assertTrue(fx.ledgerEntries().single().estimated)
    }

    @Test
    fun aCloudProviderError_thatEchoesKeyMaterial_neverReachesTheWrist() = runBlocking {
        val leaked = "sk-live-9f8e7d6c5b4a"
        val port = serve(401, "application/json", "{\"error\":{\"message\":\"Incorrect API key provided: $leaked\"}}")
        val fx = TreeFixture()
        val events = runner(fx, spec = cloudSpec(), engine = OpenAiCompatEngine(provider(port), apiKey = leaked)).collect("q")

        assertEquals(WristTurnEvent.Failed(WristTurnRunner.CLOUD_FAILED_REASON), events.last())
        assertFalse("key material in an event: $events", events.toString().contains(leaked))
        assertFalse(events.toString().contains("Incorrect API key"))
        assertEquals(listOf(Role.SYSTEM, Role.USER), fx.nodes().map { it.role })
    }

    @Test
    fun theEngineLabel_dropsOnlyTheLeadingCloudMarker() {
        assertEquals("Acme · big-model", WristTurnRunner.engineLabel(cloudSpec()))
        assertEquals("tiny", WristTurnRunner.engineLabel(localSpec()))
        assertEquals("only-id", WristTurnRunner.engineLabel(localSpec(id = "only-id", name = "  ")))
        assertEquals(80, WristTurnRunner.engineLabel(localSpec(name = "n".repeat(300))).length)
    }

    @Test
    fun watchedObject_followsTheSpec_notTheRuntimeAlone() = runTest {
        val flaggedWatched = localSpec(id = "x", name = "x").copy(watched = true)
        val events = runner(TreeFixture(), spec = flaggedWatched).collect("q")
        assertTrue((events.first() as WristTurnEvent.Engine).watchedObject)
        val plain = runner(TreeFixture(), spec = localSpec()).collect("q")
        assertFalse((plain.first() as WristTurnEvent.Engine).watchedObject)
    }
}

/**
 * A one-purpose HTTP responder on the loopback interface: answers every request with a fixed status,
 * content type and body, then closes. Plain sockets (not the JDK's httpserver module, which an Android
 * module's test compile classpath does not see). It exists so the cloud tests drive the PRODUCTION
 * OpenAiCompatEngine's SSE parsing and usage capture rather than a stand-in.
 */
private class LoopbackServer(
    private val status: Int,
    private val contentType: String,
    private val body: String,
    private val hits: AtomicInteger,
) {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    @Volatile private var running = true

    fun start(): Int {
        Thread({
            while (running) {
                val client = try { socket.accept() } catch (e: Exception) { return@Thread }
                Thread({
                    client.use { c ->
                        try {
                            val input = c.getInputStream()
                            val head = ByteArrayOutputStream()
                            // Read up to the blank line that ends the request headers.
                            var last4 = 0
                            while (true) {
                                val b = input.read()
                                if (b < 0) break
                                head.write(b)
                                last4 = (last4 shl 8) or b
                                if (last4 == 0x0D0A0D0A) break
                            }
                            val length = Regex("(?i)content-length:\\s*(\\d+)").find(head.toString(Charsets.ISO_8859_1.name()))?.groupValues?.get(1)?.toInt() ?: 0
                            var remaining = length
                            while (remaining > 0) { if (input.read() < 0) break; remaining-- }
                            hits.incrementAndGet()
                            val payload = body.toByteArray(Charsets.UTF_8)
                            val reason = if (status == 200) "OK" else "Error"
                            val header = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                            val out = c.getOutputStream()
                            out.write(header.toByteArray(Charsets.ISO_8859_1))
                            out.write(payload)
                            out.flush()
                        } catch (e: Exception) {
                            // a dropped client is not a test failure
                        }
                    }
                }, "loopback-conn").apply { isDaemon = true }.start()
            }
        }, "loopback-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }
}
