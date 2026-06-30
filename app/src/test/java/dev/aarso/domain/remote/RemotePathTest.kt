package dev.aarso.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {

    @Test fun `join cleans redundant slashes and keeps a leading root`() {
        assertEquals("home/pi/app", RemotePath.join("home", "pi", "app"))
        assertEquals("/home/pi/app", RemotePath.join("/home", "/pi/", "app"))
        assertEquals("/home/pi", RemotePath.join("/home/", "", "pi"))
    }

    @Test fun `parent and name decompose a path`() {
        assertEquals("/home/pi", RemotePath.parent("/home/pi/app.py"))
        assertEquals("app.py", RemotePath.name("/home/pi/app.py"))
        assertEquals("/", RemotePath.parent("/etc"))
        assertEquals("etc", RemotePath.name("/etc/"))
    }

    @Test fun `host validation rejects bad input`() {
        val ok = RemoteHost("dell", "10.0.0.5", 2222, "build")
        assertEquals("10.0.0.5:2222", ok.endpoint)
        assertThrows { RemoteHost("", "h", 22, "u") }
        assertThrows { RemoteHost("a", "h", 0, "u") }
        assertThrows { RemoteHost("a", "", 22, "u") }
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try { block() } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
