package dev.fonebrew.domain.gitcore

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random

/** Shared fixtures: temp dirs, a tiny git CLI driver, and a pack builder that can emit deltas. */
object GitTestSupport {
    fun tempDir(prefix: String = "gitcore"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    fun gitAvailable(): Boolean = try {
        ProcessBuilder("git", "--version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
    } catch (_: Exception) { false }

    /** Runs git in [dir] with a hermetic environment; returns stdout. Throws on non-zero exit. */
    fun git(dir: File, vararg args: String, stdin: ByteArray? = null, allowFail: Boolean = false): String {
        val pb = ProcessBuilder(listOf("git", "-c", "core.quotePath=false") + args).directory(dir).redirectErrorStream(true)
        val env = pb.environment()
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_CONFIG_GLOBAL"] = "/dev/null"
        env["HOME"] = dir.absolutePath
        env["GIT_AUTHOR_NAME"] = "Test A"; env["GIT_AUTHOR_EMAIL"] = "a@test"
        env["GIT_COMMITTER_NAME"] = "Test C"; env["GIT_COMMITTER_EMAIL"] = "c@test"
        env["GIT_AUTHOR_DATE"] = "1700000000 +0000"; env["GIT_COMMITTER_DATE"] = "1700000000 +0000"
        env["GIT_TERMINAL_PROMPT"] = "0"
        val p = pb.start()
        if (stdin != null) p.outputStream.use { it.write(stdin) } else p.outputStream.close()
        val out = p.inputStream.readBytes()
        val rc = p.waitFor()
        if (rc != 0 && !allowFail) throw AssertionError("git ${args.joinToString(" ")} failed ($rc): ${String(out)}")
        return String(out, Charsets.UTF_8)
    }

    fun gitBytes(dir: File, vararg args: String): ByteArray {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir)
        pb.environment()["GIT_CONFIG_NOSYSTEM"] = "1"; pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        val p = pb.start(); val out = p.inputStream.readBytes(); p.waitFor(); return out
    }

    fun write(f: File, text: String) { f.parentFile.mkdirs(); f.writeBytes(text.toByteArray()) }

    /** Minimal delta encoder for tests: copy [base] prefix of [keep] bytes then insert [tail]. */
    fun makeDelta(base: ByteArray, keep: Int, tail: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { val b = (x and 0x7f).toInt(); x = x ushr 7; if (x == 0L) { o.write(b); break } else o.write(b or 0x80) } }
        varint(base.size.toLong()); varint((keep + tail.size).toLong())
        if (keep > 0) {
            // copy op: offset 0 (no offset bytes), size in up to 3 bytes
            var op = 0x80; val sizeBytes = ArrayList<Int>()
            var k = keep
            for (i in 0 until 3) { val b = k and 0xff; k = k ushr 8; if (b != 0) { op = op or (0x10 shl i); sizeBytes.add(b) } else if (k != 0 || i < 0) {} }
            // sizes with zero middle bytes need explicit handling: rebuild robustly
            op = 0x80; sizeBytes.clear()
            for (i in 0 until 3) { val b = (keep ushr (8 * i)) and 0xff; if (b != 0) { op = op or (0x10 shl i); sizeBytes.add(b) } }
            require(keep < 0x10000 && keep != 0x10000)
            o.write(op); sizeBytes.forEach { o.write(it) }
        }
        var p = 0
        while (p < tail.size) { val n = minOf(127, tail.size - p); o.write(n); o.write(tail, p, n); p += n }
        return o.toByteArray()
    }

    class PackBuilder {
        private val body = ByteArrayOutputStream()
        private var count = 0
        val offsets = ArrayList<Long>()
        fun whole(type: ObjectType, data: ByteArray): Long {
            val off = 12L + body.size(); offsets.add(off)
            body.write(PackWriter.entryHeader(type.packCode, data.size.toLong())); body.write(Zlib.deflate(data)); count++
            return off
        }
        fun ofsDelta(baseOffset: Long, delta: ByteArray, resultSize: Long): Long {
            val off = 12L + body.size(); offsets.add(off)
            body.write(PackWriter.entryHeader(6, delta.size.toLong()))
            var rel = off - baseOffset
            val enc = ArrayList<Int>(); enc.add((rel and 0x7f).toInt()); rel = rel ushr 7
            while (rel != 0L) { rel -= 1; enc.add(0x80 or (rel and 0x7f).toInt()); rel = rel ushr 7 }
            enc.reversed().forEach { body.write(it) }
            body.write(Zlib.deflate(delta)); count++
            return off
        }
        fun refDelta(baseId: ObjectId, delta: ByteArray): Long {
            val off = 12L + body.size(); offsets.add(off)
            body.write(PackWriter.entryHeader(7, delta.size.toLong())); body.write(baseId.toByteArray()); body.write(Zlib.deflate(delta)); count++
            return off
        }
        fun build(corruptTrailer: Boolean = false): ByteArray {
            val head = ByteArrayOutputStream()
            head.write("PACK".toByteArray()); head.write(byteArrayOf(0, 0, 0, 2))
            head.write(byteArrayOf((count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte()))
            val all = head.toByteArray() + body.toByteArray()
            val sum = java.security.MessageDigest.getInstance("SHA-1").digest(all)
            if (corruptTrailer) sum[0] = (sum[0] + 1).toByte()
            return all + sum
        }
    }

    fun randomBytes(n: Int, seed: Long = 7): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }
}

/** A fresh engine repository in a temp dir with a fixed identity and a steppable clock. */
class TestRepo(bare: Boolean = false, limits: GitLimits = GitLimits(), ignore: IgnoreRules = IgnoreRules.NONE) {
    val dir: File = GitTestSupport.tempDir()
    var now = 1_700_000_000L
    val repo: Repository = Repository.init(JvmFileSystem(), dir.absolutePath, bare, "main", limits, ignore, clock = { now++ })
    init {
        repo.config.set("user", null, "name", "Eng Test")
        repo.config.set("user", null, "email", "eng@test")
        repo.saveConfig()
    }
    fun file(rel: String) = File(dir, rel)
    fun write(rel: String, text: String) { file(rel).parentFile.mkdirs(); file(rel).writeBytes(text.toByteArray()) }
    fun read(rel: String) = file(rel).readText()
    fun commitAll(msg: String): ObjectId { repo.add(listOf(".")); return repo.commit(CommitOptions(msg)) }
    fun git(vararg args: String, allowFail: Boolean = false) = GitTestSupport.git(dir, *args, allowFail = allowFail)
    fun gitDir() = if (repo.isBare) dir else File(dir, ".git")
}
