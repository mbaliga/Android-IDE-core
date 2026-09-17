package dev.fonebrew.domain.authority

/**
 * FB-RAT-EXE-007 / binding rule 5's runtime proof mechanism: given the set of secret values a
 * broker currently holds "live" for a run, scans arbitrary text (a log excerpt, a receipt field)
 * for verbatim appearances of any of them. This is the "secret-redaction sentinel test" the WP-4
 * brief names as a required gate -- a real scan against real live values, not a regex heuristic
 * for credential-shaped strings (which would both over- and under-match).
 */
object SecretRedactionScanner {

    /** Every live secret value found verbatim inside [text], in [liveSecretValues] order. Empty means zero hits. */
    fun scanForLeaks(text: String, liveSecretValues: Collection<String>): List<String> =
        liveSecretValues.filter { it.isNotEmpty() && text.contains(it) }

    fun isClean(text: String, liveSecretValues: Collection<String>): Boolean =
        scanForLeaks(text, liveSecretValues).isEmpty()
}
