package dev.fonebrew.probe.steptwo

import java.io.InputStream
import java.security.MessageDigest

/**
 * STEP TWO (docs/design/userland-proot.md section 9): would run a real proot against a Debian
 * rootfs. DISABLED in the shipped probe: it is reachable only from the separate `stepTwo` build
 * type, which the owner must build by name after the legal ruling in
 * docs/non_ratified/USERLAND_PROPOSALS.md. No proot binary or source is in this repository; no URL
 * or checksum is pinned here (the owner supplies them, see [StepTwoConfig.UNSET]).
 */
class StepTwoGate(val enabled: Boolean) {
    fun require() { check(enabled) { "step two is disabled in this build (build the stepTwo build type to enable it)" } }
}

/** What to fetch and run. Empty by design: nothing is pinned, nothing can be fetched until the owner fills it in. */
data class StepTwoConfig(
    val prootUrl: String,           // optional; blank = use libproot.so from nativeLibraryDir if the owner packaged one
    val prootSha256: String,
    val rootfsUrl: String,          // a .tar.gz of a Debian arm64 rootfs (no xz decoder without a dependency)
    val rootfsSha256: String,
) {
    fun problems(): List<String> = buildList {
        if (rootfsUrl.isBlank()) add("rootfsUrl is empty")
        else if (!rootfsUrl.startsWith("https://")) add("rootfsUrl must be https")
        if (!Sha256.isHex(rootfsSha256)) add("rootfsSha256 must be 64 hex characters")
        if (prootUrl.isNotBlank()) {
            if (!prootUrl.startsWith("https://")) add("prootUrl must be https")
            if (!Sha256.isHex(prootSha256)) add("prootSha256 must be 64 hex characters when prootUrl is set")
        }
    }
    companion object { val UNSET = StepTwoConfig("", "", "", "") }
}

object Sha256 {
    fun isHex(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(actualHex: String, expectedHex: String) = actualHex.equals(expectedHex, ignoreCase = true)
}

/** The three commands the owner asked for, as argv lists. */
object StepTwoPlan {
    fun commands(proot: String, rootfs: String): List<List<String>> = listOf(
        listOf(proot, "-r", rootfs, "/bin/true"),
        listOf(proot, "-r", rootfs, "/bin/uname", "-a"),
        listOf(proot, "-r", rootfs, "/usr/bin/apt", "--version"),
    )
}

/** Where extracted entries go; implemented over java.io on device and in memory in tests. */
interface TarSink {
    fun dir(path: String, mode: Int)
    fun file(path: String, mode: Int, content: InputStream, size: Long)
    fun symlink(path: String, target: String)
}

/** Minimal ustar reader (public POSIX format, also GNU 'L' long names). Rejects absolute and '..' paths. */
object TarReader {
    class UnsafePath(path: String) : Exception("refusing unsafe tar path: $path")

    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/")) return false
        return path.split('/').none { it == ".." }
    }

    fun extract(input: InputStream, sink: TarSink) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            var name = str(header, 0, 100)
            val mode = str(header, 100, 8).trim().ifEmpty { "0" }.toInt(8)
            val size = str(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val link = str(header, 157, 100)
            val prefix = str(header, 345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            if (type == 'L') { longName = String(readBytes(input, size)).trimEnd('\u0000'); skipPad(input, size); continue }
            if (type == 'x' || type == 'g') { readBytes(input, size); skipPad(input, size); continue }
            longName?.let { name = it; longName = null }
            name = name.removePrefix("./").trimEnd('/')
            if (name.isEmpty() || name == ".") { skipPad(input, size); continue }
            if (!isSafe(name)) throw UnsafePath(name)
            when (type) {
                '5' -> sink.dir(name, mode)
                '2' -> sink.symlink(name, link)
                '0', '\u0000' -> { val body = BoundedStream(input, size); sink.file(name, mode, body, size); body.close(); skipPad(input, size) }
                else -> { readBytes(input, size); skipPad(input, size) } // hard links, devices: skipped, not guessed
            }
        }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off; while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }
    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var n = 0; while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) return false; n += r }; return true
    }
    private fun readBytes(i: InputStream, size: Long): ByteArray { val b = ByteArray(size.toInt()); if (!readFully(i, b)) throw java.io.EOFException("truncated tar"); return b }
    private fun skipPad(i: InputStream, size: Long) { var pad = (512 - size % 512) % 512; while (pad > 0) { val s = i.skip(pad); if (s <= 0) { if (i.read() < 0) return; pad-- } else pad -= s } }

    /** Exposes exactly [size] bytes of the tar stream to the sink, and drains what the sink left unread. */
    private class BoundedStream(private val i: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = i.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = i.read(b, off, minOf(len.toLong(), left).toInt()); if (r > 0) left -= r; return r
        }
        override fun close() { while (left > 0) { if (read() < 0) break } }
    }
}
