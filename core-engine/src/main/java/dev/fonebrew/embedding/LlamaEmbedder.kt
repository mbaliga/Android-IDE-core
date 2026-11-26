package dev.fonebrew.embedding

import dev.fonebrew.domain.search.semantic.SemanticEmbedder
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A typed embedding failure; never a crash. */
class EmbedException(val reason: String, val code: EmbedCode? = null) : Exception(reason)

data class EmbeddedText(val vector: FloatArray, val truncated: Boolean)

sealed interface EmbedderOpen {
    data class Opened(val embedder: LlamaEmbedder) : EmbedderOpen
    data class Failed(val reason: String) : EmbedderOpen
}

/**
 * The real on-device [Embedder]: a GGUF embedding model on its own llama context. Construct via
 * [open]; [id] carries the model file's identity and [dim] comes from the model. A Kotlin mutex
 * serialises load/embed/unload (the native side has its own, this keeps use-after-close out of
 * the picture too). [close] unloads and makes later calls fail with a typed error.
 */
class LlamaEmbedder private constructor(
    private val native: EmbedNative,
    private val handle: Long,
    override val id: String,
    override val dim: Int,
    val prefixProfile: PrefixProfile,
    val pooling: Int,
    private val dispatcher: CoroutineDispatcher,
) : Embedder, SemanticEmbedder {

    private val lock = Mutex()
    @Volatile private var closed = false

    override suspend fun embed(text: String): FloatArray = embedChecked(text).vector

    suspend fun embedChecked(text: String): EmbeddedText = lock.withLock {
        if (closed) throw EmbedException("embedder is unloaded")
        if (text.isEmpty()) throw EmbedException("empty text", EmbedCode.EMPTY)
        withContext(dispatcher) {
            val out = FloatArray(dim)
            val code = EmbedCode.of(native.embed(handle, text.toByteArray(Charsets.UTF_8), out))
            when (code) {
                EmbedCode.OK -> EmbeddedText(out, false)
                EmbedCode.OK_TRUNCATED -> EmbeddedText(out, true)
                else -> throw EmbedException("embedding failed: $code", code)
            }
        }
    }

    override suspend fun embedDocument(text: String): FloatArray = embed(prefixProfile.document + text)
    override suspend fun embedQuery(text: String): FloatArray = embed(prefixProfile.query + text)

    suspend fun close() = lock.withLock {
        if (!closed) {
            closed = true
            withContext(dispatcher) { native.unload(handle) }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT = 512
        const val DEFAULT_THREADS = 3

        /** Loads [file] and reads dim/pooling from the model. Failures are typed, not thrown. */
        suspend fun open(
            native: EmbedNative,
            file: File,
            contextSize: Int = DEFAULT_CONTEXT,
            threads: Int = DEFAULT_THREADS,
            dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
        ): EmbedderOpen = withContext(dispatcher) {
            if (!file.isFile) return@withContext EmbedderOpen.Failed(LoadCode.FILE_MISSING.message)
            val fingerprint = try {
                EmbedderIdentity.fileFingerprint(file)
            } catch (e: Exception) {
                return@withContext EmbedderOpen.Failed(LoadCode.FILE_MISSING.message)
            }
            val h = native.load(file.absolutePath, contextSize, threads)
            if (h <= 0) return@withContext EmbedderOpen.Failed(LoadCode.of(h).message)
            val dim = native.dim(h)
            if (dim <= 0) {
                native.unload(h)
                return@withContext EmbedderOpen.Failed("model reported no embedding dimension")
            }
            val profile = PrefixProfile.forFileName(file.name)
            EmbedderOpen.Opened(
                LlamaEmbedder(native, h, EmbedderIdentity.id(file.name, fingerprint, profile.tag, dim), dim, profile, native.pooling(h), dispatcher),
            )
        }
    }
}
