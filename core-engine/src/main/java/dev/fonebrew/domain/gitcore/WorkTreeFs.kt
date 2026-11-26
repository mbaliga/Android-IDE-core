package dev.fonebrew.domain.gitcore

/**
 * Every write into the work tree goes through here. The invariant: the path is vetted by
 * [PathSafety], and before any byte is written each ancestor is lstat'd and must be a real
 * directory - a symlink ancestor is refused (UNSAFE_PATH), so a symlink planted by an earlier
 * entry can never redirect a later write outside the root or into `.git`. A symlink at the leaf
 * is replaced, never followed.
 */
internal class WorkTreeFs(private val fs: FileSystemPort, val root: String, private val limits: GitLimits) {
    fun abs(rel: String) = "$root/$rel"
    fun lstat(rel: String): FileStat? = fs.stat(abs(rel))

    /** Refuses when any ancestor directory of [rel] is a symlink (for reads/hashes of explicit paths). */
    fun requireRealAncestors(rel: String) {
        var slash = rel.indexOf('/')
        while (slash >= 0) {
            val anc = rel.substring(0, slash)
            val st = lstat(anc)
            if (st?.kind == FileKind.SYMLINK) throw GitException(GitErrorKind.UNSAFE_PATH, "'${PathSafety.display(anc)}' is a symlink; refusing to go through it", listOf(PathSafety.display(rel)))
            slash = rel.indexOf('/', slash + 1)
        }
    }

    fun ensureParents(rel: String) {
        var slash = rel.indexOf('/')
        while (slash >= 0) {
            val anc = rel.substring(0, slash)
            val st = lstat(anc)
            when (st?.kind) {
                null -> fs.mkdirs(abs(anc))
                FileKind.DIRECTORY -> {}
                FileKind.SYMLINK -> throw GitException(GitErrorKind.UNSAFE_PATH, "refusing to write through symlink '${PathSafety.display(anc)}'", listOf(PathSafety.display(rel)))
                else -> throw GitException(GitErrorKind.DIRTY_TREE, "'${PathSafety.display(anc)}' is a file where a directory is needed", listOf(PathSafety.display(anc)))
            }
            slash = rel.indexOf('/', slash + 1)
        }
    }

    fun writeFile(rel: String, bytes: ByteArray, executable: Boolean) {
        PathSafety.check(rel, limits)
        ensureParents(rel)
        val st = lstat(rel)
        if (st?.kind == FileKind.DIRECTORY) throw GitException(GitErrorKind.DIRTY_TREE, "'${PathSafety.display(rel)}' is a directory", listOf(PathSafety.display(rel)))
        fs.writeAtomic(abs(rel), bytes, executable)
    }

    fun writeFromStore(rel: String, db: ObjectDatabase, id: ObjectId, executable: Boolean) {
        PathSafety.check(rel, limits)
        ensureParents(rel)
        val st = lstat(rel)
        if (st?.kind == FileKind.DIRECTORY) throw GitException(GitErrorKind.DIRTY_TREE, "'${PathSafety.display(rel)}' is a directory", listOf(PathSafety.display(rel)))
        val peek = db.peek(id) ?: throw GitException(GitErrorKind.NOT_FOUND, "blob ${id.abbrev()} missing")
        if (peek.first != ObjectType.BLOB) malformed("${id.abbrev()} is not a blob")
        if (peek.second <= limits.streamThreshold) {
            fs.writeAtomic(abs(rel), db.readRaw(id)!!.data, executable)
        } else {
            val tmp = abs(rel) + ".fbtmp-" + java.lang.Long.toHexString(System.nanoTime())
            try {
                fs.openWrite(tmp).use { db.streamTo(id, it) }
                if (executable) fs.setExecutable(tmp, true)
                fs.rename(tmp, abs(rel))
            } finally { fs.delete(tmp) }
        }
    }

    fun writeSymlink(rel: String, target: String) {
        PathSafety.check(rel, limits)
        if (target.isEmpty() || target.indexOf('\u0000') >= 0 || target.length > limits.maxPathBytes) {
            throw GitException(GitErrorKind.UNSAFE_PATH, "bad symlink target for '${PathSafety.display(rel)}'", listOf(PathSafety.display(rel)))
        }
        ensureParents(rel)
        val st = lstat(rel)
        if (st?.kind == FileKind.DIRECTORY) throw GitException(GitErrorKind.DIRTY_TREE, "'${PathSafety.display(rel)}' is a directory", listOf(PathSafety.display(rel)))
        fs.createSymlink(abs(rel), target)
    }

    fun delete(rel: String) {
        val st = lstat(rel) ?: return
        if (st.kind == FileKind.DIRECTORY) return
        requireRealAncestors(rel)
        fs.delete(abs(rel))
        pruneEmptyDirs(rel)
    }

    fun pruneEmptyDirs(rel: String) {
        var dir = rel.substringBeforeLast('/', "")
        while (dir.isNotEmpty()) {
            val st = lstat(dir)
            if (st?.kind != FileKind.DIRECTORY || fs.list(abs(dir)).isNotEmpty()) return
            fs.delete(abs(dir))
            dir = dir.substringBeforeLast('/', "")
        }
    }

    /** Content id the work-tree entry at [rel] would have as a blob, hashing streamed; null if not a file/symlink. */
    fun hash(rel: String, st: FileStat, algorithm: HashAlgorithm): ObjectId? = when (st.kind) {
        FileKind.SYMLINK -> {
            val t = fs.readSymlink(abs(rel)) ?: return null
            ObjectId.hashObject(ObjectType.BLOB, t.toByteArray(Charsets.UTF_8), algorithm)
        }
        FileKind.FILE -> {
            val md = algorithm.newDigest()
            md.update("blob ${st.size}".toByteArray(Charsets.US_ASCII)); md.update(0)
            var n = 0L
            fs.openRead(abs(rel)).use { ins ->
                val buf = ByteArray(32 * 1024)
                while (true) { val r = ins.read(buf); if (r < 0) break; n += r; md.update(buf, 0, r) }
            }
            // size changed while hashing: hash is of what was read, with a header that may lie -> force a mismatch
            if (n != st.size) null else ObjectId(md.digest(), algorithm)
        }
        else -> null
    }

    fun modeOf(st: FileStat): Int = when (st.kind) {
        FileKind.SYMLINK -> FileMode.SYMLINK
        else -> if (st.executable) FileMode.EXECUTABLE else FileMode.REGULAR
    }
}
