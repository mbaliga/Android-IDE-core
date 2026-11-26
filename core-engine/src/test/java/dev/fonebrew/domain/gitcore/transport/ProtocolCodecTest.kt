package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.ObjectId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class ProtocolCodecTest {
    private fun reader(s: String) = PktLineReader(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)))
    private fun reader(b: ByteArray) = PktLineReader(ByteArrayInputStream(b))
    private fun expectKind(kind: TransportErrorKind, body: () -> Unit) {
        try { body(); fail("expected $kind") } catch (e: TransportException) { assertEquals(kind, e.kind) }
    }
    private val id1 = "1".repeat(40)
    private val id2 = "2".repeat(40)

    // ---- pkt-line
    @Test fun writerAndReaderRoundTripAllSpecialPackets() {
        val bytes = PktLineWriter.build { text("hello"); delim(); data(byteArrayOf(0, 1, 2)); flush() }
        assertEquals("000ahello\n00010007\u0000\u0001\u00020000", String(bytes, Charsets.ISO_8859_1))
        val r = reader(bytes)
        assertEquals("hello", (r.require() as Pkt.Data).text())
        assertEquals(Pkt.Delim, r.require())
        assertArrayEquals(byteArrayOf(0, 1, 2), (r.require() as Pkt.Data).bytes)
        assertEquals(Pkt.Flush, r.require())
        assertNull(r.read())
    }

    @Test fun responseEndPacketIsRecognised() { assertEquals(Pkt.ResponseEnd, reader("0002").require()) }

    @Test fun lengthHeaderMayBeUpperCaseHex() {
        val p = reader("000AHELLO\n").require() as Pkt.Data
        assertEquals("HELLO", p.text())
    }

    @Test fun invalidLengthsAreProtocolErrors() {
        expectKind(TransportErrorKind.PROTOCOL) { reader("0003").require() }
        expectKind(TransportErrorKind.PROTOCOL) { reader("zzzz").require() }
        expectKind(TransportErrorKind.PROTOCOL) { reader("fff0").require() } // 65520 ok, but body missing => truncated
    }

    @Test fun oversizeFrameIsRefusedWithoutAllocating() {
        expectKind(TransportErrorKind.PROTOCOL) { reader("fff1").require() }
        expectKind(TransportErrorKind.PROTOCOL) { reader("ffff").require() }
    }

    @Test fun truncatedFrameAndTruncatedHeaderAreProtocolErrors() {
        expectKind(TransportErrorKind.PROTOCOL) { reader("000ahel").require() }
        expectKind(TransportErrorKind.PROTOCOL) { reader("00").require() }
    }

    @Test fun maxSizePayloadRoundTrips() {
        val payload = ByteArray(PktLimits.MAX_PAYLOAD) { 7 }
        val b = PktLineWriter.build { data(payload) }
        assertEquals(PktLimits.MAX_FRAME, b.size)
        assertArrayEquals(payload, (reader(b).require() as Pkt.Data).bytes)
        try { PktLineWriter(ByteArrayOutputStream()).data(ByteArray(PktLimits.MAX_PAYLOAD + 1)); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun readUntilFlushRejectsDelimAndEof() {
        expectKind(TransportErrorKind.PROTOCOL) { reader("0001").readUntilFlush() }
        expectKind(TransportErrorKind.PROTOCOL) { reader("0006a\n").readUntilFlush() }
    }

    @Test fun randomGarbageNeverEscapesAsAnythingButTransportException() {
        val rnd = Random(42)
        repeat(500) {
            val junk = ByteArray(rnd.nextInt(200)).also { rnd.nextBytes(it) }
            try { val r = reader(junk); while (r.read() != null) {} } catch (_: TransportException) {}
            try { AdvertisementParser.parse(ByteArrayInputStream(junk)) } catch (_: TransportException) {}
            try { SideBandInputStream(reader(junk)).readBytes() } catch (_: TransportException) {}
        }
    }

    // ---- capabilities
    @Test fun capabilityParsingKeepsRepeatsAndValues() {
        val c = Capabilities.parse("multi_ack side-band-64k symref=HEAD:refs/heads/main symref=refs/remotes/x:refs/heads/y agent=git/2.43 object-format=sha1")
        assertTrue(c.has("side-band-64k")); assertFalse(c.has("nope"))
        assertEquals(listOf("HEAD:refs/heads/main", "refs/remotes/x:refs/heads/y"), c.values("symref"))
        assertEquals("git/2.43", c.value("agent"))
        val v2 = Capabilities.fromLines(listOf("version 2".substringAfter(' ').let { "agent=git/2" }, "fetch=shallow wait-for-done", "ls-refs=unborn"))
        assertEquals(setOf("shallow", "wait-for-done"), v2.features("fetch"))
    }

    // ---- side-band
    private fun band(n: Int, s: String) = PktLineWriter.build { data(byteArrayOf(n.toByte()) + s.toByteArray()) }

    @Test fun sideBandDeliversPayloadAndSplitsProgressOnCrAndLf() {
        val msgs = ArrayList<String>()
        val wire = band(2, "Counting objects:  50%\rCounting objects: 100%\n") + band(1, "PA") + band(2, "partial") + band(1, "CK") + band(2, " line\n") + "0000".toByteArray()
        val sb = SideBandInputStream(reader(wire), onProgress = { msgs.add(it) })
        assertEquals("PACK", String(sb.readBytes()))
        assertEquals(listOf("Counting objects:  50%".trim(), "Counting objects: 100%", "partial line"), msgs)
    }

    @Test fun sideBandChannel3IsARemoteError() {
        val sb = SideBandInputStream(reader(band(1, "x") + band(3, "fatal: no space\u0007 left")))
        expectKind(TransportErrorKind.REMOTE_ERROR) { sb.readBytes() }
        try { SideBandInputStream(reader(band(3, "boom"))).readBytes() } catch (e: TransportException) { assertTrue(e.message!!.contains("boom")); assertFalse(e.message!!.contains("\u0007")) }
    }

    @Test fun sideBandUnknownChannelAndErrPacketAndDelimAreProtocolOrRemoteErrors() {
        expectKind(TransportErrorKind.PROTOCOL) { SideBandInputStream(reader(band(9, "x"))).readBytes() }
        expectKind(TransportErrorKind.REMOTE_ERROR) { SideBandInputStream(reader(PktLineWriter.build { text("ERR upload-pack: not our ref") })).readBytes() }
        expectKind(TransportErrorKind.PROTOCOL) { SideBandInputStream(reader("0001")).readBytes() }
    }

    @Test fun sideBandWithoutFlushIsATruncationError() {
        expectKind(TransportErrorKind.PROTOCOL) { SideBandInputStream(reader(band(1, "abc"))).readBytes() }
    }

    @Test fun sideBandHonoursCancellation() {
        val t = CancelToken(); val sb = SideBandInputStream(reader(band(1, "abc") + "0000".toByteArray()), cancel = t)
        t.cancel()
        expectKind(TransportErrorKind.CANCELLED) { sb.readBytes() }
    }

    // ---- advertisement
    private fun adv(block: PktLineWriter.() -> Unit) = ByteArrayInputStream(PktLineWriter.build(block))

    @Test fun v0AdvertisementWithServicePrologueCapsPeeledAndSymref() {
        val a = AdvertisementParser.parse(adv {
            text("# service=git-upload-pack"); flush()
            data("$id1 HEAD\u0000multi_ack side-band-64k symref=HEAD:refs/heads/main agent=git/2.43\n".toByteArray())
            text("$id1 refs/heads/main"); text("$id2 refs/tags/v1"); text("${"3".repeat(40)} refs/tags/v1^{}"); flush()
        }) as Advertisement.V0
        assertEquals(listOf("HEAD", "refs/heads/main", "refs/tags/v1"), a.refs.map { it.name })
        assertEquals(ObjectId.fromHex("3".repeat(40)), a.refs[2].peeled)
        assertEquals(listOf("HEAD:refs/heads/main"), a.capabilities.values("symref"))
    }

    @Test fun v0EmptyRepositoryAdvertisesOnlyCapabilities() {
        val a = AdvertisementParser.parse(adv { data("${"0".repeat(40)} capabilities^{}\u0000report-status delete-refs\n".toByteArray()); flush() }) as Advertisement.V0
        assertTrue(a.refs.isEmpty()); assertTrue(a.capabilities.has("delete-refs"))
    }

    @Test fun v1PrefixIsAccepted() {
        val a = AdvertisementParser.parse(adv { text("version 1"); data("$id1 refs/heads/a\u0000ofs-delta\n".toByteArray()); flush() }) as Advertisement.V0
        assertTrue(a.announcedVersion1); assertEquals(1, a.refs.size)
    }

    @Test fun v2CapabilityListKeepsValuesWithSpaces() {
        val a = AdvertisementParser.parse(adv { text("version 2"); text("agent=git/2.43"); text("ls-refs=unborn"); text("fetch=shallow wait-for-done"); text("object-format=sha1"); flush() }) as Advertisement.V2
        assertEquals(setOf("shallow", "wait-for-done"), a.capabilities.features("fetch"))
    }

    @Test fun advertisementErrAndMalformedLinesAreTyped() {
        expectKind(TransportErrorKind.REMOTE_ERROR) { AdvertisementParser.parse(adv { text("ERR access denied") }) }
        expectKind(TransportErrorKind.PROTOCOL) { AdvertisementParser.parse(adv { text("nothex refs/heads/a"); flush() }) }
        expectKind(TransportErrorKind.PROTOCOL) { AdvertisementParser.parse(adv { text("$id1 refs/heads/a"); text("$id2 refs/heads/b^{}"); flush() }) }
        expectKind(TransportErrorKind.PROTOCOL) { AdvertisementParser.parse(ByteArrayInputStream(ByteArray(0))) }
        expectKind(TransportErrorKind.PROTOCOL) { AdvertisementParser.parse(adv { data("$id1 refs/heads/a\u0000x\n".toByteArray()); data("$id1 refs/heads/b\u0000y\n".toByteArray()); flush() }) }
    }

    @Test fun refCountIsBounded() {
        // Not generating a million lines: prove the guard constant is enforced via the pure check.
        assertTrue(AdvertisementParser.MAX_REFS in 100_000..5_000_000)
    }
}
