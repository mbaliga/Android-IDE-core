package dev.fonebrew.domain.benchmark

import kotlin.math.ceil

/**
 * Summary of one metric across repeats. A null input is an Unknown repeat: it is excluded and
 * counted in [unknown], never treated as zero.
 *
 * [driftPercent] is (last - first) / first * 100 over the first and last *known* repeats, in repeat
 * order: a sustained-load indicator (a negative value on decode tok/s is what thermal throttling
 * looks like). It is null with fewer than two known repeats or a non-positive first value.
 * p10/p90 use nearest-rank, so with few repeats they collapse to min/max: they are shown anyway
 * with [known] beside them so the reader can see how thin they are.
 */
data class StatSummary(
    val known: Int,
    val unknown: Int,
    val median: Double?,
    val p10: Double?,
    val p90: Double?,
    val min: Double?,
    val max: Double?,
    val driftPercent: Double?,
)

object BenchmarkStats {

    fun of(values: List<Double?>): StatSummary {
        val known = values.filterNotNull()
        if (known.isEmpty()) {
            return StatSummary(0, values.size, null, null, null, null, null, null)
        }
        val sorted = known.sorted()
        val first = known.first()
        val last = known.last()
        val drift = if (known.size >= 2 && first > 0.0) (last - first) / first * 100.0 else null
        return StatSummary(
            known = known.size,
            unknown = values.size - known.size,
            median = median(sorted),
            p10 = nearestRank(sorted, 0.10),
            p90 = nearestRank(sorted, 0.90),
            min = sorted.first(),
            max = sorted.last(),
            driftPercent = drift,
        )
    }

    private fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Nearest-rank percentile: the smallest value with at least [p] of the data at or below it. */
    private fun nearestRank(sorted: List<Double>, p: Double): Double {
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
