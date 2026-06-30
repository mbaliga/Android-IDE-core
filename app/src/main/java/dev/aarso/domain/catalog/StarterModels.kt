package dev.aarso.domain.catalog

import dev.aarso.domain.device.DeviceSpec
import dev.aarso.domain.device.FitVerdict
import dev.aarso.domain.device.ModelFit

/**
 * Picks the one model the first-run setup card offers. Pure and emergent from
 * [ModelFit] — no hardcoded device table.
 *
 * The starter should be genuinely useful but quick to fetch and comfortably
 * within RAM, so: the largest entry that FITS inside the 3–8B band; if the band
 * is empty, the largest entry that FITS at all; null when nothing fits (the UI
 * then points at the catalog/custom import instead of recommending). TIGHT is
 * never recommended as a default.
 */
object StarterModels {

    private const val BAND_MIN_B = 2.5
    private const val BAND_MAX_B = 8.5

    fun recommend(catalog: List<CatalogModel>, device: DeviceSpec): CatalogModel? {
        val fitting = catalog.filter {
            ModelFit.check(it.sizeBytes, device).verdict == FitVerdict.FITS
        }
        val band = fitting.filter { paramsBillions(it.params) in BAND_MIN_B..BAND_MAX_B }
        return (band.ifEmpty { fitting }).maxByOrNull { it.sizeBytes }
    }

    /** "1.5B" → 1.5, "8B" → 8.0, "30B-A3B" → 30.0; unparseable → 0. */
    internal fun paramsBillions(params: String): Double =
        params.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
}
