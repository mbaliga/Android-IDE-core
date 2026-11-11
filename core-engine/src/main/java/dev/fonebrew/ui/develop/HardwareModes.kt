package dev.fonebrew.ui.develop

/** The control paths in the Develop -> Hardware tab. */
internal enum class HardwareMode { PI, ARDUINO, ESP, USB }

/**
 * Pure derivation of which Hardware modes exist and which one the tab opens on. [usbAvailable]
 * comes from the flavor seam (`InvocationFeatures.USB_FLASH_AVAILABLE`): the play build declares no
 * usb.host feature, so it must not offer a flasher it cannot use.
 */
internal object HardwareModes {
    fun available(usbAvailable: Boolean): List<HardwareMode> =
        listOf(HardwareMode.PI, HardwareMode.ARDUINO, HardwareMode.ESP) +
            if (usbAvailable) listOf(HardwareMode.USB) else emptyList()

    /** Hosts present -> the Pi path; no host -> USB when the flavor has it (the one host-less path),
     *  else still the Pi path, whose in-place hint says what is missing. */
    fun default(hasHosts: Boolean, usbAvailable: Boolean): HardwareMode =
        if (!hasHosts && usbAvailable) HardwareMode.USB else HardwareMode.PI
}
