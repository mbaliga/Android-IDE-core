package dev.fonebrew.embedding

/** A fake native seam: no library, no model. Vectors are a normalised word-hash bag. */
class FakeEmbedNative(
    private val dim: Int = 8,
    var loadResult: Long? = null, // null = hand out the next handle; otherwise return this code
    var embedCode: Int = EmbedCode.OK.code,
    var pooling: Int = 1,
) : EmbedNative {
    val loaded = HashSet<Long>()
    val unloaded = ArrayList<Long>()
    val loadedPaths = ArrayList<String>()
    val embedded = ArrayList<String>()
    private var next = 1L

    override fun load(modelPath: String, contextSize: Int, threads: Int): Long {
        loadedPaths.add(modelPath)
        loadResult?.let { return it }
        val h = next++
        loaded.add(h)
        return h
    }

    override fun embed(handle: Long, utf8: ByteArray?, out: FloatArray?): Int {
        if (utf8 == null || out == null) return EmbedCode.BAD_ARGS.code
        if (handle !in loaded) return EmbedCode.INVALID_HANDLE.code
        if (embedCode < 0) return embedCode
        val text = String(utf8, Charsets.UTF_8)
        embedded.add(text)
        val v = FloatArray(dim)
        for (w in text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }) v[Math.floorMod(w.hashCode(), dim)] += 1f
        var n = 0f
        for (x in v) n += x * x
        val inv = if (n > 0f) 1f / kotlin.math.sqrt(n) else 0f
        for (i in v.indices) out[i] = v[i] * inv
        return embedCode
    }

    override fun dim(handle: Long): Int = if (handle in loaded) dim else 0
    override fun pooling(handle: Long): Int = pooling
    override fun unload(handle: Long) { loaded.remove(handle); unloaded.add(handle) }
}
