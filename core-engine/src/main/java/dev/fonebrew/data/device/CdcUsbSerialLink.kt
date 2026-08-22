package dev.fonebrew.data.device

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.fonebrew.domain.device.usb.SerialLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A USB-CDC (ACM) [SerialLink] over the platform USB-host API — the device-gated transport that
 * bridges the **tested** [dev.fonebrew.domain.device.usb.Stk500] protocol to a real board cable
 * (agentic-ide #4). Pure-CDC, no external dependency: it can *open* any board that enumerates as
 * CDC — a genuine Uno R3 (via its 16U2), a Leonardo/Micro (whose 32u4 is the USB device itself),
 * an ESP32-S2/S3 native-USB part. **CH340 / CP210x / FTDI clones do NOT enumerate as CDC and need
 * a vendor driver — a tracked follow-up (usb-serial-for-android).**
 *
 * Opening a board is not the same as flashing it, and reading this class as the support list is
 * the mistake the on-phone flash UI used to make: a Leonardo/Micro opens fine here and still
 * cannot be flashed, because its Caterina bootloader speaks AVR109 rather than the STK500v1 this
 * app implements. [dev.fonebrew.domain.device.usb.AvrParts] holds the two-axis truth (cable side
 * *and* protocol side); this file only owns the cable side.
 *
 * Entirely **owner-verified**: there is no USB host or board in CI, so none of this is exercised
 * here. Compilation only proves it type-checks (rule 6 — never claim hardware behaviour works).
 */
class CdcUsbSerialLink private constructor(
    private val connection: UsbDeviceConnection,
    private val controlIface: UsbInterface,
    private val dataIface: UsbInterface,
    private val readEndpoint: UsbEndpoint,
    private val writeEndpoint: UsbEndpoint,
) : SerialLink {

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        var off = 0
        while (off < bytes.size) {
            val chunk = minOf(writeEndpoint.maxPacketSize, bytes.size - off)
            val n = connection.bulkTransfer(writeEndpoint, bytes.copyOfRange(off, off + chunk), chunk, IO_TIMEOUT_MS)
            if (n < 0) error("USB write failed at offset $off")
            off += n
        }
    }

    override suspend fun read(n: Int): ByteArray = read(n, READ_TIMEOUT_MS)

    /**
     * The deadline used to be advisory: the loop checked it and then handed `bulkTransfer` a fixed
     * 2-second timeout, so a caller asking for a 150 ms budget could still block for two seconds
     * inside a single transfer. Capping each transfer at whatever is left makes the deadline bind,
     * which is what lets [dev.fonebrew.domain.device.usb.Stk500]'s sync retries actually retry
     * inside the bootloader's window instead of after it.
     */
    override suspend fun read(n: Int, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArray(n)
        var got = 0
        val deadline = nowMs() + timeoutMs
        val buf = ByteArray(readEndpoint.maxPacketSize)
        while (got < n) {
            val remaining = deadline - nowMs()
            if (remaining <= 0) error("USB read timed out ($got/$n bytes)")
            val slice = minOf(IO_TIMEOUT_MS.toLong(), remaining).toInt().coerceAtLeast(1)
            val r = connection.bulkTransfer(readEndpoint, buf, buf.size, slice)
            if (r > 0) {
                val take = minOf(r, n - got)
                System.arraycopy(buf, 0, out, got, take)
                got += take
            } else {
                delay(2)
            }
        }
        out
    }

    /**
     * Throw away whatever the board already sent — typically a sketch's own `Serial.println` output
     * still sitting in the pipe when we reset it. Bounded twice over (a very short per-poll timeout
     * and a pass cap) so a board that never stops talking cannot park the flash here.
     */
    override suspend fun drain() {
        withContext(Dispatchers.IO) {
            val buf = ByteArray(readEndpoint.maxPacketSize)
            repeat(DRAIN_PASSES) {
                if (connection.bulkTransfer(readEndpoint, buf, buf.size, DRAIN_TIMEOUT_MS) <= 0) return@withContext
            }
        }
    }

    /** DTR (and RTS low) via CDC SET_CONTROL_LINE_STATE — the auto-reset into the bootloader. */
    override suspend fun setDtr(asserted: Boolean) = withContext(Dispatchers.IO) {
        val value = if (asserted) 0x01 else 0x00 // bit0 = DTR, bit1 = RTS
        connection.controlTransfer(REQTYPE_CLASS_OUT, SET_CONTROL_LINE_STATE, value, controlIface.id, null, 0, IO_TIMEOUT_MS)
        Unit
    }

    fun close() {
        runCatching { connection.releaseInterface(dataIface) }
        runCatching { connection.releaseInterface(controlIface) }
        runCatching { connection.close() }
    }

    private fun setLineCoding(baud: Int) {
        // 7 bytes: baud (LE 32-bit), stop bits (0=1), parity (0=none), data bits (8).
        val data = byteArrayOf(
            (baud and 0xFF).toByte(), ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(), ((baud shr 24) and 0xFF).toByte(),
            0, 0, 8,
        )
        connection.controlTransfer(REQTYPE_CLASS_OUT, SET_LINE_CODING, 0, controlIface.id, data, data.size, IO_TIMEOUT_MS)
    }

    companion object {
        private const val IO_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 5000L

        /** A drain poll waits only long enough to notice the pipe is empty, not for new traffic. */
        private const val DRAIN_TIMEOUT_MS = 20
        private const val DRAIN_PASSES = 8
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val REQTYPE_CLASS_OUT = 0x21 // host→device | class | interface

        private fun nowMs() = System.currentTimeMillis()

        /** True if [device] exposes a CDC comm/data interface pair we can drive. */
        fun isCdc(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }

        /**
         * Open [device] as a CDC serial link at [baud], or null if it isn't CDC / can't be claimed.
         * Caller must already hold USB permission for the device.
         */
        fun open(manager: UsbManager, device: UsbDevice, baud: Int = 115200): CdcUsbSerialLink? {
            var control: UsbInterface? = null
            var data: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                when (iface.interfaceClass) {
                    UsbConstants.USB_CLASS_COMM -> control = control ?: iface
                    UsbConstants.USB_CLASS_CDC_DATA -> data = data ?: iface
                }
            }
            val dataIface = data ?: return null
            val controlIface = control ?: dataIface
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until dataIface.endpointCount) {
                val ep = dataIface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            val read = inEp ?: return null
            val write = outEp ?: return null
            val conn = manager.openDevice(device) ?: return null
            if (controlIface !== dataIface) conn.claimInterface(controlIface, true)
            conn.claimInterface(dataIface, true)
            return CdcUsbSerialLink(conn, controlIface, dataIface, read, write).also { it.setLineCoding(baud) }
        }
    }
}
