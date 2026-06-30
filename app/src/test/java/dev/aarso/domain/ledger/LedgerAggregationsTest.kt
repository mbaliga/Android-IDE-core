package dev.aarso.domain.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerAggregationsTest {

    // Fixed epoch millis (UTC) so bucket keys are deterministic (verified with java.time):
    //   2026-03-12T10:00:00Z = 1773309600000  (ISO week 11)
    //   2026-03-14T10:00:00Z = 1773482400000  (ISO week 11)
    //   2026-03-14T22:00:00Z = 1773525600000  (same UTC day, +6h offset crosses into 03-15)
    //   2026-04-02T10:00:00Z = 1775124000000  (next month, ISO week 14)
    private val tMar12 = 1773309600000L
    private val tMar14 = 1773482400000L
    private val tMar14Late = 1773525600000L
    private val tApr02 = 1775124000000L

    private fun e(
        ts: Long = tMar14,
        project: String? = "proj",
        chat: String = "c1",
        node: String = "n1",
        model: String = "qwen2.5-7b",
        provider: String = "on-device",
        provenance: Provenance = Provenance.LOCAL,
        interaction: InteractionModel = InteractionModel.SINGLE,
        member: String? = null,
        inTok: Long = 100,
        outTok: Long = 100,
        cost: Long = 0,
        estimated: Boolean = false,
    ) = LedgerEntry(
        timestampMillis = ts, projectId = project, chatId = chat, nodeId = node,
        model = model, provider = provider, provenance = provenance,
        interactionModel = interaction, councilMemberId = member,
        inputTokens = inTok, outputTokens = outTok, estCostMinor = cost,
        latencyMs = 500, tier = if (provenance == Provenance.LOCAL) Tier.ON_DEVICE else Tier.CLOUD,
        status = Status.COMPLETE, estimated = estimated,
    )

    @Test fun `totalTokens on an entry sums input and output`() {
        assertEquals(200L, e(inTok = 120, outTok = 80).totalTokens)
    }

    @Test fun `totals sums tokens cost and turns`() {
        val t = LedgerAggregations.totals(
            listOf(e(inTok = 100, outTok = 50, cost = 10), e(inTok = 200, outTok = 25, cost = 5)),
        )
        assertEquals(300L, t.inputTokens)
        assertEquals(75L, t.outputTokens)
        assertEquals(375L, t.totalTokens)
        assertEquals(15L, t.estCostMinor)
        assertEquals(2, t.turns)
    }

    @Test fun `totals of empty is all zero`() {
        val t = LedgerAggregations.totals(emptyList())
        assertEquals(0L, t.totalTokens)
        assertEquals(0, t.turns)
    }

    @Test fun `provenance split computes sovereignty ratio 0_7 for 700 local 300 cloud`() {
        // 700 on-device tokens, 300 cloud tokens.
        val entries = listOf(
            e(provenance = Provenance.LOCAL, provider = "on-device", inTok = 400, outTok = 300, cost = 0),
            e(provenance = Provenance.CLOUD, provider = "anthropic", inTok = 200, outTok = 100, cost = 42, estimated = true),
        )
        val s = LedgerAggregations.provenanceSplit(entries)
        assertEquals(700L, s.onDeviceTokens)
        assertEquals(300L, s.cloudTokens)
        assertEquals(0.7, s.sovereigntyRatio, 1e-9)
        assertEquals(0L, s.onDeviceCostMinor)
        assertEquals(42L, s.cloudCostMinor)
    }

    @Test fun `provenance split of empty is fully sovereign`() {
        assertEquals(1.0, LedgerAggregations.provenanceSplit(emptyList()).sovereigntyRatio, 1e-9)
    }

    @Test fun `provenance split treats MIXED aggregate entry as cloud`() {
        val s = LedgerAggregations.provenanceSplit(
            listOf(e(provenance = Provenance.MIXED, inTok = 50, outTok = 50, cost = 7)),
        )
        assertEquals(0L, s.onDeviceTokens)
        assertEquals(100L, s.cloudTokens)
        assertEquals(7L, s.cloudCostMinor)
        assertEquals(0.0, s.sovereigntyRatio, 1e-9)
    }

    @Test fun `byProvider rolls up and sorts by total tokens descending`() {
        val entries = listOf(
            e(provider = "anthropic", provenance = Provenance.CLOUD, inTok = 100, outTok = 100, cost = 10),
            e(provider = "on-device", inTok = 500, outTok = 400),
            e(provider = "anthropic", provenance = Provenance.CLOUD, inTok = 50, outTok = 50, cost = 5),
        )
        val m = LedgerAggregations.byProvider(entries)
        val keys = m.keys.toList()
        assertEquals(listOf("on-device", "anthropic"), keys) // 900 > 300
        val anthropic = m["anthropic"]!!
        assertEquals(150L, anthropic.inputTokens)
        assertEquals(150L, anthropic.outputTokens)
        assertEquals(15L, anthropic.estCostMinor)
        assertEquals(2, anthropic.calls)
    }

    @Test fun `byModel reports provenance per model and marks mixed`() {
        val entries = listOf(
            e(model = "qwen2.5-7b", provenance = Provenance.LOCAL, inTok = 100, outTok = 100),
            e(model = "qwen2.5-7b", provenance = Provenance.CLOUD, provider = "openai-compat", inTok = 100, outTok = 100, cost = 9),
            e(model = "claude", provenance = Provenance.CLOUD, provider = "anthropic", inTok = 10, outTok = 10, cost = 3),
        )
        val m = LedgerAggregations.byModel(entries)
        assertEquals(listOf("qwen2.5-7b", "claude"), m.keys.toList()) // 400 > 20
        assertEquals(Provenance.MIXED, m["qwen2.5-7b"]!!.provenance)
        assertEquals(Provenance.CLOUD, m["claude"]!!.provenance)
        assertEquals(2, m["qwen2.5-7b"]!!.calls)
        assertEquals(400L, m["qwen2.5-7b"]!!.totalTokens)
    }

    @Test fun `byInteractionModel counts and sums per kind`() {
        val entries = listOf(
            e(interaction = InteractionModel.SINGLE, inTok = 50, outTok = 50),
            e(interaction = InteractionModel.COUNCIL_PERSONAS, member = "m1", inTok = 100, outTok = 0, cost = 4),
            e(interaction = InteractionModel.COUNCIL_PERSONAS, member = "m2", inTok = 100, outTok = 0, cost = 4),
        )
        val m = LedgerAggregations.byInteractionModel(entries)
        assertEquals(1, m[InteractionModel.SINGLE]!!.entries)
        assertEquals(100L, m[InteractionModel.SINGLE]!!.tokens)
        assertEquals(2, m[InteractionModel.COUNCIL_PERSONAS]!!.entries)
        assertEquals(200L, m[InteractionModel.COUNCIL_PERSONAS]!!.tokens)
        assertEquals(8L, m[InteractionModel.COUNCIL_PERSONAS]!!.estCostMinor)
        assertFalse(m.containsKey(InteractionModel.COUNCIL_MODELS))
    }

    @Test fun `counts distinct chats projects and council rounds`() {
        val entries = listOf(
            e(chat = "c1", project = "p1"),
            e(chat = "c1", project = "p1"),
            e(chat = "c2", project = null),
            // one council round: two members sharing chat + timestamp
            e(chat = "c3", project = "p2", ts = tMar12, member = "m1", interaction = InteractionModel.COUNCIL_PERSONAS),
            e(chat = "c3", project = "p2", ts = tMar12, member = "m2", interaction = InteractionModel.COUNCIL_PERSONAS),
            // a separate later round in same chat
            e(chat = "c3", project = "p2", ts = tApr02, member = "m1", interaction = InteractionModel.COUNCIL_PERSONAS),
        )
        val c = LedgerAggregations.counts(entries)
        assertEquals(3, c.distinctChats)      // c1, c2, c3
        assertEquals(2, c.distinctProjects)   // p1, p2 (null excluded)
        assertEquals(2, c.councilRounds)      // (c3,tMar12) and (c3,tApr02)
        assertEquals(6, c.totalEntries)
    }

    @Test fun `timeBuckets DAY uses deterministic key at zero offset`() {
        val b = LedgerAggregations.timeBuckets(
            listOf(e(ts = tMar14, inTok = 100, outTok = 100)),
            zoneOffsetHours = 0, bucket = LedgerAggregations.Bucket.DAY,
        )
        assertEquals(1, b.size)
        assertEquals("2026-03-14", b[0].bucketKey)
        assertEquals(200L, b[0].tokens)
    }

    @Test fun `timeBuckets DAY respects positive zone offset crossing midnight`() {
        // 2026-03-14T22:00Z at +6h is 2026-03-15T04:00 local.
        val b = LedgerAggregations.timeBuckets(
            listOf(e(ts = tMar14Late)),
            zoneOffsetHours = 6, bucket = LedgerAggregations.Bucket.DAY,
        )
        assertEquals("2026-03-15", b[0].bucketKey)
    }

    @Test fun `timeBuckets DAY groups merges and orders ascending`() {
        val entries = listOf(
            e(ts = tApr02, inTok = 10, outTok = 0),
            e(ts = tMar14, inTok = 20, outTok = 0),
            e(ts = tMar14, inTok = 30, outTok = 0),
        )
        val b = LedgerAggregations.timeBuckets(entries, 0, LedgerAggregations.Bucket.DAY)
        assertEquals(2, b.size)
        assertEquals("2026-03-14", b[0].bucketKey) // ascending: March before April
        assertEquals(50L, b[0].tokens)             // 20 + 30 merged
        assertEquals("2026-04-02", b[1].bucketKey)
        assertEquals(10L, b[1].tokens)
    }

    @Test fun `timeBuckets MONTH key is year dash month`() {
        val b = LedgerAggregations.timeBuckets(
            listOf(e(ts = tMar14), e(ts = tApr02)),
            0, LedgerAggregations.Bucket.MONTH,
        )
        assertEquals(listOf("2026-03", "2026-04"), b.map { it.bucketKey })
    }

    @Test fun `timeBuckets WEEK uses ISO week key and groups same week`() {
        // tMar12 and tMar14 are both in ISO week 11 of 2026.
        val b = LedgerAggregations.timeBuckets(
            listOf(e(ts = tMar12, inTok = 100, outTok = 0), e(ts = tMar14, inTok = 50, outTok = 0)),
            0, LedgerAggregations.Bucket.WEEK,
        )
        assertEquals(1, b.size)
        assertEquals("2026-W11", b[0].bucketKey)
        assertEquals(150L, b[0].tokens)
    }

    @Test fun `timeBuckets onDeviceFraction is per-bucket sovereignty`() {
        val entries = listOf(
            e(ts = tMar14, provenance = Provenance.LOCAL, inTok = 80, outTok = 0),
            e(ts = tMar14, provenance = Provenance.CLOUD, provider = "anthropic", inTok = 20, outTok = 0, cost = 3),
        )
        val b = LedgerAggregations.timeBuckets(entries, 0, LedgerAggregations.Bucket.DAY)
        assertEquals(0.8, b[0].onDeviceFraction, 1e-9)
        assertEquals(3L, b[0].costMinor)
    }

    @Test fun `estimatedFlagged counts only estimated entries`() {
        val entries = listOf(
            e(inTok = 100, outTok = 0, cost = 5, estimated = true),
            e(inTok = 50, outTok = 0, cost = 2, estimated = true),
            e(inTok = 999, outTok = 0, cost = 0, estimated = false),
        )
        val f = LedgerAggregations.estimatedFlagged(entries)
        assertEquals(2, f.count)
        assertEquals(150L, f.tokens)
        assertEquals(7L, f.estCostMinor)
    }

    @Test fun `estimatedFlagged of all-measured is zero`() {
        val f = LedgerAggregations.estimatedFlagged(listOf(e(estimated = false)))
        assertEquals(0, f.count)
        assertTrue(f.tokens == 0L)
    }
}
