package dev.fonebrew.domain.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkStatsTest {

    @Test fun oneRepeatCollapsesEverythingAndHasNoDrift() {
        val s = BenchmarkStats.of(listOf(12.5))
        assertEquals(1, s.known)
        assertEquals(12.5, s.median!!, 0.0)
        assertEquals(12.5, s.p10!!, 0.0)
        assertEquals(12.5, s.p90!!, 0.0)
        assertEquals(12.5, s.min!!, 0.0)
        assertEquals(12.5, s.max!!, 0.0)
        assertNull(s.driftPercent)
    }

    @Test fun noValuesIsAllUnknownNotZero() {
        val s = BenchmarkStats.of(listOf(null, null))
        assertEquals(0, s.known); assertEquals(2, s.unknown)
        assertNull(s.median); assertNull(s.p10); assertNull(s.p90); assertNull(s.min); assertNull(s.max); assertNull(s.driftPercent)
        assertEquals(0, BenchmarkStats.of(emptyList()).known)
    }

    @Test fun unknownRepeatsAreExcludedAndCounted() {
        val s = BenchmarkStats.of(listOf(10.0, null, 30.0))
        assertEquals(2, s.known); assertEquals(1, s.unknown)
        assertEquals(20.0, s.median!!, 1e-9)
        assertEquals(200.0, s.driftPercent!!, 1e-9) // first known 10 -> last known 30
    }

    @Test fun oddAndEvenMedian() {
        assertEquals(3.0, BenchmarkStats.of(listOf(5.0, 1.0, 3.0)).median!!, 0.0)
        assertEquals(2.5, BenchmarkStats.of(listOf(4.0, 1.0, 2.0, 3.0)).median!!, 0.0)
    }

    @Test fun nearestRankPercentilesOverTenValues() {
        val s = BenchmarkStats.of((1..10).map { it.toDouble() })
        assertEquals(1.0, s.p10!!, 0.0)
        assertEquals(9.0, s.p90!!, 0.0)
        assertEquals(1.0, s.min!!, 0.0)
        assertEquals(10.0, s.max!!, 0.0)
    }

    @Test fun driftIsFirstToLastInRepeatOrderNotSortedOrder() {
        val s = BenchmarkStats.of(listOf(20.0, 25.0, 15.0))
        assertEquals(-25.0, s.driftPercent!!, 1e-9)
    }

    @Test fun driftUnknownWhenFirstIsZero() {
        assertNull(BenchmarkStats.of(listOf(0.0, 5.0)).driftPercent)
    }

    @Test fun aKnownZeroIsAValueNotUnknown() {
        val s = BenchmarkStats.of(listOf(0.0))
        assertEquals(1, s.known)
        assertEquals(0.0, s.median!!, 0.0)
    }
}
