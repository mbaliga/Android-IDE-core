package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.ContractHello
import dev.fonebrew.watchbridge.HelloCompatibility
import dev.fonebrew.watchbridge.VersionMismatch

/** A paired phone as the wrist knows it. */
data class PhoneNode(val id: String, val displayName: String)

/** A node the Data Layer reported as connected (input to [PhoneNodePicker]). */
data class ObservedNode(val id: String, val displayName: String, val nearby: Boolean)

/** Why a link call failed, classified once at the transport edge so the rest of the module never sees GMS types. */
enum class LinkFailure {
    /** No connected node to talk to (the Data Layer's own "target node not connected"). */
    NO_NODE,

    /** Google Play services' Wearable API is missing or disabled on this watch. */
    API_UNAVAILABLE,

    /** Anything else the transport reported. */
    OTHER,
}

enum class UnreachableCause { NO_PHONE_CONNECTED, LINK_ERROR, LINK_UNAVAILABLE }

/**
 * The state of the link to the phone — a first-class rendered state, not a spinner. The design doc
 * (section 3, "Connection honesty"): *phone unreachable is a first-class rendered state, never a
 * spinner that implies progress.*
 *
 * Honest semantics: [Reachable] means **the radio link to a paired phone is up**, nothing more.
 * [Reachable.phoneAppVersion] is `null` until Fonebrew itself has said hello — a connected node does
 * not prove Fonebrew is installed or running. The Ask flow's own timeout ([AskFailure.NoAnswer]) is
 * what catches "linked but silent".
 */
sealed interface Connection {
    /** Nothing observed yet (just launched). */
    data object Checking : Connection

    data class Reachable(val node: PhoneNode, val phoneAppVersion: String?) : Connection

    data class Unreachable(val cause: UnreachableCause) : Connection

    /** The two apps speak different contract versions; [VersionMismatch.reason] says which to update. */
    data class Incompatible(val mismatch: VersionMismatch, val node: PhoneNode?) : Connection
}

/** Events that move [Connection]. All carry facts the transport actually observed. */
sealed interface LinkEvent : WatchEvent {
    data class NodesObserved(val nodes: List<ObservedNode>) : LinkEvent
    data class HelloReceived(val nodeId: String, val hello: ContractHello) : LinkEvent
    data class EnvelopeIncompatible(val nodeId: String?, val mismatch: VersionMismatch) : LinkEvent

    /** A valid WatchBrew message arrived from the phone: proof of life. */
    data class PhoneHeard(val nodeId: String) : LinkEvent
    data class SendSucceeded(val nodeId: String) : LinkEvent

    /** A link call (node query or send) failed. */
    data class CallFailed(val failure: LinkFailure) : LinkEvent
}

/** Chooses *the* phone from the connected nodes: nearby first, then a stable order. */
object PhoneNodePicker {
    fun pick(nodes: List<ObservedNode>): PhoneNode? =
        nodes.sortedWith(compareByDescending<ObservedNode> { it.nearby }.thenBy { it.id })
            .firstOrNull()
            ?.let { PhoneNode(it.id, it.displayName.ifBlank { DEFAULT_PHONE_NAME }) }

    internal const val DEFAULT_PHONE_NAME = "Phone"
}

