package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReceivePackProtocolTest {
    private val a = "a".repeat(40); private val b = "b".repeat(40); private val zero = "0".repeat(40)
    private fun id(h: String) = ObjectId.fromHex(h)
    private fun advert(caps: String) = PktLineWriter.build { text("# service=git-receive-pack"); flush(); data("$a refs/heads/main\u0000$caps\n".toByteArray()); flush() }
    private fun band(s: ByteArray) = PktLineWriter.build { data(byteArrayOf(1) + s) }
    private fun report(vararg lines: String) = PktLineWriter.build { lines.forEach { text(it) }; flush() }
    private val caps = "report-status delete-refs side-band-64k quiet atomic ofs-delta agent=git/2 object-format=sha1"

    @Test fun commandLayoutCapabilitiesAndPackFollowFlush() {
        val resp = band(report("unpack ok", "ok refs/heads/main", "ok refs/heads/new")) + "0000".toByteArray()
        val t = FakeTransport(advert(caps), listOf(resp))
        val conn = ReceivePackConnection.open(t)
        val r = conn.push(listOf(PushCommand("refs/heads/main", id(a), id(b)), PushCommand("refs/heads/new", null, id(b))), { it.write("PACK!".toByteArray()) })
        val raw = t.requests.single()
        assertTrue(String(raw, Charsets.ISO_8859_1).endsWith("0000PACK!"))
        assertEquals(listOf("$a $b refs/heads/main<NUL>report-status side-band-64k object-format=sha1 agent=fonebrew", "$zero $b refs/heads/new", "<flush>"), describe(raw).take(3))
        assertNull(r.unpackError); assertEquals(mapOf("refs/heads/main" to null, "refs/heads/new" to null), r.results)
    }

    @Test fun deleteSendsNoPackAndAtomicIsOnlySentWhenAsked() {
        val t = FakeTransport(advert(caps), listOf(band(report("unpack ok", "ok refs/heads/main")) + "0000".toByteArray()))
        ReceivePackConnection.open(t).push(listOf(PushCommand("refs/heads/main", id(a), null)), null, atomic = true)
        val d = describe(t.requests.single())
        assertEquals("$a $zero refs/heads/main<NUL>report-status side-band-64k atomic object-format=sha1 agent=fonebrew", d[0])
        assertEquals(listOf(d[0], "<flush>"), d)
    }

    @Test fun serverRefusalsAreReportedVerbatimPerRef() {
        val t = FakeTransport(advert(caps), listOf(band(report("unpack ok", "ng refs/heads/main non-fast-forward")) + "0000".toByteArray()))
        val r = ReceivePackConnection.open(t).push(listOf(PushCommand("refs/heads/main", id(a), id(b))), { it.write(byteArrayOf()) })
        assertEquals("non-fast-forward", r.results["refs/heads/main"])
        val t2 = FakeTransport(advert(caps), listOf(band(report("unpack index-pack abnormal exit", "ng refs/heads/main unpacker error")) + "0000".toByteArray()))
        assertEquals("index-pack abnormal exit", ReceivePackConnection.open(t2).push(listOf(PushCommand("refs/heads/main", id(a), id(b))), { }).unpackError)
    }

    @Test fun withoutSidebandTheReportIsReadDirectly() {
        val t = FakeTransport(advert("report-status delete-refs"), listOf(report("unpack ok", "ok refs/heads/main")))
        assertTrue(ReceivePackConnection.open(t).push(listOf(PushCommand("refs/heads/main", id(a), id(b))), { }).results.containsKey("refs/heads/main"))
    }

    @Test fun missingCapabilitiesAreRefusedBeforeAnythingIsSent() {
        fun refused(c: String, cmds: List<PushCommand>, atomic: Boolean = false) {
            val t = FakeTransport(advert(c), emptyList())
            try { ReceivePackConnection.open(t).push(cmds, { }, atomic); fail("should refuse: $c") } catch (e: TransportException) { assertEquals(TransportErrorKind.UNSUPPORTED, e.kind) }
            assertTrue(t.requests.isEmpty())
        }
        refused("delete-refs", listOf(PushCommand("refs/heads/x", id(a), id(b))))                                  // no report-status
        refused("report-status", listOf(PushCommand("refs/heads/x", id(a), null)))                                 // no delete-refs
        refused("report-status", listOf(PushCommand("refs/heads/x", id(a), id(b))), atomic = true)                 // no atomic
        refused("report-status object-format=sha256", listOf(PushCommand("refs/heads/x", id(a), id(b))))
    }

    @Test fun incompleteReportIsAProtocolError() {
        val t = FakeTransport(advert(caps), listOf(band(report("unpack ok")) + "0000".toByteArray()))
        try { ReceivePackConnection.open(t).push(listOf(PushCommand("refs/heads/main", id(a), id(b))), { }); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.PROTOCOL, e.kind) }
        assertFalse(t.requests.isEmpty())
    }
}
