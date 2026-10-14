package dev.fonebrew.watch.runtime

import dev.fonebrew.watch.core.LinkFailure
import dev.fonebrew.watch.core.ObservedNode

/**
 * The watch's only outbound capability, as an interface: "who is connected" and "send these bytes to
 * that node on that path". The one production implementation (`link/DataLayerPhoneLink`) is a thin
 * wrapper over the Wearable Data Layer's NodeClient and MessageClient — the device-local pair
 * transport, not a network client. Everything above this interface is plain Kotlin and JVM-tested
 * against a fake.
 *
 * Implementations must wrap every transport failure in a [LinkException] (so callers classify by
 * [LinkFailure], never by GMS exception types) and must let coroutine cancellation propagate.
 */
interface PhoneLink {
    suspend fun connectedNodes(): List<ObservedNode>
    suspend fun send(nodeId: String, path: String, payload: ByteArray)
}

class LinkException(val failure: LinkFailure, message: String? = null, cause: Throwable? = null) : Exception(message, cause)
