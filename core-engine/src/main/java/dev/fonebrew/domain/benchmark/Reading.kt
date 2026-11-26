package dev.fonebrew.domain.benchmark

/**
 * A probe or engine answer that may not exist. [Unknown] is a first-class value, never a stand-in
 * for zero or a guess: the harness shows it as "unknown" and keeps the reason.
 */
sealed class Reading<out T> {
    data class Known<T>(val value: T) : Reading<T>()
    data class Unknown(val why: String) : Reading<Nothing>()

    val valueOrNull: T? get() = (this as? Known<T>)?.value
    val whyUnknown: String? get() = (this as? Unknown)?.why
}

/** Wall + monotonic time, injected so [BenchmarkRunner]'s arithmetic is exactly testable. */
interface BenchmarkClock {
    /** Monotonic, for durations. */
    fun nanos(): Long

    /** Wall clock, for the human-facing started/ended stamps only. */
    fun wallMillis(): Long
}

/** Android's thermal status ladder (PowerManager.THERMAL_STATUS_*), index == the platform int. */
enum class ThermalLevel {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;

    /** SEVERE or worse: the benchmark stops and says so. */
    val isAbortLevel: Boolean get() = this >= SEVERE

    companion object {
        /** Maps the platform int; anything outside 0..6 is Unknown rather than clamped. */
        fun fromPlatform(status: Int): Reading<ThermalLevel> =
            entries.getOrNull(status)?.let { Reading.Known(it) }
                ?: Reading.Unknown("unrecognised thermal status $status")
    }
}

/** Thermal state of the device now. Android: PowerManager.getCurrentThermalStatus. */
fun interface ThermalProbe {
    fun read(): Reading<ThermalLevel>
}

/** Proportional set size of this process in KiB. Android: Debug.MemoryInfo.getTotalPss. */
fun interface MemoryProbe {
    fun pssKb(): Reading<Long>
}

/** Battery charge 0..100. Android: sticky ACTION_BATTERY_CHANGED. */
fun interface BatteryProbe {
    fun percent(): Reading<Int>
}
