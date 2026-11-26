package dev.fonebrew.domain.gitcore

/** A ref's stored value: another ref name, or an object id. */
sealed class RefValue {
    data class Symbolic(val target: String) : RefValue()
    data class Direct(val id: ObjectId) : RefValue()
}

/** What a caller expects the ref to hold when it updates it (compare-and-swap). */
sealed class Expect {
    /** No check. */
    object Any : Expect()
    /** The ref must not exist. */
    object Absent : Expect()
    /** The ref must currently resolve to exactly this id. */
    data class Id(val id: ObjectId) : Expect()
}

/** Who/when for a reflog line. */
data class ReflogIdentity(val name: String, val email: String, val epochSeconds: Long, val tzMinutes: Int)

data class ReflogEntry(val oldId: ObjectId, val newId: ObjectId, val who: Signature, val message: String)

/**
 * Loose refs, `packed-refs`, symbolic refs and a minimal reflog under one git dir.
 *
 * Updates take `<ref>.lock` with exclusive create (so two writers cannot interleave), re-check the
 * expected old value under the lock, then rename the lock over the ref (atomic on POSIX). Names
 * are validated by [RefName] before they touch a path, so a hostile ref name can never address
 * a file outside `refs/`. Directory/file clashes (`refs/heads/a` vs `refs/heads/a/b`) are refused
 * as REF_CONFLICT. Symbolic chains are followed at most [MAX_SYMREF_DEPTH] deep.
 */
class RefStore(private val fs: FileSystemPort, val gitDir: String, private val algorithm: HashAlgorithm = HashAlgorithm.SHA1) {
    companion object { const val MAX_SYMREF_DEPTH = 5; private const val MAX_REF_FILE = 4096L }

    private fun path(name: String) = "$gitDir/$name"

    // ------------------------------------------------------------------ reading

    private var packedCache: Triple<Long, Long, Map<String, ObjectId>>? = null

    /** name -> id from `packed-refs` (peeled lines skipped). Cached on (size, mtime). */
    private fun packed(): Map<String, ObjectId> {
        val p = path("packed-refs")
        val st = fs.stat(p)
        if (st == null || st.kind != FileKind.FILE) { packedCache = null; return emptyMap() }
        packedCache?.let { if (it.first == st.size && it.second == st.mtimeSeconds * 1_000_000_000L + st.mtimeNanos) return it.third }
        val out = LinkedHashMap<String, ObjectId>()
        val text = String(fs.readBytes(p, 64L * 1024 * 1024), Charsets.UTF_8)
        for (line in text.split('\n')) {
            if (line.isEmpty() || line[0] == '#' || line[0] == '^') continue
            val sp = line.indexOf(' ')
            if (sp != algorithm.hexLength) malformed("bad packed-refs line")
            val id = ObjectId.fromHexOrNull(line.substring(0, sp), algorithm) ?: malformed("bad packed-refs id")
            val name = line.substring(sp + 1)
            RefName.requireValid(name)
            out[name] = id
        }
        packedCache = Triple(st.size, st.mtimeSeconds * 1_000_000_000L + st.mtimeNanos, out)
        return out
    }

    /** Raw value of one ref without following symbolic links, or null. */
    fun read(name: String): RefValue? {
        RefName.requireValid(name)
        val p = path(name)
        val st = fs.stat(p)
        if (st != null && st.kind == FileKind.FILE) {
            val text = String(fs.readBytes(p, MAX_REF_FILE), Charsets.UTF_8).trim()
            if (text.startsWith("ref:")) {
                val target = text.removePrefix("ref:").trim()
                RefName.requireValid(target)
                return RefValue.Symbolic(target)
            }
            return RefValue.Direct(ObjectId.fromHexOrNull(text, algorithm) ?: malformed("ref '$name' does not contain an object id"))
        }
        return packed()[name]?.let { RefValue.Direct(it) }
    }

    /** Follows symbolic refs; null when the chain ends at a ref that does not exist (unborn branch). */
    fun resolve(name: String): ObjectId? {
        var cur = name
        for (i in 0..MAX_SYMREF_DEPTH) {
            when (val v = read(cur)) {
                null -> return null
                is RefValue.Direct -> return v.id
                is RefValue.Symbolic -> cur = v.target
            }
        }
        malformed("symbolic ref chain from '$name' is too deep or cyclic")
    }

