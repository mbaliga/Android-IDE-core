package dev.fonebrew.data.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import dev.fonebrew.contracts.devices.BoardRef
import dev.fonebrew.contracts.devices.CatalogRef
import dev.fonebrew.domain.device.broker.WrongBoardPreflight
import dev.fonebrew.domain.device.usb.AvrPart
import dev.fonebrew.domain.device.usb.AvrParts
import dev.fonebrew.domain.device.usb.BootloaderProtocol
import dev.fonebrew.domain.device.usb.IntelHex
import dev.fonebrew.domain.device.usb.Stk500
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Direct-USB flashing (agentic-ide #4): discover attached boards, get USB permission, identify what
 * is actually on the other end of the cable, and only then flash a host-built `.hex` over
 * [CdcUsbSerialLink] using the **tested** STK500v1 protocol. Compilation does NOT happen on the
 * phone — the `.hex` comes from `arduino-cli`/CI (e.g. the Devices → Arduino-via-Pi flow, or a CI
 * build artifact).
 *
 * **What this can actually flash, precisely:** a board that both enumerates as USB-CDC *and* runs
 * an STK500v1 bootloader — in practice a genuine Uno R3 and its optiboot siblings. Not a
 * Micro/Leonardo/Pro Micro (32u4, AVR109/Caterina), not a Mega (STK500v2), not an ESP (esptool ROM
 * protocol), and not a CH340/CP210x/FTDI board of any kind (no CDC interface to claim).
 * [AvrParts] is the table; [flash] refuses the rest by name rather than by timeout.
 *
 * **Owner-verified only** — there is no board, USB host or emulator in this build environment, so
 * nothing below has ever moved a byte over a real cable (rule 6).
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

    /**
     * The connected board is flashable and is a real part, but it is not the part the `.hex` was
     * said to be built for. Carried as a typed failure rather than a message so the UI can offer a
     * deliberate override — "I know what this board is" — instead of either silently proceeding or
     * making the mismatch unrecoverable. Nothing has been written when this is thrown.
     */
    class BoardMismatch(val found: AvrPart, val expected: AvrPart) :
        Exception("this is a ${found.name}, but the .hex was marked as being for a ${expected.name}. Nothing was written.")

    /**
     * Read a `.hex` the user picked through SAF (`ActivityResultContracts.OpenDocument`).
     *
     * This exists because the panel's only input used to be a typed absolute path read with
     * `java.io.File(path).readText()` — and the app declares no storage permission in any manifest,
     * so on minSdk 31 that could only ever read the app's own sandbox. A `.hex` sitting in
     * `/sdcard/Download`, which is where a file arrives from a browser or a chat app, failed with
     * EACCES surfaced as a bare "can't read .hex". SAF hands back a grant for exactly the one file
     * the user chose, which is the right amount of access for a local-first app to ask for; a broad
     * storage permission would not be.
     */
    suspend fun readHex(uri: Uri): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("couldn't open that file")
            val text = bytes.toString(Charsets.UTF_8)
            // Cheap shape check so picking the .bin/.elf/.zip sitting next to the .hex says so,
            // rather than failing later inside the parser with "record must start with ':'".
            val firstRecord = text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart()
            require(firstRecord?.startsWith(":") == true) {
                "that isn't an Intel .hex — its first line has no ':' record. A compiled sketch's .hex " +
                    "is text; the .bin/.elf/.zip built alongside it won't work here."
            }
            text
        }
    }

    /**
     * Parse [hexText], open the board, **identify it**, then flash and close. Streams progress.
     *
     * The identify step is the point. Before it, [flash] opened a cable, synced, and wrote flash
     * pages to whatever answered — [Stk500.readSignature] existed and was tested, but its only
     * caller in the whole repo was its own unit test. So the flasher's "verification" was
     * verify-by-protocol-acks: the board acknowledged the frames it was sent, which says nothing at
     * all about whether it was the right board or whether the image fits its flash. Now the
     * signature is read first — a top-level STK500 command, before ENTER_PROGMODE, so nothing has
     * been erased at the point any of these refusals fire — and the part it names decides three
     * things the old path guessed at:
     *
     *  - **can we flash this at all** — an unknown signature, or a known part whose bootloader is
     *    AVR109/STK500v2, is refused by name instead of failing somewhere mid-write;
     *  - **is it the right board** — [expectedPart] (what the user says the `.hex` is for) is
     *    compared through [WrongBoardPreflight], the spec's own wrong-board matcher, and a mismatch
     *    needs [allowBoardMismatch] to proceed;
     *  - **the page size** — was hard-coded 128 (the atmega328 SPM page). Correct for a 328P and
     *    wrong for anything else; it now comes from the identified part, alongside a flash-size
     *    check the old path did not make at all.
     *
     * Owner-verified only: no board here (rule 6).
     */
    suspend fun flash(
        device: UsbDevice,
        hexText: String,
        expectedPart: AvrPart? = null,
        allowBoardMismatch: Boolean = false,
        onProgress: (String) -> Unit,
    ): Result<Unit> = runCatching {
        val mgr = manager ?: error("no USB service")
        require(mgr.hasPermission(device)) { "no USB permission for the device" }
        onProgress("parsing .hex…")
        val image = IntelHex.parse(hexText)
        require(image.size > 0) { "empty .hex" }
        val link = CdcUsbSerialLink.open(mgr, device) ?: error("not a CDC board (clone chips need a vendor driver)")
        try {
            onProgress("resetting & syncing…")
            val stk = Stk500(link)
            stk.connect()

            onProgress("reading device signature…")
            val signature = stk.readSignature()
            val part = AvrParts.bySignature(signature)
                ?: error(
                    "unknown device signature ${AvrParts.signatureHex(signature)} — refusing to write " +
                        "flash to a part this app can't identify. Nothing was written.",
                )
            if (part.protocol != BootloaderProtocol.STK500V1) {
                error(
                    "that's a ${part.name}, whose bootloader speaks ${part.protocol.name} — this app only " +
                        "implements STK500v1. Nothing was written.",
                )
            }
            if (expectedPart != null && !partMatches(found = part, expected = expectedPart)) {
                if (!allowBoardMismatch) throw BoardMismatch(found = part, expected = expectedPart)
                onProgress("board check overridden — writing a ${expectedPart.name} image to a ${part.name}…")
            }
            // The image's own top address, not just its length: a .hex based high in flash still
            // has to land inside the part. The boot section isn't modelled here on purpose —
            // refusing images that reach it would also refuse a deliberate bootloader write.
            val topAddress = image.baseAddress + image.size
            require(topAddress <= part.flashBytes) {
                "this .hex reaches address $topAddress and a ${part.name} has only ${part.flashBytes} " +
                    "bytes of flash — wrong board, or wrong build target. Nothing was written."
            }

            val pages = IntelHex.pages(image, pageSize = part.flashPageSize)
            onProgress("flashing ${image.size} bytes to a ${part.name} in ${pages.size} pages…")
            stk.program(pages)
            onProgress("done — flashed ${pages.size} pages to a ${part.name}.")
        } finally {
            link.close()
        }
    }

    /**
     * Runs the expected-vs-found check through [WrongBoardPreflight] — the JVM-tested matcher
     * `DEVICE_STATE_AND_SAFETY_SPEC.md` §4 step 3 specifies — rather than restating the rule here.
     * It had zero callers before this one.
     *
     * Both [BoardRef]s are deliberately `UNCATALOGED`, which makes the matcher fall back to its
     * documented `displayName` comparison. That is the honest shape for this path: an AVR signature
     * identifies the **chip**, so an Uno, a Nano and a Pro Mini are all "ATmega328P" and genuinely
     * indistinguishable over the wire — there is no catalog id to compare and inventing one would
     * be fabricating identity the hardware never gave us. The check therefore catches the
     * destructive confusions (a 32u4 board, a 328P image aimed at a 168's 16 KB) and not cosmetic
     * ones. A future call site that *does* hold a real catalog id — e.g. an `arduino-cli` FQBN
     * coming out of the Arduino-via-Pi flow — gets the stronger `(catalogRef, catalogId)` match
     * from the same function, for free.
     */
    private fun partMatches(found: AvrPart, expected: AvrPart): Boolean =
        WrongBoardPreflight.isCompatible(
            BoardRef(CatalogRef.UNCATALOGED, found.name),
            listOf(BoardRef(CatalogRef.UNCATALOGED, expected.name)),
        )

    private companion object {
        const val ACTION_PERMISSION = "dev.fonebrew.USB_PERMISSION"
    }
}
