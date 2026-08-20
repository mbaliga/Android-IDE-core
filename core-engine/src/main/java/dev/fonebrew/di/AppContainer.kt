package dev.fonebrew.di

import android.content.Context
import androidx.room.Room
import dev.fonebrew.core_engine.BuildConfig
import dev.fonebrew.data.AppDatabase
import dev.fonebrew.data.DownloadCenter
import dev.fonebrew.data.ImageProviderStore
import dev.fonebrew.data.ImageStore
import dev.fonebrew.data.KvCacheStore
import dev.fonebrew.data.LocalModelStore
import dev.fonebrew.data.MessageTreeRepository
import dev.fonebrew.data.ModelDownloader
import dev.fonebrew.data.ProviderStore
import dev.fonebrew.data.SdModelStore
import dev.fonebrew.data.SessionStore
import dev.fonebrew.data.ApkInstaller
import dev.fonebrew.data.BuildsRepo
import dev.fonebrew.data.SharedIntake
import dev.fonebrew.embedding.Embedder
import dev.fonebrew.embedding.EmbeddingLogger
import dev.fonebrew.embedding.PlaceholderEmbedder
import dev.fonebrew.inference.EchoInferenceEngine
import dev.fonebrew.inference.EngineProvider
import dev.fonebrew.inference.InferenceEngine
import dev.fonebrew.inference.ModelRegistry
import dev.fonebrew.inference.echoDevSpecs

