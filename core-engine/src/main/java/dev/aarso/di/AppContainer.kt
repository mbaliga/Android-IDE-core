package dev.aarso.di

import android.content.Context
import androidx.room.Room
import dev.aarso.core_engine.BuildConfig
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

    /** Fonebrew handoff-pack WP-2: append-only receipt store (FB-RAT-COM-006). Same on-device,
     *  no-telemetry shape as [ledgerStore] — see docs/ratified/COMMON_CONVENTIONS.md and
     *  HANDOFF_STATE.md. No consumer wired in yet; later work packages (execution receipts,
     *  import receipts, loop activation receipts) call [dev.aarso.data.ReceiptStore.append]. */
    val receiptStore: dev.aarso.data.ReceiptStore =
        dev.aarso.data.ReceiptStore(database.receiptDao())

    /** Fonebrew handoff-pack WP-3: Workspace Kernel journal (FB-RAT-WS-003/005) + the LOCAL
     *  provider (FB-RAT-WS-002/004). No consumer wired in yet -- WP-3 is the domain/data layer;
     *  wiring into the live IDE surfaces (Develop tab, RepoWorkLoop) is a later work package's
     *  job per docs/ratified/WORKSPACE_KERNEL_SPEC.md's own scope note. */
    val workspaceJournal: dev.aarso.data.RoomWorkspaceJournal =
        dev.aarso.data.RoomWorkspaceJournal(
            journalDao = database.bufferJournalDao(),
            snapshotDao = database.recoverySnapshotDao(),
            registryDao = database.bufferRegistryDao(),
        )

    val localWorkspaceProvider: dev.aarso.domain.workspace.LocalWorkspaceProvider =
        dev.aarso.domain.workspace.LocalWorkspaceProvider(context.applicationContext.filesDir)

    /** Fonebrew handoff-pack WP-6: workspace-buffer lexical search, wiring the existing real
     *  LexicalSearch engine (already used for conversation search, data/search/) into the
     *  Workspace Kernel via WorkspaceSearchProjector. Deliberately in-memory, not a new
     *  SQLDelight table -- see WorkspaceSearchIndex's own doc comment. No consumer wired in yet;
     *  a future Develop-tab search surface calls .index()/.search() as buffers open/save. The
     *  semantic stage stays the real, honest default (disabled) until an embedder ships. */
    val workspaceSearchIndex: dev.aarso.domain.search.WorkspaceSearchIndex =
        dev.aarso.domain.search.WorkspaceSearchIndex()
    val semanticSearchProvider: dev.aarso.domain.search.SemanticSearchProvider =
        dev.aarso.domain.search.DisabledSemanticSearchProvider

    /** Fonebrew handoff-pack WP-4: the real authority policy engine (default-deny, no transitive
     *  delegation, purpose/target/time binding -- docs/ratified/CAPABILITY_AUTHORITY_MODEL.md).
     *  Grant/principal storage is in-memory only this pass (no consumer or persistence need yet,
     *  same "no consumer wired in yet" note WP-2/WP-3 each left for similar pieces); decisions
     *  are audited through [receiptStore] via [dev.aarso.domain.authority.AuditedAuthorityEngine]. */
    val grantStore: dev.aarso.domain.authority.InMemoryGrantStore = dev.aarso.domain.authority.InMemoryGrantStore()
    val principalStore: dev.aarso.domain.authority.InMemoryPrincipalStore = dev.aarso.domain.authority.InMemoryPrincipalStore()
    val authorityEngine: dev.aarso.domain.authority.AuditedAuthorityEngine =
        dev.aarso.domain.authority.AuditedAuthorityEngine(
            engine = dev.aarso.domain.authority.AuthorityEngine(
                grants = grantStore, principals = principalStore, policyVersion = "1.0.0"
            ),
            receiptStore = receiptStore,
            producer = dev.aarso.contracts.common.ProducerRef(name = "core-engine", version = "1.0.0"),
        )

    /** WP-4: reference (non-Keystore) secret-handle broker -- see that class's own doc comment
     *  for why a real Keystore-backed implementation is owner-verified, not built here. */
    val secretHandleBroker: dev.aarso.domain.authority.InMemorySecretHandleBroker =
        dev.aarso.domain.authority.InMemorySecretHandleBroker(emptyMap())

    /** WP-4: the LOCAL_ANDROID ExecutionProvider. No consumer wired in yet -- see that class's
     *  own doc comment on W^X and the "process supervisor semantics JVM-mocked" scope note. */
    val localExecutionProvider: dev.aarso.domain.execution.LocalProcessExecutionProvider =
        dev.aarso.domain.execution.LocalProcessExecutionProvider()

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

    /** The Task substrate (free floor's To-do; paid Board/List/Waterfall lenses read the
     *  same table, CORE_PHASES.md P1). */
    val taskStore: dev.aarso.data.TaskStore = dev.aarso.data.TaskStore(database.taskDao())

    /** The Watchlist substrate (free floor's Watch tab — renewals/expiries/status,
     *  CORE_PHASES.md P2). */
    val watchStore: dev.aarso.data.WatchStore = dev.aarso.data.WatchStore(database.watchDao())

    /** The Conversation Instrument's data gateway (verdicts, message-level bookmarks, versions,
     *  compaction directives, ghost branches, form state — STUDIO_UX_SPEC.md §5.1). */
    val curationStore: dev.aarso.data.CurationStore = dev.aarso.data.CurationStore(
        database.verdictDao(),
        database.messageBookmarkDao(),
        database.versionDao(),
        database.compactionDirectiveDao(),
        database.ghostBranchDao(),
        database.formStateDao(),
    )

    /** THREAD_TOPOLOGY_PLAN.md WP1's thread-topology stores: retroactive markers (chapters,
     *  session starts) and delegation ("choose for me") records, both shaped like
     *  [curationStore] — fronting their own Room tables (`thread_markers`/`delegation_events`,
     *  AppDatabase v9), never writing to MessageNode.metadata. */
    val threadMarkerStore: dev.aarso.data.ThreadMarkerStore =
        dev.aarso.data.ThreadMarkerStore(database.threadMarkerDao())
    val delegationStore: dev.aarso.data.DelegationStore =
        dev.aarso.data.DelegationStore(database.delegationEventDao())

    /** The Aarso event log's write side (STUDIO_UX_SPEC.md §10) — off by default, per-signal
     *  toggles; see [dev.aarso.domain.mirror.AarsoCaptureSettings]. No settings UI exists yet to
     *  flip these on, so [aarsoCaptureSettings] currently always resolves to
     *  [dev.aarso.domain.mirror.AarsoCaptureSettings.OFF] — wiring a real toggle is a named
     *  follow-up, not a silent gap: the log is inert (writes nothing) until one exists. */
    private var aarsoCaptureSettings: dev.aarso.domain.mirror.AarsoCaptureSettings =
        dev.aarso.domain.mirror.AarsoCaptureSettings.OFF
    val aarsoEventLog: dev.aarso.domain.mirror.AarsoEventLog = dev.aarso.domain.mirror.AarsoEventLog(
        sink = dev.aarso.data.AarsoFileEventSink.forContext(context),
        settings = { aarsoCaptureSettings },
    )

    /** THREAD_TOPOLOGY_PLAN.md WP8: the shared "record twice" writer (queryable [delegationStore]
     *  row + inert [aarsoEventLog] line) for every "choose for me" surface — [dev.aarso.ui.ChatViewModel]
     *  and [dev.aarso.ui.loops.LoopRoom] (the `GATEWAY_AUTO` surface) both use this one instance
     *  rather than duplicating the two-write sequence. See [dev.aarso.data.DelegationRecorder]'s KDoc. */
    val delegationRecorder: dev.aarso.data.DelegationRecorder =
        dev.aarso.data.DelegationRecorder(delegationStore, aarsoEventLog)

    /** THREAD_TOPOLOGY_PLAN.md WP9: the graph observer, gated by [sessionStore]'s
     *  `observerEnabled` toggle (default off — owner decision 5). Reads [repository]/
     *  [threadMarkerStore]/[delegationStore] only, never [aarsoEventLog] (binding constraint 3).
     *  No consumer wired in yet — WP10's observer remark card is the first UI reader; this exists
     *  so the substrate is real ahead of that surface, same "no consumer wired in yet" pattern
     *  this file uses throughout. */
    val threadObserver: dev.aarso.data.ThreadObserver = dev.aarso.data.ThreadObserver(
        repository = repository,
        markerStore = threadMarkerStore,
        delegationStore = delegationStore,
        enabled = { sessionStore.observerEnabled.value },
    )

    /** The free-tier guide (bundled JSON, Nooz-refreshed) + per-provider free-tier usage. */
    val freeTierStore: dev.aarso.data.FreeTierStore = dev.aarso.data.FreeTierStore(context)
    val freeTierUsageStore: dev.aarso.data.FreeTierUsageStore = dev.aarso.data.FreeTierUsageStore(context)
    /** Consented online refresh of the free-tier guide (never automatic without opt-in). */
    val freeTierUpdater: dev.aarso.data.FreeTierUpdater = dev.aarso.data.FreeTierUpdater(context)

    /** The shared model catalog (bundled JSON, Nooz-refreshed — see docs/STATE.md): one-click
     *  download entries for chat GGUFs and SD checkpoints alike. Aarso no longer hand-maintains
     *  its own model list. */
    val modelCatalogStore: dev.aarso.data.ModelCatalogStore = dev.aarso.data.ModelCatalogStore(context)
    /** Consented online refresh of the model catalog (never automatic without opt-in). */
    val modelCatalogUpdater: dev.aarso.data.ModelCatalogUpdater = dev.aarso.data.ModelCatalogUpdater(context)

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

    /** FTS5 search index (separate SQLite file from Room's [database] — see
     *  [dev.aarso.data.search.SearchDriverFactory]'s KDoc) + the repository that projects the
     *  message tree into it and serves ranked queries against it. */
    val searchDatabaseHandle: dev.aarso.data.search.SearchDatabaseHandle =
        dev.aarso.data.search.SearchDriverFactory.create(context)
    val searchRepository: dev.aarso.data.search.SearchRepository =
        dev.aarso.data.search.SearchRepository(repository, sessionStore, ledgerStore, threadMarkerStore, searchDatabaseHandle.database)
}
