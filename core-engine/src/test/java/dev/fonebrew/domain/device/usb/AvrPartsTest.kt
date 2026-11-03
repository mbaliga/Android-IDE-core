package dev.fonebrew.domain.device.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table is the flasher's whole safety story — an unknown or duplicated signature means the
 * wrong page size, the wrong flash-size check, or a refusal that never fires. None of that can be
 * caught on hardware here (no board in this environment), so it is caught as data.
 */
class AvrPartsTest {

    @Test fun `an Uno's signature resolves to the atmega328p`() {
        val part = AvrParts.bySignature(byteArrayOf(0x1E, 0x95.toByte(), 0x0F))
        assertSame(AvrParts.ATMEGA328P, part)
        assertEquals(128, part!!.flashPageSize)
        assertEquals(BootloaderProtocol.STK500V1, part.protocol)
    }

    @Test fun `an unknown signature resolves to nothing rather than a guess`() {
        assertNull(AvrParts.bySignature(byteArrayOf(0x1E, 0x00, 0x00)))
    }

    @Test fun `a short or long signature is not a match`() {
        assertNull(AvrParts.bySignature(byteArrayOf(0x1E, 0x95.toByte())))
        assertNull(AvrParts.bySignature(byteArrayOf(0x1E, 0x95.toByte(), 0x0F, 0x00)))
    }

    @Test fun `signatures are unique across the table`() {
        assertEquals(AvrParts.PARTS.size, AvrParts.PARTS.map { it.signature }.toSet().size)
    }

    @Test fun `signatureHex prints the form avrdude prints`() {
        assertEquals("1E 95 0F", AvrParts.signatureHex(byteArrayOf(0x1E, 0x95.toByte(), 0x0F)))
    }

    @Test fun `every board's part is one of the known parts`() {
        AvrParts.BOARDS.mapNotNull { it.part }.forEach { assertTrue(it in AvrParts.PARTS) }
    }

    @Test fun `only STK500v1 parts are ever marked supported`() {
        // The two axes have to agree: a board is SUPPORTED only if the protocol side is one this
        // app implements. A 32u4 or a Mega slipping into SUPPORTED is the exact regression the
        // old UI copy shipped.
        AvrParts.BOARDS.filter { it.verdict == UsbFlashVerdict.SUPPORTED }.forEach {
            assertEquals(BootloaderProtocol.STK500V1, it.part?.protocol)
        }
    }

    @Test fun `the default board is a supported one`() {
        assertEquals(UsbFlashVerdict.SUPPORTED, AvrParts.DEFAULT_BOARD.verdict)
        assertTrue(AvrParts.DEFAULT_BOARD in AvrParts.BOARDS)
    }

    @Test fun `every board says why, so no verdict is unexplained`() {
        AvrParts.BOARDS.forEach { assertTrue(it.displayName, it.why.isNotBlank()) }
    }
}
