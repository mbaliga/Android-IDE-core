package dev.fonebrew.domain.gitcore

import java.io.ByteArrayOutputStream

enum class ObjectType(val tag: String, val packCode: Int) {
    COMMIT("commit", 1), TREE("tree", 2), BLOB("blob", 3), TAG("tag", 4);

    companion object {
        fun fromTag(tag: String): ObjectType? = values().firstOrNull { it.tag == tag }
        fun fromPackCode(c: Int): ObjectType? = values().firstOrNull { it.packCode == c }
    }
}

/** Tree-entry mode constants (octal as git writes them). */
object FileMode {
    const val REGULAR = 0x81A4 // 0100644
    const val EXECUTABLE = 0x81ED // 0100755
    const val SYMLINK = 0xA000 // 0120000
    const val TREE = 0x4000 // 040000
    const val GITLINK = 0xE000 // 0160000

    fun isTree(m: Int) = (m and 0xF000) == TREE
    fun isGitlink(m: Int) = (m and 0xF000) == GITLINK
    fun isSymlink(m: Int) = (m and 0xF000) == SYMLINK
    fun isFile(m: Int) = (m and 0xF000) == 0x8000

    /** Canonical mode for a work-tree file: git only distinguishes executable-or-not. */
    fun canonicalFile(executable: Boolean) = if (executable) EXECUTABLE else REGULAR
}

/** Anything git stores. [id] is the hash of its canonical bytes. */
sealed class GitObject {
    abstract val type: ObjectType
    /** Exact on-disk body (the part after the `type size\0` header). */
    abstract fun body(): ByteArray
    fun id(algorithm: HashAlgorithm = HashAlgorithm.SHA1): ObjectId = ObjectId.hashObject(type, body(), algorithm)
}

class Blob(val data: ByteArray) : GitObject() {
    override val type get() = ObjectType.BLOB
    override fun body() = data
    override fun equals(other: Any?) = other is Blob && other.data.contentEquals(data)
    override fun hashCode() = data.contentHashCode()
}

/** A tree entry. Names are raw bytes (git does not mandate UTF-8); [name] is the lossy text view. */
class TreeEntry(val mode: Int, val nameBytes: ByteArray, val id: ObjectId) {
    constructor(mode: Int, name: String, id: ObjectId) : this(mode, name.toByteArray(Charsets.UTF_8), id)
    val name: String get() = String(nameBytes, Charsets.UTF_8)
    val isTree get() = FileMode.isTree(mode)
    override fun equals(other: Any?) = other is TreeEntry && other.mode == mode && other.nameBytes.contentEquals(nameBytes) && other.id == id
    override fun hashCode() = 31 * (31 * mode + nameBytes.contentHashCode()) + id.hashCode()
    override fun toString() = "${java.lang.Integer.toOctalString(mode)} $name ${id.abbrev()}"
}

/**
 * A tree. Entries are held in git's canonical order (names compared as bytes, a directory
 * compared as if its name ended in `/`). When parsed from bytes the original body is kept so a
 * hostile-but-hashed tree (e.g. `040000` zero-padded mode) re-hashes to its true id.
 */
class Tree private constructor(val entries: List<TreeEntry>, private val raw: ByteArray?) : GitObject() {
    constructor(entries: List<TreeEntry>) : this(entries.sortedWith(ENTRY_ORDER), null)

    override val type get() = ObjectType.TREE

    override fun body(): ByteArray = raw ?: serializeEntries(entries)

    fun find(name: String): TreeEntry? {
        val nb = name.toByteArray(Charsets.UTF_8)
        return entries.firstOrNull { it.nameBytes.contentEquals(nb) }
    }

    override fun equals(other: Any?) = other is Tree && other.entries == entries
    override fun hashCode() = entries.hashCode()