    /** The branch HEAD names (`refs/heads/x`), or null when HEAD is detached. */
    fun headBranch(): String? = (read("HEAD") as? RefValue.Symbolic)?.target

    /** All refs under [prefix] (e.g. `refs/heads/`) with their ids; loose wins over packed. */
    fun list(prefix: String = "refs/"): Map<String, ObjectId> {
        val out = sortedMapOf<String, ObjectId>()
        for ((n, id) in packed()) if (n.startsWith(prefix)) out[n] = id
        fun walk(dir: String, rel: String) {
            for (e in fs.list(dir)) {
                if (e.endsWith(".lock")) continue
                val full = "$dir/$e"
                val childRel = "$rel/$e"
                when (fs.stat(full)?.kind) {
                    FileKind.DIRECTORY -> walk(full, childRel)
                    FileKind.FILE -> if (childRel.startsWith(prefix) && RefName.isValid(childRel)) {
                        (read(childRel) as? RefValue.Direct)?.let { out[childRel] = it.id }
                    }
                    else -> {}
                }
            }
        }
        walk(path("refs"), "refs")
        return out
    }

    // ------------------------------------------------------------------ writing

    private fun checkDirFileConflict(name: String) {
        val parts = name.split('/')
        for (i in 1 until parts.size) {
            val prefix = parts.take(i).joinToString("/")
            if (prefix != "refs" && (fs.stat(path(prefix))?.kind == FileKind.FILE || packed().containsKey(prefix))) {
                throw GitException(GitErrorKind.REF_CONFLICT, "cannot create '$name': '$prefix' exists as a ref")
            }
        }
        if (fs.stat(path(name))?.kind == FileKind.DIRECTORY && !hasFileBeneath(path(name))) fs.deleteRecursively(path(name))
        if (fs.stat(path(name))?.kind == FileKind.DIRECTORY || packed().keys.any { it.startsWith("$name/") }) {
            throw GitException(GitErrorKind.REF_CONFLICT, "cannot create '$name': refs exist beneath it")
        }
    }

    private fun checkExpect(name: String, expect: Expect) {
        val cur = try { resolve(name) } catch (e: GitException) { if (e.kind == GitErrorKind.MALFORMED) null else throw e }
        when (expect) {
            Expect.Any -> {}
            Expect.Absent -> if (read(name) != null) throw GitException(GitErrorKind.REF_CONFLICT, "ref '$name' already exists")
            is Expect.Id -> if (cur != expect.id) throw GitException(GitErrorKind.REF_CONFLICT, "ref '$name' is not at the expected value (moved by someone else)")
        }
    }

    private inline fun <T> locked(name: String, body: () -> T): T {
        val lock = path("$name.lock")
        if (!fs.createExclusive(lock, ByteArray(0))) throw GitException(GitErrorKind.LOCKED, "'$name' is locked by another writer")
        try { return body() } finally { fs.delete(lock) }
    }

    /**
     * Sets [name] (a real ref, not following HEAD's symlink target - pass the branch name) to [id].
     * [reflog] (when given) is appended with [message].
     */
    fun update(name0: String, id: ObjectId, expect: Expect = Expect.Any, reflog: ReflogIdentity? = null, message: String = "") {
        RefName.requireValid(name0)
        var name = name0
        var hops = 0
        while (true) {
            val v = read(name) as? RefValue.Symbolic ?: break
            if (++hops > MAX_SYMREF_DEPTH) malformed("symbolic ref chain too deep")
            name = v.target
        }
        if (name != "HEAD") checkDirFileConflict(name)
        val old = resolveOrNull(name)
        locked(name) {
            checkExpect(name, expect)
            val before = resolveOrNull(name)
            fs.writeAtomic(path(name), (id.hex + "\n").toByteArray())
            if (reflog != null) appendReflog(name, before ?: algorithm.zero(), id, reflog, message)
        }
    }

    private fun resolveOrNull(name: String) = try { resolve(name) } catch (e: GitException) { if (e.kind == GitErrorKind.MALFORMED) null else throw e }

    /** Writes a direct id into [name] itself even when it is currently symbolic (detaches HEAD). */
    fun detach(name: String, id: ObjectId) {
        RefName.requireValid(name)
        locked(name) { fs.writeAtomic(path(name), (id.hex + "\n").toByteArray()) }
    }

