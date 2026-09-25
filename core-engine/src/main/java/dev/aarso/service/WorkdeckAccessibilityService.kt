package dev.aarso.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import dev.aarso.data.workdeck.WorkdeckInboundEvent
import dev.aarso.data.workdeck.WorkdeckSessionHub
import dev.aarso.domain.workdeck.WorkdeckCoordinateMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Optional generic cross-app input. Android Settings is the only way to enable this service. */
class WorkdeckAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputJob: Job? = null
    private var pointerPath: Path? = null
    private var pointerStartedAt = 0L

    override fun onServiceConnected() {
        inputJob?.cancel()
        inputJob = scope.launch {
            WorkdeckSessionHub.inbound.collect { event ->
                if (!inputEnabled()) return@collect
                when (event) {
                    is WorkdeckInboundEvent.Pointer -> pointer(event)
                    is WorkdeckInboundEvent.Keyboard -> insertText(event.text)
                    is WorkdeckInboundEvent.Control -> control(event.action)
                    else -> Unit
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pointer(event: WorkdeckInboundEvent.Pointer) {
        val metrics = resources.displayMetrics
        val point = WorkdeckCoordinateMapper.toPixels(
            event.pointer, metrics.widthPixels, metrics.heightPixels, displayRotationDegrees(),
        )
        when (event.action) {
            ACTION_DOWN -> {
                pointerPath = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
                pointerStartedAt = android.os.SystemClock.elapsedRealtime()
            }
            ACTION_MOVE -> pointerPath?.lineTo(point.x.toFloat(), point.y.toFloat())
            ACTION_UP -> {
                val path = (pointerPath ?: Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) })
                    .apply { lineTo(point.x.toFloat(), point.y.toFloat()) }
                val duration = (android.os.SystemClock.elapsedRealtime() - pointerStartedAt).coerceIn(50L, 5_000L)
                dispatch(path, duration)
                pointerPath = null
            }
            ACTION_LONG_PRESS -> dispatch(Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }, 600L)
        }
    }

    private fun dispatch(path: Path, duration: Long) {
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build(),
            null, null,
        )
    }

    private fun displayRotationDegrees(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun insertText(text: String) {
        if (text.isEmpty()) return
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val current = focused.text?.toString().orEmpty()
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun control(action: String) {
        when (action.lowercase()) {
            "escape", "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun inputEnabled(): Boolean = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false)

    companion object {
        const val PREFERENCES = "workdeck_input"
        const val KEY_ENABLED = "accessibility_routing_enabled"
        private const val ACTION_DOWN = 0
        private const val ACTION_MOVE = 1
        private const val ACTION_UP = 2
        private const val ACTION_LONG_PRESS = 3
    }
}
