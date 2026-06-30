package dev.aarso.domain.device.usb

/**
 * A byte pipe to a board's serial port — the seam between the pure [Stk500] protocol and the
 * device-gated USB transport (`usb-serial-for-android`, wired on the phone, owner-verified). Pure
 * code talks only to this; tests use a fake.
 */
interface SerialLink {
    suspend fun write(bytes: ByteArray)
    /** Read exactly [n] bytes (the impl blocks/awaits); throws on timeout/close. */
    suspend fun read(n: Int): ByteArray
    /** Toggle DTR (the auto-reset line that drops Uno/Nano into the bootloader). */
    suspend fun setDtr(asserted: Boolean)
}

/**
 * STK500 v1 — the bootloader protocol of classic AVR Arduinos (Uno/Nano/optiboot), i.e. what
 * `avrdude` speaks. Pure frame encode + a page-program flow over a [SerialLink]; **JVM-tested**
 * against a fake link. Compilation is NOT here — the `.hex` comes from a host/CI build; this only
 * flashes it (agentic-ide #4). The USB transport + the on-device flash UI are device-gated and
 * deliberately not wired into the app here (rule 6 — never claim unverified hardware behaviour).
 */
class Stk500(private val link: SerialLink) {

    class ProtocolException(message: String) : Exception(message)

    /** Reset into the bootloader (toggle DTR) and establish sync. Call before [program]. */
    suspend fun connect(retries: Int = 3) {
        link.setDtr(true)
        link.setDtr(false)
        var lastError: Throwable? = null
        repeat(retries) {
            try { getSync(); return } catch (e: Throwable) { lastError = e }
        }
        throw ProtocolException("no sync after $retries tries: ${lastError?.message}")
    }

    /** Flash [pages] (byte addresses) and leave program mode. Page byte-address → word-address. */
    suspend fun program(pages: List<IntelHex.Page>) {
        transact(byteArrayOf(ENTER_PROGMODE, CRC_EOP))
        for (p in pages) {
            val wordAddr = p.address / 2 // STK500 flash addressing is in 16-bit words
            transact(byteArrayOf(LOAD_ADDRESS, (wordAddr and 0xFF).toByte(), ((wordAddr shr 8) and 0xFF).toByte(), CRC_EOP))
            transact(progPageFrame(p.data))
        }
        transact(byteArrayOf(LEAVE_PROGMODE, CRC_EOP))
    }

    /** Read the 3-byte device signature (e.g. 1E 95 0F for an atmega328p). */
    suspend fun readSignature(): ByteArray =
        transact(byteArrayOf(READ_SIGN, CRC_EOP), expectedData = 3)

    private suspend fun getSync() {
        transact(byteArrayOf(GET_SYNC, CRC_EOP))
    }

    /**
     * Write a command frame, then expect `INSYNC, <expectedData bytes>, OK`. Returns the data.
     * This is the whole STK500 v1 response contract.
     */
    private suspend fun transact(frame: ByteArray, expectedData: Int = 0): ByteArray {
        link.write(frame)
        val insync = link.read(1)
        if (insync.isEmpty() || insync[0] != RESP_INSYNC) {
            throw ProtocolException("expected INSYNC (0x14), got ${insync.firstOrNull()?.toHex() ?: "nothing"}")
        }
        val data = if (expectedData > 0) link.read(expectedData) else ByteArray(0)
        val ok = link.read(1)
        if (ok.isEmpty() || ok[0] != RESP_OK) {
            throw ProtocolException("expected OK (0x10), got ${ok.firstOrNull()?.toHex() ?: "nothing"}")
        }
        return data
    }

    companion object {
        // Commands / responses (avrdude's stk500.h).
        const val GET_SYNC: Byte = 0x30
        const val ENTER_PROGMODE: Byte = 0x50
        const val LEAVE_PROGMODE: Byte = 0x51
        const val LOAD_ADDRESS: Byte = 0x55
        const val PROG_PAGE: Byte = 0x64
        const val READ_SIGN: Byte = 0x75.toByte()
        const val CRC_EOP: Byte = 0x20
        const val RESP_OK: Byte = 0x10
        const val RESP_INSYNC: Byte = 0x14

        /** `STK_PROG_PAGE size_hi size_lo 'F' <data…> CRC_EOP` — flash page write frame. */
        fun progPageFrame(data: ByteArray): ByteArray {
            val out = ByteArray(data.size + 5)
            out[0] = PROG_PAGE
            out[1] = ((data.size shr 8) and 0xFF).toByte()
            out[2] = (data.size and 0xFF).toByte()
            out[3] = 'F'.code.toByte()
            System.arraycopy(data, 0, out, 4, data.size)
            out[out.size - 1] = CRC_EOP
            return out
        }

        private fun Byte.toHex(): String = "0x%02X".format(this.toInt() and 0xFF)
    }
}
