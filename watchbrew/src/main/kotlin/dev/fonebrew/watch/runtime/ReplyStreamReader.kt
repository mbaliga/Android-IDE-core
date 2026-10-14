package dev.fonebrew.watch.runtime

import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.FrameDecoder
import java.io.IOException
import java.io.InputStream

/** How a reply stream ended, as far as the bytes can tell. Whether the *reply* finished is the reducer's call (terminal chunk or not). */
enum class StreamEnd {
    /** The stream closed exactly on a frame boundary. */
    CLEAN,

    /** The stream closed in the middle of a frame. */
    TRUNCATED,

    /** A frame length was invalid: the byte stream itself can no longer be trusted. */
    BROKEN,

    /** The transport threw while reading. */
    IO_FAILURE,
}

/**
 * Reads one reply channel to its end, handing each decoded frame to [onFrame] in order. Blocking by
 * design: the Wearable listener callback that owns the channel runs on a worker thread, and keeping
 * the read inside it keeps the service alive for as long as the reply is streaming.
 *
 * The framing itself — length prefix, bounds, partial reads — is the bridge's [FrameDecoder] (shared
 * with the phone's writer so the two halves cannot drift); this class only pumps bytes into it.
 */
class ReplyStreamReader(private val input: InputStream, private val bufferSize: Int = 2_048) {
    fun readAll(onFrame: (DecodeResult) -> Unit): StreamEnd {
        val decoder = FrameDecoder()
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                return StreamEnd.IO_FAILURE
            }
            if (read < 0) break
            decoder.feed(buffer, 0, read).forEach(onFrame)
            if (decoder.isBroken) return StreamEnd.BROKEN
        }
        return if (decoder.bufferedBytes > 0) StreamEnd.TRUNCATED else StreamEnd.CLEAN
    }
}
