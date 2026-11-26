package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The request bytes and answer handling of upload-pack, against scripted servers (the real-git suite is in the interop tests). */
class UploadPackProtocolTest {
    private val a = "a".repeat(40); private val b = "b".repeat(40); private val c = "c".repeat(40)
    private fun id(h: String) = ObjectId.fromHex(h)
    private fun band(n: Int, s: ByteArray) = PktLineWriter.build { data(byteArrayOf(n.toByte()) + s) }
    private fun v0Advert(caps: String) = PktLineWriter.build {
        text("# service=git-upload-pack"); flush()
        data("$a HEAD\u0000$caps symref=HEAD:refs/heads/main\n".toByteArray()); text("$a refs/heads/main"); text("$b refs/tags/t"); text("$c refs/pull/1/head"); flush()
    }
    private val packBytes = "PACKDATA".toByteArray()

    @Test fun v0RequestLayoutWantsShallowDeepenFlushHavesDone() {
        val resp = PktLineWriter.build { text("shallow $a"); flush(); text("NAK") } + band(1, packBytes) + "0000".toByteArray()
        val t = FakeTransport(v0Advert("multi_ack side-band-64k thin-pack ofs-delta include-tag shallow no-done agent=git/2"), listOf(resp))
        val conn = UploadPackConnection.open(t)
        assertEquals(listOf("refs/heads/main", "refs/tags/t"), conn.refs.filter { it.name != "HEAD" }.map { it.name })
        assertEquals("refs/heads/main", conn.headTarget)
        val fr = conn.fetch(FetchRequest(listOf(id(a), id(b)), listOf(id(c)), depth = 1, clientShallow = listOf(id(c))))
        assertEquals(packBytes.toList(), fr.pack.readBytes().toList())
        assertEquals(listOf(id(a)), fr.shallow)
        val req = describe(t.requests.single())
        assertEquals("want $a side-band-64k thin-pack ofs-delta include-tag shallow agent=fonebrew", req[0])
        assertEquals(listOf("want $b", "shallow $c", "deepen 1", "<flush>", "have $c", "done"), req.drop(1))
        assertFalse("multi_ack must not be requested: we do one round with done", req[0].contains("multi_ack"))
        assertFalse(t.requestV2Flags.single())
    }