/**
 * Manual, constructor-based dependency wiring — deliberately no annotation-
 * processor DI framework (Hilt/Dagger). The legibility thesis (handoff §0)
 * favours wiring you can read top-to-bottom over generated magic, and it keeps
 * the build surface small.
 *
 * Single instance held by [dev.fonebrew.FonebrewApp].
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
     *  Implements [dev.fonebrew.ui.state.LedgerSource]; capture writer hooks in later. */
    val ledgerStore: dev.fonebrew.data.LedgerStore =
        dev.fonebrew.data.LedgerStore(database.ledgerDao())

    /** Fonebrew handoff-pack WP-2: append-only receipt store (FB-RAT-COM-006). Same on-device,
     *  no-telemetry shape as [ledgerStore] — see docs/ratified/COMMON_CONVENTIONS.md and
     *  HANDOFF_STATE.md. No consumer wired in yet; later work packages (execution receipts,
     *  import receipts, loop activation receipts) call [dev.fonebrew.data.ReceiptStore.append]. */
    val receiptStore: dev.fonebrew.data.ReceiptStore =
        dev.fonebrew.data.ReceiptStore(database.receiptDao())

    /** Fonebrew handoff-pack WP-3: Workspace Kernel journal (FB-RAT-WS-003/005) + the LOCAL
     *  provider (FB-RAT-WS-002/004). No consumer wired in yet -- WP-3 is the domain/data layer;
     *  wiring into the live IDE surfaces (Develop tab, RepoWorkLoop) is a later work package's
     *  job per docs/ratified/WORKSPACE_KERNEL_SPEC.md's own scope note. */
    val workspaceJournal: dev.fonebrew.data.RoomWorkspaceJournal =
        dev.fonebrew.data.RoomWorkspaceJournal(
            journalDao = database.bufferJournalDao(),
            snapshotDao = database.recoverySnapshotDao(),
            registryDao = database.bufferRegistryDao(),
        )

    val localWorkspaceProvider: dev.fonebrew.domain.workspace.LocalWorkspaceProvider =
        dev.fonebrew.domain.workspace.LocalWorkspaceProvider(context.applicationContext.filesDir)

    /** Fonebrew handoff-pack WP-6: workspace-buffer lexical search, wiring the existing real
     *  LexicalSearch engine (already used for conversation search, data/search/) into the
     *  Workspace Kernel via WorkspaceSearchProjector. Deliberately in-memory, not a new
     *  SQLDelight table -- see WorkspaceSearchIndex's own doc comment. No consumer wired in yet;
     *  a future Develop-tab search surface calls .index()/.search() as buffers open/save. The
     *  semantic stage stays the real, honest default (disabled) until an embedder ships. */
    val workspaceSearchIndex: dev.fonebrew.domain.search.WorkspaceSearchIndex =
        dev.fonebrew.domain.search.WorkspaceSearchIndex()
    val semanticSearchProvider: dev.fonebrew.domain.search.SemanticSearchProvider =
        dev.fonebrew.domain.search.DisabledSemanticSearchProvider

    /** Fonebrew handoff-pack WP-4: the real authority policy engine (default-deny, no transitive
     *  delegation, purpose/target/time binding -- docs/ratified/CAPABILITY_AUTHORITY_MODEL.md).
     *  Grant/principal storage is in-memory only this pass (no consumer or persistence need yet,
     *  same "no consumer wired in yet" note WP-2/WP-3 each left for similar pieces); decisions
     *  are audited through [receiptStore] via [dev.fonebrew.domain.authority.AuditedAuthorityEngine]. */
    val grantStore: dev.fonebrew.domain.authority.InMemoryGrantStore = dev.fonebrew.domain.authority.InMemoryGrantStore()
    val principalStore: dev.fonebrew.domain.authority.InMemoryPrincipalStore = dev.fonebrew.domain.authority.InMemoryPrincipalStore()
    val authorityEngine: dev.fonebrew.domain.authority.AuditedAuthorityEngine =
        dev.fonebrew.domain.authority.AuditedAuthorityEngine(
            engine = dev.fonebrew.domain.authority.AuthorityEngine(
                grants = grantStore, principals = principalStore, policyVersion = "1.0.0"
            ),
            receiptStore = receiptStore,
            producer = dev.fonebrew.contracts.common.ProducerRef(name = "core-engine", version = "1.0.0"),
        )

    /** WP-4: reference (non-Keystore) secret-handle broker -- see that class's own doc comment
     *  for why a real Keystore-backed implementation is owner-verified, not built here. */
    val secretHandleBroker: dev.fonebrew.domain.authority.InMemorySecretHandleBroker =
        dev.fonebrew.domain.authority.InMemorySecretHandleBroker(emptyMap())

    /** WP-4: the LOCAL_ANDROID ExecutionProvider. No consumer wired in yet -- see that class's
     *  own doc comment on W^X and the "process supervisor semantics JVM-mocked" scope note. */
    val localExecutionProvider: dev.fonebrew.domain.execution.LocalProcessExecutionProvider =
        dev.fonebrew.domain.execution.LocalProcessExecutionProvider()

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

    /** 3D object generation/import (docs/design/objects-3d.md): saved model files + watched
     *  3D-cloud-provider configs — same shape as the image pair above. */
    val object3dStore: dev.fonebrew.data.Object3dStore = dev.fonebrew.data.Object3dStore(context)
    val object3dProviderStore: dev.fonebrew.data.Object3dProviderStore = dev.fonebrew.data.Object3dProviderStore(context)

    /** Content routed in from share / process-text / assist (§7). */
    val sharedIntake: SharedIntake = SharedIntake()

    /** Where the user was (leaf/model) + small UI prefs — survives process death. */
    val sessionStore: SessionStore = SessionStore(context)

    /** Conversations room source (Doc 02): folds the tree + session (stars/projects/opens) +
     *  ledger into the room's row model. Implements [dev.fonebrew.ui.state.ConversationsSource];
     *  consumed by a [dev.fonebrew.ui.state.ConversationsViewModel] once the room is mounted. */
    val conversationsSource: dev.fonebrew.data.ConversationsStore =
        dev.fonebrew.data.ConversationsStore(repository, sessionStore, ledgerStore)

    /** User-set per-model prices (Cost epic G1/P2). Reads price a finished cloud turn. */
    val pricingStore: dev.fonebrew.data.PricingStore = dev.fonebrew.data.PricingStore(context)

    /** Project-room notes (local-first, plain text). */
    val notesStore: dev.fonebrew.data.NotesStore = dev.fonebrew.data.NotesStore(context)
    val incidentsStore: dev.fonebrew.data.IncidentsStore = dev.fonebrew.data.IncidentsStore(context)
    val councilStore: dev.fonebrew.data.CouncilStore = dev.fonebrew.data.CouncilStore(context)

    /** The Task substrate (free floor's To-do; paid Board/List/Waterfall lenses read the
     *  same table, CORE_PHASES.md P1). */
    val taskStore: dev.fonebrew.data.TaskStore = dev.fonebrew.data.TaskStore(database.taskDao())

    /** The Watchlist substrate (free floor's Watch tab — renewals/expiries/status,
     *  CORE_PHASES.md P2). */
    val watchStore: dev.fonebrew.data.WatchStore = dev.fonebrew.data.WatchStore(database.watchDao())

    /** The Conversation Instrument's data gateway (verdicts, message-level bookmarks, versions,
     *  compaction directives, ghost branches, form state — STUDIO_UX_SPEC.md §5.1). */
    val curationStore: dev.fonebrew.data.CurationStore = dev.fonebrew.data.CurationStore(
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
    val threadMarkerStore: dev.fonebrew.data.ThreadMarkerStore =
        dev.fonebrew.data.ThreadMarkerStore(database.threadMarkerDao())
    val delegationStore: dev.fonebrew.data.DelegationStore =
        dev.fonebrew.data.DelegationStore(database.delegationEventDao())

    /** The Aarso event log's write side (STUDIO_UX_SPEC.md §10) — off by default, per-signal
     *  toggles; see [dev.fonebrew.domain.mirror.AarsoCaptureSettings]. No settings UI exists yet to
     *  flip these on, so [aarsoCaptureSettings] currently always resolves to
     *  [dev.fonebrew.domain.mirror.AarsoCaptureSettings.OFF] — wiring a real toggle is a named
     *  follow-up, not a silent gap: the log is inert (writes nothing) until one exists. */
    private var aarsoCaptureSettings: dev.fonebrew.domain.mirror.AarsoCaptureSettings =
        dev.fonebrew.domain.mirror.AarsoCaptureSettings.OFF
    val aarsoEventLog: dev.fonebrew.domain.mirror.AarsoEventLog = dev.fonebrew.domain.mirror.AarsoEventLog(
        sink = dev.fonebrew.data.AarsoFileEventSink.forContext(context),
        settings = { aarsoCaptureSettings },
    )

    /** THREAD_TOPOLOGY_PLAN.md WP8: the shared "record twice" writer (queryable [delegationStore]
     *  row + inert [aarsoEventLog] line) for every "choose for me" surface — [dev.fonebrew.ui.ChatViewModel]
     *  and [dev.fonebrew.ui.loops.LoopRoom] (the `GATEWAY_AUTO` surface) both use this one instance
     *  rather than duplicating the two-write sequence. See [dev.fonebrew.data.DelegationRecorder]'s KDoc. */
    val delegationRecorder: dev.fonebrew.data.DelegationRecorder =
        dev.fonebrew.data.DelegationRecorder(delegationStore, aarsoEventLog)

    /** THREAD_TOPOLOGY_PLAN.md WP9: the graph observer, gated by [sessionStore]'s
     *  `observerEnabled` toggle (default off — owner decision 5). Reads [repository]/
     *  [threadMarkerStore]/[delegationStore] only, never [aarsoEventLog] (binding constraint 3).
     *  No consumer wired in yet — WP10's observer remark card is the first UI reader; this exists
     *  so the substrate is real ahead of that surface, same "no consumer wired in yet" pattern
     *  this file uses throughout. */
    val threadObserver: dev.fonebrew.data.ThreadObserver = dev.fonebrew.data.ThreadObserver(
        repository = repository,
        markerStore = threadMarkerStore,
        delegationStore = delegationStore,
        enabled = { sessionStore.observerEnabled.value },
    )

    /** The free-tier guide (bundled JSON, Nooz-refreshed) + per-provider free-tier usage. */
    val freeTierStore: dev.fonebrew.data.FreeTierStore = dev.fonebrew.data.FreeTierStore(context)
    val freeTierUsageStore: dev.fonebrew.data.FreeTierUsageStore = dev.fonebrew.data.FreeTierUsageStore(context)
    /** Consented online refresh of the free-tier guide (never automatic without opt-in). */
    val freeTierUpdater: dev.fonebrew.data.FreeTierUpdater = dev.fonebrew.data.FreeTierUpdater(context)

    /** The shared model catalog (bundled JSON, Nooz-refreshed — see docs/STATE.md): one-click
     *  download entries for chat GGUFs and SD checkpoints alike. Aarso no longer hand-maintains
     *  its own model list. */
    val modelCatalogStore: dev.fonebrew.data.ModelCatalogStore = dev.fonebrew.data.ModelCatalogStore(context)
    /** Consented online refresh of the model catalog (never automatic without opt-in). */
    val modelCatalogUpdater: dev.fonebrew.data.ModelCatalogUpdater = dev.fonebrew.data.ModelCatalogUpdater(context)

    /** Saved Loops (visual-editor definitions as BPMN + lifecycle envelope). */
    val loopStore: dev.fonebrew.data.LoopStore = dev.fonebrew.data.LoopStore(context)

    /** Connected Git hosts (watched) + their Keystore-encrypted tokens, and the
     *  thin REST transport that talks only to the user's host. */
    val gitHostStore: dev.fonebrew.data.GitHostStore = dev.fonebrew.data.GitHostStore(context)
    val gitTransport: dev.fonebrew.data.GitTransport = dev.fonebrew.data.GitTransport()
    val gitBackup: dev.fonebrew.data.GitBackup by lazy { dev.fonebrew.data.GitBackup(repository, gitTransport) }
    val gitBrowse: dev.fonebrew.data.GitBrowse = dev.fonebrew.data.GitBrowse(gitTransport)
    val buildsRepo: BuildsRepo = BuildsRepo(gitTransport, gitHostStore)
    /** Push/pull loop .bpmn files to the user's Git host (P6 made real). */
    val loopSyncRepo: dev.fonebrew.data.LoopSyncRepo =
        dev.fonebrew.data.LoopSyncRepo(gitTransport, gitHostStore, loopStore)
    val issueBoardRepo: dev.fonebrew.data.IssueBoardRepo =
        dev.fonebrew.data.IssueBoardRepo(gitTransport, gitHostStore)
    /** IDE last mile: create a repo + push a scaffold. Side-effectful — gate the UI. */
    val scaffoldPublishRepo: dev.fonebrew.data.ScaffoldPublishRepo =
        dev.fonebrew.data.ScaffoldPublishRepo(gitTransport, gitHostStore)
    val apkInstaller: ApkInstaller = ApkInstaller(context.applicationContext)

    /** SSH remotes (the remote-exec spine): host configs + trust pins + Keystore-encrypted
     *  keys, and a factory for the sshj transport that talks only to the user's machines
     *  (runtime owner-verified — no SSH server in CI). */
    val remoteHostStore: dev.fonebrew.data.RemoteHostStore = dev.fonebrew.data.RemoteHostStore(context)
    fun newSshTransport(): dev.fonebrew.domain.remote.RemoteTransport =
        dev.fonebrew.data.remote.SshjTransport(secretProvider = { remoteHostStore.secret(it) })

    /** Durable, retrying queue for network journeys so they survive the subway (P5). The worker
     *  drains it against per-kind handlers; a permanent error (auth/no-host) parks the op for the
     *  user instead of retrying forever. */
    val operationQueueStore: dev.fonebrew.data.OperationQueueStore = dev.fonebrew.data.OperationQueueStore(context)
    val operationWorker: dev.fonebrew.domain.net.OperationWorker by lazy {
        dev.fonebrew.domain.net.OperationWorker(
            load = { operationQueueStore.queue.value },
            save = { operationQueueStore.set(it) },
            handlers = mapOf(
                "loop.push" to dev.fonebrew.domain.net.OpHandler { loopSyncRepo.push().map {} },
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
    val agentRepoRunner: dev.fonebrew.data.AgentRepoRunner =
        dev.fonebrew.data.AgentRepoRunner(gitTransport, gitHostStore, modelRegistry, engineProvider)

    // Device recipes over the SSH spine (IA: agentic-ide #3): RPi / Arduino-via-Pi / ESP-OTA.
    val deviceRepo: dev.fonebrew.data.DeviceRepo = dev.fonebrew.data.DeviceRepo { newSshTransport() }

    /** FTS5 search index (separate SQLite file from Room's [database] — see
     *  [dev.fonebrew.data.search.SearchDriverFactory]'s KDoc) + the repository that projects the
     *  message tree into it and serves ranked queries against it. */
    val searchDatabaseHandle: dev.fonebrew.data.search.SearchDatabaseHandle =
        dev.fonebrew.data.search.SearchDriverFactory.create(context)
    val searchRepository: dev.fonebrew.data.search.SearchRepository =
        dev.fonebrew.data.search.SearchRepository(repository, sessionStore, ledgerStore, threadMarkerStore, searchDatabaseHandle.database)
}
