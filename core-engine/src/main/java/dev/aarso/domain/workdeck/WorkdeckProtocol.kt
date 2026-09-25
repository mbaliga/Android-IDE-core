package dev.aarso.domain.workdeck

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class WorkdeckMessageType(val wireId: Int) {
    HELLO(1),
    FULL_FRAME(2),
    DIRTY_RECTANGLE(3),
    REFRESH_HINT(4),
    TOUCH_POINTER(5),
    KEYBOARD(6),
    CLIPBOARD_TEXT(7),
    VIEWPORT_ROTATION(8),
    PING_RECONNECT(9),
    SUSPEND_WAKE(10),
    NATIVE_DOCUMENT(11),
    CONTROL_ACTION(12),
    AUTH_CHALLENGE(13),
    AUTH_RESPONSE(14),
    ERROR(15),
    ;

    companion object {
        fun fromWireId(value: Int): WorkdeckMessageType =
            entries.firstOrNull { it.wireId == value } ?: error("Unknown Workdeck message type $value")
    }
}

data class WorkdeckPacket(
    val version: Int = WorkdeckProtocol.VERSION,
    val type: WorkdeckMessageType,
    val flags: Int = 0,
    val sequence: Long,
    val payload: ByteArray = byteArrayOf(),
) {
    init {
        require(version == WorkdeckProtocol.VERSION) { "Unsupported Workdeck protocol version: $version" }
        require(flags in 0..0xffff) { "Workdeck flags exceed the wire field." }
        require(sequence >= 0) { "Workdeck sequence must be non-negative." }
        require(payload.size <= WorkdeckProtocol.MAX_PAYLOAD_BYTES) { "Workdeck payload is too large." }
    }

    override fun equals(other: Any?): Boolean =
        other is WorkdeckPacket && version == other.version && type == other.type && flags == other.flags &&
            sequence == other.sequence && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * sequence.hashCode() + payload.contentHashCode()
}

object WorkdeckProtocol {
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x57444b31 // WDK1

    fun encode(packet: WorkdeckPacket): ByteArray = ByteArrayOutputStream(HEADER_BYTES + packet.payload.size).use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(packet.version)
            output.writeByte(packet.type.wireId)
            output.writeShort(packet.flags)
            output.writeLong(packet.sequence)
            output.writeInt(packet.payload.size)
            output.write(packet.payload)
        }
        buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): WorkdeckPacket = DataInputStream(ByteArrayInputStream(bytes)).use(::read)

    fun read(input: DataInputStream): WorkdeckPacket {
        require(input.readInt() == MAGIC) { "Invalid Workdeck frame magic." }
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Unsupported Workdeck protocol version: $version" }
        val type = WorkdeckMessageType.fromWireId(input.readUnsignedByte())
        val flags = input.readUnsignedShort()
        val sequence = input.readLong()
        val length = input.readInt()
        require(length in 0..MAX_PAYLOAD_BYTES) { "Invalid Workdeck payload length: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return WorkdeckPacket(version, type, flags, sequence, payload)
    }

    const val HEADER_BYTES = 20
}

data class WorkdeckCapabilities(
    val deviceId: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val grayscaleLevels: Int,
    val touch: Boolean,
    val keyboard: Boolean,
    val pageButtons: Boolean,
    val waveforms: Set<String>,
) {
    init {
        require(deviceId.isNotBlank()) { "Workdeck device id is required." }
        require(displayWidth > 0 && displayHeight > 0) { "Workdeck display dimensions are invalid." }
        require(grayscaleLevels in setOf(2, 4, 16, 256)) { "Unsupported grayscale capability." }
    }
}

object WorkdeckPayloadCodec {
    fun hello(capabilities: WorkdeckCapabilities): ByteArray = dataOutput { output ->
        output.writeUtf8(capabilities.deviceId)
        output.writeInt(capabilities.displayWidth)
        output.writeInt(capabilities.displayHeight)
        output.writeInt(capabilities.grayscaleLevels)
        output.writeBoolean(capabilities.touch)
        output.writeBoolean(capabilities.keyboard)
        output.writeBoolean(capabilities.pageButtons)
        output.writeInt(capabilities.waveforms.size)
        capabilities.waveforms.sorted().forEach(output::writeUtf8)
    }

    fun decodeHello(payload: ByteArray): WorkdeckCapabilities = dataInput(payload) { input ->
        val deviceId = input.readUtf8()
        val width = input.readInt()
        val height = input.readInt()
        val levels = input.readInt()
        val touch = input.readBoolean()
        val keyboard = input.readBoolean()
        val buttons = input.readBoolean()
        val count = input.readInt()
        require(count in 0..64) { "Invalid waveform count." }
        WorkdeckCapabilities(deviceId, width, height, levels, touch, keyboard, buttons, List(count) { input.readUtf8() }.toSet())
    }

    fun viewport(width: Int, height: Int, rotationDegrees: Int): ByteArray = dataOutput {
        require(width > 0 && height > 0) { "Viewport dimensions are invalid." }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Viewport rotation is invalid." }
        it.writeInt(width); it.writeInt(height); it.writeInt(rotationDegrees)
    }

    data class ViewportPayload(val width: Int, val height: Int, val rotationDegrees: Int)

    fun decodeViewport(payload: ByteArray): ViewportPayload = dataInput(payload) {
        ViewportPayload(it.readInt(), it.readInt(), it.readInt()).also { viewport ->
            require(viewport.width > 0 && viewport.height > 0)
            require(viewport.rotationDegrees in setOf(0, 90, 180, 270))
            require(it.available() == 0) { "Trailing Workdeck viewport data." }
        }
    }

