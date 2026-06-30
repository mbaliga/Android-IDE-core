package dev.aarso.domain.device

/**
 * Heuristic "will this model fit in RAM?" gate (handoff §1: RAM is the gating
 * factor; §3: 7–14B at Q4/Q5 on a high-RAM device leaves room for KV cache + OS).
 *
 * This is an **engineering safety check** to avoid downloading something that will
 * OOM — not the on-device tok/s benchmark (§10.6), which is the real performance
 * gate and only exists once the native engine runs. Constants are documented and
 * adjustable, deliberately conservative.
 */
enum class FitVerdict { FITS, TIGHT, WONT_FIT }

data class FitResult(
    val verdict: FitVerdict,
    val requiredBytes: Long,
    val usableBytes: Long,
    val reason: String,
)

object ModelFit {

    /** RAM Android + other apps keep resident; not available to a model. */
    const val OS_RESERVE_BYTES = 3_500_000_000L

    /** Working set beyond the weights: KV cache, context, runtime buffers. */
    const val RUNTIME_OVERHEAD_BYTES = 1_500_000_000L

    /** Quantized weights are roughly the file size; small dequant/alloc margin. */
    private const val WEIGHTS_FACTOR = 1.15

    fun estimateRequired(fileBytes: Long): Long =
        (fileBytes * WEIGHTS_FACTOR).toLong() + RUNTIME_OVERHEAD_BYTES

    fun check(fileBytes: Long, device: DeviceSpec): FitResult {
        val usable = (device.totalRamBytes - OS_RESERVE_BYTES).coerceAtLeast(0)
        val required = estimateRequired(fileBytes)
        val verdict = when {
            required <= usable * 0.8 -> FitVerdict.FITS
            required <= usable -> FitVerdict.TIGHT
            else -> FitVerdict.WONT_FIT
        }
        val reason = when (verdict) {
            FitVerdict.FITS -> "fits comfortably"
            FitVerdict.TIGHT -> "tight — close other apps; may be slow"
            FitVerdict.WONT_FIT -> "needs ~${gb(required)} GB, only ~${gb(usable)} GB usable"
        }
        return FitResult(verdict, required, usable, reason)
    }

    private fun gb(bytes: Long): String = "%.1f".format(bytes / 1_000_000_000.0)
}
