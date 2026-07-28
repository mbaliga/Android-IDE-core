package dev.aarso.domain.device

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFitTest {

    // A high-RAM phone (24 GB, arm64).
    private val bigPhone = DeviceSpec(
        totalRamBytes = 24_000_000_000L,
        availRamBytes = 18_000_000_000L,
        abis = listOf("arm64-v8a"),
    )
    private val midPhone = DeviceSpec(
        totalRamBytes = 8_000_000_000L,
        availRamBytes = 4_000_000_000L,
        abis = listOf("arm64-v8a"),
    )

    @Test
    fun midSizedModelFitsOnBigPhone() {
        // ~10.5 GB Q5 14B
        assertEquals(FitVerdict.FITS, ModelFit.check(10_500_000_000L, bigPhone).verdict)
    }

    @Test
    fun largeModelWontFitOn8gb() {
        assertEquals(FitVerdict.WONT_FIT, ModelFit.check(18_000_000_000L, midPhone).verdict)
    }

    @Test
    fun smallModelIsTightOn8gb() {
        // ~2 GB 3B Q4 on 8 GB: usable ~4.5 GB, required ~3.8 GB -> TIGHT (conservative).
        assertEquals(FitVerdict.TIGHT, ModelFit.check(2_000_000_000L, midPhone).verdict)
    }

    @Test
    fun smallModelFitsComfortablyOnBigPhone() {
        assertEquals(FitVerdict.FITS, ModelFit.check(2_000_000_000L, bigPhone).verdict)
    }

    @Test
    fun requiredIncludesWeightsAndOverhead() {
        // 10 GB file -> 11.5 GB weights + 1.5 GB overhead = 13 GB
        assertEquals(13_000_000_000L, ModelFit.estimateRequired(10_000_000_000L))
    }
}
