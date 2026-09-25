package dev.aarso.data.workdeck

import dev.aarso.domain.workdeck.DirtyRegionDetector
import dev.aarso.domain.workdeck.GrayFrame
import dev.aarso.domain.workdeck.WorkdeckMessageType
import dev.aarso.domain.workdeck.WorkdeckPacket
import dev.aarso.domain.workdeck.WorkdeckPayloadCodec
import dev.aarso.domain.workdeck.WorkdeckQuality
import dev.aarso.domain.workdeck.WorkdeckRefreshMode
import dev.aarso.domain.workdeck.WorkdeckRefreshScheduler
import dev.aarso.domain.workdeck.WorkdeckRect
import java.util.concurrent.atomic.AtomicLong

data class FramePublishDecision(
    val packets: Int,
    val targetFramesPerSecond: Int,
    val fullRefresh: Boolean,
)

class WorkdeckFramePublisher(
    private val detector: DirtyRegionDetector = DirtyRegionDetector(),
    private val scheduler: WorkdeckRefreshScheduler = WorkdeckRefreshScheduler(),
) {
    private var previous: GrayFrame? = null
    private val sequence = AtomicLong(1)

    fun publish(
        frame: GrayFrame,
        nowMillis: Long,
        scrolling: Boolean = false,
        textDominant: Boolean = false,
    ): FramePublishDecision {
        val dirty = detector.detect(previous, frame)
        if (dirty.changedPixels == 0) return FramePublishDecision(0, 1, false)
        val ratio = dirty.changedPixels.toFloat() / frame.pixels.size
        val refresh = scheduler.decide(nowMillis, ratio, scrolling, textDominant)
        val quality = when (refresh.mode) {
            WorkdeckRefreshMode.TEXT_HIGH_QUALITY -> WorkdeckQuality.TEXT
            WorkdeckRefreshMode.FAST_SCROLL -> WorkdeckQuality.FAST_SCROLL
            WorkdeckRefreshMode.UI_GRAYSCALE, WorkdeckRefreshMode.FULL_CLEANUP -> WorkdeckQuality.GENERAL
        }
        val full = dirty.fullFrame || refresh.forceFullFrame
        val rectangles = if (full) listOf(WorkdeckRect(0, 0, frame.width, frame.height)) else dirty.rectangles
        // The Kindle must choose its waveform before applying pixels, not one packet later.
        WorkdeckSessionHub.send(
            WorkdeckPacket(
                type = WorkdeckMessageType.REFRESH_HINT,
                sequence = sequence.getAndIncrement(),
                payload = WorkdeckPayloadCodec.text(refresh.mode.name),
            ),
        )
        rectangles.forEach { rect ->
            WorkdeckSessionHub.send(
                WorkdeckPacket(
                    type = if (full) WorkdeckMessageType.FULL_FRAME else WorkdeckMessageType.DIRTY_RECTANGLE,
                    sequence = sequence.getAndIncrement(),
                    payload = WorkdeckPayloadCodec.region(frame, rect, quality),
                ),
            )
        }
        previous = frame
        return FramePublishDecision(rectangles.size, refresh.targetFramesPerSecond, refresh.forceFullFrame)
    }

    fun reset() { previous = null }
}
