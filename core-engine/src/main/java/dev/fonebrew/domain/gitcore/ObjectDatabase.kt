package dev.fonebrew.domain.gitcore

import java.io.InputStream
import java.io.OutputStream

/**
 * Loose objects + packs behind one has/read/write facade. `objects/info/alternates` is
 * deliberately IGNORED (named follow-up): an alternate could point anywhere on the device.
 *
 * Packs are discovered from `.idx` files in `objects/pack` and re-scanned once on a miss, so objects
 * added by [indexPack] (or by another process) become visible without reopening.
 */
class ObjectDatabase(
    private val fs: FileSystemPort,
    val objectsDir: String,
    val limits: GitLimits = GitLimits(),
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
) : AutoCloseable {
    val packDir = "$objectsDir/pack"
    private val loose = LooseObjectStore(fs, objectsDir, limits, algorithm)
    private val packs = LinkedHashMap<String, PackFile>()

    @Synchronized private fun scanPacks() {
        val names = fs.list(packDir).filter { it.startsWith("pack-") && it.endsWith(".idx") }
        for (n in names) {
            if (packs.containsKey(n)) continue
            val packPath = "$packDir/${n.removeSuffix(".idx")}.pack"
            if (fs.stat(packPath) == null) continue
            try {
                packs[n] = PackFile(fs, packPath, PackIndex.open(fs, "$packDir/$n", limits, algorithm), limits, algorithm) { id, budget -> readRaw(id, budget) }
            } catch (_: GitException) {
                // A damaged/half-written pack is skipped, never fatal to reading other objects.
            }
        }
    }

    @Synchronized private fun packList(): List<PackFile> {
        if (packs.isEmpty()) scanPacks()
        return packs.values.toList()
    }

    @Synchronized private fun rescan() = scanPacks()

    fun has(id: ObjectId): Boolean {
        if (loose.has(id)) return true
        if (packList().any { it.has(id) }) return true
        rescan()
        return packList().any { it.has(id) }
    }

    /** Reads an object as stored, or null when absent. Hostile content throws a typed [GitException]. */
    fun readRaw(id: ObjectId, chainBudget: Int = limits.maxDeltaChain): RawObject? {
        loose.read(id)?.let { return it }
        for (p in packList()) p.read(id, chainBudget)?.let { return it }
        rescan()
        for (p in packList()) p.read(id, chainBudget)?.let { return it }
        return null
    }

    /** Reads and parses; verifies the content hashes to [id] (a swapped or corrupt object is CHECKSUM). */
    fun read(id: ObjectId, verify: Boolean = true): GitObject? {
        val raw = readRaw(id) ?: return null
        if (verify && ObjectId.hashObject(raw.type, raw.data, algorithm) != id) {
            throw GitException(GitErrorKind.CHECKSUM, "object ${id.abbrev()} content does not match its name")
        }
        return GitObjects.parse(raw.type, raw.data, limits, algorithm)
    }

    fun require(id: ObjectId): GitObject = read(id) ?: throw GitException(GitErrorKind.NOT_FOUND, "object ${id.abbrev()} not found")
    fun readCommit(id: ObjectId): Commit = require(id) as? Commit ?: malformed("${id.abbrev()} is not a commit")
    fun readTree(id: ObjectId): Tree = require(id) as? Tree ?: malformed("${id.abbrev()} is not a tree")

    /** (type, size) without materialising the body. */
    fun peek(id: ObjectId): Pair<ObjectType, Long>? {
        loose.peek(id)?.let { return it }
        for (round in 0..1) {
            for (p in packList()) {
                val t = p.typeOf(id) ?: continue
                return t to p.sizeOf(id)!!
            }
            if (round == 0) rescan()
        }
        return null
    }

    /**
     * Streams the object's body to [out]. Loose and whole-in-pack objects stream in constant
     * memory with no size ceiling; a deltified object must be materialised and so obeys
     * [GitLimits.maxInMemoryObject] (typed LIMIT_EXCEEDED beyond it).
     */
    fun streamTo(id: ObjectId, out: OutputStream): Boolean {
        if (loose.streamTo(id, out)) return true
        for (round in 0..1) {
            for (p in packList()) {
                if (!p.has(id)) continue
                if (p.streamNonDelta(id, out)) return true
                out.write((p.read(id) ?: return false).data)
                return true
            }
            if (round == 0) rescan()
        }
        return false
    }

    fun write(type: ObjectType, body: ByteArray): ObjectId = loose.write(type, body)
    fun write(obj: GitObject): ObjectId = loose.write(obj.type, obj.body())

    /** Receives a pack stream, completes it if thin, and makes its objects visible. */
    fun indexPack(input: InputStream): PackIndexer.Result {
        val r = PackIndexer(fs, packDir, this, limits, algorithm).index(input)
        rescan()
        return r
    }

    fun allLooseIds(): List<ObjectId> = loose.allIds()

    /** Every object id in the packs (for tests/fsck-style sweeps). */
    fun allPackedIds(): List<ObjectId> {
        rescan()
        return packList().flatMap { p -> (0 until p.index.objectCount).map { p.index.idAt(it) } }
    }

    /** Resolves an abbreviated lower-case hex id; typed errors for ambiguous or missing. */
    fun resolvePrefix(hex: String): ObjectId {
        if (hex.length < 4 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad object prefix")
        if (hex.length == algorithm.hexLength) return ObjectId.fromHex(hex, algorithm)
        val found = LinkedHashSet<ObjectId>()
        val dir = "$objectsDir/${hex.substring(0, 2)}"
        for (f in fs.list(dir)) ObjectId.fromHexOrNull(hex.substring(0, 2) + f, algorithm)?.takeIf { it.hex.startsWith(hex) }?.let(found::add)
        rescan()
        for (p in packList()) found.addAll(p.index.idsWithPrefix(hex, 4))
        return when (found.size) {
            0 -> throw GitException(GitErrorKind.NOT_FOUND, "no object starts with $hex")
            1 -> found.first()
            else -> throw GitException(GitErrorKind.INVALID_ARGUMENT, "prefix $hex is ambiguous")
        }
    }

    @Synchronized override fun close() { packs.values.forEach { try { it.close() } catch (_: Exception) {} }; packs.clear() }
}

/**
 * Writes a blob from a stream of known [size] without holding it in memory: pass 1 hashes, and
 * only if the object is new does pass 2 deflate it into a temp file that is renamed into place.
 * If the source changes between the passes the temp file is dropped and IO is thrown.
 */
fun ObjectDatabase.writeBlobFromStream(fs: FileSystemPort, size: Long, open: () -> InputStream): ObjectId {
    val header = "blob $size".toByteArray(Charsets.US_ASCII)
    val md = algorithm.newDigest()
    md.update(header); md.update(0)
    var n = 0L
    val buf = ByteArray(32 * 1024)
    open().use { ins -> while (true) { val r = ins.read(buf); if (r < 0) break; n += r; md.update(buf, 0, r) } }
    if (n != size) throw GitException(GitErrorKind.IO, "file changed while being read")
    val id = ObjectId(md.digest(), algorithm)
    if (has(id)) return id
    val hex = id.hex
    val finalPath = "$objectsDir/${hex.substring(0, 2)}/${hex.substring(2)}"
    val tmp = "$objectsDir/tmp_obj_${java.lang.Long.toHexString(System.nanoTime())}"
    try {
        val md2 = algorithm.newDigest(); md2.update(header); md2.update(0)
        var m = 0L
        val def = java.util.zip.Deflater(1)
        try {
            fs.openWrite(tmp).use { raw ->
                val dos = java.util.zip.DeflaterOutputStream(raw, def)
                dos.write(header); dos.write(0)
                open().use { ins -> while (true) { val r = ins.read(buf); if (r < 0) break; m += r; md2.update(buf, 0, r); dos.write(buf, 0, r) } }
                dos.finish()
            }
        } finally { def.end() }
        if (m != size || !md2.digest().contentEquals(id.toByteArray())) throw GitException(GitErrorKind.IO, "file changed while being added")
        fs.rename(tmp, finalPath)
    } finally { fs.delete(tmp) }
    return id
}
