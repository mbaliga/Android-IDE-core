package dev.fonebrew.watch.link

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Wearable
import dev.fonebrew.watch.core.LinkFailure
import dev.fonebrew.watch.core.LinkFailureClassifier
import dev.fonebrew.watch.core.ObservedNode
import dev.fonebrew.watch.runtime.LinkException
import dev.fonebrew.watch.runtime.PhoneLink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PhoneLink] over the Wearable Data Layer: `NodeClient` to see the paired phone, `MessageClient` to
 * send to it. This is **device-local pair transport** (Bluetooth / Wi-Fi between the two paired
 * devices) — not a network client and not telemetry (docs/design/watchbrew.md section 2). It is the
 * only outbound capability the watch has, and it carries only the contract's own messages.
 *
 * Every failure becomes a [LinkException] carrying a [LinkFailure] classified from the GMS status
 * code, so nothing above this class sees GMS types. Device-only behaviour: nothing here has run
 * against a real watch or phone (the build container has neither).
 */
internal class DataLayerPhoneLink(context: Context) : PhoneLink {
    private val nodes = Wearable.getNodeClient(context)
    private val messages = Wearable.getMessageClient(context)

    override suspend fun connectedNodes(): List<ObservedNode> = guarded {
        nodes.connectedNodes.await().map { ObservedNode(it.id, it.displayName, it.isNearby) }
    }

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        guarded { messages.sendMessage(nodeId, path, payload).await() }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: LinkException) {
        throw e
    } catch (e: ApiException) {
        throw LinkException(LinkFailureClassifier.fromStatusCode(e.statusCode), "Data Layer status ${e.statusCode}", e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // A Task that failed with a non-ApiException (e.g. a wrapped one): classify by its cause chain.
        val api = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<ApiException>().firstOrNull()
        throw LinkException(if (api != null) LinkFailureClassifier.fromStatusCode(api.statusCode) else LinkFailure.OTHER, e.message, e)
    }
}

/** Awaits a Play-services [Task] without pulling in kotlinx-coroutines-play-services. Cancellation-aware. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}
