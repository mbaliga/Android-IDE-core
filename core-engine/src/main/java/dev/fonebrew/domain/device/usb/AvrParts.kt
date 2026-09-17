package dev.fonebrew.domain.device.usb

/**
 * Which bootloader protocol a part's stock Arduino bootloader actually speaks. Fonebrew implements
 * exactly one of these — [Stk500] — and naming the other two is the point of this enum: it lets the
 * flasher refuse a board *by name*, before touching it, instead of letting the user watch a sync
 * time out and guess why. (Before this existed, the UI listed Leonardo/Micro as supported and the
 * flash path would just fail to sync — the audit's "tell the truth about boards".)
 */
enum class BootloaderProtocol {
    /** optiboot / ATmegaBOOT — the classic Uno-class bootloader, and the one [Stk500] speaks. */
    STK500V1,

    /** Mega-class: STK500**v2** framing (MESSAGE_START/seq/len/token + XOR checksum). Not implemented. */
    STK500V2,

    /** Caterina on the 32u4 (AVR109 "butterfly"), entered by a 1200-baud touch. Not implemented. */
    AVR109,
}

/**
 * One AVR part, keyed by the 3-byte device signature `STK_READ_SIGN` (0x75) returns. Values are
 * avrdude.conf's (signature, flash size, SPM page size) for the same parts; they are the two facts
 * a flasher needs beyond the protocol — how big the flash is, and how large a page write may be.
 *
 * [signature] is the three bytes packed big-endian into 24 bits (0x1E950F = `1E 95 0F`), which is
 * how a signature is written everywhere else (avrdude output, datasheets), so the table reads the
 * same as the thing it describes.
 *
 * A signature identifies the **chip, not the board**: an Uno, a Nano and a Pro Mini are all
 * `1E 95 0F` and are indistinguishable over the wire. That is exactly why [ArduinoBoard] is a
 * separate table and why the wrong-board preflight compares *parts*, not board names — it catches
 * the destructive confusions (a 328P image aimed at a 168's 16 KB flash, a 32u4 board that cannot
 * be flashed at all) rather than cosmetic ones.
 */
data class AvrPart(
    val signature: Int,
    val name: String,
    val flashBytes: Int,
    val flashPageSize: Int,
    val protocol: BootloaderProtocol,
)

/** Whether *this app, over the phone's own USB port* can flash a board — and if not, what is missing. */
enum class UsbFlashVerdict {
    /** Enumerates as USB-CDC **and** speaks STK500v1: the only combination wired end to end here. */
    SUPPORTED,

    /** Right protocol, wrong cable side — the USB bridge is CH340/CP210x/FTDI, or there is none. */
    NEEDS_ADAPTER,

    /** Cable side is fine (or irrelevant); the boot protocol is one this app does not implement. */
    WRONG_PROTOCOL,
}

/**
 * A board the owner is plausibly holding, with an honest verdict. [part] is null when the board
 * is not an AVR at all (so there is no signature to preflight against).
 */
data class ArduinoBoard(
    val displayName: String,
    val part: AvrPart?,
    val verdict: UsbFlashVerdict,
    val why: String,
)

/**
 * The parts + boards the on-phone USB flash path knows about. Pure Kotlin, no Android, JVM-tested.
 *
 * Two constraints stack to decide whether a board can be flashed from the phone, and the audit
 * found the UI was collapsing them into one cheerful sentence:
 *  1. **the cable side** — [dev.fonebrew.data.device.CdcUsbSerialLink] is USB-CDC only, so a board
 *     behind a CH340/CP210x/FTDI bridge gives Android no CDC interface to claim; and
 *  2. **the protocol side** — [Stk500] is STK500v1 only, so a 32u4 (AVR109) or a Mega (STK500v2)
 *     is unreachable even over a perfect CDC link.
 * A board needs to clear *both* to be [UsbFlashVerdict.SUPPORTED]. Today exactly one entry does.
 *
 * Signature/page/flash values come from avrdude.conf; the protocol column is the stock Arduino
 * bootloader for that part. None of it is verified against a board here — this container has no
 * USB host and no hardware (rule 6). It is verified only as a table.
 */
object AvrParts {

    val ATMEGA328P = AvrPart(0x1E950F, "ATmega328P", flashBytes = 32768, flashPageSize = 128, protocol = BootloaderProtocol.STK500V1)
    val ATMEGA328 = AvrPart(0x1E9514, "ATmega328", flashBytes = 32768, flashPageSize = 128, protocol = BootloaderProtocol.STK500V1)
    val ATMEGA328PB = AvrPart(0x1E9516, "ATmega328PB", flashBytes = 32768, flashPageSize = 128, protocol = BootloaderProtocol.STK500V1)
    val ATMEGA168P = AvrPart(0x1E940B, "ATmega168P", flashBytes = 16384, flashPageSize = 128, protocol = BootloaderProtocol.STK500V1)
    val ATMEGA168 = AvrPart(0x1E9406, "ATmega168", flashBytes = 16384, flashPageSize = 128, protocol = BootloaderProtocol.STK500V1)
    val ATMEGA8 = AvrPart(0x1E9307, "ATmega8", flashBytes = 8192, flashPageSize = 64, protocol = BootloaderProtocol.STK500V1)

