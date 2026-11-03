package dev.fonebrew.data.watch

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.domain.tree.TreeFork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WristConversationTest {

    private var next = 0
    private val ids: () -> String = { "id-${next++}" }

    @Test
    fun firstUse_createsTheNamedRoot_exactlyOnce_asAParentlessSystemNode() = runTest {
        val fx = TreeFixture()
        val tip = WristConversation.tipForNextTurn(fx.repository, now = 100, idGen = ids)

        assertNull(tip.parentId)
        assertEquals(Role.SYSTEM, tip.role)
        assertEquals(WristConversation.ROOT_TEXT, tip.content)
        assertEquals("true", tip.metadata[WristConversation.ROOT_KEY])
        assertEquals(WristConversation.TITLE, tip.metadata[Conversations.TITLE_KEY])
        assertEquals(WristConversation.VIA_WATCHBREW, tip.metadata[WristConversation.VIA_KEY])
        assertEquals(1, fx.nodeDao.insertOrder.size)
    }

    @Test
    fun laterUse_reusesTheRoot_andNeverInsertsAgain() = runTest {
        val fx = TreeFixture()
        val first = WristConversation.tipForNextTurn(fx.repository, 100, ids)
        val second = WristConversation.tipForNextTurn(fx.repository, 200, ids)
        assertEquals(first.id, second.id)
        assertEquals("a second use must not mint a second root", 1, fx.nodeDao.insertOrder.size)
    }

    @Test
    fun theTip_followsTheNewestBranch_likeOpeningAConversationDoes() = runTest {
        val fx = TreeFixture()
        val root = WristConversation.tipForNextTurn(fx.repository, 100, ids)
        val older = MessageNode("older", root.id, Role.USER, "old branch", createdAt = 110)
        val newer = MessageNode("newer", root.id, Role.USER, "new branch", createdAt = 120)
        fx.repository.insert(older)
        fx.repository.insert(newer)
        assertEquals("newer", WristConversation.tipForNextTurn(fx.repository, 200, ids).id)
    }

    @Test
    fun aConversationTheUserContinuedOnThePhone_isContinuedFromItsTip() = runTest {
        val fx = TreeFixture()
        val root = WristConversation.tipForNextTurn(fx.repository, 100, ids)
        val u = MessageNode("phone-user", root.id, Role.USER, "typed on the phone", createdAt = 110)
        val a = MessageNode("phone-reply", u.id, Role.ASSISTANT, "answered", createdAt = 120)
        fx.repository.insert(u); fx.repository.insert(a)
        assertEquals("phone-reply", WristConversation.tipForNextTurn(fx.repository, 200, ids).id)
    }

    @Test
    fun aForkedCopyOfTheWristConversation_isNeverTheRelaysTarget() {
        val original = MessageNode(
            "orig", null, Role.SYSTEM, WristConversation.ROOT_TEXT, createdAt = 100,
            metadata = mapOf(WristConversation.ROOT_KEY to "true"),
        )
        val fork = original.copy(
            id = "fork", createdAt = 100, // a fork preserves createdAt: a tie on time must not hand the relay the copy
            metadata = original.metadata + (TreeFork.LINEAGE_KIND_KEY to "FORK"),
        )
        assertEquals("orig", WristConversation.rootIn(MessageTree(listOf(fork, original)))!!.id)
        assertEquals("orig", WristConversation.rootIn(MessageTree(listOf(original, fork)))!!.id)
        assertNull("a fork alone is not a wrist conversation", WristConversation.rootIn(MessageTree(listOf(fork))))
    }

    @Test
    fun ordinaryRoots_areNeverMistakenForTheWristConversation() {
        val chat = MessageNode("c", null, Role.USER, "hi", createdAt = 1)
        assertNull(WristConversation.rootIn(MessageTree(listOf(chat))))
    }

    @Test
    fun theConversationsList_namesItByTitle_notByItsFirstQuestion() = runTest {
        val fx = TreeFixture()
        val root = WristConversation.tipForNextTurn(fx.repository, 100, ids)
        fx.repository.insert(MessageNode("q", root.id, Role.USER, "what is the weather like on my wrist today", createdAt = 110))
        val summary = Conversations.summarize(fx.repository.tree()).single()
        assertEquals(WristConversation.TITLE, summary.title)
    }
}

class ConversationsTitleKeyTest {

    @Test
    fun aRootWithoutTheKey_isTitledByItsOpeningAsk_exactlyAsBefore() {
        val root = MessageNode("r", null, Role.USER, "one two three four five six seven eight nine ten", createdAt = 1)
        val title = Conversations.summarize(MessageTree(listOf(root))).single().title
        assertEquals("one two three four five six seven eight…", title)
    }

