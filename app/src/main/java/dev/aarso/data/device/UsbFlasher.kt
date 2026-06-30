package dev.aarso.data.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.aarso.domain.device.usb.IntelHex
import dev.aarso.domain.device.usb.Stk500
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Direct-USB flashing (agentic-ide #4): discover attached boards, get USB permission, and flash a
 * host-built `.hex` over [CdcUsbSerialLink] using the **tested** STK500 protocol. Compilation does
 * NOT happen on the phone — the `.hex` comes from `arduino-cli`/CI (e.g. the Devices → Arduino-via-
 * Pi flow, or a CI build artifact).
 *
 * **Owner-verified only** — no board in CI. CDC boards (Uno R3/Leonardo/Micro, ESP-CDC) only;
 * CH340/CP210x clones need a vendor driver (follow-up). Page size 128 = atmega328 SPM page.
 */
class UsbFlasher(private val context: Context) {

    private val manager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    data class Board(val device: UsbDevice, val label: String, val cdc: Boolean)

    /** Attached USB devices, flagged for whether they look CDC-flashable. */
    fun attached(): List<Board> =
        manager?.deviceList?.values?.map {
            Board(it, "${it.productName ?: it.deviceName} (${"%04X".format(it.vendorId)}:${"%04X".format(it.productId)})", CdcUsbSerialLink.isCdc(it))
        }.orEmpty()

    fun hasPermission(device: UsbDevice): Boolean = manager?.hasPermission(device) == true

    /** Request USB permission for [device] (suspends until the user answers). */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        val mgr = manager ?: return false
        if (mgr.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_PERMISSION) return
                    context.unregisterReceiver(this)
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
            context.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            mgr.requestPermission(device, pi)
        }
    }

    /** Parse [hexText], open the board, flash, verify-by-protocol-acks, close. Streams progress. */
    suspend fun flash(device: UsbDevice, hexText: String, onProgress: (String) -> Unit): Result<Unit> = runCatching {
        val mgr = manager ?: error("no USB service")
        require(mgr.hasPermission(device)) { "no USB permission for the device" }
        onProgress("parsing .hex…")
        val image = IntelHex.parse(hexText)
        val pages = IntelHex.pages(image, pageSize = 128)
        require(pages.isNotEmpty()) { "empty .hex" }
        val link = CdcUsbSerialLink.open(mgr, device) ?: error("not a CDC board (clone chips need a vendor driver)")
        try {
            onProgress("resetting & syncing…")
            val stk = Stk500(link)
            stk.connect()
            onProgress("flashing ${image.size} bytes in ${pages.size} pages…")
            stk.program(pages)
            onProgress("done — flashed ${pages.size} pages.")
        } finally {
            link.close()
        }
    }

    private companion object {
        const val ACTION_PERMISSION = "dev.aarso.USB_PERMISSION"
    }
}