    @Test fun v0AckLineWithoutSuffixEndsNegotiationAndErrIsSurfaced() {
        val t = FakeTransport(v0Advert("side-band-64k"), listOf(PktLineWriter.build { text("ACK $a") } + band(1, packBytes) + "0000".toByteArray()))
        assertEquals(packBytes.toList(), UploadPackConnection.open(t).fetch(FetchRequest(listOf(id(a)))).pack.readBytes().toList())
        val t2 = FakeTransport(v0Advert("side-band-64k"), listOf(PktLineWriter.build { text("ERR upload-pack: not our ref $b") }))
        try { UploadPackConnection.open(t2).fetch(FetchRequest(listOf(id(b)))); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.REMOTE_ERROR, e.kind) }
    }

    @Test fun v0DepthWithoutShallowCapabilityIsRefusedBeforeSending() {
        val t = FakeTransport(v0Advert("side-band-64k"), emptyList())
        try { UploadPackConnection.open(t).fetch(FetchRequest(listOf(id(a)), depth = 1)); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.UNSUPPORTED, e.kind) }
        assertTrue(t.requests.isEmpty())
    }

    @Test fun v0ServerAcksEveryCommonHaveThenSendsThePackFromTheFirstSidebandFrame() {
        val t = FakeTransport(v0Advert("side-band-64k"), listOf(PktLineWriter.build { text("ACK $a"); text("ACK $b"); text("ACK $c") } + band(2, "progress\n".toByteArray()) + band(1, packBytes) + "0000".toByteArray()))
        val seen = ArrayList<String>()
        val fr = UploadPackConnection.open(t).fetch(FetchRequest(listOf(id(a)), listOf(id(b), id(c)))) { seen.add(it) }
        assertEquals(packBytes.toList(), fr.pack.readBytes().toList()); assertEquals(listOf("progress"), seen)
    }

    @Test fun v0ServerWithoutSidebandIsRefusedBeforeAnythingIsSent() {
        val t = FakeTransport(v0Advert("ofs-delta"), emptyList())
        try { UploadPackConnection.open(t).fetch(FetchRequest(listOf(id(a)))); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.UNSUPPORTED, e.kind) }
        assertTrue(t.requests.isEmpty())
    }

    private val v2Advert = PktLineWriter.build { text("version 2"); text("agent=git/2.43"); text("ls-refs=unborn"); text("fetch=shallow wait-for-done"); text("object-format=sha1"); flush() }

    @Test fun v2LsRefsAndFetchRequestLayoutAndSections() {
        val ls = PktLineWriter.build { text("$a HEAD symref-target:refs/heads/main"); text("$a refs/heads/main"); text("$b refs/tags/t peeled:$c"); flush() }
        val fetch = PktLineWriter.build { text("shallow-info"); text("shallow $a"); delim(); text("packfile") } + band(2, "Enumerating objects: 3\n".toByteArray()) + band(1, packBytes) + "0000".toByteArray()
        val t = FakeTransport(v2Advert, listOf(ls, fetch))
        val conn = UploadPackConnection.open(t)
        assertTrue(conn.isV2); assertEquals("refs/heads/main", conn.headTarget)
        assertEquals(id(c), conn.refs.first { it.name == "refs/tags/t" }.peeled)
        assertEquals(listOf("command=ls-refs", "agent=fonebrew", "object-format=sha1", "<delim>", "peel", "symrefs", "ref-prefix HEAD", "ref-prefix refs/heads/", "ref-prefix refs/tags/", "<flush>"), describe(t.requests[0]))
        val progress = ArrayList<String>()
        val fr = conn.fetch(FetchRequest(listOf(id(a)), listOf(id(b)), depth = 2, clientShallow = listOf(id(c)))) { progress.add(it) }
        assertEquals(packBytes.toList(), fr.pack.readBytes().toList())
        assertEquals(listOf(id(a)), fr.shallow); assertEquals(listOf("Enumerating objects: 3"), progress)
        assertEquals(listOf("command=fetch", "agent=fonebrew", "object-format=sha1", "<delim>", "thin-pack", "ofs-delta", "include-tag", "want $a", "shallow $c", "deepen 2", "have $b", "done", "<flush>"), describe(t.requests[1]))
        assertTrue(t.requestV2Flags.all { it })
    }

    @Test fun v2AnswerWithoutPackfileIsAProtocolErrorAndNeverHangs() {
        val t = FakeTransport(v2Advert, listOf(PktLineWriter.build { flush() }, PktLineWriter.build { text("acknowledgments"); text("NAK"); flush() }))
        val conn = UploadPackConnection.open(t)
        try { conn.fetch(FetchRequest(listOf(id(a)))); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.PROTOCOL, e.kind) }
    }

    @Test fun v2WithoutFetchCapabilityOrWithSha256IsUnsupported() {
        val bad = PktLineWriter.build { text("version 2"); text("ls-refs"); flush() }
        try { UploadPackConnection.open(FakeTransport(bad, emptyList())); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.UNSUPPORTED, e.kind) }
        val sha256 = PktLineWriter.build { text("version 2"); text("ls-refs"); text("fetch"); text("object-format=sha256"); flush() }
        try { UploadPackConnection.open(FakeTransport(sha256, emptyList())); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.UNSUPPORTED, e.kind) }
        assertNotNull(sha256)
    }

    @Test fun sessionIsClosedWhenOpeningFails() {
        val t = FakeTransport(PktLineWriter.build { text("ERR no such repo") }, emptyList())
        try { UploadPackConnection.open(t); fail() } catch (_: TransportException) {}
        assertTrue(t.closed)
    }
}
