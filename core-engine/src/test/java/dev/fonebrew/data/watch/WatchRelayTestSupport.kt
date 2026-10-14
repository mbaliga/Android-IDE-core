package dev.fonebrew.data.watch

import dev.fonebrew.data.LedgerStore
import dev.fonebrew.data.MessageTreeRepository
import dev.fonebrew.data.TaskStore
import dev.fonebrew.data.dao.EmbeddingDao
import dev.fonebrew.data.dao.LedgerDao
import dev.fonebrew.data.dao.MessageNodeDao
import dev.fonebrew.data.dao.TaskDao
import dev.fonebrew.data.dao.TokenCountDao
import dev.fonebrew.data.entity.LedgerEntryEntity
import dev.fonebrew.data.entity.MessageEmbeddingEntity
import dev.fonebrew.data.entity.MessageNodeEntity
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.data.entity.TokenCountEntity
import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import dev.fonebrew.inference.InferenceEngine
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.FrameDecoder
import dev.fonebrew.watchbridge.WatchBridgeCodec
import dev.fonebrew.watchbridge.WatchMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

/** In-memory [MessageNodeDao] with a REAL ancestor walk (the shipped one is a recursive CTE). Records insert order. */
internal class FakeMessageNodeDao : MessageNodeDao {
    val rows = MutableStateFlow<List<MessageNodeEntity>>(emptyList())
    val insertOrder = mutableListOf<String>()
    var failInserts = false

    override suspend fun insert(node: MessageNodeEntity) {
        if (failInserts) throw IllegalStateException("disk full")
        check(rows.value.none { it.id == node.id }) { "append-only: id collision ${node.id}" }
        insertOrder += node.id
        rows.value = rows.value + node
    }

    override suspend fun getById(id: String): MessageNodeEntity? = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<MessageNodeEntity>> = rows.map { list -> list.sortedBy { it.createdAt } }
    override fun observeChildren(parentId: String): Flow<List<MessageNodeEntity>> =
        rows.map { list -> list.filter { it.parentId == parentId }.sortedBy { it.createdAt } }

    override suspend fun pathToRoot(leafId: String): List<MessageNodeEntity> {
        val byId = rows.value.associateBy { it.id }
        val out = mutableListOf<MessageNodeEntity>()
        var cur = byId[leafId]
        while (cur != null) {
            out += cur
            cur = cur.parentId?.let(byId::get)
        }
        return out // leaf-first, like the CTE
    }
}

internal class FakeTokenCountDao : TokenCountDao {
    val counts = mutableListOf<TokenCountEntity>()
    override suspend fun upsert(count: TokenCountEntity) {
        counts.removeAll { it.nodeId == count.nodeId && it.tokenizerId == count.tokenizerId }
        counts += count
    }

    override suspend fun forNode(nodeId: String): List<TokenCountEntity> = counts.filter { it.nodeId == nodeId }
}

internal class FakeEmbeddingDao : EmbeddingDao {
    override suspend fun upsert(embedding: MessageEmbeddingEntity) {}
    override suspend fun forNode(nodeId: String): MessageEmbeddingEntity? = null
    override suspend fun count(): Int = 0
}

internal class FakeLedgerDao : LedgerDao {
    val rows = MutableStateFlow<List<LedgerEntryEntity>>(emptyList())
    override suspend fun insert(e: LedgerEntryEntity) {
        rows.value = rows.value + e
    }

    override fun all(): Flow<List<LedgerEntryEntity>> = rows.map { list -> list.sortedBy { it.timestampMillis } }
}

internal class FakeTaskDao : TaskDao {
    val rows = MutableStateFlow<List<TaskEntity>>(emptyList())
    var failUpdates = false

    override suspend fun insert(task: TaskEntity) {
        rows.value = rows.value + task
    }

    override suspend fun update(task: TaskEntity) {
        if (failUpdates) throw IllegalStateException("db locked")
        rows.value = rows.value.map { if (it.id == task.id) task else it }
    }

    override suspend fun delete(task: TaskEntity) {
        rows.value = rows.value.filterNot { it.id == task.id }
    }

    override suspend fun getById(id: String): TaskEntity? = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<TaskEntity>> =
        rows.map { list -> list.sortedWith(compareBy({ it.orderKey }, { it.createdAt })) }

    override suspend fun getAll(): List<TaskEntity> = rows.value
    override suspend fun maxOrderKey(): Double? = rows.value.maxOfOrNull { it.orderKey }
}

/** A repository over fake DAOs, exposing them for assertions. */
internal class TreeFixture {
    val nodeDao = FakeMessageNodeDao()
    val tokenDao = FakeTokenCountDao()
    val repository = MessageTreeRepository(nodeDao, tokenDao, FakeEmbeddingDao())
    val ledgerDao = FakeLedgerDao()
    val ledger = LedgerStore(ledgerDao)
    val taskDao = FakeTaskDao()
    val tasks = TaskStore(taskDao)

