package dev.aarso.data.kindle

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.aarso.domain.kindle.KindleDeviceProfileCodec
import dev.aarso.domain.kindle.KindleIdentity
import dev.aarso.domain.kindle.KindleProfileMatch
import dev.aarso.domain.kindle.KindleProfileRegistry
import dev.aarso.domain.kindle.KindleUsbState

data class KindleUsbCandidate(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val permissionGranted: Boolean,
    val massStorageInterface: Boolean,
    val profileMatch: KindleProfileMatch,
) {
    val usbState: KindleUsbState
        get() = when {
            !permissionGranted -> KindleUsbState.PERMISSION_REQUIRED
            massStorageInterface -> KindleUsbState.DETECTED
            else -> KindleUsbState.RAW_HOST_ONLY
        }
}

/** Android USB discovery only. All browsing/copying remains in Fylz. */
class KindleDeviceRepository(private val context: Context) {
    val profiles: KindleProfileRegistry by lazy {
        val raw = context.assets.open(PROFILES_ASSET).bufferedReader().use { it.readText() }
        KindleProfileRegistry(KindleDeviceProfileCodec.decode(raw))
    }

    fun discoverUsb(): List<KindleUsbCandidate> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values.mapNotNull { device ->
            val hasMassStorage = device.hasInterfaceClass(UsbConstants.USB_CLASS_MASS_STORAGE)
            val likelyAmazon = device.vendorId == AMAZON_VENDOR_ID
            val permission = manager.hasPermission(device)
            val manufacturer = if (permission) runCatching { device.manufacturerName }.getOrNull() else null
            val product = if (permission) runCatching { device.productName }.getOrNull() else null
            val serial = if (permission) runCatching { device.serialNumber }.getOrNull() else null
            val match = profiles.match(
                KindleIdentity(
                    serial = serial,
                    modelLabel = product,
                    usbVendorId = device.vendorId,
                    usbProductId = device.productId,
                ),
            )
            if (!likelyAmazon && !hasMassStorage && match == KindleProfileMatch.Unknown) return@mapNotNull null
            KindleUsbCandidate(
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturer = manufacturer,
                product = product,
                serial = serial,
                permissionGranted = permission,
                massStorageInterface = hasMassStorage,
                profileMatch = match,
            )
        }.sortedWith(compareByDescending<KindleUsbCandidate> { it.profileMatch is KindleProfileMatch.Matched }.thenBy { it.deviceId })
    }

    fun usbDevice(deviceId: Int): UsbDevice? =
        context.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { it.deviceId == deviceId }

    private fun UsbDevice.hasInterfaceClass(deviceClass: Int): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == deviceClass }

    companion object {
        const val AMAZON_VENDOR_ID = 0x1949
        const val PROFILES_ASSET = "kindle/device_profiles.v1.json"
    }
}

