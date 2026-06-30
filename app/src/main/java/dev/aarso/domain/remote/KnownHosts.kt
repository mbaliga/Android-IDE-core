package dev.aarso.domain.remote

/**
 * The host-key trust model — the legibility centerpiece of the remote spine. Connecting
 * to a machine you've never met is *materially distinct* from connecting to one you've
 * vetted (THE LAW: the difference is shown by state, not by a "⚠ unverified" word the user
 * skims past). This is SSH's known-hosts / trust-on-first-use, modelled purely so the host
 * app can render the three states honestly and the user makes the trust decision.
 *
 * A host key fingerprint is the server's public-key digest (e.g. SHA-256 base64). We never
 * compute crypto here; the transport hands us the presented fingerprint and we classify it
 * against what (if anything) we've pinned. Pure Kotlin; JVM-tested.
 */

/** A presented server key: its algorithm (e.g. "ssh-ed25519") and fingerprint digest. */
data class HostKey(val algorithm: String, val fingerprint: String) {
    init {
        require(algorithm.isNotBlank()) { "empty key algorithm" }
        require(fingerprint.isNotBlank()) { "empty fingerprint" }
    }
}

/** What we've pinned for an endpoint, if anything. */
data class PinnedHost(val endpoint: String, val key: HostKey)

/**
 * The trust verdict for a presented key — the material the UI renders. Deliberately three
 * distinct states, not a boolean: "unknown" and "changed" demand *different* human responses
 * (first-time accept vs. a possible MITM), and collapsing them would hide the danger.
 */
sealed interface Trust {
    /** Endpoint matches a pinned key — vetted. Proceed silently. */
    data object Vetted : Trust

    /** Never seen this endpoint — trust-on-first-use decision is the user's. */
    data class Unknown(val presented: HostKey) : Trust

    /**
     * We have a pinned key for this endpoint and the presented one DIFFERS. This is the
     * MITM-or-reinstall fork: surface both fingerprints loudly; never auto-accept.
     */
    data class Changed(val pinned: HostKey, val presented: HostKey) : Trust
}

/**
 * An immutable view over pinned host keys with pure trust classification and explicit
 * pin/unpin transforms (returning a new map — the data layer persists it). The classify →
 * (user decides) → pin flow is the whole trust lifecycle.
 */
class KnownHosts(private val pins: Map<String, HostKey> = emptyMap()) {

    /** Classify a [presented] key for [endpoint] against what we've pinned. */
    fun classify(endpoint: String, presented: HostKey): Trust {
        val pinned = pins[endpoint] ?: return Trust.Unknown(presented)
        return if (pinned == presented) Trust.Vetted else Trust.Changed(pinned, presented)
    }

    /** Convenience: classify the key a [host] presented. */
    fun classify(host: RemoteHost, presented: HostKey): Trust = classify(host.endpoint, presented)

    /** True only when the presented key is already vetted (no user prompt needed). */
    fun isVetted(endpoint: String, presented: HostKey): Boolean = classify(endpoint, presented) is Trust.Vetted

    /** Pin (or re-pin, after the user accepts a change) a key for [endpoint] → a new map. */
    fun pin(endpoint: String, key: HostKey): KnownHosts = KnownHosts(pins + (endpoint to key))

    /** Forget an endpoint's pin → a new map. */
    fun unpin(endpoint: String): KnownHosts = KnownHosts(pins - endpoint)

    /** The pinned key for [endpoint], if any. */
    fun pinned(endpoint: String): HostKey? = pins[endpoint]

    /** All pins, for a "vetted machines" surface. */
    fun all(): List<PinnedHost> = pins.map { (e, k) -> PinnedHost(e, k) }
}
