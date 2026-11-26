package dev.fonebrew.data.search.semantic

import dev.fonebrew.contracts.search.SemanticUnavailableReason
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SemanticSearchOutcome
import dev.fonebrew.domain.search.SemanticSearchProvider
import dev.fonebrew.domain.search.query.ParsedQuery
import dev.fonebrew.domain.search.semantic.DocSimilarity
import dev.fonebrew.domain.search.semantic.FusedResult
import dev.fonebrew.domain.search.semantic.SemanticFusion
import dev.fonebrew.domain.search.semantic.SemanticIndexState
import dev.fonebrew.domain.search.semantic.SemanticIndexStates
import dev.fonebrew.domain.search.semantic.SemanticIndexer
import dev.fonebrew.domain.search.semantic.SemanticQuery
import dev.fonebrew.domain.search.semantic.VectorStore
import dev.fonebrew.embedding.EmbedNative
import dev.fonebrew.embedding.EmbedderOpen
import dev.fonebrew.embedding.LlamaEmbedder
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Settings section shows. Nothing here is a measurement of quality or speed. */
data class SemanticStatus(
    val index: SemanticIndexState,
    val modelName: String? = null,
    /** Typed reason the model could not be used, if loading failed. */
    val loadError: String? = null,
    /** Set when indexing stopped on repeated embed failures. */
    val indexError: String? = null,
    val storeBytes: Long = 0,
    val embedderId: String? = null,
)

sealed interface MeaningRank {
    data class Ranked(val docs: List<DocSimilarity>) : MeaningRank
    data class Unavailable(val reason: SemanticUnavailableReason) : MeaningRank
}

