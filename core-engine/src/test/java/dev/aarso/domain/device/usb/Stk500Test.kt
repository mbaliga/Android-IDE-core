package dev.aarso.domain.device.usb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted serial peer: returns the queued response bytes and records what was written. */
private class FakeLink(response: List<Int>) : SerialLink {
    val written = ArrayList<Byte>()
    val dtr = ArrayList<Boolean>()
    private val buf = ArrayDeque<Byte>().apply { response.forEach { add(it.toByte()) } }
    override suspend fun write(bytes: ByteArray) { written.addAll(bytes.toList()) }
    override suspend fun read(n: Int): ByteArray = ByteArray(n) {
        if (buf.isEmpty()) error("read past end"); buf.removeFirst()
    }
    override suspend fun setDtr(asserted: Boolean) { dtr.add(asserted) }
}

private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

class Stk500Test {

    @Test fun `connect toggles DTR and syncs`() = runBlocking {
        val link = FakeLink(listOf(0x14, 0x10)) // INSYNC, OK
        Stk500(link).connect(retries = 1)
        assertArrayEquals(bytes(0x30, 0x20), link.written.toByteArray()) // GET_SYNC, CRC_EOP
        assertEquals(listOf(true, false), link.dtr) // reset pulse
    }

    @Test fun `program writes enter, load-address (word), page, leave`() = runBlocking {
        // 4 transactions × (INSYNC, OK).
        val link = FakeLink(List(4) { listOf(0x14, 0x10) }.flatten())
        val page = IntelHex.Page(address = 0x0100, data = bytes(1, 2, 3, 4))
        Stk500(link).program(listOf(page))
        val expected =
            bytes(0x50, 0x20) +                                   // ENTER_PROGMODE
                bytes(0x55, 0x80, 0x00, 0x20) +                   // LOAD_ADDRESS word 0x0080 (= byte 0x0100/2)
                bytes(0x64, 0x00, 0x04, 0x46, 1, 2, 3, 4, 0x20) + // PROG_PAGE 'F' + data
                bytes(0x51, 0x20)                                 // LEAVE_PROGMODE
        assertArrayEquals(expected, link.written.toByteArray())
    }

    @Test fun `missing INSYNC throws`() {
        val link = FakeLink(listOf(0x00, 0x10))
        val ex = assertThrows(Stk500.ProtocolException::class.java) {
            runBlocking { Stk500(link).connect(retries = 1) }
        }
        assertTrue(ex.message!!.contains("INSYNC"))
    }

    @Test fun `readSignature returns the three data bytes`() = runBlocking {
        val link = FakeLink(listOf(0x14, 0x1E, 0x95, 0x0F, 0x10))
        val sig = Stk500(link).readSignature()
        assertArrayEquals(bytes(0x1E, 0x95, 0x0F), sig)
    }

    @Test fun `progPageFrame encodes size and F marker`() {
        val frame = Stk500.progPageFrame(bytes(0xAA, 0xBB))
        assertArrayEquals(bytes(0x64, 0x00, 0x02, 0x46, 0xAA, 0xBB, 0x20), frame)
    }
}
