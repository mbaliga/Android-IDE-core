package dev.fonebrew.probe.steptwo

import dev.fonebrew.probe.BuildConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StepTwoTest {
    private fun tarEntry(out: ByteArrayOutputStream, name: String, type: Char, body: ByteArray = ByteArray(0), link: String = "", mode: Int = 420) {
        val h = ByteArray(512)
        fun put(off: Int, s: String) { s.toByteArray().copyInto(h, off) }
        put(0, name); put(100, "%07o".format(mode)); put(124, "%011o".format(body.size)); h[156] = type.code.toByte(); put(157, link)
        out.write(h); out.write(body); out.write(ByteArray((512 - body.size % 512) % 512))
    }
    private class Rec : TarSink {
        val log = mutableListOf<String>()
        override fun dir(path: String, mode: Int) { log += "dir $path" }
        override fun file(path: String, mode: Int, content: InputStream, size: Long) { log += "file $path ${String(content.readBytes())} mode=${mode.toString(8)}" }
        override fun symlink(path: String, target: String) { log += "link $path -> $target" }
    }
    private fun tar(build: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also { build(it); it.write(ByteArray(1024)) }.toByteArray()
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("u0").toFile()

    @Test fun buildFlagIsOffInTheDefaultBuild() = assertFalse(BuildConfig.STEP_TWO_ENABLED)

    @Test fun gateClosedThrowsBeforeAnyFetchOrWrite() {
        val fetched = mutableListOf<String>()
        val work = tmp()
        val r = StepTwoRunner(StepTwoGate(false), StepTwoConfig("", "", "https://x/y.tar.gz", "a".repeat(64)),
            { u -> fetched += u; ByteArrayInputStream(ByteArray(0)) }, File(work, "w"), work)
        try { r.run(); fail("expected IllegalStateException") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("disabled")) }
        assertTrue(fetched.isEmpty()); assertFalse(File(work, "w").exists())
    }

    @Test fun unsetConfigRefusesEvenWithGateOpen() {
        val fetched = mutableListOf<String>()
        val work = tmp()
        val text = StepTwoRunner(StepTwoGate(true), StepTwoConfig.UNSET, { u -> fetched += u; ByteArrayInputStream(ByteArray(0)) }, File(work, "w"), work).run()
        assertTrue(text.contains("rootfsUrl is empty")); assertTrue(fetched.isEmpty())
    }

    @Test fun configRequiresHttpsAndHexDigests() {
        assertTrue(StepTwoConfig("", "", "http://x", "zz").problems().size >= 2)
        assertTrue(StepTwoConfig("https://p", "bad", "https://r", "a".repeat(64)).problems().any { it.contains("prootSha256") })
        assertTrue(StepTwoConfig("", "", "https://r", "A".repeat(64)).problems().isEmpty())
    }

    @Test fun sha256KnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex(ByteArrayInputStream("abc".toByteArray())))
        assertTrue(Sha256.matches("BA78", "ba78"))
    }

    @Test fun planIsTheThreeRequestedCommands() {
        val c = StepTwoPlan.commands("/p", "/r")
        assertEquals(listOf("/p", "-r", "/r", "/bin/true"), c[0])
        assertEquals(listOf("/p", "-r", "/r", "/bin/uname", "-a"), c[1])
        assertEquals(listOf("/p", "-r", "/r", "/usr/bin/apt", "--version"), c[2])
    }

    @Test fun tarExtractsDirsFilesLinks() {
        val rec = Rec()
        TarReader.extract(ByteArrayInputStream(tar { o ->
            tarEntry(o, "./", '5'); tarEntry(o, "./bin/", '5', mode = 493)
            tarEntry(o, "./bin/true", '0', "hello".toByteArray(), mode = 493); tarEntry(o, "./bin/sh", '2', link = "dash")
        }), rec)
        assertEquals(listOf("dir bin", "file bin/true hello mode=755", "link bin/sh -> dash"), rec.log)
    }

    @Test fun tarRejectsTraversalAndAbsolutePaths() {
        for (bad in listOf("../evil", "a/../../evil", "/etc/passwd")) {
            try { TarReader.extract(ByteArrayInputStream(tar { tarEntry(it, bad, '0', "x".toByteArray()) }), Rec()); fail("accepted $bad") }
            catch (_: TarReader.UnsafePath) {}
        }
    }

    @Test fun tarSkipsUnknownTypesWithoutDesync() {
        val rec = Rec()
        TarReader.extract(ByteArrayInputStream(tar { o -> tarEntry(o, "dev", '3'); tarEntry(o, "f", '0', ByteArray(700) { 65 }); tarEntry(o, "g", '0', "z".toByteArray()) }), rec)
        assertEquals(2, rec.log.size); assertTrue(rec.log[1].startsWith("file g z"))
    }

    @Test fun tarUnreadContentIsDrainedForTheNextEntry() {
        val sink = object : TarSink {
            val names = mutableListOf<String>()
            override fun dir(path: String, mode: Int) {}
            override fun file(path: String, mode: Int, content: InputStream, size: Long) { names += path }
            override fun symlink(path: String, target: String) {}
        }
        TarReader.extract(ByteArrayInputStream(tar { o -> tarEntry(o, "a", '0', ByteArray(600)); tarEntry(o, "b", '0', ByteArray(10)) }), sink)
        assertEquals(listOf("a", "b"), sink.names)
    }
}
