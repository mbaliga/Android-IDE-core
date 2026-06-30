package dev.aarso.di

import android.content.Context
import androidx.room.Room
import dev.aarso.BuildConfig
import dev.aarso.data.AppDatabase
import dev.aarso.data.DownloadCenter
import dev.aarso.data.ImageProviderStore
import dev.aarso.data.ImageStore
import dev.aarso.data.KvCacheStore
import dev.aarso.data.LocalModelStore
import dev.aarso.data.MessageTreeRepository
import dev.aarso.data.ModelDownloader
import dev.aarso.data.ProviderStore
import dev.aarso.data.SdModelStore
import dev.aarso.data.SessionStore
import dev.aarso.data.ApkInstaller
import dev.aarso.data.BuildsRepo
import dev.aarso.data.SharedIntake
import dev.aarso.embedding.Embedder
import dev.aarso.embedding.EmbeddingLogger
import dev.aarso.embedding.PlaceholderEmbedder
import dev.aarso.inference.EchoInferenceEngine
import dev.aarso.inference.EngineProvider
import dev.aarso.inference.InferenceEngine
import dev.aarso.inference.ModelRegistry
import dev.aarso.inference.echoDevSpecs

/**
 * Manual, constructor-based dependency wiring — deliberately no annotation-
 * processor DI framework (Hilt/Dagger). The legibility thesis (handoff §0)
 * favours wiring you can read top-to-bottom over generated magic, and it keeps
 * the build surface small.
 *
 * Single instance held by [dev.aarso.AarsoApp].
 */
class AppContainer(context: Context) {

