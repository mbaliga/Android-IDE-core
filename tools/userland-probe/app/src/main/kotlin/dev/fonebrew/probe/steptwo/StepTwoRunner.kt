package dev.fonebrew.probe.steptwo

import dev.fonebrew.probe.Exec
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** The only code in the probe that can touch the network; every entry point starts with [StepTwoGate.require]. */
fun interface Fetcher { fun open(url: String): InputStream }

class HttpFetcher : Fetcher {
    override fun open(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        check(c.responseCode == 200) { "HTTP ${c.responseCode} for $url" }
        return c.inputStream
    }
}

class StepTwoRunner(
    private val gate: StepTwoGate,
    private val config: StepTwoConfig,
    private val fetcher: Fetcher,
    private val work: File,
    private val nativeDir: File,
) {
    /** Returns a plain-text transcript. Throws if the gate is closed, BEFORE any fetch or file write. */
    fun run(): String {
        gate.require()
        val problems = config.problems()
        if (problems.isNotEmpty()) return "step two not run: " + problems.joinToString("; ")
        val log = StringBuilder()
        work.mkdirs()
        val prootPath = if (config.prootUrl.isNotBlank()) {
            val f = File(work, "proot_downloaded")
            download(config.prootUrl, config.prootSha256, f, log)
            f.setExecutable(true, true); f.path
        } else File(nativeDir, "libproot.so").path.also { log.appendLine("using packaged $it (exists=${File(it).exists()})") }
        val tgz = File(work, "rootfs.tar.gz")
        download(config.rootfsUrl, config.rootfsSha256, tgz, log)
        val root = File(work, "rootfs"); root.deleteRecursively(); root.mkdirs()
        tgz.inputStream().buffered().use { TarReader.extract(GZIPInputStream(it), FileSink(root)) }
        log.appendLine("extracted rootfs to ${root.path}")
        for (cmd in StepTwoPlan.commands(prootPath, root.path)) {
            val r = Exec.run(cmd, 60_000, mapOf("PROOT_TMP_DIR" to work.path))
            log.appendLine("$ ${cmd.drop(1).joinToString(" ")}\n  exit=${r.exit} timedOut=${r.timedOut} thrown=${r.thrown}\n  ${r.output.trim()}")
        }
        return log.toString()
    }

    private fun download(url: String, expected: String, dest: File, log: StringBuilder) {
        fetcher.open(url).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        val actual = dest.inputStream().use { Sha256.hex(it) }
        if (!Sha256.matches(actual, expected)) { dest.delete(); throw IllegalStateException("sha256 mismatch for $url: expected $expected, got $actual (file deleted)") }
        log.appendLine("downloaded and verified sha256 ${actual.take(12)}... for $url")
    }
}

private class FileSink(private val root: File) : TarSink {
    override fun dir(path: String, mode: Int) { File(root, path).mkdirs() }
    override fun file(path: String, mode: Int, content: InputStream, size: Long) {
        val f = File(root, path); f.parentFile?.mkdirs()
        f.outputStream().use { content.copyTo(it) }
        if (mode and 0b001_001_001 != 0) f.setExecutable(true, false)
    }
    override fun symlink(path: String, target: String) {
        val f = File(root, path); f.parentFile?.mkdirs()
        android.system.Os.symlink(target, f.path)
    }
}
