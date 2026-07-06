package dev.aarso.domain.library

import dev.aarso.domain.ledger.InteractionModel
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.Provenance
import dev.aarso.domain.ledger.Status
import dev.aarso.domain.ledger.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFlairsTest {

    private val t0 = 1_700_000_000_000L

    private fun entry(
        ts: Long,
        model: String,
        provenance: Provenance,
        node: String = "$model-$ts-${provenance.name}",
    ) = LedgerEntry(
        timestampMillis = ts,
        projectId = null,
        chatId = "chat",
        nodeId = node,
        model = model,
        provider = if (provenance == Provenance.LOCAL) "on-device" else "anthropic",
        provenance = provenance,
        interactionModel = InteractionModel.SINGLE,
        councilMemberId = null,
        inputTokens = 10,
        outputTokens = 20,
        estCostMinor = 0,
        latencyMs = 5,
        tier = if (provenance == Provenance.LOCAL) Tier.ON_DEVICE else Tier.CLOUD,
        status = Status.COMPLETE,
        estimated = false,
    )

    @Test fun empty_entries_yield_empty_flairset() {
        val fs = ModelFlairs.deriveFlairs(emptyList())
        assertTrue(fs.flairs.isEmpty())
        assertEquals(0, fs.moreCount)
    }

    @Test fun single_entry_one_flair() {
        val fs = ModelFlairs.deriveFlairs(listOf(entry(t0, "qwen", Provenance.LOCAL)))
        assertEquals(1, fs.flairs.size)
        assertEquals(Flair("qwen", Provenance.LOCAL), fs.flairs[0])
        assertEquals(0, fs.moreCount)
    }

    @Test fun distinct_pairs_deduplicated() {
        // Same model+provenance across many turns collapses to one flair.
        val entries = listOf(
            entry(t0 + 1, "qwen", Provenance.LOCAL),
            entry(t0 + 2, "qwen", Provenance.LOCAL),
            entry(t0 + 3, "qwen", Provenance.LOCAL),
        )
        val fs = ModelFlairs.deriveFlairs(entries)
        assertEquals(1, fs.flairs.size)
        assertEquals(0, fs.moreCount)
    }

    @Test fun same_model_different_provenance_is_two_flairs() {
        val entries = listOf(
            entry(t0 + 1, "claude", Provenance.LOCAL),
            entry(t0 + 2, "claude", Provenance.CLOUD),
        )
        val fs = ModelFlairs.deriveFlairs(entries)
        assertEquals(2, fs.flairs.size)
        assertTrue(Flair("claude", Provenance.LOCAL) in fs.flairs)
        assertTrue(Flair("claude", Provenance.CLOUD) in fs.flairs)
    }

    @Test fun ordering_is_most_recent_first() {
        val entries = listOf(
            entry(t0 + 1, "old", Provenance.LOCAL),
            entry(t0 + 9, "new", Provenance.CLOUD),
            entry(t0 + 5, "mid", Provenance.LOCAL),
        )
        val fs = ModelFlairs.deriveFlairs(entries, max = 5)
        assertEquals(listOf("new", "mid", "old"), fs.flairs.map { it.model })
    }

    @Test fun bound_caps_visible_and_counts_remainder() {
        val entries = listOf(
            entry(t0 + 5, "a", Provenance.LOCAL),
            entry(t0 + 4, "b", Provenance.LOCAL),
            entry(t0 + 3, "c", Provenance.CLOUD),
            entry(t0 + 2, "d", Provenance.LOCAL),
            entry(t0 + 1, "e", Provenance.CLOUD),
        )
        val fs = ModelFlairs.deriveFlairs(entries, max = 3)
        assertEquals(3, fs.flairs.size)
        assertEquals(listOf("a", "b", "c"), fs.flairs.map { it.model })
        assertEquals(2, fs.moreCount) // d, e
    }

    @Test fun default_max_is_three() {
        val entries = (1..5).map { entry(t0 + it, "m$it", Provenance.LOCAL) }
        val fs = ModelFlairs.deriveFlairs(entries)
        assertEquals(3, fs.flairs.size)
        assertEquals(2, fs.moreCount)
    }

    @Test fun more_count_zero_when_under_cap() {
        val entries = listOf(
            entry(t0 + 1, "a", Provenance.LOCAL),
            entry(t0 + 2, "b", Provenance.CLOUD),
        )
        val fs = ModelFlairs.deriveFlairs(entries, max = 5)
        assertEquals(0, fs.moreCount)
    }

    @Test fun non_positive_max_hides_all_and_counts_total_distinct() {
        val entries = listOf(
            entry(t0 + 1, "a", Provenance.LOCAL),
            entry(t0 + 2, "b", Provenance.CLOUD),
        )
        val fs = ModelFlairs.deriveFlairs(entries, max = 0)
        assertTrue(fs.flairs.isEmpty())
        assertEquals(2, fs.moreCount)
        val negative = ModelFlairs.deriveFlairs(entries, max = -3)
        assertTrue(negative.flairs.isEmpty())
        assertEquals(2, negative.moreCount)
    }

    @Test fun provenance_local_and_cloud_preserved_on_flairs() {
        val entries = listOf(
            entry(t0 + 2, "local-model", Provenance.LOCAL),
            entry(t0 + 1, "cloud-model", Provenance.CLOUD),
        )
        val fs = ModelFlairs.deriveFlairs(entries, max = 5)
        assertEquals(Provenance.LOCAL, fs.flairs.first { it.model == "local-model" }.provenance)
        assertEquals(Provenance.CLOUD, fs.flairs.first { it.model == "cloud-model" }.provenance)
    }

    @Test fun timestamp_ties_break_deterministically() {
        // Two entries at the same millis -> tie-break on model then provenance name.
        val entries = listOf(
            entry(t0, "zeta", Provenance.LOCAL),
            entry(t0, "alpha", Provenance.CLOUD),
        )
        val a = ModelFlairs.deriveFlairs(entries, max = 5)
        val b = ModelFlairs.deriveFlairs(entries.reversed(), max = 5)
        assertEquals(a.flairs, b.flairs)
        // alpha sorts before zeta at the same timestamp.
        assertEquals(listOf("alpha", "zeta"), a.flairs.map { it.model })
    }

    @Test fun council_fanout_same_round_distinct_models_all_flaired() {
        // A council round: several entries share a near-identical timestamp, different models.
        val entries = listOf(
            entry(t0 + 100, "member-x", Provenance.LOCAL),
            entry(t0 + 100, "member-y", Provenance.CLOUD),
            entry(t0 + 100, "member-z", Provenance.LOCAL),
        )
        val fs = ModelFlairs.deriveFlairs(entries, max = 5)
        assertEquals(3, fs.flairs.size)
        assertEquals(0, fs.moreCount)
    }

    @Test fun derive_is_deterministic_across_runs() {
        val entries = listOf(
            entry(t0 + 3, "a", Provenance.LOCAL),
            entry(t0 + 2, "b", Provenance.CLOUD),
            entry(t0 + 1, "a", Provenance.CLOUD),
        )
        val r1 = ModelFlairs.deriveFlairs(entries, max = 2)
        val r2 = ModelFlairs.deriveFlairs(entries, max = 2)
        assertEquals(r1, r2)
    }
}
