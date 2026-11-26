package dev.fonebrew.probe

import java.util.concurrent.TimeUnit

/** Runs a child process with a deadline and captures merged output. All failures become data. */
object Exec {
    fun run(cmd: List<String>, timeoutMs: Long, env: Map<String, String> = emptyMap()): ExecResult {
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        } catch (t: Throwable) {
            return ExecResult(null, "", "$t", false)
        }
        val sb = StringBuilder()
        val reader = Thread {
            try { p.inputStream.bufferedReader().use { r -> val b = CharArray(2048); while (true) { val n = r.read(b); if (n < 0) break; synchronized(sb) { sb.append(b, 0, n) } } } } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.start() }
        val done = try { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done) p.destroyForcibly()
        reader.join(500)
        val text = synchronized(sb) { sb.toString() }
        return ExecResult(if (done) p.exitValue() else null, text, null, !done)
    }
}
