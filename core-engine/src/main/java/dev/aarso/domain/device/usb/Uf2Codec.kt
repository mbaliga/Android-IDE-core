package dev.aarso.domain.device.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Microsoft UF2 ("USB Flashing Format") block codec — the RP2040/Pico-family mass-storage
 * bootloader format (`UsbFamily.UF2`). Pure frame encode/decode; JVM-tested against hand-built
 * transcripts matching the publicly documented UF2 spec (github.com/microsoft/uf2) — **not
 * independently verified against real hardware in this sandbox** (`CLAUDE.md` "Environment
 * honesty" — no device exists here), same posture [Stk500] already takes toward avrdude's
 * `stk500.h`. Every block is exactly 512 bytes — deliberately matching one FAT sector, the design
 * goal that lets a UF2 bootloader present as a drag-and-drop mass-storage device.
 */
object Uf2Codec {

    const val BLOCK_SIZE = 512
    const val DATA_SIZE = 476

    private const val MAGIC_START0 = 0x0A324655
    private const val MAGIC_START1 = 0x9E5D5157.toInt()
    private const val MAGIC_END = 0x0AB16F30

    const val FLAG_NOT_MAIN_FLASH = 0x00000001L
    const val FLAG_FILE_CONTAINER = 0x00001000L
    const val FLAG_FAMILY_ID_PRESENT = 0x00002000L
    const val FLAG_MD5_CHECKSUM_PRESENT = 0x00004000L

    class MalformedBlockException(message: String) : Exception(message)

    /** @param data MUST have exactly [payloadSize] bytes — the real payload, unpadded; [encode] pads to [DATA_SIZE] and [decode] trims back, so a round trip is exact. */
    data class Block(
        val flags: Long,
        val targetAddr: Long,
        val payloadSize: Int,
        val blockNo: Long,
        val numBlocks: Long,
        val familyIdOrFileSize: Long,
        val data: ByteArray,
    ) {
        init {
            require(payloadSize in 0..DATA_SIZE) { "Uf2Codec.Block.payloadSize must be within [0, $DATA_SIZE], got $payloadSize." }
            require(data.size == payloadSize) { "Uf2Codec.Block.data.size (${data.size}) must equal payloadSize ($payloadSize)." }
        }

        override fun equals(other: Any?): Boolean = other is Block &&
            flags == other.flags && targetAddr == other.targetAddr && payloadSize == other.payloadSize &&
            blockNo == other.blockNo && numBlocks == other.numBlocks && familyIdOrFileSize == other.familyIdOrFileSize &&
            data.contentEquals(other.data)

        override fun hashCode(): Int = java.util.Objects.hash(flags, targetAddr, payloadSize, blockNo, numBlocks, familyIdOrFileSize, data.contentHashCode())
    }

    fun encode(block: Block): ByteArray {
        val buf = ByteBuffer.allocate(BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC_START0)
        buf.putInt(MAGIC_START1)
        buf.putInt(block.flags.toInt())
        buf.putInt(block.targetAddr.toInt())
        buf.putInt(block.payloadSize)
        buf.putInt(block.blockNo.toInt())
        buf.putInt(block.numBlocks.toInt())
        buf.putInt(block.familyIdOrFileSize.toInt())
        val dataField = ByteArray(DATA_SIZE)
        System.arraycopy(block.data, 0, dataField, 0, block.data.size)
        buf.put(dataField)
        buf.putInt(MAGIC_END)
        return buf.array()
    }

    fun decode(bytes: ByteArray): Block {
        if (bytes.size != BLOCK_SIZE) throw MalformedBlockException("Uf2Codec.decode: expected exactly $BLOCK_SIZE bytes, got ${bytes.size}.")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic0 = buf.int
        val magic1 = buf.int
        if (magic0 != MAGIC_START0 || magic1 != MAGIC_START1) {
            throw MalformedBlockException("Uf2Codec.decode: bad start magic (got 0x%08X 0x%08X).".format(magic0, magic1))
        }
        val flags = buf.int.toLong() and 0xFFFFFFFFL
        val targetAddr = buf.int.toLong() and 0xFFFFFFFFL
        val payloadSize = buf.int
        val blockNo = buf.int.toLong() and 0xFFFFFFFFL
        val numBlocks = buf.int.toLong() and 0xFFFFFFFFL
        val familyIdOrFileSize = buf.int.toLong() and 0xFFFFFFFFL
        val data = ByteArray(DATA_SIZE)
        buf.get(data)
        val magicEnd = buf.int
        if (magicEnd != MAGIC_END) throw MalformedBlockException("Uf2Codec.decode: bad end magic (got 0x%08X).".format(magicEnd))
        if (payloadSize !in 0..DATA_SIZE) throw MalformedBlockException("Uf2Codec.decode: payloadSize $payloadSize out of range [0, $DATA_SIZE].")
        return Block(flags, targetAddr, payloadSize, blockNo, numBlocks, familyIdOrFileSize, data.copyOf(payloadSize))
    }
}
