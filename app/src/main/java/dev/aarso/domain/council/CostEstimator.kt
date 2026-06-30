package dev.aarso.domain.council

/**
 * Estimates the [Cost] of running an expert step, to feed the escalation gates
 * (docs/design/workflow-builder.md). Pre-run, so it uses the standard rough
 * token heuristic (≈ chars/4 — the same estimate the cloud engines use) × a
 * per-model profile. Pure; JVM-tested.
 */
data class ModelCostProfile(
    /** Cents per 1k tokens (0 for on-device — no money changes hands). */
    val centsPer1kTokens: Int,
    /** Rough throughput, for the time dimension. */
    val tokensPerSecond: Int,
) {
    companion object {
        /** On-device: free; local throughput. */
        val ON_DEVICE = ModelCostProfile(centsPer1kTokens = 0, tokensPerSecond = 30)
        /** A conservative cloud default until per-provider pricing is wired. */
        val CLOUD_DEFAULT = ModelCostProfile(centsPer1kTokens = 1, tokensPerSecond = 60)
    }
}

object CostEstimator {

    /** Estimate one step over [text] (prompt + expected output) on [profile]. */
    fun estimate(text: String, profile: ModelCostProfile, calls: Int = 1): Cost {
        val tokens = (text.length / 4).toLong().coerceAtLeast(1)
        return Cost(
            tokens = tokens,
            seconds = if (profile.tokensPerSecond > 0) tokens / profile.tokensPerSecond else 0,
            moneyCents = tokens * profile.centsPer1kTokens / 1000,
            calls = calls,
        )
    }
}