    companion object {
        val ENTRY_ORDER: Comparator<TreeEntry> = Comparator { a, b -> compareNames(a.nameBytes, a.isTree, b.nameBytes, b.isTree) }

        fun compareNames(a: ByteArray, aTree: Boolean, b: ByteArray, bTree: Boolean): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (c != 0) return c
            }
            val ca = if (a.size > n) a[n].toInt() and 0xff else if (aTree) '/'.code else -1
            val cb = if (b.size > n) b[n].toInt() and 0xff else if (bTree) '/'.code else -1
            return ca - cb
        }

        private fun serializeEntries(entries: List<TreeEntry>): ByteArray {
            val out = ByteArrayOutputStream()
            for (e in entries) {
                out.write(java.lang.Integer.toOctalString(e.mode).toByteArray(Charsets.US_ASCII))
                out.write(' '.code)
                out.write(e.nameBytes)
                out.write(0)
                out.write(e.id.toByteArray())
            }
            return out.toByteArray()
        }

        /** Parses tree bytes; any malformation is a typed error. Does not require canonical order. */
        fun parse(body: ByteArray, limits: GitLimits = GitLimits(), algorithm: HashAlgorithm = HashAlgorithm.SHA1): Tree {
            val entries = ArrayList<TreeEntry>()
            var p = 0
            val w = algorithm.byteLength
            while (p < body.size) {
                var sp = p
                while (sp < body.size && body[sp] != ' '.code.toByte()) sp++
                if (sp >= body.size || sp == p || sp - p > 7) malformed("bad tree entry mode")
                var mode = 0
                for (i in p until sp) {
                    val d = body[i] - '0'.code.toByte()
                    if (d < 0 || d > 7) malformed("non-octal tree mode")
                    mode = mode * 8 + d
                }
                var nul = sp + 1
                while (nul < body.size && body[nul] != 0.toByte()) nul++
                if (nul >= body.size) malformed("unterminated tree entry name")
                val name = body.copyOfRange(sp + 1, nul)
                if (name.isEmpty()) malformed("empty tree entry name")
                if (nul + 1 + w > body.size) malformed("truncated tree entry id")
                entries.add(TreeEntry(mode, name, ObjectId.fromBytes(body, nul + 1, algorithm)))
                if (entries.size > limits.maxTreeEntries) limit("tree has more than ${limits.maxTreeEntries} entries")
                p = nul + 1 + w
            }
            return Tree(entries, body)
        }
    }
}

/** `Name <email> seconds +hhmm`. [tzMinutes] is the signed offset from UTC in minutes. */
data class Signature(val name: String, val email: String, val epochSeconds: Long, val tzMinutes: Int) {
    fun format(): String {
        val sign = if (tzMinutes < 0) '-' else '+'
        val a = Math.abs(tzMinutes)
        return String.format(java.util.Locale.ROOT, "%s <%s> %d %c%02d%02d", name, email, epochSeconds, sign, a / 60, a % 60)
    }

    companion object {
        fun parse(s: String): Signature {
            val lt = s.indexOf('<')
            val gt = s.lastIndexOf('>')
            if (lt < 0 || gt < lt) malformed("signature without <email>")
            val name = s.substring(0, lt).trimEnd()
            val email = s.substring(lt + 1, gt)
            val rest = s.substring(gt + 1).trim().split(' ').filter { it.isNotEmpty() }
            if (rest.size != 2) malformed("signature without time and zone")
            val secs = rest[0].toLongOrNull() ?: malformed("bad signature time")
            val tz = rest[1]
            if (tz.length != 5 || (tz[0] != '+' && tz[0] != '-') || !tz.substring(1).all { it in '0'..'9' }) malformed("bad timezone")
            val mins = tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()
            return Signature(name, email, secs, if (tz[0] == '-') -mins else mins)
        }
    }
}

/** Parses `key value` headers with space-led continuation lines into ([key], multi-line value) pairs. */
private fun splitHeaders(text: String): Triple<List<Pair<String, String>>, String, Boolean> {
    val headers = ArrayList<Pair<String, String>>()
    var pos = 0
    while (true) {
        if (pos >= text.length) return Triple(headers, "", false)
        if (text[pos] == '\n') return Triple(headers, text.substring(pos + 1), true)
        val eol = text.indexOf('\n', pos).let { if (it < 0) malformed("unterminated header line") else it }
        val line = text.substring(pos, eol)
        pos = eol + 1
        if (line.startsWith(" ")) {
            if (headers.isEmpty()) malformed("continuation line before any header")
            val (k, v) = headers.last()
            headers[headers.size - 1] = k to (v + "\n" + line.substring(1))
        } else {
            val sp = line.indexOf(' ')
            headers.add(if (sp < 0) line to "" else line.substring(0, sp) to line.substring(sp + 1))
        }
    }
}

private fun headerBytes(key: String, value: String): String = key + " " + value.replace("\n", "\n ") + "\n"

