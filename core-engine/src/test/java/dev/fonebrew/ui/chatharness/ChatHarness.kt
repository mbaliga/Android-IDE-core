package dev.fonebrew.ui.chatharness

import dev.fonebrew.data.AttachmentStore
import dev.fonebrew.data.CouncilStore
import dev.fonebrew.data.CurationStore
import dev.fonebrew.data.DelegationRecorder
import dev.fonebrew.data.DelegationStore
import dev.fonebrew.data.DownloadCenter
import dev.fonebrew.data.FreeTierUsageStore
import dev.fonebrew.data.ImageProviderStore
import dev.fonebrew.data.ImageStore
import dev.fonebrew.data.KvCacheStore
import dev.fonebrew.data.LedgerStore
import dev.fonebrew.data.LocalModelStore
import dev.fonebrew.data.MessageTreeRepository
import dev.fonebrew.data.ModelCatalogStore
import dev.fonebrew.data.ModelDownloader
import dev.fonebrew.data.Object3dProviderStore
import dev.fonebrew.data.Object3dStore
import dev.fonebrew.data.PricingStore
import dev.fonebrew.data.ProviderStore
import dev.fonebrew.data.ReceiptStore
import dev.fonebrew.data.SdModelStore
import dev.fonebrew.data.SessionStore
import dev.fonebrew.data.SharedIntake
import dev.fonebrew.data.TaskStore
import dev.fonebrew.data.ThreadMarkerStore
import dev.fonebrew.data.ThreadObserver
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.device.DeviceSpec
import dev.fonebrew.domain.mirror.AarsoCaptureSettings
import dev.fonebrew.domain.mirror.AarsoEventLog
import dev.fonebrew.domain.mirror.AarsoEventSink
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import dev.fonebrew.embedding.Embedder
import dev.fonebrew.embedding.EmbeddingLogger
import dev.fonebrew.inference.ModelRegistry
import dev.fonebrew.ui.ChatUiState
import dev.fonebrew.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Installs a [StandardTestDispatcher] as `Dispatchers.Main` (what `viewModelScope` runs on) and removes it after. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}

internal val ECHO_A = ModelSpec(
    id = "echo-a", displayName = "Echo A (dev)", family = "echo", contextWindow = 8192,
    tokenizerId = "echo:a", templateId = TemplateId.CHATML, runtime = Runtime.ECHO_DEV,
)
internal val ECHO_B = ModelSpec(
    id = "echo-b", displayName = "Echo B (dev)", family = "echo", contextWindow = 4096,
    tokenizerId = "echo:b", templateId = TemplateId.PLAIN, runtime = Runtime.ECHO_DEV,
)

/** A cloud-shaped spec exactly as [dev.fonebrew.inference.ModelRegistry] builds one: CLOUD runtime, watched. */
internal val CLOUD_SPEC = ModelSpec(
    id = "cloud:acme", displayName = "Acme big-model (cloud)", family = "openai_compatible",
    contextWindow = 8192, tokenizerId = "cloud:big-model", templateId = TemplateId.PLAIN,
    runtime = Runtime.CLOUD, providerId = "acme", watched = true,
)

private class FixedEmbedder : Embedder {
    override val id = "fixed"
    override val dim = 2
    override suspend fun embed(text: String): FloatArray = floatArrayOf(0f, 1f)
}

/**
 * Builds a REAL [ChatViewModel] on the JVM: real stores over an in-memory [FakeContext], fake DAOs
 * behind the real [MessageTreeRepository], a scripted engine behind the [dev.fonebrew.ui.ChatEngines]
 * seam. Nothing in the view-model under test is faked.
 */
