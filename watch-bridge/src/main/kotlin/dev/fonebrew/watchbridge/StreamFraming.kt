package dev.fonebrew.watchbridge

/**
 * Length-prefixed framing for the streamed reply channel ([WatchPaths.ASK_REPLY]). A channel is a
 * byte stream with no message boundaries, so each [WatchMessage] travels as one frame:
 *
 * ```
 * [4 bytes: payload length, unsigned big-endian][payload: UTF-8 envelope from WatchBridgeCodec.encode]
 * ```
 *
 * The payload is exactly what [WatchBridgeCodec.encodeToBytes] produces, so a frame's body is decoded
 * by the same strict [WatchBridgeCodec.decode] as a standalone message: same version handshake, same
 * all-or-nothing malformed handling. A reply is a run of non-terminal [AskChunk]s then one terminal
 * chunk, or a single [AskError]; the sender then ends the stream. A stream that ends **without** a
 * terminal chunk or error is a lost link, and [FrameDecoder.bufferedBytes] > 0 at end-of-stream
 * additionally says the last frame was cut mid-way. The watch should render both as the
 * "phone unreachable" state, never as a finished answer.
 *
 * Why a length prefix and not newline-delimited JSON: a corrupt or hostile length is detectable up
 * front ([MAX_FRAME_BYTES]) before any allocation, and a payload is never searched for a delimiter.
 */
public object StreamFraming {
    /** Bytes in the length prefix. */
    public const val HEADER_BYTES: Int = 4

    /**
     * The largest payload a frame may carry. A reader that sees a bigger (or zero) length treats the
     * stream as corrupt and gives up on it; nothing in v1 comes close (the relay caps chunk text well
     * below this).
     */
    public const val MAX_FRAME_BYTES: Int = 64 * 1024

    /**
     * One frame for [message]. Throws [IllegalArgumentException] if the encoded payload exceeds
     * [MAX_FRAME_BYTES]: that is a sender bug (it should have split the text), never a runtime
     * condition to paper over.
     */
    public fun encodeFrame(message: WatchMessage): ByteArray {
        val payload = WatchBridgeCodec.encodeToBytes(message)
        require(payload.size in 1..MAX_FRAME_BYTES) {
            "frame payload of ${payload.size} bytes is outside 1..$MAX_FRAME_BYTES"
        }
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = (payload.size ushr 24).toByte()
        out[1] = (payload.size ushr 16).toByte()
        out[2] = (payload.size ushr 8).toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, destinationOffset = HEADER_BYTES)
        return out
    }
}

/**
 * The reading half of [StreamFraming]: feed it whatever byte runs the channel delivers (any split,
 * including one byte at a time) and it returns one [DecodeResult] per complete frame, in order.
 *
 * - A frame whose body does not decode is [DecodeResult.Malformed] / [DecodeResult.Incompatible] for
 *   *that frame only*; the frame boundaries were intact, so later frames still decode.
 * - A frame whose **length** is invalid (zero or over [StreamFraming.MAX_FRAME_BYTES]) means the byte
 *   stream itself can no longer be trusted: the decoder returns one [DecodeResult.Malformed], marks
 *   itself [isBroken], and ignores everything after. The reader should drop the channel.
 * - Not thread-safe: one decoder per channel, fed from one reader.
 */
public class FrameDecoder {
    private var buffer: ByteArray = ByteArray(INITIAL_CAPACITY)
    private var size: Int = 0

    /** `true` once an invalid length was seen; the decoder then yields nothing more. */
    public var isBroken: Boolean = false
        private set

    /** Bytes held back waiting for the rest of a frame. Non-zero at end-of-stream means a truncated frame. */
    public val bufferedBytes: Int get() = size

    /** Consumes `bytes[offset until offset + length]` and returns every frame it completes. */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<DecodeResult> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "bad range" }
        if (isBroken || length == 0) return emptyList()
        append(bytes, offset, length)

        val results = ArrayList<DecodeResult>()
        var cursor = 0
        while (size - cursor >= StreamFraming.HEADER_BYTES) {
            val declared = readLength(cursor)
            if (declared < 1 || declared > StreamFraming.MAX_FRAME_BYTES) {
                isBroken = true
                size = 0
                results += DecodeResult.Malformed("frame length $declared is outside 1..${StreamFraming.MAX_FRAME_BYTES}")
                return results
            }
            val end = cursor + StreamFraming.HEADER_BYTES + declared
            if (size < end) break
            results += WatchBridgeCodec.decode(buffer.copyOfRange(cursor + StreamFraming.HEADER_BYTES, end))
            cursor = end
        }
        if (cursor > 0) {
            buffer.copyInto(buffer, destinationOffset = 0, startIndex = cursor, endIndex = size)
            size -= cursor
        }
        return results
    }

    private fun readLength(at: Int): Int =
        ((buffer[at].toInt() and 0xFF) shl 24) or
            ((buffer[at + 1].toInt() and 0xFF) shl 16) or
            ((buffer[at + 2].toInt() and 0xFF) shl 8) or
            (buffer[at + 3].toInt() and 0xFF)

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        val needed = size + length
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
        bytes.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size = needed
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
