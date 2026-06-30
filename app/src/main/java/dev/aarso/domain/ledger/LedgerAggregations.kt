package dev.aarso.domain.ledger

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * The **backing logic for the "Myself" usage views** (Doc 07). Every function here is a
 * pure, deterministic fold over `List<LedgerEntry>` — the UI is a thin reader, and the
 * whole surface is an *on-device aggregation* (binding rule: no telemetry, no server
 * rollup). Nothing reads a clock or a random source; time bucketing is derived purely
 * from each entry's `timestampMillis` plus an explicit zone offset.
 *
 * Two design choices worth stating, both on-thesis:
 *  - **Sovereignty is a first-class number.** [provenanceSplit] reports the fraction of
 *    your tokens that stayed on your own device — the headline of the "Myself" view.
 *  - **Estimated ≠ measured.** [estimatedFlagged] keeps the honesty visible: how much of
 *    the cost figure is a guess versus a provider-reported truth.
 *
 * All money is in the smallest currency unit (paise / cents). On-device work costs 0.
 */
object LedgerAggregations {

    // --- totals -----------------------------------------------------------------

    /** Grand totals across every entry. `turns` is the entry count (council members count separately). */
    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        val estCostMinor: Long,
        val turns: Int,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    /** Sum tokens, cost and entry count over [entries]. */
    fun totals(entries: List<LedgerEntry>): Totals = Totals(
        inputTokens = entries.sumOf { it.inputTokens },
        outputTokens = entries.sumOf { it.outputTokens },
        estCostMinor = entries.sumOf { it.estCostMinor },
        turns = entries.size,
    )

    // --- provenance split (the sovereignty headline) ----------------------------

    /**
     * On-device vs cloud, plus the **sovereignty ratio** — the fraction of tokens that
     * stayed local, in `[0,1]` (1.0 when everything ran on-device, 0.5 if exactly half
     * your tokens went to a cloud provider). On-device cost is always 0. A [Provenance.MIXED]
     * entry (only ever an aggregate artefact) is counted on the cloud side conservatively,
     * since it implies some work left the device.
     */
    data class ProvenanceSplit(
        val onDeviceTokens: Long,
        val cloudTokens: Long,
        val onDeviceCostMinor: Long,
        val cloudCostMinor: Long,
        val sovereigntyRatio: Double,
    ) {
        val totalTokens: Long get() = onDeviceTokens + cloudTokens
    }

    fun provenanceSplit(entries: List<LedgerEntry>): ProvenanceSplit {
        var onDeviceTokens = 0L
        var cloudTokens = 0L
        var cloudCost = 0L
        for (e in entries) {
            if (e.provenance == Provenance.LOCAL) {
                onDeviceTokens += e.totalTokens
            } else {
                cloudTokens += e.totalTokens
                cloudCost += e.estCostMinor
            }
        }
        val total = onDeviceTokens + cloudTokens
        val ratio = if (total == 0L) 1.0 else onDeviceTokens.toDouble() / total.toDouble()
        return ProvenanceSplit(
            onDeviceTokens = onDeviceTokens,
            cloudTokens = cloudTokens,
            onDeviceCostMinor = 0L,
            cloudCostMinor = cloudCost,
            sovereigntyRatio = ratio,
        )
    }

    // --- rollups by provider / model --------------------------------------------