    /** Leonardo/Micro/Pro Micro/LilyPad USB. Listed so the flasher can refuse it by name, never to flash it. */
    val ATMEGA32U4 = AvrPart(0x1E9587, "ATmega32U4", flashBytes = 32768, flashPageSize = 128, protocol = BootloaderProtocol.AVR109)

    /** Mega 2560 — CDC on the cable side, STK500**v2** on the protocol side. Refused, not flashed. */
    val ATMEGA2560 = AvrPart(0x1E9801, "ATmega2560", flashBytes = 262144, flashPageSize = 256, protocol = BootloaderProtocol.STK500V2)
    val ATMEGA1280 = AvrPart(0x1E9703, "ATmega1280", flashBytes = 131072, flashPageSize = 256, protocol = BootloaderProtocol.STK500V2)

    val PARTS: List<AvrPart> = listOf(
        ATMEGA328P, ATMEGA328, ATMEGA328PB, ATMEGA168P, ATMEGA168, ATMEGA8, ATMEGA32U4, ATMEGA2560, ATMEGA1280,
    )

    /**
     * The board list the Hardware screen offers as "what is this `.hex` built for". Ordered
     * supported-first; every entry carries the reason, because "unsupported" without a reason is
     * what sends someone hunting for a cable that was never the problem.
     */
    val BOARDS: List<ArduinoBoard> = listOf(
        ArduinoBoard(
            "Arduino Uno R3 (genuine)", ATMEGA328P, UsbFlashVerdict.SUPPORTED,
            "The onboard ATmega16U2 enumerates as USB-CDC and optiboot speaks STK500v1 — the one " +
                "combination this app implements end to end.",
        ),
        ArduinoBoard(
            "Arduino Uno R3 (CH340 clone)", ATMEGA328P, UsbFlashVerdict.NEEDS_ADAPTER,
            "Same chip, same bootloader, different USB bridge: a CH340 does not enumerate as CDC, " +
                "so Android hands this app no interface to claim. Needs a CH340 driver it does not ship.",
        ),
        ArduinoBoard(
            "Arduino Nano (ATmega328P)", ATMEGA328P, UsbFlashVerdict.NEEDS_ADAPTER,
            "Right chip and right protocol, but the USB side is an FT232RL or CH340 rather than CDC.",
        ),
        ArduinoBoard(
            "Arduino Pro Mini", ATMEGA328P, UsbFlashVerdict.NEEDS_ADAPTER,
            "No USB hardware at all — it is flashed through an external FTDI/CH340 breakout, which " +
                "is the same driver this app is missing.",
        ),
        ArduinoBoard(
            "LilyPad / LilyPad Simple (ATmega328P)", ATMEGA328P, UsbFlashVerdict.NEEDS_ADAPTER,
            "FTDI header, no onboard USB — same missing driver as the Pro Mini.",
        ),
        ArduinoBoard(
            "Arduino Duemilanove (ATmega168)", ATMEGA168, UsbFlashVerdict.NEEDS_ADAPTER,
            "STK500v1, but behind an FT232 — and only 16 KB of flash, so an Uno-sized .hex will not fit.",
        ),
        ArduinoBoard(
            "Arduino Micro / Leonardo / Pro Micro", ATMEGA32U4, UsbFlashVerdict.WRONG_PROTOCOL,
            "On a 32u4 the USB *is* the MCU, and its Caterina bootloader speaks AVR109 after a " +
                "1200-baud touch — a different protocol from STK500v1. Not implemented here.",
        ),
        ArduinoBoard(
            "LilyPad USB (ATmega32U4)", ATMEGA32U4, UsbFlashVerdict.WRONG_PROTOCOL,
            "Same 32u4 Caterina/AVR109 path as the Micro.",
        ),
        ArduinoBoard(
            "Arduino Mega 2560", ATMEGA2560, UsbFlashVerdict.WRONG_PROTOCOL,
            "Its 16U2 does enumerate as CDC, so the cable side is fine — but the Mega's bootloader " +
                "speaks STK500v2, a different framing this app does not implement.",
        ),
        ArduinoBoard(
            "Arduino Uno R4 (Minima / WiFi)", null, UsbFlashVerdict.WRONG_PROTOCOL,
            "A Renesas RA4M1, not an AVR — there is no STK500 anywhere in its boot path.",
        ),
    )

    /** The one board a user can pick and expect to work; also the honest default for the picker. */
    val DEFAULT_BOARD: ArduinoBoard = BOARDS.first { it.verdict == UsbFlashVerdict.SUPPORTED }

    /** The part whose signature matches [sig] (3 bytes, as `STK_READ_SIGN` returns), or null if unknown. */
    fun bySignature(sig: ByteArray): AvrPart? {
        if (sig.size != 3) return null
        val packed = ((sig[0].toInt() and 0xFF) shl 16) or ((sig[1].toInt() and 0xFF) shl 8) or (sig[2].toInt() and 0xFF)
        return PARTS.firstOrNull { it.signature == packed }
    }

    /** `1E 95 0F` — the form avrdude prints, so an unknown signature can be searched for verbatim. */
    fun signatureHex(sig: ByteArray): String =
        sig.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
