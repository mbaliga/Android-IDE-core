// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.contracts

import dev.fonebrew.contracts.common.DigestAlgorithm
import dev.fonebrew.contracts.common.IntegrityRef
import java.security.MessageDigest

/**
 * SHA-256(+) digest computation for [IntegrityRef] (FB-RAT-COM-005: "SHA-256 or stronger, plus
 * exact byte length, on artifacts/manifests/receipts"). This is the first shared digest helper
 * in the codebase — the WP-2 codebase map found exactly one prior digest computation anywhere in
 * `core-engine` (`SshjTransport.sha256Fingerprint`, SSH host-key fingerprinting), inlined at its
 * one call site with no shared utility to converge with. This object generalizes that same JDK
 * idiom (`MessageDigest.getInstance(...).digest(bytes)`) into a reusable, algorithm-parameterized
 * form, rather than introducing a third-party crypto/hash dependency (none exists anywhere in
 * this module's dependency graph).
 */
object Digest {

    /** Computes an [IntegrityRef] over [bytes] using [algorithm] (default SHA-256, FB-RAT-COM-005's minimum). */
    fun of(bytes: ByteArray, algorithm: DigestAlgorithm = DigestAlgorithm.SHA_256): IntegrityRef {
        val jdkAlgorithmName = when (algorithm) {
            DigestAlgorithm.SHA_256 -> "SHA-256"
            DigestAlgorithm.SHA_384 -> "SHA-384"
            DigestAlgorithm.SHA_512 -> "SHA-512"
        }
        val raw = MessageDigest.getInstance(jdkAlgorithmName).digest(bytes)
        return IntegrityRef(
            algorithm = algorithm,
            digestHex = raw.joinToString(separator = "") { "%02x".format(it) },
            byteLength = bytes.size.toLong()
        )
    }

    /** Convenience overload for UTF-8 text (the common case: digesting a canonical JSON string). */
    fun ofUtf8(text: String, algorithm: DigestAlgorithm = DigestAlgorithm.SHA_256): IntegrityRef =
        of(text.toByteArray(Charsets.UTF_8), algorithm)

    /**
     * Recomputes the digest over [bytes] and compares it to [expected] — the check every reader
     * of a digest-addressed artifact MUST perform before trusting it (`ArtifactRef.digest`,
     * `envelope.integrity`; see `fixtures/common/adversarial/envelope-integrity-digest-mismatch.adversarial.json`
     * and `fixtures/common/adversarial/artifact-ref-path-traversal.adversarial.json`'s sibling
     * digest-mismatch class). Returns `false` on any mismatch — algorithm, hex digest, or byte
     * length — never throws, so a caller can turn a mismatch into a normal `ErrorEnvelope`
     * (`ENVELOPE_INTEGRITY_MISMATCH` per that adversarial fixture's `.expected.txt`) rather than
     * an uncaught exception.
     */
    fun verify(bytes: ByteArray, expected: IntegrityRef): Boolean {
        val recomputed = of(bytes, expected.algorithm)
        return recomputed.digestHex == expected.digestHex && recomputed.byteLength == expected.byteLength
    }
}
