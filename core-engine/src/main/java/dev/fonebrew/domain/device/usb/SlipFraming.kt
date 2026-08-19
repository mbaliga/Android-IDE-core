package dev.fonebrew.domain.device.usb

/**
 * SLIP framing (RFC 1055), the byte-stuffing layer the Espressif ROM/second-stage bootloader
 * protocol (`UsbFamily.ESP_BOOTLOADER`, esptool.py's own wire format) is built on: every packet
 * is delimited by `0xC0`, with any `0xC0`/`0xDB` byte inside the payload escaped so the delimiter
 * can never appear mid-packet. Pure byte-level codec, JVM-tested against hand-built transcripts
 * matching the publicly documented esptool protocol — **not independently verified against a
 * real ESP32/ESP8266 device or Espressif's own spec text in this sandbox** (`CLAUDE.md`
 * "Environment honesty" — no device exists here), same posture [Stk500] already takes toward
 * avrdude's `stk500.h`.
 */
object SlipFraming {

    private const val END: Byte = 0xC0.toByte()
    private const val ESC: Byte = 0xDB.toByte()
    private const val ESC_END: Byte = 0xDC.toByte()
    private const val ESC_ESC: Byte = 0xDD.toByte()

    class FramingException(message: String) : Exception(message)

    /** Wraps [payload] in leading + trailing `END` delimiters, escaping any `END`/`ESC` byte found inside it. */
    fun encode(payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(payload.size + 4)
        out.add(END)
        for (b in payload) {
            when (b) {
                END -> { out.add(ESC); out.add(ESC_END) }
                ESC -> { out.add(ESC); out.add(ESC_ESC) }
                else -> out.add(b)
            }
        }
        out.add(END)
        return out.toByteArray()
    }

    /** Inverse of [encode]: strips the delimiters and un-escapes. Throws [FramingException] on a malformed frame (missing delimiters, a dangling escape byte, or an unrecognized escape sequence). */
    fun decode(frame: ByteArray): ByteArray {
        if (frame.size < 2 || frame.first() != END || frame.last() != END) {
            throw FramingException("SlipFraming.decode: frame must start and end with END (0xC0).")
        }
        val out = ArrayList<Byte>(frame.size)
        var i = 1
        while (i < frame.size - 1) {
            val b = frame[i]
            if (b == ESC) {
                if (i + 1 >= frame.size - 1) throw FramingException("SlipFraming.decode: dangling ESC byte with no following escape code.")
                when (frame[i + 1]) {
                    ESC_END -> out.add(END)
                    ESC_ESC -> out.add(ESC)
                    else -> throw FramingException("SlipFraming.decode: unrecognized escape sequence 0xDB 0x%02X.".format(frame[i + 1]))
                }
                i += 2
            } else {
                out.add(b)
                i += 1
            }
        }
        return out.toByteArray()
    }
}
