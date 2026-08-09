package dev.aarso.domain.authority

import dev.aarso.contracts.execution.SecretHandleRef

/**
 * FB-RAT-EXE-007's runtime counterpart: resolves a purpose-bound [SecretHandleRef] to an actual
 * usable secret value for exactly one execution, never returning that value through a receipt,
 * log, or any other durable/inspectable channel (the caller that resolves a secret is trusted to
 * use it and discard it -- this interface's contract ends at "handed the raw value to the one
 * call site that legitimately needs it").
 *
 * This is the INTERFACE the WP-4 brief asks for, not a full Android Keystore integration: this
 * module's real secret-at-rest storage is `security/KeystoreSecret.kt` (binding rule 5,
 * AES-GCM, Android Keystore-backed), which needs a real Android Keystore to exercise and is
 * therefore owner-verified, not JVM-testable. [InMemorySecretHandleBroker] below is a
 * JVM-testable reference implementation over a plain in-memory map, useful for wiring and for
 * this pass's own tests -- production wiring in `AppContainer` would swap it for an
 * implementation backed by `KeystoreSecret` without changing this interface.
 */
fun interface SecretHandleBroker {
    /**
     * Resolves [handle] on behalf of [requestingPrincipalId]. Implementations MUST verify the
     * handle's declared [SecretHandleRef.purpose] before returning a value -- a purpose-bound
     * handle resolved for a different purpose than it was issued for is exactly the
     * CAPABILITY_AUTHORITY_MODEL.md §7 violation this broker exists to prevent ("a model key
     * must be unusable by a shell operation").
     */
    fun resolve(handle: SecretHandleRef, requestingPrincipalId: String): SecretResolution
}

sealed interface SecretResolution {
    /** The raw secret value. Callers MUST NOT log, persist, or echo this into any receipt/log field. */
    data class Resolved(val value: String) : SecretResolution
    data class Denied(val reason: String) : SecretResolution
}

/**
 * Reference [SecretHandleBroker] over a plain in-memory map, keyed by `(handleId, purpose)` so a
 * handle registered for one purpose cannot be resolved under a different one even if the same
 * `handleId` string were reused (defense in depth -- `handleId`s are expected to be unique per
 * purpose in practice, but this makes the purpose-binding check unconditional rather than
 * trusting caller discipline alone).
 */
class InMemorySecretHandleBroker(
    private val secretsByHandleAndPurpose: Map<Pair<String, String>, String>,
) : SecretHandleBroker {
    override fun resolve(handle: SecretHandleRef, requestingPrincipalId: String): SecretResolution {
        val value = secretsByHandleAndPurpose[handle.handleId to handle.purpose]
            ?: return SecretResolution.Denied("No secret registered for handleId='${handle.handleId}' purpose='${handle.purpose}'.")
        return SecretResolution.Resolved(value)
    }
}
