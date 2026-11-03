package dev.fonebrew.data.watch

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [WearTransport] over the Wearable Data Layer: `MessageClient` for single envelopes, `ChannelClient`
 * for the streamed reply. **Device-only and deliberately thin**: no decisions live here, so the JVM
 * gate has nothing to say about it and nothing in this file is claimed to work until the owner has run
 * it between a real phone and a real watch (docs/design/watchbrew.md section 7).
 *
 * The Data Layer is device-local pair transport (Bluetooth / Wi-Fi between the paired devices). It is
 * not telemetry and nothing here leaves the pair; the app-id and signing-key match that the Data Layer
 * requires is what scopes it to the user's own watch.
 *
 * Owner-verification notes (open questions, not answered by this container):
 *  - **Channel tail delivery.** The reply is ended by closing the channel's output stream, which should
 *    flush and signal end-of-stream to the reader. [ChannelClient.close] may discard undelivered bytes,
 *    so it is NOT called straight after: it is deferred by [CHANNEL_LINGER_MS] as a leak guard, giving
 *    the watch time to read the terminal chunk (which carries usage) first. If the terminal chunk is ever
 *    missing on the wrist, the first suspect is this ordering.
 *  - **Where the reader is.** The watch must register for `onChannelOpened` (or a channel callback) on
 *    [dev.fonebrew.watchbridge.WatchPaths.ASK_REPLY] before it asks.
 */
internal class GmsWearTransport(
    context: Context,
    private val scope: CoroutineScope,
) : WearTransport {

    private val messages = Wearable.getMessageClient(context.applicationContext)
    private val channels: ChannelClient = Wearable.getChannelClient(context.applicationContext)

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        try {
            messages.sendMessage(nodeId, path, payload).await()
        } catch (e: IOException) {
            throw e
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Data Layer message failed: ${e.javaClass.simpleName}", e)
        }
    }

    override suspend fun openReplyChannel(nodeId: String, path: String): ReplyChannel {
        try {
            val channel = channels.openChannel(nodeId, path).await()
            val out = channels.getOutputStream(channel).await()
            return GmsReplyChannel(channels, channel, out, scope)
        } catch (e: IOException) {
            throw e
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Data Layer channel failed: ${e.javaClass.simpleName}", e)
        }
    }

    private class GmsReplyChannel(
        private val channels: ChannelClient,
        private val channel: ChannelClient.Channel,
        private val out: OutputStream,
        private val scope: CoroutineScope,
    ) : ReplyChannel {
        private var closed = false

        override suspend fun write(frame: ByteArray) {
            withContext(Dispatchers.IO) {
                out.write(frame)
                out.flush()
            }
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            withContext(NonCancellable + Dispatchers.IO) { runCatching { out.close() } }
            // Leak guard, not the normal path: see the class KDoc on tail delivery.
            scope.launch {
                delay(CHANNEL_LINGER_MS)
                runCatching { channels.close(channel).await() }
            }
        }
    }

    private companion object {
        const val CHANNEL_LINGER_MS = 15_000L
    }
}

/** A minimal `Task.await()` so no extra coroutines-play-services artifact is needed. Cancels with the caller. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
