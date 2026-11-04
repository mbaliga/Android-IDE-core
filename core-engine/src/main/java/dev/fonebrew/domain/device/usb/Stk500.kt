package dev.fonebrew.domain.device.usb

/**
 * A byte pipe to a board's serial port — the seam between the pure [Stk500] protocol and the
 * device-gated USB transport (`usb-serial-for-android`, wired on the phone, owner-verified). Pure
 * code talks only to this; tests use a fake.
 */
interface SerialLink {
    suspend fun write(bytes: ByteArray)
    /** Read exactly [n] bytes (the impl blocks/awaits); throws on timeout/close. */
    suspend fun read(n: Int): ByteArray

    /**
     * Read exactly [n] bytes but give up after [timeoutMs] instead of the transport's own (much
     * longer) default. The sync handshake needs this: the bootloader's listen window is about a
     * second, so a sync attempt that inherits a 5-second bulk-read timeout cannot retry inside the
     * window at all — it just waits, once, in the wrong place. Transports that can honour a
     * shorter deadline override this; the default keeps every existing implementation (and the
     * test fake) working unchanged.
     */
    suspend fun read(n: Int, timeoutMs: Long): ByteArray = read(n)

    /**
     * Discard whatever is already sitting in the receive pipe, without waiting for more. A sketch
     * that was printing to Serial before the reset leaves bytes behind, and one stale byte read as
     * the INSYNC reply desynchronises every frame after it. Default is a no-op for transports (and
     * fakes) that have nothing to drain.
     */
    suspend fun drain() {}

    /** Toggle DTR (the auto-reset line that drops Uno/Nano into the bootloader). */
    suspend fun setDtr(asserted: Boolean)
}

/**
 * STK500 v1 — the bootloader protocol of classic AVR Arduinos (Uno/Nano/optiboot), i.e. what
 * `avrdude` speaks. Pure frame encode + a page-program flow over a [SerialLink]; **JVM-tested**
 * against a fake link. Compilation is NOT here — the `.hex` comes from a host/CI build; this only
 * flashes it (agentic-ide #4).
 *
 * This file's doc used to claim "the USB transport + the on-device flash UI are device-gated and
 * deliberately not wired into the app here". That was false and load-bearing — the chain is fully
 * wired (SpatialRoot → DevelopRoom's Hardware tab → the USB mode → `UsbFlashPanel` →
 * [dev.fonebrew.data.device.UsbFlasher] → here), so a reader trusting the comment would conclude
 * nothing downstream could reach a real board and skip exactly the code that does. What *is* true
 * is the honesty caveat that comment was reaching for: none of the timing or transport behaviour
 * below has ever run against hardware in this build environment — no board, no USB host — so every
 * constant here is protocol reasoning and is owner-verified only (rule 6).
 *
 * Scope, stated once so it is not re-inferred from the class name: **STK500 v1 only**. The 32u4
 * boards (Micro/Leonardo/Pro Micro/LilyPad USB) speak AVR109/Caterina and the Mega speaks
 * STK500v2; neither is implemented, and [AvrParts] is the table that lets callers refuse them by
 * name rather than by timeout.
 */
