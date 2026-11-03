package dev.fonebrew.ui

import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.AttachmentStore
import dev.fonebrew.data.DeviceInfo
import dev.fonebrew.data.DownloadCenter
import dev.fonebrew.data.ImageProviderStore
import dev.fonebrew.data.ImageStore
import dev.fonebrew.data.KvCacheStore
import dev.fonebrew.data.LocalModelStore
import dev.fonebrew.data.MessageTreeRepository
import dev.fonebrew.data.ModelDownloader
import dev.fonebrew.data.ProviderStore
import dev.fonebrew.data.Intake
import dev.fonebrew.data.SdModelStore
import dev.fonebrew.data.SessionStore
import dev.fonebrew.data.SharedIntake
import dev.fonebrew.domain.catalog.CatalogModel
import dev.fonebrew.domain.catalog.ModelCatalogMapper
import dev.fonebrew.domain.catalog.StarterModels
import dev.fonebrew.flavor.InvocationFeatures
import dev.fonebrew.domain.device.ModelFit
import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.council.Council
import dev.fonebrew.domain.image.ImageParams
import dev.fonebrew.domain.object3d.Object3dFormat
import dev.fonebrew.domain.object3d.Object3dFormatSniffer
import dev.fonebrew.domain.object3d.ProceduralSceneCodec
import dev.fonebrew.domain.instrument.TokenStats
import dev.fonebrew.domain.model.ContextCheck
import dev.fonebrew.domain.model.DefaultModelPolicy
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.model.checkContext
import dev.fonebrew.domain.template.ChatTemplates
import dev.fonebrew.domain.tree.Attachments
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.Nodes
import dev.fonebrew.domain.tree.PathView
import dev.fonebrew.domain.tree.Sources
import dev.fonebrew.domain.tree.TreeOutline
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.fonebrew.data.Object3dNodeMeta
import dev.fonebrew.data.Object3dSource
import dev.fonebrew.embedding.EmbeddingLogger
import dev.fonebrew.inference.EngineGenerator
import dev.fonebrew.inference.EngineProvider
import dev.fonebrew.inference.InferenceEngine
import dev.fonebrew.inference.ModelRegistry
import dev.fonebrew.inference.image.ImageEngineFactory
import dev.fonebrew.inference.object3d.CloudObject3dEngineFactory
import dev.fonebrew.inference.object3d.ProceduralObjectEngine
import dev.fonebrew.inference.object3d.ProceduralOutcome
import dev.fonebrew.service.GenerationService
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a turn is in its lifecycle, for the progress indicator. */
enum class GenPhase { IDLE, LOADING, GENERATING }

/** A switchable model as shown in the picker. */
data class ModelOption(
    val id: String,
    val displayName: String,
    val contextWindow: Int,
    val runnable: Boolean,
    val watched: Boolean,
    /** File size for downloaded on-device models; null for cloud/dev specs. */
    val sizeBytes: Long? = null,
)

/**
 * The composer mode: one voice, a council of them (never labelled "MoE"), an
 * image turn (§6 of the spatial redesign — Images is a mode, not a room), or a
 * 3D-object turn (docs/design/objects-3d.md §1 — same "mode, not a room" shape).
 */
enum class ComposerMode { SINGLE, PERSONAS, MODELS, IMAGE, OBJECT3D }

/** docs/design/objects-3d.md §1's mini-chooser: which engine a 3D generation uses.
 *  On-device is always the default (binding rule 2); cloud is the explicit, per-use opt-in. */
enum class Object3dGenScope { ON_DEVICE, CLOUD }

/** One voice in the council panel (§4b) — streaming or persisted. */
data class CouncilCard(
    val agent: String,
    val text: String,
    val done: Boolean,
    val nodeId: String?,
)

/**
 * A photo picked or captured but not yet sent (daily-driver.md W1 — vision input): already
 * downscaled + re-encoded and saved via [AttachmentStore] (so the strip can thumbnail it via
 * [dev.aarso.hyle.cells.FileImage] like any other on-disk image), just not yet attached to a
 * sent user node. [id] is a local-only key for the composer strip's remove action, not a tree id.
 */
data class PendingAttachment(
    val id: String,
    val path: String,
    val mime: String,
)

data class ChatUiState(
    val steps: List<PathView.Step> = emptyList(),
    /** Per-token while streaming, so the UI can colour by entropy/confidence (§5a). */
    val streamingTokens: List<GeneratedToken> = emptyList(),
    val isGenerating: Boolean = false,
    val models: List<ModelOption> = emptyList(),
    val activeModelId: String = "",
    val activeModelLabel: String = "",
    val context: ContextCheck? = null,
    /** Input/output token ratio for the visible path (§5a). */
    val tokenStats: TokenStats? = null,
    /** THREAD_TOPOLOGY_PLAN.md WP7: the last completed turn's per-token stream, kept past the
     *  point [Transient.stream] resets to empty when generation finishes — so the Instruments
     *  panel's TokenHeatmap can inspect the turn that just finished, not only a still-streaming
     *  one. Empty until a turn has completed this session. */
    val lastTurnTokens: List<GeneratedToken> = emptyList(),
    /** THREAD_TOPOLOGY_PLAN.md WP7: "what does the model see" for the active leaf's effective
     *  (compaction-boundary-aware) path — [dev.fonebrew.domain.scope.ContextAssembly]'s own ledger,
     *  wired for the first time by [dev.fonebrew.domain.instrument.InstrumentsAssembly]. Null with
     *  no active leaf. */
    val instrumentsAssembly: dev.fonebrew.domain.scope.ContextAssembly.Assembled? = null,
    /** THREAD_TOPOLOGY_PLAN.md WP8: this conversation's "delegated N · kept N · reverted N"
     *  rollup for the Instruments panel's descriptive card — see [dev.fonebrew.domain.thread.DelegationCounts]. */
    val delegationCounts: dev.fonebrew.domain.thread.DelegationCounts.Counts = dev.fonebrew.domain.thread.DelegationCounts.EMPTY,
    val prefillPending: Boolean = false,
    val engineAvailable: Boolean = true,
    /** True when no model is active at all — the first-run / setup state. */
    val noModelActive: Boolean = false,
    /** Why the active model can't run (cloud needs a key, GGUF needs native build). */
    val unavailableReason: String? = null,
    /** A runtime error from the last turn (model load / generation failure). */
    val error: String? = null,
    /** The visible path ends on a user turn with no reply — Retry can regenerate. */
    val canRegenerate: Boolean = false,
    val genPhase: GenPhase = GenPhase.IDLE,
    /** On-demand prompt rewrite (§6b): a suggested rewrite of the input, or null. */
    val promptSuggestion: String? = null,
    val rewriting: Boolean = false,
    /** Council (§4b): whether council mode is on, and the current panel of voices. */
    val councilEnabled: Boolean = false,
    val councilCards: List<CouncilCard> = emptyList(),
    /** Council diversity: false = personas (one model, many hats), true = different models. */
    val modelDiversity: Boolean = false,
    /** Image mode (§6): send generates an image turn instead of a text turn. */
    val imageMode: Boolean = false,
    /** 3D-object mode (docs/design/objects-3d.md §1): send generates/imports a 3D-object turn
     *  instead of a text turn, against [object3dScope]. */
    val object3dMode: Boolean = false,
    val object3dScope: Object3dGenScope = Object3dGenScope.ON_DEVICE,
    /** Can the active model take image input (W1)? Gates the composer's Photo/Camera rows —
     *  they stay visible-but-disabled-with-reason when this is false, never hidden (legibility). */
    val activeSupportsVision: Boolean = false,
    /** The composer's globe-chip toggle (W2 — web search): per-turn opt-in, default off
     *  (cloud extras are opt-in — CLAUDE.md rule 2). Lives here, not a bare `remember{}` in
     *  ChatScreen — same reasoning as [pendingAttachments]. */
    val webSearchOn: Boolean = false,
    /** Can the active model use server-side web search (W2)? Gates the globe chip the same
     *  way [activeSupportsVision] gates Photo/Camera — visible-but-disabled-with-reason. */
    val activeSupportsSearch: Boolean = false,
) {
    val composerMode: ComposerMode
        get() = when {
            imageMode -> ComposerMode.IMAGE
            object3dMode -> ComposerMode.OBJECT3D
            councilEnabled && modelDiversity -> ComposerMode.MODELS
            councilEnabled -> ComposerMode.PERSONAS
            else -> ComposerMode.SINGLE
        }
    val streamingText: String? get() = if (isGenerating) streamingTokens.joinToString("") { it.text } else null
    val councilPending: Boolean get() = councilCards.isNotEmpty()
}

private data class Transient(
    val stream: StreamState = StreamState(),
    val context: ContextCheck? = null,
    val tokenStats: TokenStats? = null,
    /** THREAD_TOPOLOGY_PLAN.md WP7 — see [ChatUiState.lastTurnTokens]. */
    val lastTurnTokens: List<GeneratedToken> = emptyList(),
    /** THREAD_TOPOLOGY_PLAN.md WP7 — see [ChatUiState.instrumentsAssembly]. */
    val instrumentsAssembly: dev.fonebrew.domain.scope.ContextAssembly.Assembled? = null,
    /** THREAD_TOPOLOGY_PLAN.md WP8 — see [ChatUiState.delegationCounts]. */
    val delegationCounts: dev.fonebrew.domain.thread.DelegationCounts.Counts = dev.fonebrew.domain.thread.DelegationCounts.EMPTY,
    val prefillPending: Boolean = false,
    val error: String? = null,
    val genPhase: GenPhase = GenPhase.IDLE,
    val promptSuggestion: String? = null,
    val rewriting: Boolean = false,
    val councilMode: Boolean = false,
    val modelDiversity: Boolean = false,
    val imageMode: Boolean = false,
    val object3dMode: Boolean = false,
    val object3dScope: Object3dGenScope = Object3dGenScope.ON_DEVICE,
    /** Live per-agent streaming during a council fan-out; null when not fanning. */
    val councilStreaming: List<CouncilCard>? = null,
    /** W2: the composer's globe-chip search toggle. Default off. */
    val webSearchOn: Boolean = false,
)

private data class StreamState(
    val tokens: List<GeneratedToken> = emptyList(),
    val isGenerating: Boolean = false,
)

/**
 * Drives the chat loop, branching, model switching, and the light instrumentation
 * (handoff §2/§3/§5a). The visible conversation is the path from the root to
 * [activeLeafId]; the active model sets the template, tokenizer, and window.
 */