object ConnectionReducer {
    fun reduce(current: Connection, event: LinkEvent): Connection = when (event) {
        is LinkEvent.NodesObserved -> onNodes(current, event.nodes)
        is LinkEvent.HelloReceived -> {
            val node = nodeFor(current, event.nodeId)
            when (val verdict = event.hello.compatibility()) {
                HelloCompatibility.Compatible -> Connection.Reachable(node, event.hello.phoneAppVersion)
                is HelloCompatibility.Incompatible -> Connection.Incompatible(verdict.mismatch, node)
            }
        }
        is LinkEvent.EnvelopeIncompatible ->
            Connection.Incompatible(event.mismatch, event.nodeId?.let { nodeFor(current, it) } ?: knownNode(current))
        is LinkEvent.PhoneHeard -> when (current) {
            // A version mismatch is only lifted by a compatible hello, never by mere traffic.
            is Connection.Incompatible -> current
            is Connection.Reachable -> if (current.node.id == event.nodeId) current else Connection.Reachable(nodeFor(current, event.nodeId), null)
            else -> Connection.Reachable(nodeFor(current, event.nodeId), null)
        }
        is LinkEvent.SendSucceeded -> when (current) {
            Connection.Checking, is Connection.Unreachable -> Connection.Reachable(nodeFor(current, event.nodeId), null)
            else -> current
        }
        is LinkEvent.CallFailed -> when (event.failure) {
            LinkFailure.NO_NODE -> Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED)
            LinkFailure.API_UNAVAILABLE -> Connection.Unreachable(UnreachableCause.LINK_UNAVAILABLE)
            LinkFailure.OTHER -> if (current is Connection.Incompatible) current else Connection.Unreachable(UnreachableCause.LINK_ERROR)
        }
    }

    private fun onNodes(current: Connection, nodes: List<ObservedNode>): Connection {
        val picked = PhoneNodePicker.pick(nodes) ?: return Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED)
        return when (current) {
            Connection.Checking -> Connection.Reachable(picked, null)
            is Connection.Reachable ->
                if (current.node.id == picked.id) current.copy(node = picked) else Connection.Reachable(picked, null)
            is Connection.Incompatible ->
                if (current.node == null || current.node.id == picked.id) current.copy(node = picked) else Connection.Reachable(picked, null)
            is Connection.Unreachable ->
                // A node being *listed* does not prove a failed send would now succeed; that needs
                // proof of life (PhoneHeard / SendSucceeded). Every other cause is cleared by a node list.
                if (current.cause == UnreachableCause.LINK_ERROR) current else Connection.Reachable(picked, null)
        }
    }

    private fun knownNode(current: Connection): PhoneNode? = when (current) {
        is Connection.Reachable -> current.node
        is Connection.Incompatible -> current.node
        else -> null
    }

    /** The node for [nodeId]: reuse the known display name when it is the same phone. */
    private fun nodeFor(current: Connection, nodeId: String): PhoneNode =
        knownNode(current)?.takeIf { it.id == nodeId } ?: PhoneNode(nodeId, PhoneNodePicker.DEFAULT_PHONE_NAME)
}

/** A connection as words + a shape. Short sentences: this is read on a 1-inch screen. */
data class ConnectionView(
    val glyph: Glyph,
    val headline: String,
    val detail: String,
    /** `true` when the Ask/Tasks surfaces should say the link is not usable right now. */
    val blocking: Boolean,
)

object ConnectionPresenter {
    fun present(connection: Connection): ConnectionView = when (connection) {
        Connection.Checking -> ConnectionView(Glyph.LINK_CHECKING, "Checking phone link", "Looking for your paired phone.", blocking = false)
        is Connection.Reachable -> ConnectionView(
            Glyph.LINK_OK,
            "Phone linked",
            connection.phoneAppVersion?.let { "Fonebrew $it" } ?: "Fonebrew hasn't said hello yet.",
            blocking = false,
        )
        is Connection.Unreachable -> when (connection.cause) {
            UnreachableCause.NO_PHONE_CONNECTED -> ConnectionView(
                Glyph.LINK_BROKEN, "Phone not reachable", "No paired phone is connected. Check Bluetooth and that the phone is nearby.", blocking = true,
            )
            UnreachableCause.LINK_ERROR -> ConnectionView(
                Glyph.LINK_BROKEN, "Phone link failed", "The last call to the phone failed. Is Fonebrew installed and running?", blocking = true,
            )
            UnreachableCause.LINK_UNAVAILABLE -> ConnectionView(
                Glyph.LINK_BROKEN, "Phone link unavailable", "Google Play services isn't available for the watch-phone link.", blocking = true,
            )
        }
        is Connection.Incompatible -> ConnectionView(Glyph.LINK_MISMATCH, "Versions don't match", connection.mismatch.reason, blocking = true)
    }
}
