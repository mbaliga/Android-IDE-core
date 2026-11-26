package dev.fonebrew.domain.layout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One display the system reports. Flags are already decoded (no Android types here). */
data class DisplayDescriptor(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
    /** `Display.FLAG_PRESENTATION`: the system says this display is meant for presentations/externals. */
    val isPresentation: Boolean,
    val widthPx: Int,
    val heightPx: Int,
)

/** The app window as the activity sees it right now. */
data class WindowSnapshot(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val displayId: Int,
    /** 0, 90, 180, 270 (degrees); other values are carried as-is. */
    val rotationDegrees: Int,
)

data class InputPresence(val hasHardwareKeyboard: Boolean, val hasPointer: Boolean)

/** Source interfaces: the Android implementations live in `ui/layout`, tests use fakes. */
interface WindowSource { fun read(): WindowSnapshot }

interface ChangeNotifier {
    /** Calls [onChange] (on the main thread) whenever the underlying data may have changed; close to stop. */
    fun listen(onChange: () -> Unit): AutoCloseable
}

interface DisplayListSource : ChangeNotifier { fun read(): List<DisplayDescriptor> }
interface InputPresenceSource : ChangeNotifier { fun read(): InputPresence }

/** How the app relates to the displays that exist. */
enum class DisplayArrangement {
    /** One display, handset-sized window. */
    STANDALONE,
    /** App on the default display and no second display listed, yet the window is desktop-sized: the system simply made it large. */
    MIRRORED,
    /** Two or more displays are connected (the app may be on either). */
    EXTENDED,
}

data class DisplayState(
    val window: WindowSnapshot,
    val displays: List<DisplayDescriptor>,
    val input: InputPresence,
) {
    val density: Float get() = if (window.densityDpi > 0) window.densityDpi / 160f else 1f
    val widthDp: Float get() = window.widthPx / density
    val heightDp: Float get() = window.heightPx / density

    /** The descriptor of the display the window is on, if the system lists it. */
    val appDisplay: DisplayDescriptor? get() = displays.firstOrNull { it.id == window.displayId }
    val isDefaultDisplay: Boolean get() = appDisplay?.isDefault ?: (window.displayId == DEFAULT_DISPLAY_ID)
    val isPresentation: Boolean get() = appDisplay?.isPresentation == true

    /** Not the handset's own screen: a non-default display, or one the system flags as a presentation. */
    val isExternalDisplay: Boolean get() = !isDefaultDisplay || isPresentation

    val screenClass: ScreenClass
        get() = ScreenClassResolver.resolve(
            ScreenInputs(widthDp, heightDp, isExternalDisplay, input.hasHardwareKeyboard, input.hasPointer),
        )

    val arrangement: DisplayArrangement
        get() = when {
            displays.size >= 2 -> DisplayArrangement.EXTENDED
            isDefaultDisplay && (screenClass == ScreenClass.DESKTOP || screenClass == ScreenClass.ULTRAWIDE) -> DisplayArrangement.MIRRORED
            else -> DisplayArrangement.STANDALONE
        }

    val fingerprint: DisplayFingerprint
        get() = DisplayFingerprint(window.displayId, screenClass, DensityBucket.of(window.densityDpi))

    companion object { const val DEFAULT_DISPLAY_ID = 0 }
}

/**
 * Holds the current [DisplayState]. [start] reads once and subscribes to the display and input
 * sources; the activity (or a composable watching `LocalConfiguration`) calls [refresh] when its
 * configuration changes, since window size/density/rotation have no listener of their own here.
 */
class DisplayEnvironment(
    private val windows: WindowSource,
    private val displays: DisplayListSource,
    private val inputs: InputPresenceSource,
) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DisplayState> = _state.asStateFlow()

    private var registrations: List<AutoCloseable> = emptyList()

    private fun read() = DisplayState(windows.read(), displays.read(), inputs.read())

    fun refresh() { _state.value = read() }

    fun start() {
        if (registrations.isNotEmpty()) return
        registrations = listOf(displays.listen { refresh() }, inputs.listen { refresh() })
        refresh()
    }

    fun stop() {
        registrations.forEach { runCatching { it.close() } }
        registrations = emptyList()
    }
}

/** What the app needs to start itself on another display. Pure plan; the start is an adapter. */
sealed interface LaunchPlan {
    data class Launch(
        val targetDisplayId: Int,
        /** `MainActivity` is singleTask: without NEW_TASK + MULTIPLE_TASK the system would just refocus the existing task. UNVERIFIED on real devices. */
        val needsNewTaskFlags: Boolean = true,
        val note: String = "Launch-on-display behaviour is unverified on real hardware.",
    ) : LaunchPlan
    data class None(val reason: String) : LaunchPlan
}

object DisplayLaunchPlanner {
    /**
     * Picks a second display to open the app on: not the one it is on, presentation-flagged first,
     * then the largest, then the lowest id. [supportsSecondaryDisplayActivities] is `null` when
     * unknown (treated as allowed but flagged in the plan note), `false` to refuse.
     */
    fun plan(state: DisplayState, supportsSecondaryDisplayActivities: Boolean?): LaunchPlan {
        if (supportsSecondaryDisplayActivities == false) return LaunchPlan.None("device reports no activities on secondary displays")
        val candidates = state.displays.filter { it.id != state.window.displayId && it.widthPx > 0 && it.heightPx > 0 }
        if (candidates.isEmpty()) return LaunchPlan.None("no other display is connected")
        val best = candidates.sortedWith(
            compareByDescending<DisplayDescriptor> { it.isPresentation }
                .thenByDescending { it.widthPx.toLong() * it.heightPx }
                .thenBy { it.id },
        ).first()
        val note = if (supportsSecondaryDisplayActivities == null) "Secondary-display support is unknown on this device; launch is unverified." else "Launch-on-display behaviour is unverified on real hardware."
        return LaunchPlan.Launch(best.id, note = note)
    }
}
