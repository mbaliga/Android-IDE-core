package dev.aarso.domain.workdeck

enum class WorkdeckRefreshMode { TEXT_HIGH_QUALITY, UI_GRAYSCALE, FAST_SCROLL, FULL_CLEANUP }

data class RefreshDecision(val mode: WorkdeckRefreshMode, val targetFramesPerSecond: Int, val forceFullFrame: Boolean)

class WorkdeckRefreshScheduler(
    private val fullRefreshAfterPartials: Int = 48,
    private val fullRefreshAfterMillis: Long = 4 * 60 * 1000L,
) {
    private var partialCount = 0
    private var lastFullRefreshMillis = 0L

    fun decide(
        nowMillis: Long,
        changedRatio: Float,
        scrolling: Boolean,
        textDominant: Boolean,
    ): RefreshDecision {
        require(changedRatio in 0f..1f) { "Changed ratio must be normalized." }
        val cleanupDue = partialCount >= fullRefreshAfterPartials ||
            (lastFullRefreshMillis != 0L && nowMillis - lastFullRefreshMillis >= fullRefreshAfterMillis)
        if (cleanupDue || changedRatio >= 0.75f) {
            partialCount = 0
            lastFullRefreshMillis = nowMillis
            return RefreshDecision(WorkdeckRefreshMode.FULL_CLEANUP, 1, true)
        }
        if (changedRatio > 0f) partialCount++
        return when {
            scrolling -> RefreshDecision(WorkdeckRefreshMode.FAST_SCROLL, 8, false)
            textDominant -> RefreshDecision(WorkdeckRefreshMode.TEXT_HIGH_QUALITY, 2, false)
            changedRatio > 0.20f -> RefreshDecision(WorkdeckRefreshMode.UI_GRAYSCALE, 4, false)
            else -> RefreshDecision(WorkdeckRefreshMode.UI_GRAYSCALE, 1, false)
        }
    }
}

data class NormalizedPointer(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) { "Pointer coordinates must be normalized." } }
}

data class PixelPointer(val x: Int, val y: Int)

object WorkdeckCoordinateMapper {
    fun toPixels(pointer: NormalizedPointer, width: Int, height: Int, rotationDegrees: Int): PixelPointer {
        require(width > 0 && height > 0) { "Viewport dimensions must be positive." }
        val (x, y) = when (rotationDegrees) {
            0 -> pointer.x to pointer.y
            90 -> (1f - pointer.y) to pointer.x
            180 -> (1f - pointer.x) to (1f - pointer.y)
            270 -> pointer.y to (1f - pointer.x)
            else -> error("Unsupported viewport rotation: $rotationDegrees")
        }
        return PixelPointer(
            (x * (width - 1)).toInt().coerceIn(0, width - 1),
            (y * (height - 1)).toInt().coerceIn(0, height - 1),
        )
    }
}

enum class WorkdeckConnectionState { IDLE, LISTENING, AUTHENTICATING, CONNECTED, SUSPENDED, RECONNECTING, CLOSED }

class WorkdeckConnectionMachine(initial: WorkdeckConnectionState = WorkdeckConnectionState.IDLE) {
    var state: WorkdeckConnectionState = initial
        private set

    fun listening() = transition(setOf(WorkdeckConnectionState.IDLE, WorkdeckConnectionState.RECONNECTING), WorkdeckConnectionState.LISTENING)
    fun authenticating() = transition(setOf(WorkdeckConnectionState.LISTENING), WorkdeckConnectionState.AUTHENTICATING)
    fun authenticated() = transition(setOf(WorkdeckConnectionState.AUTHENTICATING), WorkdeckConnectionState.CONNECTED)
    fun suspend() = transition(setOf(WorkdeckConnectionState.CONNECTED), WorkdeckConnectionState.SUSPENDED)
    fun wake() = transition(setOf(WorkdeckConnectionState.SUSPENDED), WorkdeckConnectionState.CONNECTED)
    fun disconnected() = transition(
        setOf(WorkdeckConnectionState.AUTHENTICATING, WorkdeckConnectionState.CONNECTED, WorkdeckConnectionState.SUSPENDED),
        WorkdeckConnectionState.RECONNECTING,
    )
    fun close() { state = WorkdeckConnectionState.CLOSED }

    private fun transition(allowed: Set<WorkdeckConnectionState>, target: WorkdeckConnectionState) {
        require(state in allowed) { "Cannot transition Workdeck from $state to $target." }
        state = target
    }
}

