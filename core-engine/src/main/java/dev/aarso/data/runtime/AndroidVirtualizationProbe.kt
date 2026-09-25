package dev.aarso.data.runtime

import android.content.Context
import android.os.Build

data class AndroidVirtualizationSupport(
    val apiLevel: Int,
    val frameworkFeaturePresent: Boolean,
) {
    /**
     * Feature presence means the Android build exposes AVF. It does NOT mean this app currently
     * has permission/API access to create arbitrary VMs, so RuntimeBroker must not infer READY
     * VM_ISOLATION from this value alone.
     */
    val avfPresent: Boolean get() = apiLevel >= 34 && frameworkFeaturePresent
}

class AndroidVirtualizationProbe(private val context: Context) {
    fun inspect(): AndroidVirtualizationSupport = AndroidVirtualizationSupport(
        apiLevel = Build.VERSION.SDK_INT,
        frameworkFeaturePresent = context.packageManager.hasSystemFeature(FEATURE_AVF),
    )

    companion object {
        // PackageManager.FEATURE_VIRTUALIZATION_FRAMEWORK is not part of every public SDK
        // surface we compile against; the platform feature string itself is stable.
        const val FEATURE_AVF = "android.software.virtualization_framework"
    }
}
