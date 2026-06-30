package dev.aarso.domain.ledger

/**
 * **Reconciliation** between the ledger's own running total and what a provider says you
 * spent. On-thesis honesty: the app's `estCostMinor` figures are *its* arithmetic, and
 * the provider is the source of truth for money actually billed. When a provider exposes
 * a usage/billing figure we surface the **delta** so a discrepancy is visible rather than
 * quietly trusted. Many providers expose **no** usage API at all — in that case we say so
 * ([ReconResult.Unavailable]) instead of inventing a number.
 *
 * Pure domain, JVM-tested. Money is in the smallest currency unit (paise / cents).
 */
sealed interface ReconResult {
    /** The provider total this is locally tracked against. */
    val local: Long

    /**
     * The provider reported a figure. [delta] is `provider − local`: positive means the
     * provider billed *more* than the ledger estimated (we under-counted), negative means
     * the ledger over-counted.
     */
    data class Available(
        override val local: Long,
        val provider: Long,
        val delta: Long,
    ) : ReconResult

    /** The provider exposes no usage API — only the local estimate is known. */
    data class Unavailable(override val local: Long) : ReconResult
}

object Reconciliation {

    /**
     * Reconcile a [localMinor] estimate against an optional [providerMinor] figure.
     * `null` for [providerMinor] means the provider has no usage API → [ReconResult.Unavailable].
     */
    fun delta(localMinor: Long, providerMinor: Long?): ReconResult =
        if (providerMinor == null) {
            ReconResult.Unavailable(localMinor)
        } else {
            ReconResult.Available(
                local = localMinor,
                provider = providerMinor,
                delta = providerMinor - localMinor,
            )
        }
}
