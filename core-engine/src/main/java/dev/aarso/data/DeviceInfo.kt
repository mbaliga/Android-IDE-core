package dev.aarso.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.aarso.domain.device.DeviceSpec

/** Reads the live hardware spec used to gate model downloads (handoff §1). */
object DeviceInfo {
    fun read(context: Context): DeviceSpec {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceSpec(
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
        )
    }
}
