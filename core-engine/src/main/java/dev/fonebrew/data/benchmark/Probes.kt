package dev.fonebrew.data.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import dev.fonebrew.domain.benchmark.BatteryProbe
import dev.fonebrew.domain.benchmark.BenchmarkClock
import dev.fonebrew.domain.benchmark.DeviceFingerprint
import dev.fonebrew.domain.benchmark.MemoryProbe
import dev.fonebrew.domain.benchmark.Reading
import dev.fonebrew.domain.benchmark.ThermalLevel
import dev.fonebrew.domain.benchmark.ThermalProbe
import java.io.File

/**
 * Platform probes. Each returns [Reading.Unknown] rather than a guess when the platform does not
 * provide the value. The thin Android calls are injected as lambdas so the Unknown handling is
 * JVM-tested; what the platform actually reports on a given phone is owner-verified only.
 */
object Probes {

    fun thermal(readPlatformStatus: () -> Int): ThermalProbe = ThermalProbe {
        try {
            ThermalLevel.fromPlatform(readPlatformStatus())
        } catch (e: Exception) {
            Reading.Unknown("thermal status not available (${e::class.simpleName})")
        }
    }

    fun memory(readTotalPssKb: () -> Int): MemoryProbe = MemoryProbe {
        try {
            val kb = readTotalPssKb()
            if (kb > 0) Reading.Known(kb.toLong()) else Reading.Unknown("platform returned no PSS")
        } catch (e: Exception) {
            Reading.Unknown("memory info not available (${e::class.simpleName})")
        }
    }

    fun battery(readLevelAndScale: () -> Pair<Int, Int>?): BatteryProbe = BatteryProbe {
        try {
            percent(readLevelAndScale())
        } catch (e: Exception) {
            Reading.Unknown("battery info not available (${e::class.simpleName})")
        }
    }

    /** level/scale from the sticky battery intent; -1 or a non-positive scale means "not provided". */
    fun percent(levelAndScale: Pair<Int, Int>?): Reading<Int> {
        val (level, scale) = levelAndScale ?: return Reading.Unknown("no battery broadcast")
        if (level < 0 || scale <= 0) return Reading.Unknown("battery level not provided")
        return Reading.Known((level * 100 / scale).coerceIn(0, 100))
    }

    fun androidThermal(context: Context): ThermalProbe = thermal {
        context.getSystemService(PowerManager::class.java).currentThermalStatus
    }

    fun androidMemory(): MemoryProbe = memory {
        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
    }

    fun androidBattery(context: Context): BatteryProbe = battery {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) to it.getIntExtra(BatteryManager.EXTRA_SCALE, -1) }
    }

    fun systemClock(): BenchmarkClock = object : BenchmarkClock {
        override fun nanos(): Long = System.nanoTime()
        override fun wallMillis(): Long = System.currentTimeMillis()
    }

    fun androidFingerprint(): DeviceFingerprint = DeviceFingerprint(
        deviceModel = Build.MODEL?.takeIf { it.isNotBlank() },
        androidRelease = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() },
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS?.firstOrNull(),
        cpuFeatures = CpuFeatureString.describe(
            runCatching { File("/proc/cpuinfo").takeIf { it.canRead() }?.readText() }.getOrNull(),
        ),
    )
}
