package dev.fonebrew.ui.layout

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.Surface
import dev.fonebrew.domain.layout.DisplayDescriptor
import dev.fonebrew.domain.layout.DisplayEnvironment
import dev.fonebrew.domain.layout.DisplayListSource
import dev.fonebrew.domain.layout.InputPresence
import dev.fonebrew.domain.layout.InputPresenceSource
import dev.fonebrew.domain.layout.WindowSnapshot
import dev.fonebrew.domain.layout.WindowSource

/*
 * Android implementations of the DisplayEnvironment source interfaces. Generic Android only
 * (DisplayManager, WindowMetrics, Display flags, InputManager) - no vendor desktop-mode API.
 * Nothing here has run on a device; see docs/design/docked-layout.md "Owner-verified only".
 */

/** The activity's own window: size from `currentWindowMetrics`, density from its configuration. */
class ActivityWindowSource(private val activity: Activity) : WindowSource {
    override fun read(): WindowSnapshot {
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        val display = runCatching { activity.display }.getOrNull()
        return WindowSnapshot(
            widthPx = bounds.width(),
            heightPx = bounds.height(),
            densityDpi = activity.resources.configuration.densityDpi,
            displayId = display?.displayId ?: Display.DEFAULT_DISPLAY,
            rotationDegrees = rotationToDegrees(display?.rotation ?: Surface.ROTATION_0),
        )
    }

    companion object {
        fun rotationToDegrees(rotation: Int): Int = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}

/** All displays the system lists, with a `DisplayListener` for add / remove / change. */
class AndroidDisplayListSource(context: Context) : DisplayListSource {
    private val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    override fun read(): List<DisplayDescriptor> = manager.displays.mapNotNull { d ->
        if (!d.isValid) return@mapNotNull null
        val mode = d.mode
        DisplayDescriptor(
            id = d.displayId,
            name = d.name.orEmpty(),
            isDefault = d.displayId == Display.DEFAULT_DISPLAY,
            isPresentation = d.flags and Display.FLAG_PRESENTATION != 0,
            widthPx = mode.physicalWidth,
            heightPx = mode.physicalHeight,
        )
    }

    override fun listen(onChange: () -> Unit): AutoCloseable {
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChange()
            override fun onDisplayRemoved(displayId: Int) = onChange()
            override fun onDisplayChanged(displayId: Int) = onChange()
        }
        manager.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        return AutoCloseable { manager.unregisterDisplayListener(l) }
    }
}

/** What the input classifier needs from an `InputDevice` (kept plain so the rule is JVM-testable). */
data class InputDeviceInfo(val sources: Int, val keyboardType: Int, val isVirtual: Boolean)

object InputClassifier {
    /**
     * Hardware keyboard: a non-virtual device with keyboard source and an alphabetic layout (this
     * excludes media-key dongles and the on-screen keyboard's virtual device). Pointer: a non-virtual
     * mouse, relative mouse or touchpad. The touchscreen is deliberately not a pointer.
     */
    fun classify(devices: List<InputDeviceInfo>): InputPresence {
        val real = devices.filter { !it.isVirtual }
        val kb = real.any {
            it.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
                it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }
        val pointer = real.any {
            it.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
                it.sources and InputDevice.SOURCE_MOUSE_RELATIVE == InputDevice.SOURCE_MOUSE_RELATIVE ||
                it.sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD
        }
        return InputPresence(hasHardwareKeyboard = kb, hasPointer = pointer)
    }
}

/** Keyboard / mouse presence from `InputManager`, with an `InputDeviceListener`. */
class AndroidInputPresenceSource(context: Context) : InputPresenceSource {
    private val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    override fun read(): InputPresence = InputClassifier.classify(
        InputDevice.getDeviceIds().asList().mapNotNull { id ->
            InputDevice.getDevice(id)?.let { InputDeviceInfo(it.sources, it.keyboardType, it.isVirtual) }
        },
    )

    override fun listen(onChange: () -> Unit): AutoCloseable {
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = onChange()
            override fun onInputDeviceRemoved(deviceId: Int) = onChange()
            override fun onInputDeviceChanged(deviceId: Int) = onChange()
        }
        manager.registerInputDeviceListener(l, Handler(Looper.getMainLooper()))
        return AutoCloseable { manager.unregisterInputDeviceListener(l) }
    }
}

object AndroidDisplayEnvironment {
    fun create(activity: Activity): DisplayEnvironment =
        DisplayEnvironment(ActivityWindowSource(activity), AndroidDisplayListSource(activity), AndroidInputPresenceSource(activity))
}

