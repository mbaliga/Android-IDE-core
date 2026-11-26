package dev.fonebrew.domain.remote.term

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real shell output through a real host pty (util-linux `script`) into the real parser, fed in
 * 3-byte reads so multibyte characters split. Skipped where `script` is missing. This exercises
 * the VT parser against genuine line-discipline output (ONLCR `\r\n`); it says nothing about
 * Android's pty or /system/bin/sh, which are owner-verified only.
 */
class HostPtyTest {
    private fun run(cmd: String): ByteArray? {
        val script = listOf("/usr/bin/script", "/bin/script").firstOrNull { File(it).canExecute() } ?: return null
        val p = ProcessBuilder(script, "-qec", cmd, "/dev/null").redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.readBytes()
        return if (p.waitFor() == 0) out else null
    }

    @Test fun `colour and utf8 shell output lands on the screen`() {
        val bytes = run("printf 'a\\033[31mb\\033[0m\\n\\346\\227\\245\\346\\234\\254\\n'")
        assumeTrue(bytes != null)
        val ch = PtyChannel(5, 20)
        bytes!!.toList().chunked(3).forEach { ch.onOutputBytes(it.toByteArray()) }
        assertEquals("ab", ch.screen.lineText(0))
        assertEquals("日本", ch.screen.lineText(1))
        assertEquals(TermColor.Indexed(1), ch.screen.rowsSnapshot()[0][1].sgr.fg)
    }
}