class Commit private constructor(
    val tree: ObjectId,
    val parents: List<ObjectId>,
    val author: Signature,
    val committer: Signature,
    /** Remaining headers in file order: `encoding`, `gpgsig`, `mergetag`, and anything unknown. */
    val extraHeaders: List<Pair<String, String>>,
    val message: String,
    private val raw: ByteArray?,
) : GitObject() {
    constructor(
        tree: ObjectId,
        parents: List<ObjectId>,
        author: Signature,
        committer: Signature,
        message: String,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ) : this(tree, parents, author, committer, extraHeaders, message, null)

    override val type get() = ObjectType.COMMIT
    val encoding: String? get() = extraHeaders.firstOrNull { it.first == "encoding" }?.second
    val gpgSignature: String? get() = extraHeaders.firstOrNull { it.first == "gpgsig" }?.second

    override fun body(): ByteArray = raw ?: buildString {
        append("tree ").append(tree.hex).append('\n')
        for (p in parents) append("parent ").append(p.hex).append('\n')
        append("author ").append(author.format()).append('\n')
        append("committer ").append(committer.format()).append('\n')
        for ((k, v) in extraHeaders) append(headerBytes(k, v))
        append('\n').append(message)
    }.toByteArray(Charsets.UTF_8)

    val summary: String get() = message.lineSequence().firstOrNull().orEmpty()

    override fun equals(other: Any?) = other is Commit && other.tree == tree && other.parents == parents &&
        other.author == author && other.committer == committer && other.extraHeaders == extraHeaders && other.message == message
    override fun hashCode() = tree.hashCode() * 31 + parents.hashCode()

    companion object {
        fun parse(body: ByteArray, limits: GitLimits = GitLimits(), algorithm: HashAlgorithm = HashAlgorithm.SHA1): Commit {
            if (body.size > limits.maxCommitBytes) limit("commit larger than ${limits.maxCommitBytes} bytes")
            val (headers, message, _) = splitHeaders(String(body, Charsets.UTF_8))
            var tree: ObjectId? = null
            val parents = ArrayList<ObjectId>()
            var author: Signature? = null
            var committer: Signature? = null
            val extra = ArrayList<Pair<String, String>>()
            for ((k, v) in headers) when (k) {
                "tree" -> if (tree == null) tree = ObjectId.fromHex(v, algorithm) else malformed("duplicate tree header")
                "parent" -> parents.add(ObjectId.fromHex(v, algorithm))
                "author" -> if (author == null) author = Signature.parse(v) else malformed("duplicate author")
                "committer" -> if (committer == null) committer = Signature.parse(v) else malformed("duplicate committer")
                else -> extra.add(k to v)
            }
            return Commit(
                tree ?: malformed("commit has no tree"), parents,
                author ?: malformed("commit has no author"), committer ?: malformed("commit has no committer"),
                extra, message, body,
            )
        }
    }
}

class Tag private constructor(
    val target: ObjectId,
    val targetType: ObjectType,
    val name: String,
    val tagger: Signature?,
    val message: String,
    private val raw: ByteArray?,
) : GitObject() {
    constructor(target: ObjectId, targetType: ObjectType, name: String, tagger: Signature?, message: String) :
        this(target, targetType, name, tagger, message, null)

    override val type get() = ObjectType.TAG

    override fun body(): ByteArray = raw ?: buildString {
        append("object ").append(target.hex).append('\n')
        append("type ").append(targetType.tag).append('\n')
        append("tag ").append(name).append('\n')
        if (tagger != null) append("tagger ").append(tagger.format()).append('\n')
        append('\n').append(message)
    }.toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?) = other is Tag && other.target == target && other.targetType == targetType &&
        other.name == name && other.tagger == tagger && other.message == message
    override fun hashCode() = target.hashCode() * 31 + name.hashCode()

    companion object {
        fun parse(body: ByteArray, limits: GitLimits = GitLimits(), algorithm: HashAlgorithm = HashAlgorithm.SHA1): Tag {
            if (body.size > limits.maxCommitBytes) limit("tag larger than ${limits.maxCommitBytes} bytes")
            val (headers, message, _) = splitHeaders(String(body, Charsets.UTF_8))
            val m = headers.toMap()
            return Tag(
                ObjectId.fromHex(m["object"] ?: malformed("tag has no object"), algorithm),
                ObjectType.fromTag(m["type"] ?: malformed("tag has no type")) ?: malformed("unknown tag target type"),
                m["tag"] ?: malformed("tag has no name"),
                m["tagger"]?.let { Signature.parse(it) },
                message, body,
            )
        }
    }
}

object GitObjects {
    fun parse(type: ObjectType, body: ByteArray, limits: GitLimits = GitLimits(), algorithm: HashAlgorithm = HashAlgorithm.SHA1): GitObject = when (type) {
        ObjectType.BLOB -> Blob(body)
        ObjectType.TREE -> Tree.parse(body, limits, algorithm)
        ObjectType.COMMIT -> Commit.parse(body, limits, algorithm)
        ObjectType.TAG -> Tag.parse(body, limits, algorithm)
    }
}
