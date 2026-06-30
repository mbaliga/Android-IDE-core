package dev.aarso.domain.device.usb

/**
 * Intel HEX parser — the format `arduino-cli`/avr-gcc emit for a compiled sketch (`.hex`). Pure,
 * JVM-tested: turns the text into a contiguous flash image (gaps filled with 0xFF, as a flasher
 * expects) and splits it into write-pages. This is the input to [Stk500] for direct-USB flashing
 * (agentic-ide #4). No Android, no I/O.
 */
object IntelHex {

    class HexFormatException(message: String) : Exception(message)

    /** A contiguous flash image starting at [baseAddress]; [bytes] has 0xFF in any gaps. */
    data class Image(val baseAddress: Int, val bytes: ByteArray) {
        val size: Int get() = bytes.size
    }

    /** One write unit for the programmer: [data] to be written at byte [address]. */
    data class Page(val address: Int, val data: ByteArray)

    private const val DATA = 0x00
    private const val EOF = 0x01
    private const val EXT_SEGMENT = 0x02
    private const val EXT_LINEAR = 0x04

    /** Parse Intel HEX [text] into a flash [Image]. Throws [HexFormatException] on any bad record. */
    fun parse(text: String): Image {
        val mem = HashMap<Int, Byte>()
        var upper = 0 // upper 16 bits contributed by ext-address records
        var sawEof = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith(":")) throw HexFormatException("record must start with ':'")
            val bytes = hexBytes(line.substring(1))
            if (bytes.size < 5) throw HexFormatException("record too short")
            val count = bytes[0]
            val addr = (bytes[1] shl 8) or bytes[2]
            val type = bytes[3]
            if (bytes.size != 5 + count) throw HexFormatException("length mismatch")
            // Checksum: two's complement of the sum of all bytes except the checksum itself.
            val sum = bytes.dropLast(1).sum() and 0xFF
            val expected = (0x100 - sum) and 0xFF
            if (expected != bytes.last()) throw HexFormatException("bad checksum")
            when (type) {
                DATA -> {
                    val base = upper + addr
                    for (i in 0 until count) mem[base + i] = bytes[4 + i].toByte()
                }
                EOF -> { sawEof = true }
                EXT_SEGMENT -> upper = ((bytes[4] shl 8) or bytes[5]) * 16
                EXT_LINEAR -> upper = ((bytes[4] shl 8) or bytes[5]) shl 16
                else -> throw HexFormatException("unsupported record type $type")
            }
            if (sawEof) break
        }
        if (mem.isEmpty()) return Image(0, ByteArray(0))
        val min = mem.keys.min()
        val max = mem.keys.max()
        val out = ByteArray(max - min + 1) { 0xFF.toByte() }
        for ((a, b) in mem) out[a - min] = b
        return Image(min, out)
    }

    /** Split [image] into [pageSize]-byte pages aligned to the image base. Last page may be short. */
    fun pages(image: Image, pageSize: Int): List<Page> {
        require(pageSize > 0) { "pageSize must be positive" }
        if (image.bytes.isEmpty()) return emptyList()
        val out = ArrayList<Page>()
        var off = 0
        while (off < image.bytes.size) {
            val end = minOf(off + pageSize, image.bytes.size)
            out += Page(image.baseAddress + off, image.bytes.copyOfRange(off, end))
            off = end
        }
        return out
    }

    private fun hexBytes(s: String): IntArray {
        if (s.length % 2 != 0) throw HexFormatException("odd hex length")
        return IntArray(s.length / 2) { i ->
            val h = s.substring(i * 2, i * 2 + 2)
            h.toIntOrNull(16) ?: throw HexFormatException("non-hex '$h'")
        }
    }
}
