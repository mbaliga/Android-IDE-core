package dev.fonebrew.domain.gitcore

import java.io.ByteArrayOutputStream

/**
 * One index (dircache) entry. [path] must pass [PathSafety]; stat fields mirror what git stores
 * (32-bit truncated) so an index we write is byte-compatible with git's own.
 */
data class IndexEntry(
    val path: String,
    val id: ObjectId,
    val mode: Int,
    val stage: Int = 0,
    val size: Long = 0,
    val ctimeSeconds: Long = 0,
    val ctimeNanos: Int = 0,
    val mtimeSeconds: Long = 0,
    val mtimeNanos: Int = 0,
    val dev: Long = 0,
    val ino: Long = 0,
    val uid: Long = 0,
    val gid: Long = 0,
    val assumeValid: Boolean = false,
) {
    val isExecutable get() = (mode and 0b001_000_000) != 0
}

/**
 * Index v2 in memory. Invariants held by every mutator: entries are kept sorted by
 * (path bytes, stage); a path has either one stage-0 entry or conflict stages 1-3, never both;
 * no entry is a path prefix-directory of another (file/directory clash is resolved by [put]).
 *
 * Extensions: optional ones (first byte A-Z: `TREE`, `REUC`, `UNTR`, `FSMN`, ...) are
 * DROPPED on read (they are caches git rebuilds); a required one (lowercase first byte, e.g.
 * `link` split index) makes the index unreadable with UNSUPPORTED rather than risking a wrong
 * write. Version 3 files are read when no entry uses extended flags; version 4 is UNSUPPORTED.
 */
class GitIndex private constructor(private val map: java.util.TreeMap<Key, IndexEntry>) {
    constructor() : this(java.util.TreeMap())

