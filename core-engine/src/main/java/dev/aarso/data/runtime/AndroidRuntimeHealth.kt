package dev.aarso.data.runtime

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import dev.aarso.domain.runtime.RuntimeThermalState

data class AndroidRuntimeHealth(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val thermalState: RuntimeThermalState,
)

class AndroidRuntimeHealthProbe(context: Context) {
    private val battery = context.applicationContext.getSystemService(BatteryManager::class.java)
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)

    fun inspect(): AndroidRuntimeHealth {
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val status = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        return AndroidRuntimeHealth(percent, charging, power.currentThermalStatus.toRuntimeThermalState())
    }
}

private fun Int.toRuntimeThermalState(): RuntimeThermalState = when (this) {
    PowerManager.THERMAL_STATUS_NONE -> RuntimeThermalState.NONE
    PowerManager.THERMAL_STATUS_LIGHT -> RuntimeThermalState.LIGHT
    PowerManager.THERMAL_STATUS_MODERATE -> RuntimeThermalState.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> RuntimeThermalState.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL -> RuntimeThermalState.CRITICAL
    PowerManager.THERMAL_STATUS_EMERGENCY -> RuntimeThermalState.EMERGENCY
    PowerManager.THERMAL_STATUS_SHUTDOWN -> RuntimeThermalState.SHUTDOWN
    else -> RuntimeThermalState.UNKNOWN
}
