package dev.aarso.domain.ledger

/**
 * Pure builder that assembles a single-agent [LedgerEntry] from the facts observed when a chat
 * turn completes. Kept out of the ViewModel so the field mapping — and the tier→provenance
 * derivation — is JVM-tested rather than buried in the generation path.
 *
 * **Honest by construction (binding rule 6):** it invents nothing. Token counts, cost and the
 * [LedgerEntry.estimated] flag are passed in from the generation site — provider-reported usage
 * for a cloud turn (authoritative), the local tokenizer's own count for an on-device turn (which
 * is why on-device turns are passed `estimated = true`: the prompt count approximates the
 * templated prompt). Cost is 0 for on-device — the "no money changed hands, it stayed on your
 * device" record that the sovereignty ratio depends on. No clock, no I/O: `timestampMillis` and
 * `latencyMs` are supplied by the caller.
 */
object LedgerCapture {

    /** Provenance for a *single* turn follows its tier: on-device → LOCAL, anything else → CLOUD. */
    fun provenanceFor(tier: Tier): Provenance = when (tier) {
        Tier.ON_DEVICE -> Provenance.LOCAL
        Tier.RUNNER, Tier.CLOUD -> Provenance.CLOUD
    }

    /** Build the ledger entry for one completed single-agent turn. Negatives are floored to 0. */
    fun singleTurn(
        timestampMillis: Long,
        chatId: String,
        nodeId: String,
        projectId: String?,
        model: String,
        provider: String,
        tier: Tier,
        inputTokens: Long,
        outputTokens: Long,
        estCostMinor: Long,
        latencyMs: Long,
        status: Status,
        estimated: Boolean,
    ): LedgerEntry = LedgerEntry(
        timestampMillis = timestampMillis,
        projectId = projectId,
        chatId = chatId,
        nodeId = nodeId,
        model = model,
        provider = provider,
        provenance = provenanceFor(tier),
        interactionModel = InteractionModel.SINGLE,
        councilMemberId = null,
        inputTokens = inputTokens.coerceAtLeast(0),
        outputTokens = outputTokens.coerceAtLeast(0),
        estCostMinor = estCostMinor.coerceAtLeast(0),
        latencyMs = latencyMs.coerceAtLeast(0),
        tier = tier,
        status = status,
        estimated = estimated,
    )
}