class ChatViewModel(
    private val repository: MessageTreeRepository,
    private val registry: ModelRegistry,
    private val engines: EngineProvider,
    private val providers: ProviderStore,
    private val locals: LocalModelStore,
    private val embeddingLogger: EmbeddingLogger,
    private val kvCache: KvCacheStore,
    private val sharedIntake: SharedIntake,
    private val session: SessionStore,
    private val downloadCenter: DownloadCenter,
    private val downloader: ModelDownloader,
    private val imageStore: ImageStore,
    private val attachmentStore: AttachmentStore,
    private val imageProviders: ImageProviderStore,
    private val object3dStore: dev.fonebrew.data.Object3dStore,
    private val object3dProviders: dev.fonebrew.data.Object3dProviderStore,
    private val sdModels: SdModelStore,
    private val pricingStore: dev.fonebrew.data.PricingStore,
    private val freeTierUsage: dev.fonebrew.data.FreeTierUsageStore,
    private val councilStore: dev.fonebrew.data.CouncilStore,
    private val ledgerStore: dev.fonebrew.data.LedgerStore,
    private val catalogStore: dev.fonebrew.data.ModelCatalogStore,
    private val curationStore: dev.fonebrew.data.CurationStore,
    private val threadMarkerStore: dev.fonebrew.data.ThreadMarkerStore,
    private val delegationStore: dev.fonebrew.data.DelegationStore,
    private val delegationRecorder: dev.fonebrew.data.DelegationRecorder,
    private val receiptStore: dev.fonebrew.data.ReceiptStore,
    private val aarsoEventLog: dev.fonebrew.domain.mirror.AarsoEventLog,
    private val threadObserver: dev.fonebrew.data.ThreadObserver,
    private val taskStore: dev.fonebrew.data.TaskStore,
    private val appContext: Context,
) : ViewModel() {

    /** Content routed in from share / text-selection / assist (§7). */
    val intake: StateFlow<Intake?> get() = sharedIntake.pending
    fun consumeIntake(): Intake? = sharedIntake.consume()

    // Restored from the previous session and written through on every change,
    // so the app reopens where the user left it instead of an empty chat.
    private val activeLeafId = MutableStateFlow(session.activeLeafId.value)
    private val activeModelId = MutableStateFlow(
        DefaultModelPolicy.resolveActive(registry.allSpecs(), session.activeModelId.value)?.id,
    )
    private val transient = MutableStateFlow(
        // New conversations start in the owner's preferred council mode (Settings → Global).
        when (session.councilDefault.value) {
            "PERSONAS" -> Transient(councilMode = true, modelDiversity = false)
            "MODELS" -> Transient(councilMode = true, modelDiversity = true)
            else -> Transient()
        },
    )

    init {
        // A persisted leaf can be stale only if the DB was wiped (the tree is
        // append-only); fall back to a fresh chat rather than a broken path.
        viewModelScope.launch {
            val leaf = activeLeafId.value
            if (leaf != null && repository.node(leaf) == null) moveLeaf(null)
        }
        // When a download lands and nothing is active yet, adopt it. On-device
        // only — DefaultModelPolicy never resolves to a cloud model.
        viewModelScope.launch {
            locals.models.collect {
                if (activeModelId.value == null) {
                    DefaultModelPolicy.resolveActive(registry.allSpecs(), session.activeModelId.value)
                        ?.let { spec -> setActiveModel(spec.id) }
                }
            }
        }
        // Same adoption, for the moment the onboarding wizard confirms on-device Gemini Nano.
        viewModelScope.launch {
            session.aiCoreEnabled.collect {
                if (activeModelId.value == null) {
                    DefaultModelPolicy.resolveActive(registry.allSpecs(), session.activeModelId.value)
                        ?.let { spec -> setActiveModel(spec.id) }
                }
            }
        }
    }

    // The in-flight token collection; cancelling it is how Stop works. The local
    // engine's Flow already requests a native stop in its awaitClose, and cloud
    // engines cancel their SSE call — partial text survives and is persisted.
    private var generationJob: Job? = null
    private var stopRequested = false

    /** Stop the current generation, keeping whatever streamed so far. */
    fun stopGeneration() {
        stopRequested = true
        generationJob?.cancel()
    }

    /**
     * Run [collect] as a cancellable child, swallowing the cancellation (a stop
     * keeps partial output) but re-throwing real engine errors in the caller.
     */
    private suspend fun collectCancellable(collect: suspend () -> Unit) {
        var genError: Throwable? = null
        val job = viewModelScope.launch {
            try {
                collect()
            } catch (t: Throwable) {
                if (t !is CancellationException) genError = t
            }
        }
        generationJob = job
        job.join()
        generationJob = null
        genError?.let { throw it }
    }

    private fun moveLeaf(id: String?) {
        activeLeafId.value = id
        session.setActiveLeafId(id)
    }

    private fun setActiveModel(id: String?) {
        activeModelId.value = id
        session.setActiveModelId(id)
    }

    // First-run setup card (§ usability rework): the one recommended starter
    // model for this device, and its download state. The init collector above
    // auto-activates it the moment the file lands.
    val device = DeviceInfo.read(appContext)
    private val catalog: List<CatalogModel> =
        ModelCatalogMapper.chatModels(catalogStore.catalog(), InvocationFeatures.CATALOG_POLICY_SAFE_ONLY)
    val starter: CatalogModel? = StarterModels.recommend(catalog, device)
    val starterFitReason: String? = starter?.let { ModelFit.check(it.sizeBytes, device).reason }

    val starterDownload: StateFlow<ModelDownloader.Progress?> =
        downloadCenter.active
            .map { active -> starter?.let { active[it.id]?.progress } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** No-op when the catalog has no verified mirror for the starter yet (null downloadUrl). */
    fun downloadStarter() {
        val model = starter ?: return
        val url = model.downloadUrl ?: return
        downloadCenter.enqueue(model.id, url, model.fileName, downloader)
    }

    fun retryStarter() {
        starter?.let { downloadCenter.retry(it.id) }
    }

    /** Start a fresh conversation: the next send becomes a new root in the tree. */
    fun newChat() {
        if (transient.value.genPhase != GenPhase.IDLE) return
        moveLeaf(null)
        transient.value = transient.value.copy(
            context = null,
            tokenStats = null,
            error = null,
            promptSuggestion = null,
        )
    }

    /** Emits whenever the available model set changes (cloud providers or downloads). */
    private val registryChanges = combine(providers.providers, locals.models) { _, _ -> Unit }

    private fun ModelSpec.toOption() = ModelOption(
        id = id,
        displayName = displayName,
        contextWindow = contextWindow,
        runnable = engines.isRunnable(this),
        watched = watched,
        sizeBytes = locals.models.value.firstOrNull { "local:${it.name}" == id }?.sizeBytes,
    )

    /** UI prefs (persisted): instruments disclosure and entropy colouring. */
    val instrumentsExpanded: StateFlow<Boolean> get() = session.instrumentsExpanded
    fun setInstrumentsExpanded(expanded: Boolean) = session.setInstrumentsExpanded(expanded)
    val entropyColoring: StateFlow<Boolean> get() = session.entropyColoring
    fun setEntropyColoring(enabled: Boolean) = session.setEntropyColoring(enabled)

    fun setComposerMode(mode: ComposerMode) {
        transient.value = transient.value.copy(
            councilMode = mode == ComposerMode.PERSONAS || mode == ComposerMode.MODELS,
            modelDiversity = mode == ComposerMode.MODELS,
            imageMode = mode == ComposerMode.IMAGE,
            // OBJECT3D is entered via enterObject3dMode(scope) instead (it needs a scope from
            // the composer's mini-chooser) — routing it through here just exits it, same as
            // every other mode switch does to imageMode.
            object3dMode = false,
        )
    }

    /** docs/design/objects-3d.md §1: enter 3D-object composer mode against the mini-chooser's
     *  [scope] pick. Exit the same way image mode does — [setComposerMode] to any other mode. */
    fun enterObject3dMode(scope: Object3dGenScope) {
        transient.value = transient.value.copy(
            councilMode = false,
            modelDiversity = false,
            imageMode = false,
            object3dMode = true,
            object3dScope = scope,
        )
    }

    // ── Interaction-model immutability (IA §B4) ──────────────────────────────
    // Once a conversation has started, its interaction model (Single / Council·personas /
    // Council·models) is locked. Changing it doesn't mutate in place — it branches, with a
    // summary of the conversation so far folded into the new branch. Image is a transient
    // generation mode, not an interaction model, so it never triggers this.
    private val _pendingInteractionChange = MutableStateFlow<ComposerMode?>(null)
    val pendingInteractionChange: StateFlow<ComposerMode?> = _pendingInteractionChange.asStateFlow()

    private fun currentInteractionMode(): ComposerMode {
        val t = transient.value
        return when {
            t.councilMode && t.modelDiversity -> ComposerMode.MODELS
            t.councilMode -> ComposerMode.PERSONAS
            else -> ComposerMode.SINGLE
        }
    }

    /** Called by the composer's interaction chips. Branches-with-summary if the conversation
     *  has already started and the interaction model genuinely changes; else switches directly. */
    fun requestComposerMode(mode: ComposerMode) {
        val started = activeLeafId.value != null
        if (mode == ComposerMode.IMAGE || !started || mode == currentInteractionMode()) {
            setComposerMode(mode)
            return
        }
        _pendingInteractionChange.value = mode
    }

    fun cancelInteractionChange() { _pendingInteractionChange.value = null }

    private fun interactionModeLabel(mode: ComposerMode): String = when (mode) {
        ComposerMode.MODELS -> "Council · models"
        ComposerMode.PERSONAS -> "Council · personas"
        else -> "Single"
    }

    /** The [dev.fonebrew.domain.provenance.ProvenanceState] a text call on [spec] would carry — the
     *  same watched-object honesty every other provenance-tagged surface in this app uses. Null
     *  [spec] (no model call happened at all, e.g. a bare-excerpt fallback) is [ProvenanceState.LOCAL]:
     *  trivially true, since nothing left the device when nothing ran. */
    private fun provenanceFor(spec: ModelSpec?): dev.fonebrew.domain.provenance.ProvenanceState =
        if (spec?.runtime == Runtime.CLOUD) dev.fonebrew.domain.provenance.ProvenanceState.CLOUD else dev.fonebrew.domain.provenance.ProvenanceState.LOCAL

    /** [MessageNode] path -> [dev.fonebrew.domain.bridge.PriorTurn]s, the shared adapter every bridge
     *  builder ([confirmInteractionChange], [spawnFrom]) feeds into [dev.fonebrew.domain.bridge.SummaryBridges.selectCarryForward]. */
    private fun priorTurnsOf(path: List<MessageNode>): List<dev.fonebrew.domain.bridge.PriorTurn> =
        path.filter { it.role != Role.SYSTEM && it.content.isNotBlank() }
            .map { dev.fonebrew.domain.bridge.PriorTurn(role = it.role.wire, text = it.content, tokenEstimate = it.content.length / 4) }

    /**
     * Confirmed: summarize the active path, append it as a new branch, then switch the model.
     * The branch's node carries both its long-standing plain-text content (fed to future model
     * calls) and, since THREAD_TOPOLOGY_PLAN.md WP2 (`bridge`/`bridge.payload` metadata — see
     * [dev.fonebrew.domain.bridge.BridgeCodec]), a [dev.fonebrew.domain.bridge.SummaryBridge] structural
     * payload ChatScreen mounts via [dev.fonebrew.ui.components.SummaryNodeCard] instead of a plain
     * bubble — this is the exact mid-conversation-switch case that component's own KDoc names.
     */
    fun confirmInteractionChange() {
        val mode = _pendingInteractionChange.value ?: return
        _pendingInteractionChange.value = null
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            try {
                val leaf = activeLeafId.value
                val summary = summarizeActivePath(leaf)
                val parent = leaf?.let { repository.node(it) }
                val label = interactionModeLabel(mode)
                val spec = activeModelId.value?.let { registry.byId(it) }
                val ancestors = leaf?.let { repository.tree().pathToRoot(it) }.orEmpty()
                val switchEvent = dev.fonebrew.domain.bridge.SwitchEvent(
                    kind = dev.fonebrew.domain.bridge.SwitchKind.INTERACTION_MODEL,
                    from = interactionModeLabel(currentInteractionMode()),
                    to = label,
                )
                val bridge = dev.fonebrew.domain.bridge.SummaryBridges.build(
                    event = switchEvent,
                    priorTurns = priorTurnsOf(ancestors),
                    authorModel = spec?.id,
                    authorProvenance = provenanceFor(spec),
                )
                val node = Nodes.child(
                    parent = parent,
                    role = Role.SYSTEM,
                    content = "Interaction model → $label. Summary of the conversation so far:\n\n$summary",
                    now = System.currentTimeMillis(),
                    metadata = mapOf(
                        "interactionSwitch" to mode.name,
                        "summary" to "true",
                        dev.fonebrew.domain.bridge.BridgeCodec.BRIDGE_KEY to "interaction_switch",
                        dev.fonebrew.domain.bridge.BridgeCodec.BRIDGE_PAYLOAD_KEY to dev.fonebrew.domain.bridge.BridgeCodec.encode(bridge),
                        // Fixed (WP2 known defect): this bridge lives in the SAME tree/root as
                        // before the switch (a branch, not a fork/spawn new root), so it never had
                        // a cross-root lineage.srcRoot to stamp — the "View full prior context"
                        // button in SummaryNodeCard resolved that key, found it always null, and
                        // silently no-op'd. lineage.srcNode instead names the immediate pre-switch
                        // node, which IS on this same path — ChatScreen falls back to scrolling to
                        // it when lineage.srcRoot is absent. Empty string (never a null map value)
                        // when there's somehow no parent, so the ChatScreen-side lookup fails an
                        // honest indexOfFirst rather than crashing on a null metadata value.
                        dev.fonebrew.domain.tree.TreeFork.LINEAGE_SRC_NODE_KEY to (parent?.id ?: ""),
                    ),
                )
                repository.insert(node)
                moveLeaf(node.id)
                setComposerMode(mode)
                recomputeStatusAsync()
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "couldn't switch model")
            }
        }
    }

    /**
     * A concise brief of the path ending at [leafId] — model-made if a model is runnable, else an
     * excerpt. Defaults to the active leaf (its original caller, [confirmInteractionChange]);
     * generalized to take an explicit anchor so [spawnFrom] (THREAD_TOPOLOGY_PLAN.md WP2) can reuse
     * the exact same prose pattern for an arbitrary spawn point rather than duplicating it.
     */
    private suspend fun summarizeActivePath(leafId: String? = activeLeafId.value): String {
        val leaf = leafId ?: return "(no conversation yet)"
        val path = repository.tree().pathToRoot(leaf)
        val transcript = path.filter { it.role != Role.SYSTEM && it.content.isNotBlank() }
            .joinToString("\n") {
                val who = if (it.role == Role.USER) "User" else "Assistant"
                "$who: ${it.content.take(800)}"
            }
        if (transcript.isBlank()) return "(no conversation yet)"
        val spec = activeModelId.value?.let { registry.byId(it) }
        val engine = spec?.let { engines.engineFor(it) }
        if (engine != null && spec != null) {
            return runCatching {
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val now = System.currentTimeMillis()
                val msgs = listOf(
                    MessageNode("sum-sys", null, Role.SYSTEM, SUMMARY_SYSTEM, createdAt = now),
                    MessageNode("sum-usr", "sum-sys", Role.USER, transcript, createdAt = now + 1),
                )
                val sb = StringBuilder()
                engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
                sb.toString().trim().ifBlank { transcript.take(1200) }
            }.getOrElse { transcript.take(1200) }
        }
        return transcript.take(1200)
    }

    fun clearError() {
        transient.value = transient.value.copy(error = null)
    }

    val uiState: StateFlow<ChatUiState> =
        combine(
            repository.observeTree(),
            activeLeafId,
            activeModelId,
            transient,
            registryChanges, // rebuild options when providers or downloads change
        ) { tree, leaf, modelId, t, _ ->
            val spec = modelId?.let { registry.byId(it) }
            val steps = if (leaf == null) emptyList() else PathView.annotate(tree, leaf)
            // Council panel: live stream if fanning out, else the persisted set of
            // agent siblings under a user leaf (the lateral axis, §4b).
            val councilCards = t.councilStreaming ?: run {
                val leafNode = leaf?.let { tree.node(it) }
                if (leafNode != null && leafNode.role == Role.USER) {
                    tree.childrenOf(leafNode.id)
                        .filter { it.metadata.containsKey("agent") }
                        .map { CouncilCard(it.metadata["agent"] ?: "voice", it.content, true, it.id) }
                } else {
                    emptyList()
                }
            }
            ChatUiState(
                steps = steps,
                streamingTokens = t.stream.tokens,
                isGenerating = t.stream.isGenerating,
                models = registry.allSpecs().map { it.toOption() },
                activeModelId = spec?.id ?: "",
                activeModelLabel = spec?.displayName ?: "No model",
                context = t.context,
                tokenStats = t.tokenStats,
                lastTurnTokens = t.lastTurnTokens,
                instrumentsAssembly = t.instrumentsAssembly,
                delegationCounts = t.delegationCounts,
                prefillPending = t.prefillPending,
                engineAvailable = spec != null && engines.isRunnable(spec),
                noModelActive = spec == null,
                unavailableReason = spec?.let { engines.unavailableReason(it) },
                error = t.error,
                canRegenerate = steps.lastOrNull()?.node?.role == Role.USER &&
                    councilCards.isEmpty() && t.genPhase == GenPhase.IDLE,
                genPhase = t.genPhase,
                promptSuggestion = t.promptSuggestion,
                rewriting = t.rewriting,
                councilEnabled = t.councilMode,
                councilCards = councilCards,
                modelDiversity = t.modelDiversity,
                imageMode = t.imageMode,
                object3dMode = t.object3dMode,
                object3dScope = t.object3dScope,
                activeSupportsVision = spec?.supportsVision ?: false,
                webSearchOn = t.webSearchOn,
                activeSupportsSearch = spec?.supportsSearch ?: false,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ChatUiState(
                models = registry.allSpecs().map { it.toOption() },
                activeModelId = activeModelId.value ?: "",
                activeModelLabel = activeModelId.value
                    ?.let { registry.byId(it)?.displayName } ?: "No model",
                noModelActive = activeModelId.value == null,
                activeSupportsVision = activeModelId.value?.let { registry.byId(it)?.supportsVision } ?: false,
                activeSupportsSearch = activeModelId.value?.let { registry.byId(it)?.supportsSearch } ?: false,
            ),
        )

    /** Pending photo attachments (W1): picked/captured but not yet sent, shown as a thumbnail
     *  strip above the composer. Lives here rather than a bare `remember{}` in ChatScreen — the
     *  same class of bug as the composer-draft-text one this codebase already has (W3), which
     *  this is deliberately not repeating. Cleared on successful send. */
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments.asStateFlow()

    /** Set by [newCameraCaptureUri] just before the camera intent launches; consumed by
     *  [onCameraCaptureResult] when it returns. Single in-flight capture at a time, which
     *  matches the composer's own one-sheet-at-a-time flow. */
    private var pendingCameraPath: String? = null

    /** A fresh `content://` Uri under the attachments dir (this app's own [AttachmentStore]
     *  authority, shared with [dev.fonebrew.data.ApkInstaller]'s FileProvider precedent) for
     *  `ActivityResultContracts.TakePicture` to write a full-resolution photo into. */
    fun newCameraCaptureUri(): Uri {
        val path = attachmentStore.newPath("jpg")
        pendingCameraPath = path
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", File(path))
    }

    /** Called with the camera activity's result. On success, downscales the full-res capture
     *  into a pending attachment and drops the temp full-res file either way. */
    fun onCameraCaptureResult(success: Boolean) {
        val path = pendingCameraPath
        pendingCameraPath = null
        if (path == null) return
        if (!success) {
            attachmentStore.delete(path)
            return
        }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { File(path).readBytes() }.getOrNull() }
            attachmentStore.delete(path) // the downscaled copy is the one we keep
            bytes?.let { addPendingAttachmentFromBytes(it) }
        }
    }

    /** A gallery pick (`ActivityResultContracts.PickVisualMedia`) — read via contentResolver
     *  since it's a SAF/MediaStore Uri, not one of ours. */
    fun addPendingAttachmentFromUri(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            bytes?.let { addPendingAttachmentFromBytes(it) }
        }
    }

    private suspend fun addPendingAttachmentFromBytes(bytes: ByteArray) {
        val path = withContext(Dispatchers.IO) { downscaleAndStore(bytes) } ?: return
        _pendingAttachments.value = _pendingAttachments.value +
            PendingAttachment(id = java.util.UUID.randomUUID().toString(), path = path, mime = "image/jpeg")
    }

    /** Longest edge to [ATTACHMENT_MAX_EDGE], JPEG re-encode q85 (plan §Composer) — bounds
     *  token cost across providers regardless of source resolution. Decode+scale is real CPU
     *  work on a full-res photo, so this always runs off the main thread (caller is on IO). */
    private fun downscaleAndStore(bytes: ByteArray): String? = runCatching {
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val longestEdge = maxOf(original.width, original.height)
        val scaled = if (longestEdge > ATTACHMENT_MAX_EDGE) {
            val scale = ATTACHMENT_MAX_EDGE.toFloat() / longestEdge
            val w = (original.width * scale).toInt().coerceAtLeast(1)
            val h = (original.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(original, w, h, true)
        } else {
            original
        }
        try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            attachmentStore.save(out.toByteArray(), "jpg")
        } finally {
            // Always recycle both the decoded source and (when downscaling happened) the
            // scaled copy — previously only `original` was recycled, and only when
            // `scaled !== original`, which leaked the held bitmap on every attach when no
            // downscale was needed (already-small source: gallery thumbnail, screenshot,
            // re-picking an already-downscaled attachment) and leaked `scaled` itself
            // whenever downscaling did happen.
            if (scaled !== original) scaled.recycle()
            original.recycle()
        }
    }.getOrNull()

    /** Remove one not-yet-sent attachment (✕ on the strip) and delete its downscaled file —
     *  nothing else can reference it yet, so there's no orphan-cleanup tradeoff here. */
    fun removePendingAttachment(id: String) {
        val target = _pendingAttachments.value.firstOrNull { it.id == id } ?: return
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
        attachmentStore.delete(target.path)
    }

    private fun clearPendingAttachments() {
        _pendingAttachments.value = emptyList()
    }

    /** Every conversation (one per root), newest activity first, for the map. */
    val conversations: StateFlow<List<Conversations.Summary>> =
        repository.observeTree()
            .map { Conversations.summarize(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The active conversation as an indented outline (§8.5). Scoped to the active
     * root: the cross-conversation view is the [conversations] list above it.
     */
    val treeOutline: StateFlow<List<TreeOutline.Row>> =
        combine(repository.observeTree(), activeLeafId) { tree, leaf ->
            val rows = TreeOutline.build(tree, leaf)
            val activeRoot = leaf?.let { Conversations.rootOf(tree, it) }
                ?: return@combine rows.takeIf { tree.roots().size <= 1 }.orEmpty()
            // Rows are grouped by root in walk order; keep the active root's slice.
            val start = rows.indexOfFirst { it.depth == 0 && it.node.id == activeRoot }
            if (start < 0) return@combine emptyList()
            val next = rows.drop(start + 1).indexOfFirst { it.depth == 0 }
            val end = if (next < 0) rows.size else start + 1 + next
            rows.subList(start, end)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    /** Open a conversation from the map: land on the tip of its newest branch. */
    fun openConversation(rootId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        // Honest "most used" signal for the Conversations room: count the open here, where the
        // user actually opens a chat (on-device only; nothing leaves the device).
        session.recordConversationOpen(rootId)
        viewModelScope.launch {
            val tree = repository.tree()
            moveLeaf(tree.descendToLeaf(rootId) ?: return@launch)
            recomputeStatusAsync()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || transient.value.genPhase != GenPhase.IDLE) return
        if (dev.fonebrew.ui.components.isShellEscape(trimmed)) { sendShellEscape(trimmed); return }
        if (transient.value.imageMode) { sendImage(trimmed); return }
        if (transient.value.object3dMode) { sendObject3d(trimmed, transient.value.object3dScope); return }
        if (transient.value.councilMode) { sendCouncil(trimmed); return }
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        // W2: the globe toggle only ever takes effect on a model that actually supports search
        // (defense in depth — the composer chip is already disabled otherwise).
        val webSearchOn = transient.value.webSearchOn && spec.supportsSearch
        val engine = engines.engineFor(spec, webSearchEnabled = webSearchOn) ?: return // not runnable yet

        // W1: a vision-blind model with pending photos never silently drops them — refuse the
        // send with a visible error instead (plan §Send path). Nothing is inserted.
        val attachments = _pendingAttachments.value
        if (attachments.isNotEmpty() && !spec.supportsVision) {
            transient.value = transient.value.copy(
                error = "${spec.displayName} can't see images — switch model or remove the photo",
            )
            return
        }

        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            try {
                val parent = activeLeafId.value?.let { repository.node(it) }
                val metadata = if (attachments.isNotEmpty()) {
                    mapOf(
                        Conversations.ATTACHMENTS_KEY to Attachments.encode(
                            attachments.map { Attachments.Attachment(path = it.path, mime = it.mime) },
                        ),
                    )
                } else {
                    emptyMap()
                }
                val userNode = Nodes.child(
                    parent = parent,
                    role = Role.USER,
                    content = trimmed,
                    now = System.currentTimeMillis(),
                    metadata = metadata,
                )
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
                moveLeaf(userNode.id)
                clearPendingAttachments()
                runTurn(spec, engine, userNode, webSearchEnabled = webSearchOn)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "send failed")
            }
        }
    }

    /**
     * `!`-sigil shell escape (Jupyter/IPython convention): runs the rest of [text] as a single
     * local command on this phone and posts its raw output as a turn — no model involved. Uses
     * [dev.fonebrew.data.remote.LocalExec], a one-shot exec, not the persistent interactive pty
     * the Terminal facet uses — a chat turn wants a finished result, not a live session.
     */
    private fun sendShellEscape(text: String) {
        val command = dev.fonebrew.ui.components.shellEscapeCommand(text) ?: return
        viewModelScope.launch {
            transient.value = transient.value.copy(error = null, genPhase = GenPhase.GENERATING)
            try {
                val parent = activeLeafId.value?.let { repository.node(it) }
                val userNode = Nodes.child(parent, Role.USER, text, System.currentTimeMillis())
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
                moveLeaf(userNode.id)
                val output = dev.fonebrew.data.remote.LocalExec.run(command, appContext.filesDir)
                val resultNode = Nodes.child(
                    userNode, Role.ASSISTANT, output.ifBlank { "(no output)" }, System.currentTimeMillis(),
                    metadata = mapOf("shell" to "true"),
                )
                repository.insert(resultNode)
                moveLeaf(resultNode.id)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "shell command failed")
            } finally {
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
            }
        }
    }

    /**
     * Regenerate a reply for the user turn the path currently ends on — the
     * state a failed or stopped-before-output turn leaves behind.
     */
    fun regenerate() {
        if (transient.value.genPhase != GenPhase.IDLE) return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val webSearchOn = transient.value.webSearchOn && spec.supportsSearch
        val engine = engines.engineFor(spec, webSearchEnabled = webSearchOn) ?: return
        viewModelScope.launch {
            val leafId = activeLeafId.value ?: return@launch
            val userNode = repository.node(leafId)?.takeIf { it.role == Role.USER } ?: return@launch
            transient.value = transient.value.copy(error = null)
            runTurn(spec, engine, userNode, webSearchEnabled = webSearchOn)
        }
    }

    /**
     * Load + stream + persist one assistant turn under [userNode]. KV-cache
     * (§8.3): resume from the node being continued (the user turn's parent) and
     * snapshot keyed to the new assistant node — local engine only; cloud/echo
     * ignore the session paths.
     */
    private suspend fun runTurn(
        spec: ModelSpec,
        engine: InferenceEngine,
        userNode: MessageNode,
        webSearchEnabled: Boolean = false,
    ) {
        val isLocal = spec.runtime == Runtime.LOCAL_GGUF
        val loadPath = userNode.parentId
            ?.takeIf { isLocal && kvCache.exists(it) }
            ?.let { kvCache.pathFor(it) }
        val assistantId = java.util.UUID.randomUUID().toString()
        val savePath = if (isLocal) kvCache.pathFor(assistantId) else null

        // Keep the process alive while an ON-DEVICE model loads/generates (survives minimize).
        // Echo/cloud turns don't hold a model process, so they never need the foreground service —
        // and starting one for them risks the framework's async "FGS did not start in time" crash
        // (it sits outside this try, so it can't be caught). This is the dev-Echo send crash.
        if (isLocal) GenerationService.start(appContext)
        stopRequested = false
        try {
            // Load the active model (idempotent; a local GGUF may take several
            // seconds — shown as the "loading model" phase).
            transient.value = transient.value.copy(genPhase = GenPhase.LOADING)
            engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
            repository.setTokenCount(
                userNode.id, spec.tokenizerId,
                runCatching { engine.countTokens(userNode.content) }.getOrDefault(0),
            )
            if (stopRequested) {
                // Stopped while the model was loading: keep the user turn, no reply.
                transient.value = transient.value.copy(stream = StreamState(), genPhase = GenPhase.IDLE)
                return
            }

            // Stream the assistant turn, keeping per-token data (entropy) for
            // the §5a colouring.
            transient.value = transient.value.copy(
                stream = StreamState(tokens = emptyList(), isGenerating = true),
                prefillPending = false,
                genPhase = GenPhase.GENERATING,
            )
            val fullPath = repository.path(userNode.id)
            // THREAD_TOPOLOGY_PLAN.md WP3: route the prompt through the newest compaction
            // boundary, if one applies to this path — the model sees the compacted
            // representation above the boundary, not the resent verbatim originals.
            val path = effectivePromptPath(fullPath)
            val genStart = System.currentTimeMillis()
            val tokens = mutableListOf<GeneratedToken>()
            collectCancellable {
                engine.generate(path, SamplingParams(), loadPath, savePath).collect { token ->
                    tokens += token
                    transient.value = transient.value.copy(
                        stream = StreamState(tokens = tokens.toList(), isGenerating = true),
                    )
                }
            }

            // Persist the assistant turn (tagged with the producing model; a
            // stopped turn keeps its partial text and says it was cut).
            val assistantText = tokens.joinToString("") { it.text }.trim()
            if (assistantText.isEmpty() && stopRequested) {
                transient.value = transient.value.copy(stream = StreamState(), genPhase = GenPhase.IDLE)
                return
            }
            // Cost (G1): price a finished cloud turn from the provider-reported usage the
            // engine captured this turn (UsageAccumulator). On-device turns report no usage,
            // so they carry no cost line — the honest "no money changed hands" state.
            val cloudEngine = engine as? dev.fonebrew.inference.cloud.CloudEngine
            val cloudUsage = cloudEngine?.lastUsage?.takeIf { it.totalTokens > 0 }
            val cloudCostMinor: Long? = cloudUsage?.let { usage ->
                // Count this turn against the provider's free-tier usage (owner ask).
                spec.providerId?.let { freeTierUsage.record(it, usage.inputTokens.toLong(), usage.outputTokens.toLong()) }
                usage.toAdviceCost(pricingStore.book.value.priceFor(engine.tokenizerId)).moneyMinor
            }
            val costMeta = if (cloudUsage != null) {
                mapOf(
                    "costMinor" to cloudCostMinor.toString(),
                    "tokensIn" to cloudUsage.inputTokens.toString(),
                    "tokensOut" to cloudUsage.outputTokens.toString(),
                )
            } else {
                emptyMap()
            }
            // W2 (web search): metadata["webSearch"]="true" records that the model was
            // *allowed* to search this turn — the watched-object fact — regardless of whether
            // it actually used the tool or any source came back. Sources/pause are only ever
            // non-empty/true on a CloudEngine that reported them via its SSE hooks.
            val searchSources = cloudEngine?.lastSources.orEmpty()
            val searchMeta = buildMap {
                if (webSearchEnabled) put(Conversations.WEB_SEARCH_KEY, "true")
                if (searchSources.isNotEmpty()) put(Conversations.SOURCES_KEY, Sources.encode(searchSources))
                if (cloudEngine?.wasPaused == true) put(Conversations.SEARCH_PAUSED_KEY, "true")
            }
            // 2026-08-29 audit gap 4 ("influence not edge-wise"): the only point this turn's
            // per-token entropy still exists is this in-memory `tokens` list — captured here, at
            // persist time, or never (see dev.fonebrew.domain.thread.MessageConfidence's own KDoc
            // for why mean-entropy and why "now or never"). Absent (no key at all), not a fake 0,
            // on a cloud turn or the Echo engine — neither ever reports entropy.
            val confidence = dev.fonebrew.domain.thread.MessageConfidence.fromEntropies(tokens.map { it.entropy })
            val confidenceMeta = confidence?.let {
                mapOf(dev.fonebrew.domain.thread.MessageConfidence.METADATA_KEY to it.toString())
            } ?: emptyMap()
            val assistantNode = Nodes.child(
                parent = userNode,
                role = Role.ASSISTANT,
                content = assistantText,
                now = System.currentTimeMillis(),
                modelId = spec.id,
                metadata = (if (stopRequested) mapOf("stopped" to "true") else emptyMap()) + costMeta + searchMeta + confidenceMeta,
                idGen = { assistantId }, // so the KV snapshot is keyed to this node
            )
            repository.insert(assistantNode)
            val outCount = runCatching { engine.countTokens(assistantText) }.getOrDefault(0)
            repository.setTokenCount(assistantNode.id, spec.tokenizerId, outCount)
            moveLeaf(assistantNode.id)

            // Usage ledger (Doc 07 "Myself"): one honest entry per turn. A cloud turn carries the
            // provider-reported tokens + cost; an on-device turn is counted with the model's own
            // tokenizer (flagged estimated — the prompt count approximates the templated prompt)
            // and costs nothing — the sovereignty record. On-device only; never leaves the device.
            // Guarded so a ledger write can never fail the turn itself.
            runCatching {
                // The chat's identity is the TRUE root, from the untruncated fullPath — a
                // compaction-boundary DROPPED root (rare, but possible for an F0 root with no
                // must-include directive) must never change which conversation a ledger entry
                // is filed under.
                val chatId = fullPath.firstOrNull()?.id ?: userNode.id
                val inCount = cloudUsage?.inputTokens?.toLong()
                    ?: runCatching { engine.countTokens(path.joinToString("\n") { it.content }).toLong() }.getOrDefault(0L)
                ledgerStore.append(
                    dev.fonebrew.domain.ledger.LedgerCapture.singleTurn(
                        timestampMillis = System.currentTimeMillis(),
                        chatId = chatId,
                        nodeId = assistantNode.id,
                        projectId = session.conversationProjects.value[chatId],
                        model = spec.id,
                        provider = spec.providerId ?: "on-device",
                        tier = if (isLocal) dev.fonebrew.domain.ledger.Tier.ON_DEVICE else dev.fonebrew.domain.ledger.Tier.CLOUD,
                        inputTokens = inCount,
                        outputTokens = outCount.toLong(),
                        estCostMinor = cloudCostMinor ?: 0L,
                        latencyMs = System.currentTimeMillis() - genStart,
                        status = if (stopRequested) dev.fonebrew.domain.ledger.Status.STOPPED else dev.fonebrew.domain.ledger.Status.COMPLETE,
                        estimated = cloudUsage == null,
                    ),
                )
            }
            // THREAD_TOPOLOGY_PLAN.md WP7: snapshot this turn's per-token stream into
            // lastTurnTokens before the reset below empties it — the Instruments panel's
            // TokenHeatmap inspects the turn that just finished, not only a live one.
            transient.value = transient.value.copy(
                stream = StreamState(),
                lastTurnTokens = tokens.toList(),
                genPhase = GenPhase.IDLE,
            )
            recomputeStatus(spec, assistantNode.id)
        } catch (t: Throwable) {
            transient.value = transient.value.copy(
                stream = StreamState(),
                error = t.message ?: "generation failed",
                genPhase = GenPhase.IDLE,
            )
        } finally {
            if (isLocal) GenerationService.stop(appContext)
        }
    }

    fun toggleCouncil() {
        transient.value = transient.value.copy(councilMode = !transient.value.councilMode)
    }

    fun toggleModelDiversity() {
        transient.value = transient.value.copy(modelDiversity = !transient.value.modelDiversity)
    }

    /** The composer's globe-chip toggle (W2 — web search): per-turn opt-in, default off. The
     *  UI only ever calls this when [ChatUiState.activeSupportsSearch] is true (the chip is
     *  disabled otherwise), but [send]/[regenerate] re-AND it against the active spec before
     *  it ever reaches an engine — defense in depth, not a second source of truth. */
    fun toggleWebSearch() {
        transient.value = transient.value.copy(webSearchOn = !transient.value.webSearchOn)
    }

    /** A single voice in a council fan-out: its label, the model + engine it runs on,
     *  and an optional persona system prompt. */
    private data class Voice(
        val label: String,
        val spec: ModelSpec,
        val engine: InferenceEngine,
        val systemPrompt: String?,
    )

    private fun councilVoices(): List<Voice> {
        return if (transient.value.modelDiversity) {
            // Model-diversity: each genuinely different runnable model is a voice
            // (a group chat of models), no personas.
            registry.allSpecs()
                .filter { it.runtime != Runtime.ECHO_DEV && engines.isRunnable(it) }
                .mapNotNull { spec -> engines.engineFor(spec)?.let { Voice(spec.displayName, spec, it, null) } }
        } else {
            // Persona council = the editable roster (IA §B4): each member runs its own model
            // (or the chat's active model) with its instructions + long-term memory folded in.
            val activeSpec = activeModelId.value?.let { registry.byId(it) }
            councilStore.participants.value.ifEmpty {
                dev.fonebrew.domain.council.Council.defaultAgents.map {
                    dev.fonebrew.data.Participant("d-${it.name}", it.name, it.systemPrompt)
                }
            }.mapNotNull { p ->
                val spec = (p.modelId?.let { registry.byId(it) } ?: activeSpec) ?: return@mapNotNull null
                val engine = engines.engineFor(spec) ?: return@mapNotNull null
                Voice(p.name, spec, engine, dev.fonebrew.data.CouncilStore.systemPromptFor(p))
            }
        }
    }

    /**
     * Council fan-out (handoff §4a/§4b): send the same context to N named agents
     * (persona-diversity — one model, different system prompts) and hold their
     * answers as sibling responses under the user turn. On-device this is
     * sequential — one phone cannot truly run N models at once (§4b) — streamed
     * into separate panes as each completes.
     */
    private fun sendCouncil(text: String) {
        val allVoices = councilVoices()
        if (allVoices.isEmpty()) {
            transient.value = transient.value.copy(
                error = if (transient.value.modelDiversity) "no runnable models for a model-diversity council — download/add at least two" else "active model not runnable",
            )
            return
        }
        // A leading "@Name" addresses one participant only — same fan-out machinery, just
        // narrowed to a single voice for this turn (handoff §4a follow-up).
        val addressee = dev.fonebrew.domain.council.CouncilRouting.addressee(text, allVoices.map { it.label })
        val voices = addressee?.let { name -> allVoices.filter { it.label.equals(name, ignoreCase = true) } } ?: allVoices

        // W1: same "never silently drop a pending photo" guarantee as the single-model send
        // path, adapted for a fan-out — block the whole turn (not a per-voice partial send)
        // when nothing in this council can see it, rather than sending the images to some
        // voices and silently dropping them for the rest.
        val attachments = _pendingAttachments.value
        if (attachments.isNotEmpty() && voices.none { it.spec.supportsVision }) {
            transient.value = transient.value.copy(
                error = "no voice in this council can see images — switch model or remove the photo",
            )
            return
        }

        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            val parent = activeLeafId.value?.let { repository.node(it) }
            val userNode = Nodes.child(
                parent, Role.USER, text, System.currentTimeMillis(),
                metadata = buildMap {
                    addressee?.let { put("addressedTo", it) }
                    // Attach like the single-model path; each voice's own engine gates on its
                    // own model's supportsVision (AnthropicEngine/GeminiEngine/OpenAiCompatEngine
                    // contentOf()), so a mixed vision/blind council sees the image only through
                    // the voices that can actually read it — never a hard requirement that every
                    // voice support vision, matching the "some voices are blind" reality of a
                    // model-diversity council.
                    if (attachments.isNotEmpty()) {
                        put(
                            Conversations.ATTACHMENTS_KEY,
                            Attachments.encode(attachments.map { Attachments.Attachment(path = it.path, mime = it.mime) }),
                        )
                    }
                },
            )
            try {
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "council failed")
                return@launch
            }
            moveLeaf(userNode.id)
            clearPendingAttachments()

            GenerationService.start(appContext)
            stopRequested = false
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.LOADING)
                val basePath = repository.path(userNode.id)
                val cards = voices.map { CouncilCard(it.label, "", false, null) }.toMutableList()
                transient.value = transient.value.copy(councilStreaming = cards.toList(), genPhase = GenPhase.GENERATING)
                for ((i, voice) in voices.withIndex()) {
                    if (stopRequested) break // stop skips the remaining voices
                    voice.engine.loadModel(voice.spec.modelPath ?: "(dev)", voice.spec.contextWindow)
                    if (stopRequested) break
                    val msgs = buildList {
                        voice.systemPrompt?.let { add(MessageNode("council-sys-$i", null, Role.SYSTEM, it, createdAt = System.currentTimeMillis())) }
                        addAll(basePath)
                    }
                    val sb = StringBuilder()
                    collectCancellable {
                        voice.engine.generate(msgs, SamplingParams()).collect { tok ->
                            sb.append(tok.text)
                            cards[i] = cards[i].copy(text = sb.toString())
                            transient.value = transient.value.copy(councilStreaming = cards.toList())
                        }
                    }
                    if (sb.isBlank() && stopRequested) break
                    val node = Nodes.child(
                        parent = userNode, role = Role.ASSISTANT, content = sb.toString().trim(),
                        now = System.currentTimeMillis(), modelId = voice.spec.id,
                        metadata = buildMap {
                            put("council", userNode.id)
                            put("agent", voice.label)
                            if (stopRequested) put("stopped", "true")
                        },
                    )
                    repository.insert(node)
                    repository.setTokenCount(node.id, voice.spec.tokenizerId, runCatching { voice.engine.countTokens(sb.toString()) }.getOrDefault(0))
                    cards[i] = cards[i].copy(done = true, nodeId = node.id)
                    transient.value = transient.value.copy(councilStreaming = cards.toList())
                }
                // Leaf stays at the user node: the panel is now held simultaneously.
                transient.value = transient.value.copy(councilStreaming = null, genPhase = GenPhase.IDLE)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(councilStreaming = null, genPhase = GenPhase.IDLE, error = t.message ?: "council failed")
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    /**
     * Image turn (§6): the prompt becomes a user node, the generated image an
     * assistant node tagged [Conversations.IMAGE_KEY] — every turn is a node,
     * including image turns. On-device SD is preferred (binding rule 2); a cloud
     * image provider with a key is the explicit fallback; neither → error.
     */
    private fun sendImage(prompt: String) {
        val sdModel = sdModels.models.value.firstOrNull()
        val cloud = imageProviders.providers.value.firstOrNull { imageProviders.hasApiKey(it.id) }
        if (sdModel == null && cloud == null) {
            transient.value = transient.value.copy(
                error = "no image model — download one in Models, or add an image provider in Settings",
            )
            return
        }
        // W1: image mode is text-to-image only — [ImageParams] has no image-input field at
        // all, so unlike the single-model/council paths there's no engine that could ever read
        // a pending photo here. Refuse the send rather than silently discarding it (same
        // "never silently drops them" guarantee as the vision-blind-model refusal in send()).
        if (_pendingAttachments.value.isNotEmpty()) {
            transient.value = transient.value.copy(
                error = "image generation doesn't use a photo attachment — switch mode or remove the photo",
            )
            return
        }
        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            val parent = activeLeafId.value?.let { repository.node(it) }
            val userNode = Nodes.child(parent, Role.USER, prompt, System.currentTimeMillis())
            try {
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "send failed")
                return@launch
            }
            moveLeaf(userNode.id)

            GenerationService.start(appContext)
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.GENERATING)
                val (path, producerId) = if (sdModel != null) {
                    // On-device: unload the LLM first (RAM), then the slow SD run.
                    engines.unloadLocalModel()
                    val engine = dev.fonebrew.inference.image.SdImageEngine(sdModel.path, imageStore)
                    try {
                        engine.generate(prompt, ImageParams(size = 512, steps = 20)) to "sd:${sdModel.name}"
                    } finally {
                        engine.release()
                    }
                } else {
                    val key = imageProviders.apiKey(cloud!!.id)
                        ?: throw IllegalStateException("no key for ${cloud.displayName}")
                    val engine = ImageEngineFactory.create(cloud, key, imageStore)
                    engine.generate(prompt, ImageParams()) to "image:${cloud.id}"
                }
                val imageNode = Nodes.child(
                    parent = userNode,
                    role = Role.ASSISTANT,
                    content = "",
                    now = System.currentTimeMillis(),
                    modelId = producerId,
                    metadata = mapOf(Conversations.IMAGE_KEY to path),
                )
                repository.insert(imageNode)
                moveLeaf(imageNode.id)
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(
                    genPhase = GenPhase.IDLE,
                    error = t.message ?: "image generation failed",
                )
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    /** What one 3D generation produced, before it's minted into a node — the shared shape
     *  [generateObject3dOnDevice]/[generateObject3dCloud] return to [sendObject3d]. */
    private data class Object3dGeneration(
        val relativePath: String,
        val format: String,
        val source: Object3dSource,
        val provider: dev.fonebrew.domain.object3d.Object3dCloudProvider?,
        val producerId: String,
    )

    /**
     * 3D-object turn (docs/design/objects-3d.md §1): the prompt becomes a user node, the
     * generated model an assistant node carrying [Object3dNodeMeta.metadata] — every turn is a
     * node, including 3D turns, exactly like [sendImage]'s image turns.
     */
    private fun sendObject3d(prompt: String, scope: Object3dGenScope) {
        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            val parent = activeLeafId.value?.let { repository.node(it) }
            val userNode = Nodes.child(parent, Role.USER, prompt, System.currentTimeMillis())
            try {
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "send failed")
                return@launch
            }
            moveLeaf(userNode.id)

            GenerationService.start(appContext)
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.GENERATING)
                val generated = when (scope) {
                    Object3dGenScope.ON_DEVICE -> generateObject3dOnDevice(prompt)
                    Object3dGenScope.CLOUD -> generateObject3dCloud(prompt)
                }
                val objectNode = Nodes.child(
                    parent = userNode,
                    role = Role.ASSISTANT,
                    content = "",
                    now = System.currentTimeMillis(),
                    modelId = generated.producerId,
                    metadata = Object3dNodeMeta.metadata(
                        relativePath = generated.relativePath,
                        format = generated.format,
                        source = generated.source,
                        provider = generated.provider,
                        prompt = prompt,
                    ),
                )
                repository.insert(objectNode)
                moveLeaf(objectNode.id)
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE, object3dMode = false)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(
                    genPhase = GenPhase.IDLE,
                    error = t.message ?: "3D generation failed",
                )
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    /** §4: prompts the ACTIVE chat engine (on-device by default, binding rule 2) via the same
     *  [EngineGenerator] seam `GitConnect`/`CodeLens` use for a one-shot model call. Never falls
     *  back to cloud on its own — that is only ever the user's separate, explicit mini-chooser
     *  pick (rule 2's "cloud is opt-in per use, never a hidden fallback"). */
    private suspend fun generateObject3dOnDevice(prompt: String): Object3dGeneration {
        val spec = activeModelId.value?.let { registry.byId(it) }
            ?: throw IllegalStateException("no active model — pick one to generate on-device")
        val engine = engines.engineFor(spec)
            ?: throw IllegalStateException("the active model isn't runnable yet")
        val generator = EngineGenerator(engine, spec.modelPath)
        return when (val outcome = ProceduralObjectEngine(generator).generate(prompt)) {
            is ProceduralOutcome.Dsl -> {
                val bytes = ProceduralSceneCodec.toJsonString(outcome.scene).toByteArray(Charsets.UTF_8)
                val relativePath = object3dStore.save(bytes, Object3dNodeMeta.DSL_JSON_EXTENSION)
                Object3dGeneration(relativePath, Object3dNodeMeta.DSL_JSON_FORMAT, Object3dSource.ON_DEVICE_GENERATED, null, "object3d:on-device")
            }
            is ProceduralOutcome.RawObj -> {
                val bytes = outcome.objText.toByteArray(Charsets.UTF_8)
                val relativePath = object3dStore.save(bytes, "obj")
                Object3dGeneration(relativePath, Object3dFormat.OBJ.name, Object3dSource.ON_DEVICE_GENERATED, null, "object3d:on-device")
            }
            is ProceduralOutcome.Failure ->
                throw IllegalStateException("on-device 3D generation failed: " + outcome.reasons.joinToString("; "))
        }
    }

    /** §5: a configured, keyed cloud 3D provider — always the user's explicit per-use opt-in
     *  (the mini-chooser), never a silent fallback from the on-device path above. */
    private suspend fun generateObject3dCloud(prompt: String): Object3dGeneration {
        val config = object3dProviders.providers.value.firstOrNull { object3dProviders.hasApiKey(it.id) }
            ?: throw IllegalStateException("no 3D cloud provider configured — add one in Settings → 3D")
        val apiKey = object3dProviders.apiKey(config.id)
            ?: throw IllegalStateException("no key for ${config.displayName}")
        val engine = CloudObject3dEngineFactory.create(config.kind, apiKey, config.baseUrl)
        val bytes = engine.textTo3d(prompt)
        val format = Object3dFormatSniffer.sniff(bytes) ?: Object3dFormat.GLB
        val relativePath = object3dStore.save(bytes, format.extensions.first())
        return Object3dGeneration(relativePath, format.name, Object3dSource.CLOUD_GENERATED, config.kind, "object3d:${config.kind.name.lowercase()}")
    }

    /**
     * §1's "3D file…" import: any supported file, copied into app storage and minted as a node
     * — "the preview anything's output path" (ChatGPT, Meshy, a slicer, a scanner…). [uri] is a
     * SAF `OpenDocument` result; the caller (ChatScreen) owns the picker itself.
     */
    fun importObject3d(uri: android.net.Uri, displayName: String?) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("couldn't open the picked file")
                val format = Object3dFormatSniffer.sniff(bytes, displayName)
                    ?: throw IllegalStateException("unrecognized 3D format — none of the supported loaders matched this file")
                val relativePath = object3dStore.save(bytes, format.extensions.first())

                val parent = activeLeafId.value?.let { repository.node(it) }
                val userNode = Nodes.child(
                    parent, Role.USER,
                    "3D file: ${displayName ?: format.label}",
                    System.currentTimeMillis(),
                )
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
                moveLeaf(userNode.id)

                val objectNode = Nodes.child(
                    parent = userNode,
                    role = Role.ASSISTANT,
                    content = "",
                    now = System.currentTimeMillis(),
                    metadata = Object3dNodeMeta.metadata(relativePath, format.name, Object3dSource.IMPORTED, null, null),
                )
                repository.insert(objectNode)
                moveLeaf(objectNode.id)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "3D import failed")
            }
        }
    }

    /** Every image turn across all conversations — the §6 "browse images" filter. */
    val imageNodes: StateFlow<List<MessageNode>> =
        repository.observeTree()
            .map { Conversations.imageNodes(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Model-as-aggregator convergence (§4b, a mode). The on-thesis default is
     * human-as-aggregator — the user simply picks a voice (branchFrom) — so this
     * is an explicit, optional action.
     */
    fun autoMergeCouncil() {
        if (transient.value.genPhase != GenPhase.IDLE) return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return
        viewModelScope.launch {
            val tree = repository.tree()
            val leaf = activeLeafId.value ?: return@launch
            val userNode = tree.node(leaf) ?: return@launch
            if (userNode.role != Role.USER) return@launch
            val kids = tree.childrenOf(userNode.id).filter { it.metadata.containsKey("agent") }
            if (kids.isEmpty()) return@launch
            GenerationService.start(appContext)
            stopRequested = false
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.LOADING)
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val answers = kids.map { (it.metadata["agent"] ?: "voice") to it.content }
                val now = System.currentTimeMillis()
                val msgs = listOf(
                    MessageNode("agg-sys", null, Role.SYSTEM, Council.aggregatorSystemPrompt(), createdAt = now),
                    MessageNode("agg-usr", "agg-sys", Role.USER, Council.aggregatorUserPrompt(userNode.content, answers), createdAt = now + 1),
                )
                transient.value = transient.value.copy(genPhase = GenPhase.GENERATING)
                val sb = StringBuilder()
                collectCancellable {
                    engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
                }
                if (sb.isBlank() && stopRequested) {
                    transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
                    return@launch
                }
                val node = Nodes.child(
                    parent = userNode, role = Role.ASSISTANT, content = sb.toString().trim(),
                    now = System.currentTimeMillis(), modelId = spec.id,
                    metadata = buildMap {
                        put("aggregator", "model")
                        if (stopRequested) put("stopped", "true")
                    },
                )
                repository.insert(node)
                moveLeaf(node.id) // continue from the convergence
                // THREAD_TOPOLOGY_PLAN.md WP8: council auto-merge acceptance is one of the four
                // unified "choose for me" surfaces (owner decision 2) — record it the same way
                // chooseForMe records MODEL_PICK_BRANCH, with the individual voices as alternatives.
                delegationRecorder.record(
                    kind = dev.fonebrew.domain.thread.DelegationKind.COUNCIL_AUTOMERGE,
                    rootId = rootIdFor(userNode.id),
                    anchorMsgId = userNode.id,
                    chosenRef = node.id,
                    alternatives = kids.map { it.id },
                )
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE, error = t.message ?: "merge failed")
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    override fun onCleared() {
        // App task removed: don't leave a dangling foreground service.
        GenerationService.stop(appContext)
    }

    /**
     * On-demand prompt rewrite (handoff §6b): ask the active model to improve the
     * draft prompt. Runs over ephemeral (non-persisted) messages; the result is a
     * suggestion the user can accept or dismiss — never auto-applied.
     */
    fun refinePrompt(text: String) {
        val draft = text.trim()
        if (draft.isEmpty() || transient.value.rewriting || transient.value.genPhase != GenPhase.IDLE) return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return
        viewModelScope.launch {
            transient.value = transient.value.copy(rewriting = true, promptSuggestion = null, error = null)
            try {
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val now = System.currentTimeMillis()
                val msgs = listOf(
                    MessageNode("rw-sys", null, Role.SYSTEM, REWRITE_SYSTEM, createdAt = now),
                    MessageNode("rw-usr", "rw-sys", Role.USER, "Rewrite this prompt:\n\n$draft", createdAt = now + 1),
                )
                val sb = StringBuilder()
                engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
                transient.value = transient.value.copy(rewriting = false, promptSuggestion = sb.toString().trim())
            } catch (e: Throwable) {
                transient.value = transient.value.copy(rewriting = false, error = e.message ?: "rewrite failed")
            }
        }
    }

    fun clearSuggestion() {
        transient.value = transient.value.copy(promptSuggestion = null)
    }

    /** Restore to a touchpoint (handoff §2): make [nodeId] the active leaf. */
    fun branchFrom(nodeId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        moveLeaf(nodeId)
        recomputeStatusAsync()
    }

    /** Move between the alternative continuations at a branch point. */
    fun switchAlternative(branchNodeId: String, direction: Int) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            val tree = repository.tree()
            val children = tree.childrenOf(branchNodeId)
            if (children.size < 2) return@launch
            val leaf = activeLeafId.value ?: return@launch
            val path = tree.pathToRoot(leaf).map { it.id }
            val branchIndex = path.indexOf(branchNodeId)
            val currentChildId = path.getOrNull(branchIndex + 1)
            val currentIndex = children.indexOfFirst { it.id == currentChildId }.coerceAtLeast(0)
            val nextIndex = (currentIndex + direction).mod(children.size)
            moveLeaf(tree.descendToLeaf(children[nextIndex].id))
            recomputeStatusAsync()
        }
    }

    /**
     * THREAD_TOPOLOGY_PLAN.md WP8: "Choose for me" beside the pager row — owner decision 2's one
     * genuinely *new* "choose for me" surface (model-picks-at-branch-point; the other three reuse
     * existing council/gateway/default machinery). Asks the active model to pick one of
     * [branchNodeId]'s alternatives via [dev.fonebrew.domain.thread.DelegationPrompts], commits to
     * that alternative's tip the same way [branchFrom] does, and records a
     * [dev.fonebrew.domain.thread.DelegationKind.MODEL_PICK_BRANCH] delegation. An unparseable model
     * reply moves nothing and records nothing — see [dev.fonebrew.domain.thread.DelegationPrompts.parseChoice]'s
     * KDoc on why a failed parse is never silently treated as "picked the first one."
     */
    fun chooseForMe(branchNodeId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return
        viewModelScope.launch {
            val tree = repository.tree()
            val leaf = activeLeafId.value
            val activeChildId = leaf?.let { l ->
                val path = tree.pathToRoot(l)
                val idx = path.indexOfFirst { it.id == branchNodeId }
                if (idx < 0) null else path.getOrNull(idx + 1)?.id
            }
            val compare = dev.fonebrew.domain.tree.SiblingCompares.build(tree, branchNodeId, activeChildId)
            if (compare.alternatives.size < 2) return@launch
            GenerationService.start(appContext)
            stopRequested = false
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.LOADING)
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val objective = tree.node(branchNodeId)?.content.orEmpty()
                val now = System.currentTimeMillis()
                val msgs = listOf(
                    MessageNode(
                        "cfm-sys", null, Role.SYSTEM,
                        dev.fonebrew.domain.thread.DelegationPrompts.chooseSystemPrompt(), createdAt = now,
                    ),
                    MessageNode(
                        "cfm-usr", "cfm-sys", Role.USER,
                        dev.fonebrew.domain.thread.DelegationPrompts.chooseUserPrompt(objective, compare.alternatives.map { it.preview }),
                        createdAt = now + 1,
                    ),
                )
                transient.value = transient.value.copy(genPhase = GenPhase.GENERATING)
                val sb = StringBuilder()
                collectCancellable {
                    engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
                }
                val idx = dev.fonebrew.domain.thread.DelegationPrompts.parseChoice(sb.toString(), compare.alternatives.size)
                if (idx == null) {
                    transient.value = transient.value.copy(genPhase = GenPhase.IDLE, error = "Couldn't parse a choice from the model's reply.")
                    return@launch
                }
                val chosen = compare.alternatives[idx]
                val rootId = rootIdFor(branchNodeId)
                val alternatives = compare.alternatives.filterIndexed { i, _ -> i != idx }.map { it.childId }
                moveLeaf(chosen.leafId)
                delegationRecorder.record(
                    kind = dev.fonebrew.domain.thread.DelegationKind.MODEL_PICK_BRANCH,
                    rootId = rootId,
                    anchorMsgId = branchNodeId,
                    chosenRef = chosen.childId,
                    alternatives = alternatives,
                    now = System.currentTimeMillis(),
                )
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
                recomputeStatusAsync()
            } catch (t: Throwable) {
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE, error = t.message ?: "choose for me failed")
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    // ---- The Conversation Instrument (STUDIO_UX_SPEC.md §4-5) --------------------------------

    val verdicts: StateFlow<Map<String, dev.fonebrew.domain.curation.Verdict>> = curationStore.verdicts
        .map { list -> list.associateBy { it.msgId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val messageBookmarks: StateFlow<Map<String, List<dev.fonebrew.domain.curation.MessageBookmark>>> = curationStore.bookmarks
        .map { list -> list.groupBy { it.ref.msgId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val versionsByTip: StateFlow<Map<String, dev.fonebrew.domain.curation.Version>> = curationStore.versions
        .map { list -> list.associateBy { it.branchTipMsgId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val compactionDirectives: StateFlow<Map<String, dev.fonebrew.domain.curation.CompactionDirective>> = curationStore.directives
        .map { list -> list.associateBy { it.msgId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Verdict-drag / chevron action: sets or replaces the verdict on [msgId] (annotation only — never moves, files, or archives the message; see the curation data-model KDoc for the patent-design-around this is deliberately shaped around). */
    fun setVerdict(msgId: String, grade: Int) {
        viewModelScope.launch {
            curationStore.setVerdict(msgId, grade)
            aarsoEventLog.record(
                dev.fonebrew.domain.mirror.AarsoEventKind.VERDICT,
                """{"msgId":"$msgId","grade":$grade}""",
                now = System.currentTimeMillis(),
            )
        }
    }

    fun clearVerdict(msgId: String) {
        viewModelScope.launch { curationStore.clearVerdict(msgId) }
    }

    /** Double-tap: pin the whole message, or un-pin if it's already pinned. */
    fun toggleMessageBookmark(msgId: String, kind: dev.fonebrew.domain.curation.BookmarkKind = dev.fonebrew.domain.curation.BookmarkKind.REFERENCE) {
        viewModelScope.launch {
            val result = curationStore.toggleMessageBookmark(msgId, kind)
            if (result != null) {
                aarsoEventLog.record(
                    dev.fonebrew.domain.mirror.AarsoEventKind.BOOKMARK,
                    """{"msgId":"$msgId","kind":"${kind.name}"}""",
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    /** Double-tap on a code block: pin that block specifically. */
    fun toggleBlockBookmark(msgId: String, blockIndex: Int) {
        viewModelScope.launch { curationStore.toggleBlockBookmark(msgId, blockIndex) }
    }

    /** Curation sheet: "Mark branch as Version." */
    fun markVersion(branchTipMsgId: String, name: String, note: String? = null) {
        viewModelScope.launch {
            curationStore.markVersion(branchTipMsgId, name, note)
            aarsoEventLog.record(
                dev.fonebrew.domain.mirror.AarsoEventKind.VERSION,
                """{"branchTipMsgId":"$branchTipMsgId","name":${org.json.JSONObject.quote(name)}}""",
                now = System.currentTimeMillis(),
            )
        }
    }

    /** Curation sheet's fidelity dial: an explicit user choice, so it always wins outright — mustInclude preserves whatever was already set. */
    fun setCompactionDirective(msgId: String, mustInclude: Boolean, fidelity: dev.fonebrew.domain.curation.Fidelity) {
        viewModelScope.launch { curationStore.setDirective(msgId, mustInclude, fidelity) }
    }

    fun clearCompactionDirective(msgId: String) {
        viewModelScope.launch { curationStore.clearDirective(msgId) }
    }

    /**
     * Curation sheet's Must-include toggle, kept deliberately independent of the fidelity dial
     * (per [dev.fonebrew.domain.curation.CompactionDirective]'s own contract). Bug fixed here: a
     * naive implementation that hardcodes a fallback fidelity (e.g. F1) when no directive exists
     * yet would *silently downgrade* a message that currently auto-resolves higher — e.g. a
     * +2-verdict message auto-floors to F3 with no directive at all; flipping Must-include must
     * not be the thing that knocks it down to F1. Instead, when no directive exists, this first
     * resolves what the fidelity *would already be* under the deterministic contract (same
     * inputs [dev.fonebrew.domain.curation.CompactionEngine] would use) and preserves exactly that.
     */
    fun toggleMustInclude(msgId: String) {
        viewModelScope.launch {
            val current = curationStore.directiveFor(msgId)
            val fidelity = current?.fidelity ?: resolveCurrentFidelity(msgId)
            curationStore.setDirective(msgId, mustInclude = current?.mustInclude != true, fidelity = fidelity)
        }
    }

    /** What [dev.fonebrew.domain.curation.CompactionContract] would resolve [msgId] to right now, absent any directive — i.e. its default fidelity under its current verdict/bookmark/version-spine signals. */
    private suspend fun resolveCurrentFidelity(msgId: String): dev.fonebrew.domain.curation.Fidelity {
        val verdict = curationStore.verdictFor(msgId)
        val bookmarked = curationStore.bookmarksFor(msgId).isNotEmpty()
        val versions = curationStore.versions.first()
        val spineIds = runCatching { dev.fonebrew.domain.curation.VersionSpines.computeIds(repository.tree(), versions) }
            .getOrDefault(emptySet())
        return dev.fonebrew.domain.curation.CompactionContract.resolve(
            msgId = msgId,
            directive = null,
            verdict = verdict,
            isBookmarked = bookmarked,
            isOnVersionSpine = msgId in spineIds,
        ).fidelity
    }

    // ---- Thread topology (THREAD_TOPOLOGY_PLAN.md WP1) --------------------------------------

    val threadMarkers: StateFlow<Map<String, List<dev.fonebrew.domain.thread.ThreadMarker>>> = threadMarkerStore.markers
        .map { list -> list.filter { it.anchorMsgId != null }.groupBy { it.anchorMsgId!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val delegations: StateFlow<Map<String, List<dev.fonebrew.domain.thread.DelegationEvent>>> = delegationStore.delegations
        .map { list -> list.filter { it.anchorMsgId != null }.groupBy { it.anchorMsgId!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * THREAD_TOPOLOGY_PLAN.md WP6's "mega-thread": every conversation grouped by
     * [dev.fonebrew.domain.thread.ThreadChains] into Fork/Spawn-connected chains, newest activity
     * first — TreeRoom's "Chain" chip view and ChatsRoom's "spawned from …" chip both read this
     * one flow rather than re-deriving lineage groupings independently. Cross-conversation like
     * [conversations] above it (not scoped to the active root), and combined from the same two
     * already-live flows [conversations] and [threadMarkerStore]'s full marker list — no new
     * store or query.
     */
    val threadChains: StateFlow<List<dev.fonebrew.domain.thread.ThreadChains.Chain>> =
        combine(conversations, threadMarkerStore.markers) { summaries, markers ->
            dev.fonebrew.domain.thread.ThreadChains.build(summaries, markers)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The conversation root [msgId] currently belongs to, or null if [msgId] isn't in the tree (shouldn't happen for a live turn action, but the tree is the source of truth, not a cached assumption). */
    private suspend fun rootIdFor(msgId: String): String? =
        dev.fonebrew.domain.tree.Conversations.rootOf(repository.tree(), msgId)

    /** TurnActionsSheet: "Mark chapter here…" — same shape as [markVersion]: caller supplies the anchor + a name, the root is resolved from the tree, not passed in. */
    fun markChapter(anchorMsgId: String, label: String, note: String? = null) {
        viewModelScope.launch {
            val rootId = rootIdFor(anchorMsgId) ?: return@launch
            val marker = threadMarkerStore.markChapter(rootId, anchorMsgId, label, note)
            aarsoEventLog.record(
                dev.fonebrew.domain.mirror.AarsoEventKind.CHAPTER_MARK,
                """{"markerId":"${marker.id}","anchorMsgId":"$anchorMsgId","label":${org.json.JSONObject.quote(label)}}""",
                now = System.currentTimeMillis(),
            )
        }
    }

    fun renameChapter(marker: dev.fonebrew.domain.thread.ThreadMarker, label: String, note: String? = marker.note) {
        viewModelScope.launch { threadMarkerStore.renameChapter(marker, label, note) }
    }

    fun removeChapter(marker: dev.fonebrew.domain.thread.ThreadMarker) {
        viewModelScope.launch { threadMarkerStore.removeChapter(marker) }
    }

    /** TurnActionsSheet: "Start fresh session here" — [anchorMsgId] defaults to the currently active leaf when invoked without one. */
    fun markSessionStart(anchorMsgId: String? = activeLeafId.value) {
        viewModelScope.launch {
            val anchor = anchorMsgId ?: return@launch
            val rootId = rootIdFor(anchor) ?: return@launch
            val marker = threadMarkerStore.markSessionStart(rootId, anchor)
            aarsoEventLog.record(
                dev.fonebrew.domain.mirror.AarsoEventKind.SESSION_START,
                """{"markerId":"${marker.id}"}""",
                now = System.currentTimeMillis(),
            )
        }
    }

    // ---- Compaction runs (THREAD_TOPOLOGY_PLAN.md WP3) -------------------------------------

    /** InstrumentsStrip / TurnActionsSheet "Compact conversation…": the preview rows for a run
     *  anchored at [anchorMsgId] (defaults to the active leaf) — nothing is generated or sent to
     *  a model yet, see [dev.fonebrew.domain.curation.CompactionPreviewPresenter]'s own KDoc. */
    private val _compactionPreview = MutableStateFlow<List<dev.fonebrew.domain.curation.CompactionPreviewPresenter.Row>?>(null)
    val compactionPreview: StateFlow<List<dev.fonebrew.domain.curation.CompactionPreviewPresenter.Row>?> = _compactionPreview.asStateFlow()

    /** The anchor a confirmed [runCompaction] call will run against — set alongside
     *  [_compactionPreview] so the sheet's confirm action doesn't need the anchor threaded back
     *  through the UI layer. */
    private var compactionAnchorId: String? = null

    private val _compactionRunning = MutableStateFlow(false)
    val compactionRunning: StateFlow<Boolean> = _compactionRunning.asStateFlow()

    /** A run whose F3 (must-reproduce-exactly) contract was violated — CompactionSheet's
     *  **loud** failure dialog (binding constraint: "a violation fails the run loudly — never
     *  silently"). Nothing was persisted when this is non-null. */
    private val _compactionFailure = MutableStateFlow<List<dev.fonebrew.domain.curation.F3Violation>?>(null)
    val compactionFailure: StateFlow<List<dev.fonebrew.domain.curation.F3Violation>?> = _compactionFailure.asStateFlow()

    fun openCompactionPreview(anchorMsgId: String? = activeLeafId.value) {
        val anchor = anchorMsgId ?: return
        compactionAnchorId = anchor
        viewModelScope.launch {
            val messages = repository.path(anchor)
            _compactionPreview.value = dev.fonebrew.domain.curation.CompactionPreviewPresenter.build(
                messages = messages,
                directives = curationStore.directives.first().associateBy { it.msgId },
                verdicts = curationStore.verdicts.first().associateBy { it.msgId },
                bookmarkedIds = curationStore.bookmarks.first().mapTo(mutableSetOf()) { it.ref.msgId },
                versionSpineIds = versionSpineIds(),
            )
        }
    }

    fun closeCompactionPreview() {
        _compactionPreview.value = null
        compactionAnchorId = null
    }

    fun dismissCompactionFailure() {
        _compactionFailure.value = null
    }

    /** [dev.fonebrew.domain.curation.VersionSpines.computeIds] over the current tree + version set —
     *  shared by the preview and the real run so both resolve fidelity identically. */
    private suspend fun versionSpineIds(): Set<String> {
        val versions = curationStore.versions.first()
        return runCatching { dev.fonebrew.domain.curation.VersionSpines.computeIds(repository.tree(), versions) }
            .getOrDefault(emptySet())
    }

    /**
     * CompactionSheet's confirm action: actually calls the model ([dev.fonebrew.inference.EngineCompactionAgent])
     * for every message the preview resolved to a non-verbatim, non-dropped fate, mechanically
     * verifies F3 survived byte-for-byte, and — only on success — persists the
     * [dev.fonebrew.domain.curation.Receipt] via [receiptStore] and records a
     * [dev.fonebrew.domain.thread.ThreadMarkerKind.COMPACTION_RUN] marker anchored at [anchorMsgId]
     * so [effectivePromptPath] picks it up on the next turn. A [dev.fonebrew.domain.curation.CompactionRunResult.Failure]
     * saves nothing — the loud dialog is the only effect.
     */
    fun runCompaction(anchorMsgId: String? = compactionAnchorId) {
        if (_compactionRunning.value) return
        val anchor = anchorMsgId ?: return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return
        viewModelScope.launch {
            _compactionRunning.value = true
            try {
                val rootId = rootIdFor(anchor) ?: return@launch
                val messages = repository.path(anchor)
                val now = System.currentTimeMillis()
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val result = dev.fonebrew.domain.curation.CompactionEngine.run(
                    messages = messages,
                    directives = curationStore.directives.first().associateBy { it.msgId },
                    verdicts = curationStore.verdicts.first().associateBy { it.msgId },
                    bookmarkedIds = curationStore.bookmarks.first().mapTo(mutableSetOf()) { it.ref.msgId },
                    versionSpineIds = versionSpineIds(),
                    agent = dev.fonebrew.inference.EngineCompactionAgent(engine),
                    now = now,
                )
                when (result) {
                    is dev.fonebrew.domain.curation.CompactionRunResult.Failure -> {
                        _compactionFailure.value = result.violations
                    }
                    is dev.fonebrew.domain.curation.CompactionRunResult.Success -> {
                        val envelope = dev.fonebrew.contracts.common.ContractEnvelope(
                            schemaVersion = "1.0.0",
                            objectId = java.util.UUID.randomUUID().toString(),
                            createdAtUtc = java.time.Instant.ofEpochMilli(now),
                            producer = dev.fonebrew.contracts.common.ProducerRef(name = "core-engine", version = "1.0.0"),
                            payload = result.receipt,
                        )
                        val objectId = receiptStore.append(
                            "compaction-run",
                            envelope,
                            dev.fonebrew.domain.curation.CompactionReceiptCodec::encodeReceipt,
                        )
                        threadMarkerStore.markCompactionRun(rootId, anchor, objectId, now)
                        aarsoEventLog.record(
                            dev.fonebrew.domain.mirror.AarsoEventKind.COMPACTION_RUN,
                            """{"anchorMsgId":"$anchor","receiptObjectId":"$objectId","entries":${result.receipt.entries.size}}""",
                            now = now,
                        )
                        _compactionPreview.value = null
                        compactionAnchorId = null
                        recomputeStatusAsync(spec)
                    }
                }
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "compaction failed")
            } finally {
                _compactionRunning.value = false
            }
        }
    }

    /**
     * THREAD_TOPOLOGY_PLAN.md WP3's "prompt truncation above newest boundary node": if a
     * [dev.fonebrew.domain.thread.ThreadMarkerKind.COMPACTION_RUN] marker applies to [fullPath],
     * returns the [dev.fonebrew.domain.curation.CompactionBoundary.effectivePath] built from that
     * run's persisted [dev.fonebrew.domain.curation.Receipt]; otherwise returns [fullPath] unchanged.
     * Fails open (returns [fullPath]) on any lookup/decode problem — a stale or unreadable
     * boundary must never block sending a turn, it just means this turn resends more context than
     * strictly necessary.
     */
    private suspend fun effectivePromptPath(fullPath: List<MessageNode>): List<MessageNode> {
        val rootId = fullPath.firstOrNull()?.id ?: return fullPath
        val markers = runCatching { threadMarkerStore.forRoot(rootId) }.getOrDefault(emptyList())
        val pathIds = fullPath.map { it.id }
        val boundaryAnchorId = dev.fonebrew.domain.curation.CompactionBoundary.newestBoundaryAnchorId(markers, pathIds)
            ?: return fullPath
        val marker = markers
            .filter { it.kind == dev.fonebrew.domain.thread.ThreadMarkerKind.COMPACTION_RUN && it.anchorMsgId == boundaryAnchorId }
            .maxByOrNull { it.at }
            ?: return fullPath
        val objectId = marker.payloadJson
            ?.let { runCatching { org.json.JSONObject(it).optString("receiptObjectId") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: return fullPath
        val receipt = loadReceipt(objectId) ?: return fullPath
        return dev.fonebrew.domain.curation.CompactionBoundary.effectivePath(fullPath, boundaryAnchorId, receipt.entries)
    }

    private suspend fun loadReceipt(objectId: String): dev.fonebrew.domain.curation.Receipt? {
        val row = receiptStore.forObjectId(objectId).first().lastOrNull() ?: return null
        return runCatching {
            dev.fonebrew.domain.contracts.EnvelopeCodec.decode(row.second, dev.fonebrew.domain.curation.CompactionReceiptCodec::decodeReceipt).payload
        }.getOrNull()
    }

    // ---- Fork / Spawn / lineage + compare (THREAD_TOPOLOGY_PLAN.md WP2) --------------------

    /**
     * Radial-menu / TurnActionsSheet "Fork from here": a full-fidelity, independent copy of the
     * conversation up to [nodeId] (see [dev.fonebrew.domain.tree.TreeFork]'s own KDoc for exactly
     * which nodes get copied and why) — lands the active leaf on the new conversation's tip.
     * Records the lineage receipt twice, the same "write-only log + queryable store" split every
     * other thread-topology writer here uses: an inert [dev.fonebrew.domain.mirror.AarsoEventKind.FORK_CREATED]
     * log line (Issue #2 — write-only, never read back) and a queryable
     * [dev.fonebrew.domain.thread.ThreadMarkerKind.LINEAGE_SRC] marker the Conversations/Tree rooms
     * can read to show "forked from …" (a later WP's surface; this WP only writes the marker).
     */
    fun forkFrom(nodeId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            val srcRootId = rootIdFor(nodeId) ?: return@launch
            val now = System.currentTimeMillis()
            val result = runCatching {
                dev.fonebrew.domain.tree.TreeFork.copySubtree(repository.tree(), nodeId, srcRootId, now)
            }.getOrElse {
                transient.value = transient.value.copy(error = it.message ?: "fork failed")
                return@launch
            }
            result.nodes.forEach { repository.insert(it) }
            moveLeaf(result.newRootId)
            threadMarkerStore.markLineageSource(
                newRootId = result.newRootId,
                srcRootId = srcRootId,
                srcNodeId = nodeId,
                lineageKind = dev.fonebrew.domain.tree.TreeFork.LineageKind.FORK,
                now = now,
            )
            recordLineageEvent(dev.fonebrew.domain.thread.ThreadEventKind.FORK_CREATED, srcRootId, nodeId, result.newRootId, now)
            recomputeStatusAsync()
        }
    }

    /**
     * Radial-menu / TurnActionsSheet "Spawn from here": a lightweight independent conversation
     * anchored by a single [dev.fonebrew.domain.bridge.SpawnBridge] bridge node — condensed carry-
     * forward rather than [forkFrom]'s full copy. The bridge's prose (the node's actual, model-fed
     * content) reuses [summarizeActivePath]'s pattern generalized to [nodeId]; its structural
     * display payload ([dev.fonebrew.domain.bridge.BridgeCodec]) is what
     * [dev.fonebrew.ui.components.SummaryNodeCard] mounts.
     */
    fun spawnFrom(nodeId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            val srcRootId = rootIdFor(nodeId) ?: return@launch
            val ancestors = repository.tree().pathToRoot(nodeId)
            if (ancestors.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            try {
                val prose = summarizeActivePath(nodeId)
                val spec = activeModelId.value?.let { registry.byId(it) }
                val srcExcerpt = ancestors.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()
                val spawnBridge = dev.fonebrew.domain.bridge.SpawnBridges.build(
                    srcRootId = srcRootId,
                    srcNodeId = nodeId,
                    srcExcerpt = srcExcerpt,
                    priorTurns = priorTurnsOf(ancestors),
                    authorModel = spec?.id,
                    authorProvenance = provenanceFor(spec),
                )
                val rootNode = Nodes.child(
                    parent = null,
                    role = Role.SYSTEM,
                    content = prose,
                    now = now,
                    metadata = mapOf(
                        dev.fonebrew.domain.tree.TreeFork.LINEAGE_KIND_KEY to dev.fonebrew.domain.tree.TreeFork.LineageKind.SPAWN.name,
                        dev.fonebrew.domain.tree.TreeFork.LINEAGE_SRC_ROOT_KEY to srcRootId,
                        dev.fonebrew.domain.tree.TreeFork.LINEAGE_SRC_NODE_KEY to nodeId,
                        dev.fonebrew.domain.tree.TreeFork.LINEAGE_AT_KEY to now.toString(),
                        dev.fonebrew.domain.bridge.BridgeCodec.BRIDGE_KEY to "spawn",
                        dev.fonebrew.domain.bridge.BridgeCodec.BRIDGE_PAYLOAD_KEY to dev.fonebrew.domain.bridge.BridgeCodec.encode(spawnBridge.summary),
                    ),
                )
                repository.insert(rootNode)
                moveLeaf(rootNode.id)
                threadMarkerStore.markLineageSource(
                    newRootId = rootNode.id,
                    srcRootId = srcRootId,
                    srcNodeId = nodeId,
                    lineageKind = dev.fonebrew.domain.tree.TreeFork.LineageKind.SPAWN,
                    now = now,
                )
                recordLineageEvent(dev.fonebrew.domain.thread.ThreadEventKind.SPAWN_CREATED, srcRootId, nodeId, rootNode.id, now)
                recomputeStatusAsync()
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "spawn failed")
            }
        }
    }

    /** Shared FORK_CREATED/SPAWN_CREATED writer for [forkFrom]/[spawnFrom] — the append-only,
     *  write-only event log (Issue #2: nothing here reads it back), encoded via
     *  [dev.fonebrew.domain.thread.ThreadCodec] so the payload matches `schemas/thread/thread-event.schema.json`
     *  field-for-field rather than a hand-rolled JSON string. */
    private suspend fun recordLineageEvent(
        kind: dev.fonebrew.domain.thread.ThreadEventKind,
        srcRootId: String,
        srcNodeId: String,
        newRootId: String,
        now: Long,
    ) {
        val event = dev.fonebrew.domain.thread.ThreadEvent(
            eventId = java.util.UUID.randomUUID().toString(),
            kind = kind,
            occurredAtUtc = java.time.Instant.ofEpochMilli(now),
            rootId = newRootId,
            anchorMsgId = srcNodeId,
            srcRootId = srcRootId,
            srcNodeId = srcNodeId,
            newRootId = newRootId,
        )
        val eventKind = if (kind == dev.fonebrew.domain.thread.ThreadEventKind.FORK_CREATED) {
            dev.fonebrew.domain.mirror.AarsoEventKind.FORK_CREATED
        } else {
            dev.fonebrew.domain.mirror.AarsoEventKind.SPAWN_CREATED
        }
        aarsoEventLog.record(eventKind, dev.fonebrew.domain.thread.ThreadCodec.encodeThreadEvent(event).toString(), now = now)
    }

    /** "Compare alternatives" on the pager row: the sheet's data for the branch point [branchNodeId]. */
    private val _compareSheet = MutableStateFlow<dev.fonebrew.domain.tree.SiblingCompares.Compare?>(null)
    val compareSheet: StateFlow<dev.fonebrew.domain.tree.SiblingCompares.Compare?> = _compareSheet.asStateFlow()

    fun openCompare(branchNodeId: String) {
        viewModelScope.launch {
            val tree = repository.tree()
            val leaf = activeLeafId.value
            val activeChildId = leaf?.let { l ->
                val path = tree.pathToRoot(l)
                val idx = path.indexOfFirst { it.id == branchNodeId }
                if (idx < 0) null else path.getOrNull(idx + 1)?.id
            }
            _compareSheet.value = dev.fonebrew.domain.tree.SiblingCompares.build(tree, branchNodeId, activeChildId)
        }
    }

    fun closeCompare() {
        _compareSheet.value = null
    }

    /**
     * Curation sheet: "Rewind from here." Ghosts the branch that was active (if any existed
     * below [nodeId]) and moves the active leaf to [nodeId] — the same primitive [branchFrom]
     * uses, plus the ghost annotation (STUDIO_UX_SPEC.md §4.5). Nothing is deleted: the ghosted
     * branch stays fully reachable via the existing alternative-branch (‹ ›) navigation.
     */
    fun rewindFrom(nodeId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            val tree = repository.tree()
            val currentLeaf = activeLeafId.value
            if (currentLeaf != null) {
                val isDescendant = runCatching { tree.pathToRoot(currentLeaf) }.getOrNull()
                    ?.any { it.id == nodeId } == true
                val now = System.currentTimeMillis()
                val ghost = dev.fonebrew.domain.curation.Rewind.planGhost(
                    currentLeafId = currentLeaf,
                    rewindToId = nodeId,
                    isDescendant = isDescendant,
                    now = now,
                )
                if (ghost != null) {
                    curationStore.recordGhost(ghost)
                    aarsoEventLog.record(
                        dev.fonebrew.domain.mirror.AarsoEventKind.REWIND,
                        """{"ghostedLeaf":"${ghost.branchTipMsgId}","rewindTo":"$nodeId"}""",
                        now = now,
                    )
                }
            }
            moveLeaf(nodeId)
            recomputeStatusAsync()
        }
    }

    /**
     * Switch the model the conversation continues with (handoff §3). Re-renders
     * the path under the new template / tokenizer to re-measure context, and
     * raises the prefill reminder: the KV cache cannot cross models.
     */
    fun switchModel(modelId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        val spec = registry.byId(modelId) ?: return
        setActiveModel(modelId)
        transient.value = transient.value.copy(prefillPending = true)
        recomputeStatusAsync(spec)
    }

    private fun recomputeStatusAsync(spec: ModelSpec? = null) {
        viewModelScope.launch {
            val active = spec ?: activeModelId.value?.let { registry.byId(it) } ?: return@launch
            recomputeStatus(active, activeLeafId.value)
        }
    }

    /** Recompute the per-path instrumentation: token I/O ratio and context fit. */
    private suspend fun recomputeStatus(spec: ModelSpec, leafId: String?) {
        if (leafId == null) {
            transient.value = transient.value.copy(
                context = null, tokenStats = null, instrumentsAssembly = null,
                delegationCounts = dev.fonebrew.domain.thread.DelegationCounts.EMPTY,
            )
            return
        }
        // THREAD_TOPOLOGY_PLAN.md WP3: the instrument reflects what would actually be SENT —
        // route through the newest compaction boundary the same way runTurn does, so "ctx
        // used/window" and the token ratio don't overstate a conversation a compaction run has
        // already shrunk.
        val path = effectivePromptPath(repository.path(leafId))

        // Token I/O ratio from stored counts — independent of the active model. Stored counts
        // are per ORIGINAL msgId, so a message compacted to a shorter GIST/FAITHFUL/TOMBSTONE
        // text still reports its pre-compaction count here (a DROPPED message correctly
        // contributes nothing, since it's absent from `path` entirely) — an honest known gap,
        // not silently wrong: the ratio undercounts how much a compacted-but-kept message
        // actually shrank, never overcounts.
        val counts = path.map { it.role to repository.totalTokens(it.id) }
        val stats = TokenStats.of(counts)

        // Context fit needs an engine to tokenize the templated prompt.
        val engine = engines.engineFor(spec)
        val context = if (engine == null) {
            null
        } else {
            val rendered = ChatTemplates.forId(spec.templateId).render(path)
            checkContext(engine.countTokens(rendered), spec.contextWindow)
        }

        // THREAD_TOPOLOGY_PLAN.md WP7: "what does the model see" — wire Doc 03's
        // ContextAssembly floor over the same effective path, for the Instruments panel.
        // One CorpusPiece per turn already on `path` (so it inherits the compaction-boundary
        // routing above); filed under the conversation's assigned project (SessionStore.
        // conversationProjects) or, when unassigned, the conversation's own root id — chat has
        // no cross-project corpus concept yet, so Scope stays pinned to a single bucket (see
        // InstrumentsAssembly's own KDoc). Reuses `counts` above instead of re-querying
        // totalTokens per node.
        val chatId = path.firstOrNull()?.id ?: leafId
        val projectId = session.conversationProjects.value[chatId] ?: chatId
        val turns = path.mapIndexed { index, node ->
            dev.fonebrew.domain.instrument.InstrumentsAssembly.PathTurn(
                nodeId = node.id,
                tokenCount = counts[index].second,
                label = "${node.role.name.lowercase()}: ${node.content.take(60).replace('\n', ' ')}",
            )
        }
        val assembled = dev.fonebrew.domain.instrument.InstrumentsAssembly.assembleConversation(
            turns = turns,
            convId = chatId,
            projectId = projectId,
            budget = dev.fonebrew.domain.scope.ContextAssembly.ContextBudget(total = spec.contextWindow, reserved = 0),
        )

        // THREAD_TOPOLOGY_PLAN.md WP8: correlate this conversation's PENDING delegations against
        // the effective path just computed above, then reflect the up-to-date rollup — same
        // cadence as the rest of the per-path instrumentation (recomputed on every branch/switch/
        // rewind/send, not on a separate poll).
        val delegationCounts = recomputeDelegationOutcomes(chatId, path)

        transient.value = transient.value.copy(
            context = context, tokenStats = stats, instrumentsAssembly = assembled,
            delegationCounts = delegationCounts,
        )
    }

    /**
     * THREAD_TOPOLOGY_PLAN.md WP8: resolves [rootId]'s still-PENDING delegations against the
     * current effective [path] via [dev.fonebrew.domain.thread.DelegationOutcomes.correlate], then
     * returns the fresh [dev.fonebrew.domain.thread.DelegationCounts.Counts] for the Instruments
     * panel's descriptive card. Only [dev.fonebrew.domain.thread.DelegationKind.MODEL_PICK_BRANCH]
     * and [dev.fonebrew.domain.thread.DelegationKind.COUNCIL_AUTOMERGE] delegations carry an
     * [dev.fonebrew.domain.thread.DelegationEvent.anchorMsgId] that's a real node in *this*
     * conversation's tree — `GATEWAY_AUTO` (recorded mid-loop-run, not tied to a conversation node
     * — see [dev.fonebrew.domain.loop.RecordingGatewayPolicy]'s KDoc) and any not-yet-produced
     * `AUTO_DEFAULT` stay PENDING here; they're still counted in the totals, just never resolved
     * by this path-based signal. A documented gap, not a silent one.
     */
    private suspend fun recomputeDelegationOutcomes(rootId: String, path: List<MessageNode>): dev.fonebrew.domain.thread.DelegationCounts.Counts {
        val pending = delegationStore.forRoot(rootId).filter { it.outcome == dev.fonebrew.domain.thread.DelegationOutcome.PENDING }
        if (pending.isNotEmpty()) {
            val pathIds = path.map { it.id }
            for (delegation in pending) {
                val chosen = delegation.chosenRef ?: continue
                val branchIdx = delegation.anchorMsgId?.let { pathIds.indexOf(it) } ?: -1
                if (branchIdx < 0) continue // branch point isn't on the active path (or unknown) — can't correlate yet.
                val activeChildId = pathIds.getOrNull(branchIdx + 1)
                val status = if (activeChildId == chosen) {
                    dev.fonebrew.domain.thread.DelegationOutcomes.ChoiceStatus.STILL_ACTIVE
                } else {
                    dev.fonebrew.domain.thread.DelegationOutcomes.ChoiceStatus.SWITCHED_OFF
                }
                // See DelegationOutcomes' KDoc: a PENDING delegation found SWITCHED_OFF has, by
                // construction, not yet crossed the KEPT window while active (an earlier call
                // here would already have resolved it otherwise) — 0 is always within the window.
                val turnsSinceChoice = if (status == dev.fonebrew.domain.thread.DelegationOutcomes.ChoiceStatus.STILL_ACTIVE) {
                    (pathIds.size - 1) - (branchIdx + 1)
                } else {
                    0
                }
                val outcome = dev.fonebrew.domain.thread.DelegationOutcomes.correlate(status, turnsSinceChoice)
                if (outcome != dev.fonebrew.domain.thread.DelegationOutcome.PENDING) {
                    delegationRecorder.resolveOutcome(delegation, outcome)
                }
            }
        }
        return dev.fonebrew.domain.thread.DelegationCounts.summarize(delegationStore.forRoot(rootId))
    }

    // ---- Thread topology (THREAD_TOPOLOGY_PLAN.md WP10 — GraphRoom) -------------------------

    /**
     * A fresh, whole-app [dev.fonebrew.domain.thread.ThreadGraph] snapshot for `ui/graph/GraphRoom.kt`
     * — every conversation, not just the active one, same "cross-conversation like [threadChains]"
     * shape. Calls [dev.fonebrew.domain.thread.ThreadGraphProjector.project] directly rather than
     * going through [threadObserver]: the graph substrate is plain structural re-shape of facts
     * that already exist (its own KDoc — "computes no drift/idiolect number"), not the interpretive
     * "observer" surface owner decision 5 gates behind a toggle; only [observerRemarks] below goes
     * through that gate. [dev.fonebrew.domain.thread.ThreadGraphProjector]'s own KDoc names this
     * exact call as its second caller, alongside [dev.fonebrew.data.ThreadObserver].
     */
    suspend fun loadThreadGraph(): dev.fonebrew.domain.thread.ThreadGraph =
        dev.fonebrew.domain.thread.ThreadGraphProjector.project(
            tree = repository.tree(),
            markers = threadMarkerStore.markers.first(),
            delegations = delegationStore.delegations.first(),
            generatedAtUtc = java.time.Instant.now(),
            // 2026-08-29 audit gap 1 ("decisions invisible"): the projector's own KDoc names this
            // exact call as the one that needs the real list — curationStore.bookmarks is already
            // a constructor dependency of this ViewModel (toggleMessageBookmark/bookmarksFor above).
            bookmarks = curationStore.bookmarks.first(),
        )

    /** Live mirror of [dev.fonebrew.data.SessionStore.observerEnabled] (owner decision 5) — GraphRoom's
     *  observer remark card reads this to decide whether to offer "Show observations" at all. */
    val observerEnabled: StateFlow<Boolean> get() = session.observerEnabled

    /** Live mirror of [dev.fonebrew.data.SessionStore.disclosureTier] — **WP11**: `GraphRoom`'s "Deep
     *  view" entry point reads this (via [dev.fonebrew.domain.disclosure.Disclosure.isRevealed] against
     *  [dev.fonebrew.domain.disclosure.Surface.GRAPH_DEEP]) to decide whether to offer the G6 WebView
     *  room at all, the same "surface + tier" split every other disclosure-gated entry point uses. */
    val disclosureTier: StateFlow<String> get() = session.disclosureTier

    /** Descriptive remarks about the current whole-app graph, or empty while the observer toggle is
     *  off ([threadObserver]'s own "inert by default" contract — see its KDoc). Presented on
     *  request only (a tap in GraphRoom), never pushed, per THREAD_TOPOLOGY_PLAN.md's ObserverScript note. */
    suspend fun observerRemarks(): List<String> = threadObserver.remarks()

    /**
     * TurnActionsSheet's "Convert → Task" row (lane A3's honest missing-list update): promotes
     * one turn into a free-floor task. Title/notes/source-reference are all derived by the pure,
     * tested [dev.fonebrew.domain.tasks.TaskFromTurn] — this method only wires that derivation
     * to [taskStore], the same "presenter decides, store just writes" split every other action on
     * this ViewModel already follows. Incident conversion is **not** offered here — see
     * TurnActionsSheet's own KDoc: it stays a named Studio-side follow-up, never faked as a Task.
     *
     * @return the created [dev.fonebrew.data.entity.TaskEntity] so the caller's confirmation
     * snackbar can offer a "jump straight to *this* task" affordance (the same
     * `highlightTaskId` mechanism `SearchOverlay`'s "open" action already uses on
     * [dev.fonebrew.ui.rooms.ProductRoomFree]) rather than a bare "opened Projects."
     */
    suspend fun convertToTask(nodeId: String, content: String): dev.fonebrew.data.entity.TaskEntity =
        taskStore.createFrom(
            title = dev.fonebrew.domain.tasks.TaskFromTurn.title(content),
            source = dev.fonebrew.domain.tasks.TaskSource.CHAT,
            sourceRef = dev.fonebrew.domain.tasks.TaskFromTurn.sourceRef(nodeId),
            notes = dev.fonebrew.domain.tasks.TaskFromTurn.notes(content, nodeId),
        )

    companion object {
        /** Longest-edge cap for a pending photo attachment before it's stored (W1) — bounds
         *  token cost across providers; Anthropic's high-res tier takes more but at ~3x image
         *  tokens (plan note), so this stays conservative for now. */
        private const val ATTACHMENT_MAX_EDGE = 2048

        private const val REWRITE_SYSTEM =
            "You improve user prompts. Output ONLY the rewritten prompt — clearer, " +
                "specific, with role/format/constraints where useful. No preamble, no commentary."

        private const val SUMMARY_SYSTEM =
            "Summarize the conversation below into a concise brief that preserves the goals, " +
                "key decisions, facts established, and open threads — enough for a fresh start to " +
                "continue seamlessly. Output ONLY the summary."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FonebrewApp
                val c = app.container
                ChatViewModel(
                    c.repository,
                    c.modelRegistry,
                    c.engineProvider,
                    c.providerStore,
                    c.localModelStore,
                    c.embeddingLogger,
                    c.kvCacheStore,
                    c.sharedIntake,
                    c.sessionStore,
                    c.downloadCenter,
                    c.modelDownloader,
                    c.imageStore,
                    c.attachmentStore,
                    c.imageProviderStore,
                    c.object3dStore,
                    c.object3dProviderStore,
                    c.sdModelStore,
                    c.pricingStore,
                    c.freeTierUsageStore,
                    c.councilStore,
                    c.ledgerStore,
                    c.modelCatalogStore,
                    c.curationStore,
                    c.threadMarkerStore,
                    c.delegationStore,
                    c.delegationRecorder,
                    c.receiptStore,
                    c.aarsoEventLog,
                    c.threadObserver,
                    c.taskStore,
                    app.applicationContext,
                )
            }
        }
    }
}
