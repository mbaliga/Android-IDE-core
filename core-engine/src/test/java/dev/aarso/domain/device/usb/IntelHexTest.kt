package dev.aarso.domain.device.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IntelHexTest {

    // Two data records at 0x0000 then EOF. ":10 0000 00 <16 bytes> CC".
    private val sample = """
        :10010000214601360121470136007EFE09D2190140
        :100110002146017E17C20001FF5F16002148011928
        :00000001FF
    """.trimIndent()

    @Test fun `parses data and fills the image`() {
        val img = IntelHex.parse(sample)
        assertEquals(0x0100, img.baseAddress)
        assertEquals(32, img.size)
        assertEquals(0x21.toByte(), img.bytes[0])
        assertEquals(0x46.toByte(), img.bytes[1])
    }

    @Test fun `rejects a bad checksum`() {
        val bad = ":10010000214601360121470136007EFE09D21901FF"
        assertThrows(IntelHex.HexFormatException::class.java) { IntelHex.parse(bad) }
    }

    @Test fun `rejects a non-colon record`() {
        assertThrows(IntelHex.HexFormatException::class.java) { IntelHex.parse("10010000FF") }
    }

    @Test fun `pages split the image and the last page is short`() {
        val img = IntelHex.parse(sample) // 32 bytes from 0x0100
        val pages = IntelHex.pages(img, pageSize = 16)
        assertEquals(2, pages.size)
        assertEquals(0x0100, pages[0].address)
        assertEquals(0x0110, pages[1].address)
        assertEquals(16, pages[0].data.size)

        val odd = IntelHex.pages(img, pageSize = 20)
        assertEquals(2, odd.size)
        assertEquals(20, odd[0].data.size)
        assertEquals(12, odd[1].data.size)
    }

    @Test fun `gaps between records are filled with 0xFF`() {
        // data at 0x00 (1 byte) and 0x04 (1 byte) → image of 5 bytes with 0xFF in 1..3.
        val hex = """
            :01000000AA55
            :0100040055A6
            :00000001FF
        """.trimIndent()
        val img = IntelHex.parse(hex)
        assertEquals(0, img.baseAddress)
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x55),
            img.bytes,
        )
    }
}