    fun touch(x: Float, y: Float, action: Int): ByteArray = dataOutput {
        require(x in 0f..1f && y in 0f..1f) { "Touch coordinates must be normalized." }
        it.writeFloat(x); it.writeFloat(y); it.writeByte(action)
    }

    data class TouchPayload(val x: Float, val y: Float, val action: Int)

    fun decodeTouch(payload: ByteArray): TouchPayload = dataInput(payload) {
        TouchPayload(it.readFloat(), it.readFloat(), it.readUnsignedByte()).also { touch ->
            require(touch.x in 0f..1f && touch.y in 0f..1f) { "Touch coordinates are invalid." }
        }
    }

    data class RegionPayload(
        val frameWidth: Int,
        val frameHeight: Int,
        val rect: WorkdeckRect,
        val quality: WorkdeckQuality,
        val compressedPixels: ByteArray,
    )

    fun region(frame: GrayFrame, rect: WorkdeckRect, quality: WorkdeckQuality): ByteArray {
        val encoded = WorkdeckRegionEncoder.encode(frame, rect, quality)
        return dataOutput {
            it.writeInt(frame.width); it.writeInt(frame.height)
            it.writeInt(rect.left); it.writeInt(rect.top); it.writeInt(rect.rightExclusive); it.writeInt(rect.bottomExclusive)
            it.writeByte(quality.ordinal)
            it.writeInt(encoded.size)
            it.write(encoded)
        }
    }

    fun decodeRegion(payload: ByteArray): RegionPayload = dataInput(payload) {
        val width = it.readInt(); val height = it.readInt()
        val rect = WorkdeckRect(it.readInt(), it.readInt(), it.readInt(), it.readInt())
        val quality = WorkdeckQuality.entries.getOrNull(it.readUnsignedByte()) ?: error("Unknown Workdeck quality.")
        val count = it.readInt()
        require(width > 0 && height > 0 && rect.rightExclusive <= width && rect.bottomExclusive <= height)
        require(count in 0..WorkdeckProtocol.MAX_PAYLOAD_BYTES)
        RegionPayload(width, height, rect, quality, ByteArray(count).also(it::readFully))
    }

    fun text(value: String): ByteArray = dataOutput { it.writeUtf8(value) }

    fun decodeText(payload: ByteArray): String = dataInput(payload) { it.readUtf8() }

    private fun dataOutput(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use(block)
        bytes.toByteArray()
    }

    private fun <T> dataInput(bytes: ByteArray, block: (DataInputStream) -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use(block)

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_535) { "Workdeck string exceeds limit." }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf8(): String {
        val length = readUnsignedShort()
        return String(ByteArray(length).also(::readFully), Charsets.UTF_8)
    }
}

object WorkdeckAuthenticator {
    const val NONCE_BYTES = 32

    fun response(pairingSecret: ByteArray, nonce: ByteArray, deviceId: String): ByteArray {
        require(pairingSecret.size >= 32) { "Workdeck pairing secret must contain at least 256 bits." }
        require(nonce.size == NONCE_BYTES) { "Invalid Workdeck challenge nonce." }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingSecret, "HmacSHA256"))
        mac.update(nonce)
        mac.update(deviceId.toByteArray(Charsets.UTF_8))
        mac.update(WorkdeckProtocol.VERSION.toString().toByteArray(Charsets.US_ASCII))
        return mac.doFinal()
    }

    fun verify(pairingSecret: ByteArray, nonce: ByteArray, deviceId: String, response: ByteArray): Boolean =
        MessageDigest.isEqual(response(pairingSecret, nonce, deviceId), response)
}

/** Per-packet HMAC after pairing; the HELLO/challenge exchange itself remains unsigned. */
object WorkdeckPacketAuthenticator {
    const val AUTHENTICATED_FLAG = 1
    private const val MAC_BYTES = 32

    fun seal(pairingSecret: ByteArray, packet: WorkdeckPacket): WorkdeckPacket {
        require(packet.flags and AUTHENTICATED_FLAG == 0)
        require(packet.payload.size <= WorkdeckProtocol.MAX_PAYLOAD_BYTES - MAC_BYTES)
        val mac = mac(pairingSecret, packet.type, packet.sequence, packet.payload)
        return packet.copy(flags = packet.flags or AUTHENTICATED_FLAG, payload = mac + packet.payload)
    }

    fun open(pairingSecret: ByteArray, packet: WorkdeckPacket): WorkdeckPacket {
        require(packet.flags and AUTHENTICATED_FLAG != 0 && packet.payload.size >= MAC_BYTES) {
            "Unauthenticated Workdeck packet."
        }
        val supplied = packet.payload.copyOfRange(0, MAC_BYTES)
        val payload = packet.payload.copyOfRange(MAC_BYTES, packet.payload.size)
        require(MessageDigest.isEqual(supplied, mac(pairingSecret, packet.type, packet.sequence, payload))) {
            "Invalid Workdeck packet MAC."
        }
        return packet.copy(flags = packet.flags and AUTHENTICATED_FLAG.inv(), payload = payload)
    }

    private fun mac(secret: ByteArray, type: WorkdeckMessageType, sequence: Long, payload: ByteArray): ByteArray {
        require(secret.size >= 32)
        val signer = Mac.getInstance("HmacSHA256")
        signer.init(SecretKeySpec(secret, "HmacSHA256"))
        signer.update(WorkdeckProtocol.VERSION.toByte())
        signer.update(type.wireId.toByte())
        signer.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sequence).array())
        signer.update(payload)
        return signer.doFinal()
    }
}
