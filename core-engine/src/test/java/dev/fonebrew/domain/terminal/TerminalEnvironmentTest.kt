package dev.fonebrew.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEnvironmentTest {
    private val paths = SessionPaths("/data/h", "/data/t", "/data/b", "/data/rc")

    @Test fun `environment is the allow-list and nothing else leaks`() {
        val inherited = mapOf(
            "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data", "LD_PRELOAD" to "/evil.so",
            "OPENAI_API_KEY" to "sk-secret", "HTTPS_PROXY" to "http://p", "BOOTCLASSPATH" to "/a:/b",
            "HOME" to "/root", "PATH" to "/usr/bin", "ANDROID_ART_ROOT" to "",
        )
        val env = TerminalEnvironment.build(paths, inherited)
        assertEquals(
            setOf("HOME", "PATH", "TMPDIR", "LANG", "TERM", "COLORTERM", "ENV", "SHELL", "ANDROID_ROOT", "ANDROID_DATA", "BOOTCLASSPATH"),
            env.keys,
        )
        assertEquals("/data/h", env["HOME"])
        assertEquals("/system/bin:/system/xbin:/data/b", env["PATH"])
        assertEquals("C.UTF-8", env["LANG"]); assertEquals("xterm-256color", env["TERM"]); assertEquals("truecolor", env["COLORTERM"])
        assertEquals("/data/rc", env["ENV"])
        assertFalse(env.values.any { it.contains("sk-secret") || it.contains("evil") })
    }

    @Test fun `envp strings`() {
        assertEquals(listOf("A=1", "B=2"), TerminalEnvironment.toEnvp(linkedMapOf("A" to "1", "B" to "2")).toList())
    }

    @Test fun `rc file has prompt aliases and the marked extension point`() {
        val rc = TerminalEnvironment.rcFileContent()
        assertTrue(rc.contains("PS1='\$PWD \$ '"))
        assertTrue(rc.contains("alias ll='ls -l'"))
        assertTrue(rc.indexOf(TerminalEnvironment.EXTENSION_BEGIN) in 0 until rc.indexOf(TerminalEnvironment.EXTENSION_END))
        assertTrue(rc.contains("rc.d"))
        assertFalse(rc.contains("rm "))
    }
}
