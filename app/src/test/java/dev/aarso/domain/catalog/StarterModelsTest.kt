package dev.aarso.domain.catalog

import dev.aarso.domain.device.DeviceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarterModelsTest {

    private fun model(id: String, params: String, sizeBytes: Long) = CatalogModel(
        id = id,
        name = id,
        family = "test",
        params = params,
        quant = "Q4_K_M",
        sizeBytes = sizeBytes,
        contextWindow = 8192,
        hfRepo = "test/repo",
        hfFile = "$id.gguf",
    )

    private val catalog = listOf(
        model("tiny-1.5b", "1.5B", 1_100_000_000L),
        model("small-3b", "3B", 2_100_000_000L),
        model("mid-7b", "7B", 4_700_000_000L),
        model("mid-8b", "8B", 4_900_000_000L),
        model("big-14b", "14B", 9_000_000_000L),
        model("huge-30b", "30B-A3B", 18_000_000_000L),
    )

    private fun device(ramGb: Double) = DeviceSpec(
        totalRamBytes = (ramGb * 1_000_000_000).toLong(),
        availRamBytes = (ramGb * 1_000_000_000 / 2).toLong(),
        abis = listOf("arm64-v8a"),
    )

    @Test
    fun bigPhonePrefersTheLargestOfTheBandNotTheLargestThatFits() {
        // 24 GB: 14B fits too, but the starter stays in the 3-8B band.
        assertEquals("mid-8b", StarterModels.recommend(catalog, device(24.0))?.id)
    }

    @Test
    fun midPhoneGetsAMidModel() {
        // 12.3 GB: 7B (needs ~6.9) clears the FITS bar (~7.04), 8B (~7.1) does not.
        assertEquals("mid-7b", StarterModels.recommend(catalog, device(12.3))?.id)
    }

    @Test
    fun smallPhoneFallsBelowTheBand() {
        // 8 GB: only 1.5B genuinely FITS -> band empty -> largest fitting overall.
        assertEquals("tiny-1.5b", StarterModels.recommend(catalog, device(8.0))?.id)
    }

    @Test
    fun nothingFitsMeansNoRecommendation() {
        assertNull(StarterModels.recommend(catalog, device(3.5)))
    }

    @Test
    fun emptyCatalogMeansNoRecommendation() {
        assertNull(StarterModels.recommend(emptyList(), device(24.0)))
    }

    @Test
    fun paramsParsing() {
        assertEquals(1.5, StarterModels.paramsBillions("1.5B"), 0.0)
        assertEquals(30.0, StarterModels.paramsBillions("30B-A3B"), 0.0)
        assertEquals(0.0, StarterModels.paramsBillions("?"), 0.0)
    }
}
