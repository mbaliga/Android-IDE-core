package dev.fonebrew.domain.gitcore

import java.io.OutputStream
import java.util.zip.Inflater

/** Loose objects under `objects/xx/yyyy…`: zlib of `"<type> <size>\0<body>"`, written atomically. */
class LooseObjectStore(
    private val fs: FileSystemPort,
    private val objectsDir: String,
    private val limits: GitLimits = GitLimits(),
    private val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
) {
    fun pathOf(id: ObjectId): String = "$objectsDir/${id.hex.substring(0, 2)}/${id.hex.substring(2)}"

    fun has(id: ObjectId): Boolean = fs.stat(pathOf(id))?.kind == FileKind.FILE

    fun read(id: ObjectId): RawObject? {
        val p = pathOf(id)
        if (fs.stat(p)?.kind != FileKind.FILE) return null
        // Compressed file can legitimately exceed the body cap only by a small factor; cap the read itself.
        val z = fs.readBytes(p, limits.maxInMemoryObject + 4096 + limits.maxInMemoryObject / 100)
        val inflated = Zlib.inflate(z, 0, z.size, limits.maxInMemoryObject + 64).data
        var nul = 0
        while (nul < inflated.size && nul < 32 && inflated[nul] != 0.toByte()) nul++
        if (nul >= inflated.size || nul >= 32) malformed("loose object header missing")
        val header = String(inflated, 0, nul, Charsets.US_ASCII)
        val sp = header.indexOf(' ')
        val type = ObjectType.fromTag(if (sp < 0) "" else header.substring(0, sp)) ?: malformed("unknown loose object type")
        val size = header.substring(sp + 1).let { s -> if (s.isEmpty() || !s.all { it in '0'..'9' } || s.length > 12) malformed("bad loose size") else s.toLong() }
        if (size != (inflated.size - nul - 1).toLong()) malformed("loose object size field disagrees with content")
        if (size > limits.maxInMemoryObject) limit("loose object of $size bytes exceeds in-memory cap")
        return RawObject(type, inflated.copyOfRange(nul + 1, inflated.size))
    }

    /** Header-only size/type peek (inflates at most the first 64 bytes). */
    fun peek(id: ObjectId): Pair<ObjectType, Long>? {
        val p = pathOf(id)
        if (fs.stat(p)?.kind != FileKind.FILE) return null
        val inf = Inflater()
        try {
            fs.openRead(p).use { ins ->
                val inBuf = ByteArray(512)
                val head = ByteArray(64)
                var have = 0
                while (have < head.size && !inf.finished()) {
                    if (inf.needsInput()) {
                        val n = ins.read(inBuf)
                        if (n <= 0) break
                        inf.setInput(inBuf, 0, n)
                    }
                    have += try { inf.inflate(head, have, head.size - have) } catch (e: java.util.zip.DataFormatException) {
                        throw GitException(GitErrorKind.MALFORMED, "corrupt loose object", cause = e)
                    }
                }
                var nul = 0
                while (nul < have && head[nul] != 0.toByte()) nul++
                if (nul >= have) malformed("loose object header missing")
                val h = String(head, 0, nul, Charsets.US_ASCII)
                val sp = h.indexOf(' ')
                val type = ObjectType.fromTag(if (sp < 0) "" else h.substring(0, sp)) ?: malformed("unknown loose object type")
                val size = h.substring(sp + 1).toLongOrNull() ?: malformed("bad loose size")
                return type to size
            }
        } finally { inf.end() }
    }

    /** Streams the body to [out] with no size cap (constant memory). Header is validated. */
    fun streamTo(id: ObjectId, out: OutputStream): Boolean {
        val p = pathOf(id)
        if (fs.stat(p)?.kind != FileKind.FILE) return false
        java.util.zip.InflaterInputStream(fs.openRead(p)).use { ins ->
            val head = java.io.ByteArrayOutputStream()
            var b: Int
            while (true) {
                b = ins.read()
                if (b < 0) malformed("loose header missing")
                if (b == 0) break
                head.write(b)
                if (head.size() > 32) malformed("loose header too long")
            }
            val size = String(head.toByteArray(), Charsets.US_ASCII).substringAfter(' ').toLongOrNull() ?: malformed("bad loose size")
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = try { ins.read(buf) } catch (e: java.util.zip.ZipException) { throw GitException(GitErrorKind.MALFORMED, "corrupt loose object", cause = e) }
                if (n < 0) break
                total += n
                if (total > size) malformed("loose object longer than declared")
                out.write(buf, 0, n)
            }
            if (total != size) malformed("loose object shorter than declared")
        }
        return true
    }

    /** Writes the object if absent and returns its id. */
    fun write(type: ObjectType, body: ByteArray): ObjectId {
        val id = ObjectId.hashObject(type, body, algorithm)
        if (has(id)) return id
        val framed = ByteArray(body.size + 32)
        val head = "${type.tag} ${body.size}".toByteArray(Charsets.US_ASCII)
        System.arraycopy(head, 0, framed, 0, head.size)
        framed[head.size] = 0
        System.arraycopy(body, 0, framed, head.size + 1, body.size)
        fs.writeAtomic(pathOf(id), Zlib.deflate(framed.copyOf(head.size + 1 + body.size), 1))
        return id
    }

    fun allIds(): List<ObjectId> {
        val out = ArrayList<ObjectId>()
        for (d in fs.list(objectsDir)) {
            if (d.length != 2 || !d.all { it in '0'..'9' || it in 'a'..'f' }) continue
            for (f in fs.list("$objectsDir/$d")) ObjectId.fromHexOrNull(d + f, algorithm)?.let(out::add)
        }
        return out
    }
}
