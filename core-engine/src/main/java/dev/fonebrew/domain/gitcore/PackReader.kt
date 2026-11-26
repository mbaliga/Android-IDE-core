package dev.fonebrew.domain.gitcore

import java.io.OutputStream

/**
 * The delta-resolving core shared by [PackFile] (lookups via a `.idx`) and [PackIndexer]
 * (lookups via ids it computed itself while scanning a received pack).
 */
internal class PackReader(
    private val src: RandomAccessSource,
    private val dataEnd: Long,
    private val lookup: (ObjectId) -> Long?,
    private val limits: GitLimits,
    private val algorithm: HashAlgorithm,
    private val externalBase: (ObjectId, Int) -> RawObject?,
) {
    private val cache = object : LinkedHashMap<Long, RawObject>(64, 0.75f, true) {}
    private var cacheBytes = 0L

    private fun checkOffset(offset: Long) {
        if (offset < PackIo.HEADER_LEN || offset >= dataEnd) malformed("pack offset $offset outside pack")
    }

    /** Declared inflated size of the object at [offset] (for a delta, of its result), without resolving it. */
    fun sizeAt(offset: Long): Long {
        checkOffset(offset)
        val h = PackIo.readHeader(src, offset, algorithm)
        if (!h.isDelta) return h.size
        val first = java.io.ByteArrayOutputStream()
        PackIo.inflate(src, offset + h.headerLength, dataEnd, h.size, prefix = 20) { b, o, l -> first.write(b, o, l) }
        val d = first.toByteArray()
        var p = 0
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                if (p >= d.size || s > 56) malformed("bad delta header")
                val c = d[p++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl s); s += 7
                if (c and 0x80 == 0) return r
            }
        }
        varint()
        return varint()
    }

    /** Type of the object at [offset], following delta chains to the real base (cheap: headers only). */
    fun typeAt(offset: Long): ObjectType {
        var off = offset
        var budget = limits.maxDeltaChain
        while (true) {
            checkOffset(off)
            val h = PackIo.readHeader(src, off, algorithm)
            if (!h.isDelta) return ObjectType.fromPackCode(h.typeCode) ?: malformed("bad type")
            if (budget-- <= 0) limit("delta chain longer than ${limits.maxDeltaChain}")
            if (h.baseOffset >= 0) off = h.baseOffset
            else {
                val e = lookup(h.baseId!!)?.let { PackEntryRef(it) } ?: return externalBase(h.baseId, budget)?.type ?: throw GitException(GitErrorKind.NOT_FOUND, "delta base ${h.baseId.abbrev()} missing")
                off = e.offset
            }
        }
    }

    fun readAt(offset: Long, chainBudget: Int = limits.maxDeltaChain): RawObject {
        // Walk to the nearest non-delta (or cached) base, remembering the deltas on the way.
        val pending = ArrayList<Pair<Long, PackEntryHeader>>()
        var off = offset
        var base: RawObject? = null
        var steps = chainBudget
        while (true) {
            cache[off]?.let { base = it; return@let }
            if (base != null) break
            checkOffset(off)
            val h = PackIo.readHeader(src, off, algorithm)
            if (!h.isDelta) {
                val type = ObjectType.fromPackCode(h.typeCode) ?: malformed("bad pack object type")
                base = RawObject(type, PackIo.inflateToArray(src, off + h.headerLength, dataEnd, h.size, limits.maxInMemoryObject))
                remember(off, base!!)
                break
            }
            if (steps-- <= 0) limit("delta chain longer than ${limits.maxDeltaChain}")
            pending.add(off to h)
            if (h.baseOffset >= 0) {
                if (h.baseOffset >= off) malformed("ofs-delta base must precede its delta")
                off = h.baseOffset
            } else {
                val e = lookup(h.baseId!!)?.let { PackEntryRef(it) }
                if (e != null) {
                    if (e.offset == off) malformed("delta is its own base")
                    off = e.offset
                } else {
                    val ext = externalBase(h.baseId, steps)
                        ?: throw GitException(GitErrorKind.NOT_FOUND, "delta base ${h.baseId.abbrev()} missing")
                    base = ext
                    break
                }
            }
        }
        var cur = base!!
        for (i in pending.indices.reversed()) {
            val (doff, h) = pending[i]
            val delta = PackIo.inflateToArray(src, doff + h.headerLength, dataEnd, h.size, limits.maxInMemoryObject)
            cur = RawObject(cur.type, PackIo.applyDelta(cur.data, delta, limits.maxInMemoryObject))
            remember(doff, cur)
        }
        return cur
    }

    private fun remember(offset: Long, obj: RawObject) {
        val sz = obj.data.size.toLong()
        if (sz > limits.deltaCacheBytes) return
        if (cache.put(offset, obj) == null) cacheBytes += sz
        val it = cache.entries.iterator()
        while (cacheBytes > limits.deltaCacheBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == offset) continue
            cacheBytes -= e.value.data.size
            it.remove()
        }
    }

    fun cachedBytes(): Long = cacheBytes

    /** Streams a non-delta object at [offset] to [out]; false if it is deltified. */
    fun streamNonDeltaAt(offset: Long, out: OutputStream): Boolean {
        checkOffset(offset)
        val h = PackIo.readHeader(src, offset, algorithm)
        if (h.isDelta) return false
        PackIo.inflate(src, offset + h.headerLength, dataEnd, h.size) { b, o, l -> out.write(b, o, l) }
        return true
    }

    fun compressedEnd(start: Long, h: PackEntryHeader): Long {
        val consumed = PackIo.inflate(src, start + h.headerLength, dataEnd, h.size) { _, _, _ -> }
        return start + h.headerLength + consumed
    }
}

internal class PackEntryRef(val offset: Long)