    /** Per-key usage rollup. `calls` is the number of entries that rolled into this key. */
    data class ProviderRollup(
        val inputTokens: Long,
        val outputTokens: Long,
        val estCostMinor: Long,
        val calls: Int,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    /** A model rollup carries the same shape plus the model's effective [Provenance]. */
    data class ModelRollup(
        val inputTokens: Long,
        val outputTokens: Long,
        val estCostMinor: Long,
        val calls: Int,
        val provenance: Provenance,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    /** Provider → [ProviderRollup], ordered by total tokens descending (heaviest first). */
    fun byProvider(entries: List<LedgerEntry>): Map<String, ProviderRollup> {
        val acc = LinkedHashMap<String, ProviderRollup>()
        for (e in entries) {
            val cur = acc[e.provider]
            acc[e.provider] = if (cur == null) {
                ProviderRollup(e.inputTokens, e.outputTokens, e.estCostMinor, 1)
            } else {
                ProviderRollup(
                    cur.inputTokens + e.inputTokens,
                    cur.outputTokens + e.outputTokens,
                    cur.estCostMinor + e.estCostMinor,
                    cur.calls + 1,
                )
            }
        }
        return acc.entries
            .sortedByDescending { it.value.totalTokens }
            .associateTo(LinkedHashMap()) { it.key to it.value }
    }

    /**
     * Model → [ModelRollup], ordered by total tokens descending. The rollup's
     * [Provenance] is the model's effective provenance: [Provenance.LOCAL] or
     * [Provenance.CLOUD] if every entry agrees, else [Provenance.MIXED].
     */
    fun byModel(entries: List<LedgerEntry>): Map<String, ModelRollup> {
        val acc = LinkedHashMap<String, ModelRollup>()
        for (e in entries) {
            val cur = acc[e.model]
            acc[e.model] = if (cur == null) {
                ModelRollup(e.inputTokens, e.outputTokens, e.estCostMinor, 1, e.provenance)
            } else {
                val prov = if (cur.provenance == e.provenance) cur.provenance else Provenance.MIXED
                ModelRollup(
                    cur.inputTokens + e.inputTokens,
                    cur.outputTokens + e.outputTokens,
                    cur.estCostMinor + e.estCostMinor,
                    cur.calls + 1,
                    prov,
                )
            }
        }
        return acc.entries
            .sortedByDescending { it.value.totalTokens }
            .associateTo(LinkedHashMap()) { it.key to it.value }
    }

    // --- by interaction model ---------------------------------------------------

    /** Counts + tokens grouped by [InteractionModel] (single vs the two council shapes). */
    data class InteractionRollup(val entries: Int, val tokens: Long, val estCostMinor: Long)

    fun byInteractionModel(entries: List<LedgerEntry>): Map<InteractionModel, InteractionRollup> {
        val acc = LinkedHashMap<InteractionModel, InteractionRollup>()
        for (e in entries) {
            val cur = acc[e.interactionModel] ?: InteractionRollup(0, 0, 0)
            acc[e.interactionModel] = InteractionRollup(
                cur.entries + 1,
                cur.tokens + e.totalTokens,
                cur.estCostMinor + e.estCostMinor,
            )
        }
        return acc
    }

    // --- counts -----------------------------------------------------------------

    /**
     * Top-line tallies for the "Myself" header. `councilRounds` counts distinct council
     * *groups*: entries that carry a `councilMemberId`, grouped by `chatId` + the exact
     * `timestampMillis` they were written with (the writer stamps a fan-out with one
     * timestamp), each group being one round of deliberation.
     */
    data class Counts(
        val distinctChats: Int,
        val distinctProjects: Int,
        val councilRounds: Int,
        val totalEntries: Int,
    )

    fun counts(entries: List<LedgerEntry>): Counts {
        val chats = HashSet<String>()
        val projects = HashSet<String>()
        val councilGroups = HashSet<Pair<String, Long>>()
        for (e in entries) {
            chats.add(e.chatId)
            e.projectId?.let { projects.add(it) }
            if (e.councilMemberId != null) {
                councilGroups.add(e.chatId to e.timestampMillis)
            }
        }
        return Counts(
            distinctChats = chats.size,
            distinctProjects = projects.size,
            councilRounds = councilGroups.size,
            totalEntries = entries.size,
        )
    }

    // --- time buckets -----------------------------------------------------------

    /** Calendar granularity for the usage-over-time view. */
    enum class Bucket { DAY, WEEK, MONTH }

    /**
     * One point on the time series. [bucketKey] is a deterministic, sortable label:
     *  - `DAY`   → `"YYYY-MM-DD"`
     *  - `WEEK`  → `"YYYY-Www"` (ISO week, e.g. `"2026-W14"`)
     *  - `MONTH` → `"YYYY-MM"`
     * [onDeviceFraction] is the sovereignty ratio *within that bucket*.
     */
    data class TimeBucket(
        val bucketKey: String,
        val tokens: Long,
        val costMinor: Long,
        val onDeviceFraction: Double,
    )

    /**
     * Group [entries] into ordered [TimeBucket]s. The bucket key is derived purely from
     * each entry's `timestampMillis` interpreted at the fixed [zoneOffsetHours] offset —
     * `Instant.ofEpochMilli(t).atOffset(ZoneOffset.ofHours(zoneOffsetHours))` — so the
     * result is fully deterministic and never reads the host clock or zone. The returned
     * list is sorted ascending by key (lexicographic order matches chronological order
     * for all three key formats).
     */
    fun timeBuckets(entries: List<LedgerEntry>, zoneOffsetHours: Int, bucket: Bucket): List<TimeBucket> {
        val offset = ZoneOffset.ofHours(zoneOffsetHours)
        // key -> [tokens, cost, onDeviceTokens]
        val acc = LinkedHashMap<String, LongArray>()
        for (e in entries) {
            val dt = Instant.ofEpochMilli(e.timestampMillis).atOffset(offset)
            val key = when (bucket) {
                Bucket.DAY -> "%04d-%02d-%02d".format(dt.year, dt.monthValue, dt.dayOfMonth)
                Bucket.WEEK -> "%04d-W%02d".format(
                    dt.get(IsoFields.WEEK_BASED_YEAR),
                    dt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                )
                Bucket.MONTH -> "%04d-%02d".format(dt.year, dt.monthValue)
            }
            val cell = acc.getOrPut(key) { LongArray(3) }
            cell[0] += e.totalTokens
            cell[1] += e.estCostMinor
            if (e.provenance == Provenance.LOCAL) cell[2] += e.totalTokens
        }
        return acc.entries
            .sortedBy { it.key }
            .map { (key, cell) ->
                val frac = if (cell[0] == 0L) 1.0 else cell[2].toDouble() / cell[0].toDouble()
                TimeBucket(key, cell[0], cell[1], frac)
            }
    }

    // --- estimated vs measured (honesty) ----------------------------------------

    /** How much of the record is *estimated* cost rather than provider-measured. */
    data class EstimatedFlagged(val count: Int, val tokens: Long, val estCostMinor: Long)

    fun estimatedFlagged(entries: List<LedgerEntry>): EstimatedFlagged {
        var count = 0
        var tokens = 0L
        var cost = 0L
        for (e in entries) {
            if (e.estimated) {
                count++
                tokens += e.totalTokens
                cost += e.estCostMinor
            }
        }
        return EstimatedFlagged(count, tokens, cost)
    }
}
