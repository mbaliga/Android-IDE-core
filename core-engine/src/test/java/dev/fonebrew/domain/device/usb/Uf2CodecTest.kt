package dev.fonebrew.domain.device.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Uf2CodecTest {

    private fun block(payloadSize: Int = 4, data: ByteArray = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())) = Uf2Codec.Block(
        flags = Uf2Codec.FLAG_FAMILY_ID_PRESENT, targetAddr = 0x10000000L, payloadSize = payloadSize,
        blockNo = 0L, numBlocks = 1L, familyIdOrFileSize = 0xE48BFF56L, data = data,
    )

    @Test
    fun `a block round-trips exactly through encode then decode`() {
        val original = block()
        val encoded = Uf2Codec.encode(original)
        assertEquals(Uf2Codec.BLOCK_SIZE, encoded.size)
        val decoded = Uf2Codec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `an encoded block is always exactly 512 bytes, regardless of payload size`() {
        assertEquals(Uf2Codec.BLOCK_SIZE, Uf2Codec.encode(block(payloadSize = 0, data = ByteArray(0))).size)
        assertEquals(Uf2Codec.BLOCK_SIZE, Uf2Codec.encode(block(payloadSize = Uf2Codec.DATA_SIZE, data = ByteArray(Uf2Codec.DATA_SIZE) { it.toByte() })).size)
    }

    @Test
    fun `decode rejects a buffer that is not exactly 512 bytes`() {
        try {
            Uf2Codec.decode(ByteArray(100))
            org.junit.Assert.fail("expected MalformedBlockException")
        } catch (expected: Uf2Codec.MalformedBlockException) {
            // expected
        }
    }

    @Test
    fun `decode rejects a buffer with a corrupted start magic`() {
        val encoded = Uf2Codec.encode(block())
        encoded[0] = 0x00
        try {
            Uf2Codec.decode(encoded)
            org.junit.Assert.fail("expected MalformedBlockException")
        } catch (expected: Uf2Codec.MalformedBlockException) {
            // expected
        }
    }

    @Test
    fun `decode rejects a buffer with a corrupted end magic`() {
        val encoded = Uf2Codec.encode(block())
        encoded[encoded.size - 1] = 0x00
        try {
            Uf2Codec.decode(encoded)
            org.junit.Assert.fail("expected MalformedBlockException")
        } catch (expected: Uf2Codec.MalformedBlockException) {
            // expected
        }
    }

    @Test
    fun `Block construction rejects a data array whose size does not match payloadSize`() {
        try {
            Uf2Codec.Block(flags = 0, targetAddr = 0, payloadSize = 4, blockNo = 0, numBlocks = 1, familyIdOrFileSize = 0, data = ByteArray(2))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `multi-block sequencing fields (blockNo, numBlocks) round-trip correctly across a realistic sequence`() {
        val blocks = (0 until 5).map { i -> block().copy(blockNo = i.toLong(), numBlocks = 5L) }
        val decoded = blocks.map { Uf2Codec.decode(Uf2Codec.encode(it)) }
        assertEquals(blocks, decoded)
        assertTrue(decoded.map { it.blockNo } == (0L until 5L).toList())
    }
}