    @Test
    fun aRootThatNamedItselfAtInsertTime_winsOverItsFirstUserTurn() {
        val root = MessageNode("r", null, Role.SYSTEM, "ctx", createdAt = 1, metadata = mapOf(Conversations.TITLE_KEY to "  My name  "))
        val turn = MessageNode("u", "r", Role.USER, "hello there", createdAt = 2)
        assertEquals("My name", Conversations.summarize(MessageTree(listOf(root, turn))).single().title)
    }

    @Test
    fun aBlankTitleKey_fallsBackToTheOpeningAsk() {
        val root = MessageNode("r", null, Role.USER, "real ask", createdAt = 1, metadata = mapOf(Conversations.TITLE_KEY to "   "))
        assertEquals("real ask", Conversations.summarize(MessageTree(listOf(root))).single().title)
    }

    @Test
    fun theKeyOnANonRootNode_isIgnored() {
        val root = MessageNode("r", null, Role.USER, "the real title", createdAt = 1)
        val child = MessageNode("c", "r", Role.ASSISTANT, "x", createdAt = 2, metadata = mapOf(Conversations.TITLE_KEY to "sneaky"))
        assertEquals("the real title", Conversations.summarize(MessageTree(listOf(root, child))).single().title)
    }
}

class WristEngineResolverTest {

    private val localA = localSpec(id = "local:a.gguf", name = "a")
    private val localB = localSpec(id = "local:b.gguf", name = "b")
    private val echo = localSpec(id = "echo-chatml", name = "echo", runtime = Runtime.ECHO_DEV)
    private val cloud = cloudSpec()
    private val engine = FakeEngine()

    private fun resolver(
        specs: List<ModelSpec>, persisted: String?, engineFor: (ModelSpec) -> FakeEngine? = { engine },
        reason: (ModelSpec) -> String? = { null },
    ) = WristEngineResolver.onDevice({ specs }, { persisted }, engineFor, reason)

    @Test
    fun theChosenOnDeviceModel_isTheOneTheEngineFactoryIsAskedFor() {
        val ready = resolver(listOf(localA, localB), persisted = "local:b.gguf").resolve() as EngineResolution.Ready
        assertEquals(localB, ready.spec)
        assertSame(engine, ready.engine)
    }

    @Test
    fun aPersistedCloudModel_isNeverUsed_cloudIsNotAnAmbientDefault() {
        // The phone's own rule (DefaultModelPolicy): a cloud model left selected is not silently resumed.
        val ready = resolver(listOf(cloud, localA), persisted = cloud.id).resolve() as EngineResolution.Ready
        assertEquals(localA, ready.spec)
    }

    @Test
    fun cloudOnly_meansNoAnswer_notACloudFallback() {
        val r = resolver(listOf(cloud), persisted = cloud.id, engineFor = { error("engineFor must not even be called for cloud") }).resolve()
        assertEquals(EngineResolution.Unavailable(WristEngineResolver.NO_MODEL_REASON), r)
    }

    @Test
    fun noModelAtAll_isAFixedLegibleReason() {
        assertEquals(EngineResolution.Unavailable(WristEngineResolver.NO_MODEL_REASON), resolver(emptyList(), null).resolve())
    }

    @Test
    fun aModelThatCannotRun_carriesThePhonesOwnReason() {
        val r = resolver(listOf(localA), null, engineFor = { null }, reason = { "native engine unavailable on this device" }).resolve()
        assertTrue(r is EngineResolution.Unavailable)
        assertTrue((r as EngineResolution.Unavailable).reason.contains("native engine unavailable"))
    }

    @Test
    fun aModelThatCannotRunWithoutAReason_getsTheGenericSentence() {
        val r = resolver(listOf(localA), null, engineFor = { null }).resolve()
        assertEquals(EngineResolution.Unavailable(WristEngineResolver.NOT_RUNNABLE_REASON), r)
    }

    @Test
    fun inputsAreReadAtResolveTime_soADownloadOrSwitchIsSeen() {
        var specs = emptyList<ModelSpec>()
        val r = WristEngineResolver.onDevice({ specs }, { null }, { engine }, { null })
        assertTrue(r.resolve() is EngineResolution.Unavailable)
        specs = listOf(localA)
        assertTrue(r.resolve() is EngineResolution.Ready)
    }

    @Test
    fun echoDevSpecs_resolveWhenNothingElseIs() {
        assertEquals(echo, (resolver(listOf(echo), null).resolve() as EngineResolution.Ready).spec)
    }

    @Test
    fun theKeepAliveBoost_isForAResidentLocalModelOnly_likeThePhoneChat() {
        assertTrue(ProcessKeepAlive.needsBoost(localA))
        assertTrue(!ProcessKeepAlive.needsBoost(echo))
        assertTrue(!ProcessKeepAlive.needsBoost(cloud))
        ProcessKeepAlive.NONE.hold(localA).close() // harmless no-op
    }
}