    suspend fun nodes(): List<MessageNode> = repository.tree().allNodes().sortedBy { it.createdAt }
}

/** An on-device spec (a local GGUF) or a cloud-shaped spec, as the registry would build them. */
internal fun localSpec(id: String = "local:tiny.gguf", name: String = "tiny", runtime: Runtime = Runtime.LOCAL_GGUF) = ModelSpec(
    id = id, displayName = name, family = "gguf", contextWindow = 4096, tokenizerId = "llama.cpp:$name",
    templateId = TemplateId.CHATML, runtime = runtime, modelPath = "/models/$name.gguf",
)

internal fun cloudSpec(id: String = "cloud:acme") = ModelSpec(
    id = id, displayName = "☁ Acme · big-model", family = "openai_compatible", contextWindow = 8192,
    tokenizerId = "cloud:big-model", templateId = TemplateId.PLAIN, runtime = Runtime.CLOUD,
    providerId = "acme", watched = true,
)

/** A scripted engine: streams [script], counts one token per 4 chars unless told to fail. */
internal open class FakeEngine(
    private val script: List<GeneratedToken> = emptyList(),
    override val tokenizerId: String = "fake:tok",
    private val failAfter: Throwable? = null,
    private val failLoad: Throwable? = null,
    private val countFails: Boolean = false,
) : InferenceEngine {
    override val supportsLogprobs = false
    override val supportsSamplingParams = true
    final override var isLoaded = false
        private set
    var loadedPath: String? = null
    var loadedContext: Int? = null
    var generatedFrom: List<MessageNode>? = null
    var sessionLoad: String? = null
    var sessionSave: String? = null
    var generateCalls = 0
    /** How many scripted tokens were actually handed to the collector (a stopped stream must not drain the script). */
    var emitted = 0

    override suspend fun loadModel(modelPath: String, contextSize: Int) {
        failLoad?.let { throw it }
        loadedPath = modelPath; loadedContext = contextSize; isLoaded = true
    }

    override suspend fun unload() { isLoaded = false }
    override suspend fun countTokens(text: String): Int {
        if (countFails) throw IllegalStateException("tokenizer unavailable")
        return (text.length + 3) / 4
    }

    override fun generate(
        messages: List<MessageNode>, params: SamplingParams, sessionLoadPath: String?, sessionSavePath: String?,
    ): Flow<GeneratedToken> = flow {
        generateCalls++
        generatedFrom = messages; sessionLoad = sessionLoadPath; sessionSave = sessionSavePath
        for (t in script) {
            emitted++
            emit(t)
        }
        failAfter?.let { throw it }
    }
}

internal fun tokens(vararg texts: String): List<GeneratedToken> = texts.map { GeneratedToken(it) }

/** A recording [ReplyChannel]; writes can be made to fail after [failAfterWrites] successes. */
internal class FakeChannel(private val failAfterWrites: Int = Int.MAX_VALUE) : ReplyChannel {
    val written = mutableListOf<ByteArray>()
    var closed = 0
    override suspend fun write(frame: ByteArray) {
        if (written.size >= failAfterWrites) throw IOException("link lost")
        written += frame
    }

    override suspend fun close() { closed++ }

    /** Every frame written so far, decoded through the contract's own [FrameDecoder] (the watch's reader). */
    fun decoded(): List<WatchMessage> {
        val decoder = FrameDecoder()
        val results = written.flatMap { decoder.feed(it) }
        return results.map { r ->
            check(r is DecodeResult.Decoded) { "a frame on the wire did not decode: $r" }
            r.message
        }
    }

    fun rawBytes(): ByteArray = written.fold(ByteArray(0)) { a, b -> a + b }
}

/** A recording [WearTransport]. */
internal class FakeTransport(
    var channelFactory: (() -> FakeChannel)? = { FakeChannel() },
    var failSends: Boolean = false,
) : WearTransport {
    data class Sent(val nodeId: String, val path: String, val payload: ByteArray) {
        fun message(): WatchMessage {
            val r = WatchBridgeCodec.decode(payload)
            check(r is DecodeResult.Decoded) { "a message on the wire did not decode: $r" }
            return r.message
        }
    }

    val sent = mutableListOf<Sent>()
    val channels = mutableListOf<FakeChannel>()
    val channelPaths = mutableListOf<String>()
    var channelOpenAttempts = 0

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        if (failSends) throw IOException("unreachable")
        sent += Sent(nodeId, path, payload)
    }

    override suspend fun openReplyChannel(nodeId: String, path: String): ReplyChannel {
        channelOpenAttempts++
        val factory = channelFactory ?: throw IOException("watch unreachable")
        channelPaths += path
        return factory().also { channels += it }
    }

    /** Every message on the wire (messages and channel frames), for "nothing secret anywhere" sweeps. */
    fun everyByteOnTheWire(): ByteArray =
        sent.fold(ByteArray(0)) { a, s -> a + s.payload } + channels.fold(ByteArray(0)) { a, c -> a + c.rawBytes() }
}
