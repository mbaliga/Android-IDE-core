package dev.aarso.domain.device.usb

/**
 * USB DFU (Device Firmware Upgrade) class, revision 1.1 — the `DFU_GETSTATUS` response codec plus
 * the class's own state machine, restricted to the **download** (host-writes-to-device) path this
 * constellation actually drives; upload/read-back states are out of scope. Pure byte-level codec
 * + a fail-closed transition table, same posture as every other protocol codec in this package.
 * **Not independently verified against real hardware or the official USB-IF spec text in this
 * sandbox** (`CLAUDE.md` "Environment honesty" — no device exists here); the 6-byte response
 * layout and status/state code values are standard, widely-referenced constants (the same public
 * knowledge dfu-util/libusb implementations use), not device-verified by this pass.
 */
object DfuStatus {

    /** The 16 DFU 1.1 status codes, 0x00-0x0F — declaration order IS wire-value order (ordinal-based), unlike [State] below which uses an explicit `wireValue`. */
    enum class Status {
        OK, ERR_TARGET, ERR_FILE, ERR_WRITE, ERR_ERASE, ERR_CHECK_ERASED, ERR_PROG, ERR_VERIFY,
        ERR_ADDRESS, ERR_NOTDONE, ERR_FIRMWARE, ERR_VENDOR, ERR_USBR, ERR_POR, ERR_UNKNOWN, ERR_STALLEDPKT;

        companion object {
            fun fromWireValue(value: Int): Status = entries.getOrNull(value)
                ?: throw IllegalArgumentException("DfuStatus.Status: unknown wire value $value (DFU 1.1 defines 0x00-0x0F).")
        }
    }

    enum class State(val wireValue: Int) {
        APP_IDLE(0), APP_DETACH(1), DFU_IDLE(2), DFU_DNLOAD_SYNC(3), DFU_DNBUSY(4),
        DFU_DNLOAD_IDLE(5), DFU_MANIFEST_SYNC(6), DFU_MANIFEST(7), DFU_MANIFEST_WAIT_RESET(8),
        DFU_UPLOAD_IDLE(9), DFU_ERROR(10);

        companion object {
            fun fromWireValue(value: Int): State = entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("DfuStatus.State: unknown wire value $value (DFU 1.1 defines 0-10).")
        }
    }

    class MalformedResponseException(message: String) : Exception(message)

    data class GetStatusResponse(val status: Status, val pollTimeoutMillis: Int, val state: State, val stringIndex: Int) {
        init { require(pollTimeoutMillis in 0..0xFFFFFF) { "DfuStatus.GetStatusResponse.pollTimeoutMillis must fit in 3 bytes, got $pollTimeoutMillis." } }
    }

    /** `bStatus`(1) + `bwPollTimeout`(3, little-endian) + `bState`(1) + `iString`(1) — the DFU 1.1 `DFU_GETSTATUS` response, always exactly 6 bytes. */
    fun decodeGetStatus(bytes: ByteArray): GetStatusResponse {
        if (bytes.size != 6) throw MalformedResponseException("DfuStatus.decodeGetStatus: expected exactly 6 bytes, got ${bytes.size}.")
        val status = Status.fromWireValue(bytes[0].toInt() and 0xFF)
        val pollTimeout = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8) or ((bytes[3].toInt() and 0xFF) shl 16)
        val state = State.fromWireValue(bytes[4].toInt() and 0xFF)
        val stringIndex = bytes[5].toInt() and 0xFF
        return GetStatusResponse(status, pollTimeout, state, stringIndex)
    }

    fun encodeGetStatus(response: GetStatusResponse): ByteArray = byteArrayOf(
        response.status.ordinal.toByte(),
        (response.pollTimeoutMillis and 0xFF).toByte(),
        ((response.pollTimeoutMillis shr 8) and 0xFF).toByte(),
        ((response.pollTimeoutMillis shr 16) and 0xFF).toByte(),
        response.state.wireValue.toByte(),
        response.stringIndex.toByte(),
    )

    sealed interface Event {
        object DnloadWithData : Event
        object DnloadZeroLengthManifestTrigger : Event
        object GetStatusPollComplete : Event
        object GetStatusStillBusy : Event
        object ManifestComplete : Event
        object ClrStatus : Event
    }

    sealed interface Result {
        data class Advanced(val state: State) : Result
        data class Rejected(val reason: String) : Result
    }

    /** The download-path subset of the DFU 1.1 state diagram this constellation drives — `APP_IDLE`/`APP_DETACH`/`DFU_UPLOAD_IDLE` are out of scope (no runtime-detach or read-back support modeled here). */
    fun apply(state: State, event: Event): Result = when (state) {
        State.DFU_IDLE -> when (event) {
            Event.DnloadWithData -> Result.Advanced(State.DFU_DNLOAD_SYNC)
            else -> reject(state, event)
        }
        State.DFU_DNLOAD_SYNC -> when (event) {
            Event.GetStatusStillBusy -> Result.Advanced(State.DFU_DNBUSY)
            Event.GetStatusPollComplete -> Result.Advanced(State.DFU_DNLOAD_IDLE)
            else -> reject(state, event)
        }
        State.DFU_DNBUSY -> when (event) {
            Event.GetStatusPollComplete -> Result.Advanced(State.DFU_DNLOAD_IDLE)
            else -> reject(state, event)
        }
        State.DFU_DNLOAD_IDLE -> when (event) {
            Event.DnloadWithData -> Result.Advanced(State.DFU_DNLOAD_SYNC)
            Event.DnloadZeroLengthManifestTrigger -> Result.Advanced(State.DFU_MANIFEST_SYNC)
            else -> reject(state, event)
        }
        State.DFU_MANIFEST_SYNC -> when (event) {
            Event.GetStatusStillBusy -> Result.Advanced(State.DFU_MANIFEST)
            Event.GetStatusPollComplete -> Result.Advanced(State.DFU_MANIFEST_WAIT_RESET)
            else -> reject(state, event)
        }
        State.DFU_MANIFEST -> when (event) {
            Event.ManifestComplete -> Result.Advanced(State.DFU_MANIFEST_WAIT_RESET)
            else -> reject(state, event)
        }
        State.DFU_ERROR -> when (event) {
            Event.ClrStatus -> Result.Advanced(State.DFU_IDLE)
            else -> reject(state, event)
        }
        else -> reject(state, event)
    }

    private fun reject(state: State, event: Event): Result.Rejected = Result.Rejected(
        "DfuStatus: event ${event::class.simpleName} is not legal from state $state in this download-path subset (USB DFU 1.1)."
    )
}