    private val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        AppDatabase.NAME,
    ).fallbackToDestructiveMigration().build()

    val repository: MessageTreeRepository = MessageTreeRepository(
        nodes = database.messageNodeDao(),
        tokenCounts = database.tokenCountDao(),
        embeddings = database.embeddingDao(),
    )

    /** Usage ledger (Doc 01 §10 / Doc 07): on-device, append-only; backs the Myself views.
     *  Implements [dev.aarso.ui.state.LedgerSource]; capture writer hooks in later. */
    val ledgerStore: dev.aarso.data.LedgerStore =
        dev.aarso.data.LedgerStore(database.ledgerDao())

    // Placeholder until the Phase 2 local embedder lands (§5b). Cold-start
    // logging is live regardless (§5c).
    val embedder: Embedder = PlaceholderEmbedder()

    val embeddingLogger: EmbeddingLogger = EmbeddingLogger(embedder, repository)

    /**
     * The echo stand-in runs the chat loop without the native library; it backs
     * every ECHO_DEV model in the registry. The real LlamaCppEngine takes over
     * for LOCAL_GGUF models once the native build is enabled.
     */
    private val echoEngine: InferenceEngine = EchoInferenceEngine()

    /** Cloud provider configs + encrypted API keys (Android Keystore). */
    val providerStore: ProviderStore = ProviderStore(context)

    /** Downloaded GGUF models on this device. */
    val localModelStore: LocalModelStore = LocalModelStore(context)

    val modelDownloader: ModelDownloader =
        ModelDownloader(localModelStore.dir) { localModelStore.refresh() }

    /** Process-wide downloads (LLM + SD): survive screen changes, feed the FGS notification. */
    val downloadCenter: DownloadCenter = DownloadCenter(context.applicationContext)

    /** KV-cache session snapshots for fast branch resume (§8.3). */
    val kvCacheStore: KvCacheStore = KvCacheStore(context)

    /** Image generation (§4c): saved images + watched image-provider configs. */
    val imageStore: ImageStore = ImageStore(context)
    val imageProviderStore: ImageProviderStore = ImageProviderStore(context)

    /** Content routed in from share / process-text / assist (§7). */
    val sharedIntake: SharedIntake = SharedIntake()

    /** Where the user was (leaf/model) + small UI prefs — survives process death. */
    val sessionStore: SessionStore = SessionStore(context)

    /** Conversations room source (Doc 02): folds the tree + session (stars/projects/opens) +
     *  ledger into the room's row model. Implements [dev.aarso.ui.state.ConversationsSource];
     *  consumed by a [dev.aarso.ui.state.ConversationsViewModel] once the room is mounted. */
    val conversationsSource: dev.aarso.data.ConversationsStore =
        dev.aarso.data.ConversationsStore(repository, sessionStore, ledgerStore)

    /** User-set per-model prices (Cost epic G1/P2). Reads price a finished cloud turn. */
    val pricingStore: dev.aarso.data.PricingStore = dev.aarso.data.PricingStore(context)

    /** Project-room notes (local-first, plain text). */
    val notesStore: dev.aarso.data.NotesStore = dev.aarso.data.NotesStore(context)
    val incidentsStore: dev.aarso.data.IncidentsStore = dev.aarso.data.IncidentsStore(context)
    val councilStore: dev.aarso.data.CouncilStore = dev.aarso.data.CouncilStore(context)

    /** The free-tier guide (bundled JSON, pipeline-refreshed) + per-provider free-tier usage. */
    val freeTierStore: dev.aarso.data.FreeTierStore = dev.aarso.data.FreeTierStore(context)
    val freeTierUsageStore: dev.aarso.data.FreeTierUsageStore = dev.aarso.data.FreeTierUsageStore(context)
    /** Consented online refresh of the free-tier guide (never automatic without opt-in). */
    val freeTierUpdater: dev.aarso.data.FreeTierUpdater = dev.aarso.data.FreeTierUpdater(context)

    /** Saved Loops (visual-editor definitions as BPMN + lifecycle envelope). */
    val loopStore: dev.aarso.data.LoopStore = dev.aarso.data.LoopStore(context)

    /** Connected Git hosts (watched) + their Keystore-encrypted tokens, and the
     *  thin REST transport that talks only to the user's host. */
    val gitHostStore: dev.aarso.data.GitHostStore = dev.aarso.data.GitHostStore(context)
    val gitTransport: dev.aarso.data.GitTransport = dev.aarso.data.GitTransport()
    val gitBackup: dev.aarso.data.GitBackup by lazy { dev.aarso.data.GitBackup(repository, gitTransport) }
    val gitBrowse: dev.aarso.data.GitBrowse = dev.aarso.data.GitBrowse(gitTransport)
    val buildsRepo: BuildsRepo = BuildsRepo(gitTransport, gitHostStore)
    /** Push/pull loop .bpmn files to the user's Git host (P6 made real). */
    val loopSyncRepo: dev.aarso.data.LoopSyncRepo =
        dev.aarso.data.LoopSyncRepo(gitTransport, gitHostStore, loopStore)
    val issueBoardRepo: dev.aarso.data.IssueBoardRepo =
        dev.aarso.data.IssueBoardRepo(gitTransport, gitHostStore)
    /** IDE last mile: create a repo + push a scaffold. Side-effectful — gate the UI. */
    val scaffoldPublishRepo: dev.aarso.data.ScaffoldPublishRepo =
        dev.aarso.data.ScaffoldPublishRepo(gitTransport, gitHostStore)
    val apkInstaller: ApkInstaller = ApkInstaller(context.applicationContext)

    /** SSH remotes (the remote-exec spine): host configs + trust pins + Keystore-encrypted
     *  keys, and a factory for the sshj transport that talks only to the user's machines
     *  (runtime owner-verified — no SSH server in CI). */
    val remoteHostStore: dev.aarso.data.RemoteHostStore = dev.aarso.data.RemoteHostStore(context)
    fun newSshTransport(): dev.aarso.domain.remote.RemoteTransport =
        dev.aarso.data.remote.SshjTransport(secretProvider = { remoteHostStore.secret(it) })

    /** Durable, retrying queue for network journeys so they survive the subway (P5). The worker
     *  drains it against per-kind handlers; a permanent error (auth/no-host) parks the op for the
     *  user instead of retrying forever. */
    val operationQueueStore: dev.aarso.data.OperationQueueStore = dev.aarso.data.OperationQueueStore(context)
    val operationWorker: dev.aarso.domain.net.OperationWorker by lazy {
        dev.aarso.domain.net.OperationWorker(
            load = { operationQueueStore.queue.value },
            save = { operationQueueStore.set(it) },
            handlers = mapOf(
                "loop.push" to dev.aarso.domain.net.OpHandler { loopSyncRepo.push().map {} },
            ),
            isRetryable = { e ->
                val m = e.message ?: ""
                // Auth / config problems are permanent — don't hammer; park for the user.
                !(m.contains("401") || m.contains("403") || m.contains("connect a Git host") || m.contains("no token"))
            },
        )
    }

    /** On-device Stable Diffusion models (separate from LLM models) + downloader. */
    val sdModelStore: SdModelStore = SdModelStore(context)
    val sdModelDownloader: ModelDownloader =
        ModelDownloader(sdModelStore.dir) { sdModelStore.refresh() }

    // Echo stand-ins exist only in debug builds; release has no fake engine, and
    // "no model yet" is an explicit UI state rather than an echo default.
    val modelRegistry: ModelRegistry = ModelRegistry(
        providerStore,
        localModelStore,
        devSpecs = if (BuildConfig.DEBUG) echoDevSpecs() else emptyList(),
    )

    val engineProvider: EngineProvider = EngineProvider(echoEngine, providerStore)

    // Agentic repo loop (IA: agentic-ide #1): read repo → model proposes a ChangeSet → review → commit.
    val agentRepoRunner: dev.aarso.data.AgentRepoRunner =
        dev.aarso.data.AgentRepoRunner(gitTransport, gitHostStore, modelRegistry, engineProvider)

    // Device recipes over the SSH spine (IA: agentic-ide #3): RPi / Arduino-via-Pi / ESP-OTA.
    val deviceRepo: dev.aarso.data.DeviceRepo = dev.aarso.data.DeviceRepo { newSshTransport() }
}