internal class ChatHarness(
    specs: List<ModelSpec> = listOf(ECHO_A, ECHO_B, CLOUD_SPEC),
    val context: FakeContext = FakeContext(),
    val nodeDao: FakeNodeDao = FakeNodeDao(),
    private val ghostDao: FakeGhostDao = FakeGhostDao(),
) {
    val tokenDao = FakeTokenDao()
    val ledgerDao = FakeLedgerDao()
    val repository = MessageTreeRepository(nodeDao, tokenDao, FakeEmbedDao())
    val engines = FakeChatEngines()
    val echoA = ScriptedEngine(tokenizerId = "echo:a").also { engines.bySpecId[ECHO_A.id] = it }
    val echoB = ScriptedEngine(tokenizerId = "echo:b").also { engines.bySpecId[ECHO_B.id] = it }
    val cloud = ScriptedEngine(tokenizerId = "cloud:big-model").also { engines.bySpecId[CLOUD_SPEC.id] = it }

    private val providers = ProviderStore(context)
    private val locals = LocalModelStore(context)
    private val registry = ModelRegistry(providers, locals, devSpecs = specs)
    private val markerDao = FakeMarkerDao()
    private val delegationDao = FakeDelegationDao()
    private val delegationStore = DelegationStore(delegationDao)
    private val markerStore = ThreadMarkerStore(markerDao)
    private val eventLog = AarsoEventLog(AarsoEventSink { }, { AarsoCaptureSettings.OFF })

    var session = SessionStore(context)
        private set

    /** Build a ChatViewModel over this harness's shared state; call again to simulate a relaunch. */
    fun newViewModel(): ChatViewModel {
        session = SessionStore(context) // re-read the persisted prefs, like a cold start
        return ChatViewModel(
            repository, registry, engines, providers, locals,
            EmbeddingLogger(FixedEmbedder(), repository),
            KvCacheStore(context), SharedIntake(), session,
            DownloadCenter(context), ModelDownloader(context.filesDir) {},
            ImageStore(context), AttachmentStore(context), ImageProviderStore(context),
            Object3dStore(context), Object3dProviderStore(context), SdModelStore(context),
            PricingStore(context), FreeTierUsageStore(context), CouncilStore(context),
            LedgerStore(ledgerDao), ModelCatalogStore(context),
            CurationStore(
                FakeVerdictDao(), FakeBookmarkDao(), FakeVersionDao(),
                FakeDirectiveDao(), ghostDao, FakeFormStateDao(),
            ),
            markerStore, delegationStore, DelegationRecorder(delegationStore, eventLog),
            ReceiptStore(FakeReceiptDao()), eventLog,
            ThreadObserver(repository, markerStore, delegationStore) { false },
            TaskStore(FakeTaskDao()), context,
            deviceSpecReader = { DeviceSpec(totalRamBytes = 8L shl 30, availRamBytes = 4L shl 30, abis = listOf("arm64-v8a")) },
        )
    }

    /** Pre-load nodes with explicit `createdAt`s, for tests that need a tree whose branch order does not depend on the wall clock. */
    suspend fun seed(vararg nodes: MessageNode) = nodes.forEach { repository.insert(it) }

    val ghostedTips: Set<String> get() = ghostDao.rows.value.keys

    /** The whole persisted tree, oldest first (insertion order — the append-only log). */
    suspend fun nodes(): List<MessageNode> {
        val byId = repository.tree().allNodes().associateBy { it.id }
        return nodeDao.insertOrder.map { byId.getValue(it) }
    }
}

/** Keeps `uiState` hot (it is `WhileSubscribed`) for the length of the test, on the shared scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.observe(vm: ChatViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
}

/** Run everything the view-model has scheduled until nothing more can progress (an engine parked on its gate counts as idle). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() = advanceUntilIdle()

internal val ChatUiState.pathContents: List<String> get() = steps.map { it.node.content }
internal fun List<MessageNode>.ofRole(role: Role) = filter { it.role == role }

internal fun seedNode(id: String, parent: String?, role: Role, content: String, at: Long, modelId: String? = null) =
    MessageNode(id = id, parentId = parent, role = role, content = content, modelId = modelId, createdAt = at)