    private data class Key(val pathBytes: ByteArray, val stage: Int) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val n = minOf(pathBytes.size, other.pathBytes.size)
            for (i in 0 until n) {
                val c = (pathBytes[i].toInt() and 0xff) - (other.pathBytes[i].toInt() and 0xff)
                if (c != 0) return c
            }
            val c = pathBytes.size - other.pathBytes.size
            return if (c != 0) c else stage - other.stage
        }
        override fun equals(other: Any?) = other is Key && stage == other.stage && pathBytes.contentEquals(other.pathBytes)
        override fun hashCode() = pathBytes.contentHashCode() * 31 + stage
    }

    private fun key(path: String, stage: Int) = Key(path.toByteArray(Charsets.UTF_8), stage)

    val size: Int get() = map.size
    fun entries(): List<IndexEntry> = map.values.toList()
    fun get(path: String, stage: Int = 0): IndexEntry? = map[key(path, stage)]
    fun contains(path: String) = (0..3).any { map.containsKey(key(path, it)) }
    val hasConflicts: Boolean get() = map.values.any { it.stage != 0 }
    fun conflictedPaths(): List<String> = map.values.filter { it.stage != 0 }.map { it.path }.distinct()

    /** Paths strictly below directory [dir] (without trailing slash). */
    fun pathsUnder(dir: String): List<IndexEntry> = map.values.filter { it.path.startsWith("$dir/") }

    /** Inserts/replaces. A stage-0 put clears conflict stages; a file entry evicts clashing directory entries and vice versa. */
    fun put(e: IndexEntry) {
        PathSafety.check(e.path)
        if (e.stage !in 0..3) throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad stage")
        if (e.stage == 0) for (s in 1..3) map.remove(key(e.path, s))
        else map.remove(key(e.path, 0))
        // a path that is a directory prefix of existing entries evicts them; ancestors that are files are evicted
        val prefix = e.path + "/"
        val it = map.values.iterator()
        while (it.hasNext()) if (it.next().path.startsWith(prefix)) it.remove()
        var slash = e.path.indexOf('/')
        while (slash >= 0) {
            val anc = e.path.substring(0, slash)
            for (s in 0..3) map.remove(key(anc, s))
            slash = e.path.indexOf('/', slash + 1)
        }
        map[key(e.path, e.stage)] = e
    }

    fun remove(path: String): Boolean {
        var any = false
        for (s in 0..3) if (map.remove(key(path, s)) != null) any = true
        return any
    }

    fun removeUnder(dir: String): Int {
        val it = map.values.iterator(); var n = 0
        while (it.hasNext()) if (it.next().path.startsWith("$dir/")) { it.remove(); n++ }
        return n
    }

    fun clear() = map.clear()
    fun copy(): GitIndex = GitIndex(java.util.TreeMap(map))

    /** The flat, sorted stage-0 view (path -> entry). */
    fun stage0(): Map<String, IndexEntry> = LinkedHashMap<String, IndexEntry>().also { m -> map.values.forEach { if (it.stage == 0) m[it.path] = it } }

    // ------------------------------------------------------------------ tree <-> index

    /**
     * Writes the trees for the stage-0 entries and returns the root tree id. Refuses an index with
     * conflicts (a tree cannot represent them).
     */
    fun writeTree(db: ObjectDatabase): ObjectId {
        if (hasConflicts) throw GitException(GitErrorKind.INVALID_ARGUMENT, "unmerged paths in index", conflictedPaths())
        return writeDir(db, entries(), "")
    }

    private fun writeDir(db: ObjectDatabase, all: List<IndexEntry>, dir: String): ObjectId {
        val out = ArrayList<TreeEntry>()
        val subdirs = LinkedHashMap<String, MutableList<IndexEntry>>()
        for (e in all) {
            val rel = e.path.substring(dir.length)
            val slash = rel.indexOf('/')
            if (slash < 0) out.add(TreeEntry(e.mode, rel, e.id))
            else subdirs.getOrPut(rel.substring(0, slash)) { ArrayList() }.add(e)
        }
        for ((name, list) in subdirs) out.add(TreeEntry(FileMode.TREE, name, writeDir(db, list, "$dir$name/")))
        return db.write(Tree(out))
    }

    // ------------------------------------------------------------------ serialisation

    fun toBytes(algorithm: HashAlgorithm = HashAlgorithm.SHA1): ByteArray {
        val out = ByteArrayOutputStream(map.size * 80 + 64)
        fun u32(v: Long) { out.write((v ushr 24).toInt() and 0xff); out.write((v ushr 16).toInt() and 0xff); out.write((v ushr 8).toInt() and 0xff); out.write(v.toInt() and 0xff) }
        out.write("DIRC".toByteArray(Charsets.US_ASCII)); u32(2); u32(map.size.toLong())
        for (e in map.values) {
            val start = out.size()
            u32(e.ctimeSeconds and 0xffff_ffffL); u32(e.ctimeNanos.toLong() and 0xffff_ffffL)
            u32(e.mtimeSeconds and 0xffff_ffffL); u32(e.mtimeNanos.toLong() and 0xffff_ffffL)
            u32(e.dev and 0xffff_ffffL); u32(e.ino and 0xffff_ffffL)
            u32(e.mode.toLong()); u32(e.uid and 0xffff_ffffL); u32(e.gid and 0xffff_ffffL)
            u32(e.size and 0xffff_ffffL)
            out.write(e.id.toByteArray())
            val nameBytes = e.path.toByteArray(Charsets.UTF_8)
            val flags = (if (e.assumeValid) 0x8000 else 0) or (e.stage shl 12) or minOf(nameBytes.size, 0xfff)
            out.write(flags ushr 8); out.write(flags and 0xff)
            out.write(nameBytes)
            val len = out.size() - start
            val pad = 8 - (len % 8)           // 1..8 NULs, entry length a multiple of 8
            repeat(pad) { out.write(0) }
        }
        val body = out.toByteArray()
        return body + algorithm.newDigest().digest(body)
    }

    fun write(fs: FileSystemPort, path: String) {
        val lock = "$path.lock"
        if (!fs.createExclusive(lock, ByteArray(0))) throw GitException(GitErrorKind.LOCKED, "index is locked by another writer")
        try {
            fs.writeAtomic(path, toBytes())
        } finally { fs.delete(lock) }
    }

    companion object {
        /** Parses an index. Every offset is bounds-checked; failures are typed, never a crash. */
        fun parse(data: ByteArray, limits: GitLimits = GitLimits(), algorithm: HashAlgorithm = HashAlgorithm.SHA1): GitIndex {
            val w = algorithm.byteLength
            if (data.size < 12 + w) malformed("index too small")
            if (String(data, 0, 4, Charsets.US_ASCII) != "DIRC") malformed("not an index file")
            val sum = algorithm.newDigest().digest(data.copyOfRange(0, data.size - w))
            for (i in 0 until w) if (sum[i] != data[data.size - w + i]) throw GitException(GitErrorKind.CHECKSUM, "index checksum mismatch")
            fun u32(p: Int): Long = ((data[p].toLong() and 0xff) shl 24) or ((data[p + 1].toLong() and 0xff) shl 16) or ((data[p + 2].toLong() and 0xff) shl 8) or (data[p + 3].toLong() and 0xff)
            val version = u32(4)
            if (version != 2L && version != 3L) throw GitException(GitErrorKind.UNSUPPORTED, "index version $version")
            val count = u32(8)
            if (count > limits.maxIndexEntries) limit("index has $count entries")
            val end = data.size - w
            if (count * 62 > end - 12) malformed("index entry count impossible for file size")
            val idx = GitIndex()
            var p = 12
            var prev: Key? = null
            for (n in 0 until count) {
                if (p + 62 > end) malformed("truncated index entry")
                val ctimeS = u32(p); val ctimeN = u32(p + 4); val mtimeS = u32(p + 8); val mtimeN = u32(p + 12)
                val dev = u32(p + 16); val ino = u32(p + 20); val mode = u32(p + 24).toInt()
                val uid = u32(p + 28); val gid = u32(p + 32); val size = u32(p + 36)
                val id = ObjectId.fromBytes(data, p + 40, algorithm)
                val flags = ((data[p + 40 + w].toInt() and 0xff) shl 8) or (data[p + 41 + w].toInt() and 0xff)
                var q = p + 42 + w
                if (flags and 0x4000 != 0) throw GitException(GitErrorKind.UNSUPPORTED, "index entry uses extended flags")
                val stage = (flags shr 12) and 3
                var nameLen = flags and 0xfff
                if (nameLen == 0xfff) {
                    var z = q
                    while (z < end && data[z] != 0.toByte()) z++
                    if (z >= end) malformed("unterminated index path")
                    nameLen = z - q
                }
                if (nameLen == 0 || q + nameLen > end) malformed("index path out of range")
                if (nameLen > limits.maxPathBytes) limit("index path too long")
                val nameBytes = data.copyOfRange(q, q + nameLen)
                val path = String(nameBytes, Charsets.UTF_8)
                if (!path.toByteArray(Charsets.UTF_8).contentEquals(nameBytes)) {
                    throw GitException(GitErrorKind.UNSUPPORTED, "index contains a path that is not valid UTF-8")
                }
                PathSafety.check(path, limits)
                val entryLen = (q + nameLen) - p
                val padded = entryLen + (8 - entryLen % 8)
                if (p + padded > end) malformed("index entry padding out of range")
                for (z in (q + nameLen) until (p + padded)) if (data[z] != 0.toByte()) malformed("index entry padding is not NUL")
                val k = Key(nameBytes, stage)
                if (prev != null && prev.compareTo(k) >= 0) malformed("index entries not strictly sorted")
                prev = k
                idx.map[k] = IndexEntry(path, id, mode, stage, size, ctimeS, ctimeN.toInt(), mtimeS, mtimeN.toInt(), dev, ino, uid, gid, flags and 0x8000 != 0)
                p += padded
            }
            // extensions until the trailer
            while (p < end) {
                if (p + 8 > end) malformed("truncated index extension header")
                val sig = String(data, p, 4, Charsets.ISO_8859_1)
                val len = u32(p + 4)
                if (len < 0 || p + 8 + len > end) malformed("index extension runs past end")
                if (sig[0] !in 'A'..'Z') throw GitException(GitErrorKind.UNSUPPORTED, "required index extension '${PathSafety.display(sig)}'")
                p += 8 + len.toInt()   // optional extension: dropped
            }
            return idx
        }

        fun read(fs: FileSystemPort, path: String, limits: GitLimits = GitLimits()): GitIndex {
            if (fs.stat(path)?.kind != FileKind.FILE) return GitIndex()
            return parse(fs.readBytes(path, 1L shl 30), limits)
        }

        /** Builds an index from a flat tree listing (stat fields zero: forces a content compare in status). */
        fun fromTree(flat: Map<String, FlatEntry>): GitIndex = GitIndex().also { ix ->
            for ((p, e) in flat) ix.map[ix.key(p, 0)] = IndexEntry(p, e.id, e.mode)
        }
    }
}

/** A tree entry flattened to a full path. */
data class FlatEntry(val mode: Int, val id: ObjectId)