class Stk500(
    private val link: SerialLink,
    /**
     * Injected so the reset timing is testable without sleeping through it. Production passes the
     * real coroutine delay; a test can pass `{}` and the handshake runs instantly.
     */
    private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    class ProtocolException(message: String) : Exception(message)

    /**
     * Reset into the bootloader (pulse DTR) and establish sync. Call before [program].
     *
     * Timing is the whole job here, and the shipped version had none of it: it toggled DTR and
     * issued GET_SYNC in the same breath — no settle delay, no `delay` call anywhere in the file —
     * then "retried" three times against the transport's 5-second bulk-read timeout. A board that
     * missed the first sync therefore burned 15 seconds, and every retry landed long after
     * optiboot had given up waiting and jumped into the sketch. Retrying outside the window is not
     * retrying. This now waits after the reset the way `avrdude` does and gives each attempt its
     * own short budget, sized so the whole sync phase stays *inside* the window.
     */
    suspend fun connect(retries: Int = SYNC_ATTEMPTS) {
        // Assert DTR, hold, release. On the Arduino auto-reset circuit DTR is capacitively coupled
        // to RESET, so it is the asserting *edge* that pulses the chip; holding it briefly makes
        // that pulse a known length instead of "however long two USB control transfers happened to
        // take". avrdude drives the inverse sequence (deassert, 250 ms to unload the cap, then
        // assert and leave it asserted) — if a real board never resets, that ordering is the first
        // thing to try, since which polarity lands depends on the board's own reset circuit.
        link.setDtr(true)
        delayMs(RESET_PULSE_MS)
        link.setDtr(false)
        delayMs(RESET_SETTLE_MS)
        var lastError: Throwable? = null
        repeat(retries) {
            // Clear anything the pre-reset sketch left in the pipe before each attempt: a stale
            // byte read as the INSYNC reply desynchronises every frame after it.
            runCatching { link.drain() }
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

    /**
     * Read the 3-byte device signature (e.g. 1E 95 0F for an atmega328p) — the only way to learn
     * what is actually on the other end of the cable, and therefore the input to the wrong-board
     * preflight in [dev.fonebrew.data.device.UsbFlasher]. Until that call site existed this method's
     * only caller in the whole repo was its own unit test, which is how a flasher ended up able to
     * erase a board it had never identified.
     *
     * Safe to call before [program]: `STK_READ_SIGN` is a top-level command, and optiboot answers
     * it without entering program mode (it treats ENTER/LEAVE_PROGMODE as acknowledged no-ops).
     * Nothing destructive has happened at this point — protocol reasoning, owner-verified only.
     */
    suspend fun readSignature(): ByteArray =
        transact(byteArrayOf(READ_SIGN, CRC_EOP), expectedData = 3)

    private suspend fun getSync() {
        transact(byteArrayOf(GET_SYNC, CRC_EOP), readTimeoutMs = SYNC_TIMEOUT_MS)
    }

    /**
     * Write a command frame, then expect `INSYNC, <expectedData bytes>, OK`. Returns the data.
     * This is the whole STK500 v1 response contract. [readTimeoutMs] overrides the transport's own
     * read budget for this one exchange — only the sync handshake uses it (see [connect]); a page
     * write legitimately wants the long default, since the chip is busy erasing.
     */
    private suspend fun transact(frame: ByteArray, expectedData: Int = 0, readTimeoutMs: Long? = null): ByteArray {
        link.write(frame)
        val insync = read(1, readTimeoutMs)
        if (insync.isEmpty() || insync[0] != RESP_INSYNC) {
            throw ProtocolException("expected INSYNC (0x14), got ${insync.firstOrNull()?.toHex() ?: "nothing"}")
        }
        val data = if (expectedData > 0) read(expectedData, readTimeoutMs) else ByteArray(0)
        val ok = read(1, readTimeoutMs)
        if (ok.isEmpty() || ok[0] != RESP_OK) {
            throw ProtocolException("expected OK (0x10), got ${ok.firstOrNull()?.toHex() ?: "nothing"}")
        }
        return data
    }

    private suspend fun read(n: Int, timeoutMs: Long?): ByteArray =
        if (timeoutMs == null) link.read(n) else link.read(n, timeoutMs)

    companion object {
        // Reset/sync timing. These are the audit's "the sync handshake is likely to fail on real
        // hardware" fix, and they are reasoned from the protocol and from what avrdude does — NOT
        // measured. There is no board, no USB host and no emulator in this build environment, so
        // treat every number below as a starting point the owner tunes on the phone (rule 6).

        /** How long DTR stays asserted, so the capacitively-coupled RESET pulse has a known width. */
        const val RESET_PULSE_MS = 30L

        /** Post-reset settle before the first sync byte — avrdude's own ~50 ms wait for the bootloader to come up. */
        const val RESET_SETTLE_MS = 50L

        /**
         * Read budget for one sync attempt. Short on purpose: a bootloader that has not answered
         * in this long has missed its window, and waiting longer only pushes the *next* attempt
         * further outside it. Before this, sync inherited the transport's 5 s bulk-read timeout.
         */
        const val SYNC_TIMEOUT_MS = 150L

        /**
         * 50 ms settle + 6 × 150 ms ≈ 950 ms, sized against optiboot's roughly one-second listen
         * window — i.e. every attempt lands while the bootloader is still listening. The old
         * default of 3 attempts against a 5 s timeout spent 15 s, essentially all of it after the
         * window had closed.
         */
        const val SYNC_ATTEMPTS = 6

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