    /** Points [name] (HEAD, or a symbolic ref) at another ref. */
    fun setSymbolic(name: String, target: String) {
        RefName.requireValid(name); RefName.requireValid(target)
        locked(name) { fs.writeAtomic(path(name), "ref: $target\n".toByteArray()) }
    }

    /** Deletes the ref (loose and packed). [Expect] is checked under the lock. */
    fun delete(name: String, expect: Expect = Expect.Any) {
        RefName.requireValid(name)
        if (!name.startsWith("refs/")) throw GitException(GitErrorKind.INVALID_REF_NAME, "only refs/ names can be deleted")
        locked(name) {
            checkExpect(name, expect)
            fs.delete(path(name))
            val pk = packed()
            if (pk.containsKey(name)) rewritePacked(pk - name)
            fs.delete(path("logs/$name"))
        }
        pruneEmpty(path(name).substringBeforeLast('/'), path("refs"))
        pruneEmpty(path("logs/$name").substringBeforeLast('/'), path("logs/refs"))
    }

    private fun pruneEmpty(startDir: String, stopDir: String) {
        var dir = startDir
        while (dir.length > stopDir.length && dir.startsWith(stopDir) && fs.stat(dir)?.kind == FileKind.DIRECTORY && fs.list(dir).isEmpty()) {
            fs.delete(dir)
            dir = dir.substringBeforeLast('/')
        }
    }

    private fun hasFileBeneath(dir: String): Boolean = fs.list(dir).any {
        val k = fs.stat("$dir/$it")?.kind
        k == FileKind.FILE || (k == FileKind.DIRECTORY && hasFileBeneath("$dir/$it"))
    }

    private fun rewritePacked(map: Map<String, ObjectId>) {
        val lock = path("packed-refs.lock")
        if (!fs.createExclusive(lock, ByteArray(0))) throw GitException(GitErrorKind.LOCKED, "packed-refs is locked")
        try {
            val text = buildString {
                append("# pack-refs with: sorted \n")
                for ((n, id) in map.toSortedMap()) append(id.hex).append(' ').append(n).append('\n')
            }
            fs.writeAtomic(path("packed-refs"), text.toByteArray())
            packedCache = null
        } finally { fs.delete(lock) }
    }

    /** Test/maintenance helper: moves every loose ref under refs/ into packed-refs. */
    fun packAll() {
        val all = list("refs/")
        rewritePacked(all)
        for (n in all.keys) fs.delete(path(n))
    }

    // ------------------------------------------------------------------ reflog

    private fun appendReflog(name: String, old: ObjectId, new: ObjectId, who: ReflogIdentity, message: String) {
        val p = path("logs/$name")
        val prior = if (fs.stat(p)?.kind == FileKind.FILE) fs.readBytes(p, 16L * 1024 * 1024) else ByteArray(0)
        val sig = Signature(who.name, who.email, who.epochSeconds, who.tzMinutes).format()
        val line = "${old.hex} ${new.hex} $sig\t${message.replace('\n', ' ')}\n"
        fs.writeAtomic(p, prior + line.toByteArray(Charsets.UTF_8))
    }

    /** Appends to the reflog of [name] only (used when HEAD follows a branch). */
    fun logOnly(name: String, old: ObjectId?, new: ObjectId, who: ReflogIdentity, message: String) {
        RefName.requireValid(name)
        appendReflog(name, old ?: algorithm.zero(), new, who, message)
    }

    fun reflog(name: String, max: Int = 1000): List<ReflogEntry> {
        RefName.requireValid(name)
        val p = path("logs/$name")
        if (fs.stat(p)?.kind != FileKind.FILE) return emptyList()
        val out = ArrayList<ReflogEntry>()
        for (line in String(fs.readBytes(p, 16L * 1024 * 1024), Charsets.UTF_8).split('\n')) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            val head = if (tab < 0) line else line.substring(0, tab)
            val msg = if (tab < 0) "" else line.substring(tab + 1)
            val w = algorithm.hexLength
            if (head.length < 2 * w + 3) malformed("bad reflog line")
            val old = ObjectId.fromHex(head.substring(0, w), algorithm)
            val new = ObjectId.fromHex(head.substring(w + 1, 2 * w + 1), algorithm)
            out.add(ReflogEntry(old, new, Signature.parse(head.substring(2 * w + 2)), msg))
        }
        return out.takeLast(max).reversed()
    }
}