/**
 * Owns the embedder lifecycle and the index. The embedder is loaded only while semantic search is
 * enabled AND an installed model is chosen, and unloaded the moment either stops being true.
 * Nothing is downloaded or sent anywhere from here: the model is a local file, the vectors go to a
 * local store (docs/design/semantic-search.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchService(
    private val settings: SemanticSettings,
    private val models: EmbeddingModelStore,
    private val store: VectorStore,
    private val corpus: SemanticCorpus,
    private val native: EmbedNative,
    scope: CoroutineScope,
    private val openEmbedder: suspend (File) -> EmbedderOpen = { LlamaEmbedder.open(native, it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private data class Counts(val total: Int, val upToDate: Int, val stale: Int)

    private val _status = MutableStateFlow(SemanticStatus(SemanticIndexState.NotEnabled))
    val status: StateFlow<SemanticStatus> = _status.asStateFlow()

    @Volatile private var embedder: LlamaEmbedder? = null
    @Volatile private var loadedFile: File? = null
    @Volatile private var loadError: String? = null
    @Volatile private var docsById: Map<String, SearchDoc> = emptyMap()
    @Volatile private var counts: Counts? = null
    @Volatile private var running = false
    private val tick = MutableStateFlow(0)

    /** True when a loaded embedder is available for the user's enabled choice. */
    val isActive: Boolean get() = settings.enabled.value && embedder != null

    init {
        scope.launch {
            combine(settings.enabled, settings.modelFile, models.models, settings.paused, tick) { en, name, list, paused, _ ->
                Desired(if (en && name != null) list.firstOrNull { it.name == name }?.file else null, en, name, paused)
            }.collectLatest { reconcile(it) }
        }
    }

    private data class Desired(val file: File?, val enabled: Boolean, val modelName: String?, val paused: Boolean)

    private suspend fun reconcile(d: Desired) {
        val loaded = embedder
        if (d.file == null || loaded == null || loadedFile != d.file) {
            if (loaded != null) {
                embedder = null
                loadedFile = null
                loaded.close()
            }
            counts = null
            loadError = null
            publish(d)
            if (d.file == null) return
            when (val r = withContext(ioDispatcher) { openEmbedder(d.file) }) {
                is EmbedderOpen.Opened -> { embedder = r.embedder; loadedFile = d.file }
                is EmbedderOpen.Failed -> { loadError = r.reason; publish(d); return }
            }
        }
        val e = embedder ?: return
        runIndex(d, e)
    }

    private suspend fun runIndex(d: Desired, e: LlamaEmbedder) = withContext(ioDispatcher) {
        val docs = corpus.docs()
        docsById = docs.associateBy { it.id }
        val indexer = SemanticIndexer(store, e)
        val plan = indexer.plan(docs)
        counts = Counts(plan.total, plan.upToDate, plan.stale)
        if (d.paused) { publish(d); return@withContext }
        running = true
        publish(d)
        try {
            val out = indexer.run(docs, isPaused = { settings.paused.value }) { done, total ->
                counts = Counts(total, done, counts?.stale ?: 0)
                publish(d)
            }
            val after = indexer.plan(docs)
            counts = Counts(after.total, after.upToDate, after.stale)
            running = false
            publish(d, (out as? SemanticIndexer.Outcome.Failed)?.message)
        } finally {
            running = false
        }
    }

    private fun publish(d: Desired, indexError: String? = null) {
        val c = counts
        val usable = d.file != null
        val index = when {
            d.enabled && usable && loadError != null -> SemanticIndexState.NoModel
            else -> SemanticIndexStates.derive(
                enabled = d.enabled, modelUsable = usable,
                total = c?.total ?: 1, upToDate = c?.upToDate ?: 0, stale = c?.stale ?: 0,
                paused = d.paused, running = running,
            )
        }
        _status.value = SemanticStatus(
            index = index,
            modelName = d.modelName,
            loadError = loadError,
            indexError = indexError,
            storeBytes = runCatching { store.bytesUsed() }.getOrDefault(0),
            embedderId = embedder?.id,
        )
    }

    /** New or edited docs exist (the lexical indexer ran); embed just those. */
    fun requestSync() { tick.update { it + 1 } }

    /** Drop every vector and re-embed from scratch. */
    fun rebuild() {
        store.clear()
        settings.setPaused(false)
        tick.update { it + 1 }
    }

    /** Drop every vector. Indexing is left paused so it does not silently refill; resume or disable. */
    fun clear() {
        settings.setPaused(true)
        store.clear()
        counts = null
        tick.update { it + 1 }
    }

    suspend fun rankByMeaning(text: String, k: Int = 30): MeaningRank {
        if (!settings.enabled.value) return MeaningRank.Unavailable(SemanticUnavailableReason.FEATURE_DISABLED)
        val name = settings.modelFile.value
        if (name == null || models.models.value.none { it.name == name }) {
            return MeaningRank.Unavailable(SemanticUnavailableReason.NO_EMBEDDER_CONFIGURED)
        }
        val e = embedder ?: return MeaningRank.Unavailable(SemanticUnavailableReason.PROVIDER_ERROR)
        return try {
            val ranked = withContext(ioDispatcher) { SemanticQuery(store, e).rank(text, k) }
            // null = this embedder has no vectors yet (index still empty): not a result set.
            if (ranked == null) MeaningRank.Unavailable(SemanticUnavailableReason.PROVIDER_ERROR) else MeaningRank.Ranked(ranked)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MeaningRank.Unavailable(SemanticUnavailableReason.PROVIDER_ERROR)
        }
    }

    /**
     * Fuses semantic hits into [lexical] for a plain-words query; null means "leave the lexical
     * list exactly as it is" (semantic off, query not eligible, or semantic unavailable).
     */
    suspend fun fuse(parsed: ParsedQuery, lexical: List<SearchHit>): FusedResult? {
        if (!isActive) return null
        val text = SemanticFusion.eligibleText(parsed) ?: return null
        val ranked = rankByMeaning(text) as? MeaningRank.Ranked ?: return null
        if (docsById.isEmpty()) docsById = withContext(ioDispatcher) { corpus.docs() }.associateBy { it.id }
        return SemanticFusion.fuse(lexical, ranked.docs, docsById)
    }
}

/** The contract's provider, backed by the service. Every non-ready state is a typed Unavailable. */
class EmbeddingSemanticSearchProvider(private val service: SemanticSearchService) : SemanticSearchProvider {
    override val enabled: Boolean get() = service.isActive

    override suspend fun search(query: String, corpus: List<SearchDoc>): SemanticSearchOutcome =
        when (val r = service.rankByMeaning(query)) {
            is MeaningRank.Unavailable -> SemanticSearchOutcome.Unavailable(r.reason)
            is MeaningRank.Ranked -> {
                val byId = corpus.associateBy { it.id }
                SemanticSearchOutcome.Results(
                    r.docs.mapNotNull { s -> byId[s.docId]?.let { SearchHit(it, s.score.toDouble(), MatchedIn.CONTENT, emptyList()) } },
                )
            }
        }
}
