package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.ContractHello
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.TaskSnapshot
import dev.fonebrew.watchbridge.TaskToggleResult
import dev.fonebrew.watchbridge.WatchPaths

/**
 * Turns what the phone sent into [WatchEvent]s. Pure: the path says what the message must be, the
 * codec says whether it decoded, and anything that does not line up is **dropped, never
 * interpreted** — a `TaskSnapshot` arriving on the hello path is not a task list.
 *
 * A message from the phone is also proof of life ([LinkEvent.PhoneHeard]).
 */
object MessageRouter {

    /** A one-shot message (every phone-to-watch path except the reply channel). */
    fun route(path: String, result: DecodeResult, sourceNodeId: String, atMs: Long): List<WatchEvent> {
        if (path !in MESSAGE_PATHS) return emptyList()
        return when (result) {
            is DecodeResult.Incompatible -> listOf(LinkEvent.EnvelopeIncompatible(sourceNodeId, result.mismatch))
            is DecodeResult.Malformed -> emptyList()
            is DecodeResult.Decoded -> {
                val heard = LinkEvent.PhoneHeard(sourceNodeId)
                when (val message = result.message) {
                    is ContractHello -> if (path == WatchPaths.HELLO) listOf(LinkEvent.HelloReceived(sourceNodeId, message)) else emptyList()
                    is TaskSnapshot -> if (path == WatchPaths.TASKS) listOf(heard, TaskEvent.SnapshotReceived(message, atMs)) else emptyList()
                    is TaskToggleResult -> if (path == WatchPaths.TASK_TOGGLE_RESULT) listOf(heard, TaskEvent.ToggleResultReceived(message)) else emptyList()
                    is LoopRunSnapshot -> if (path == WatchPaths.LOOPS) listOf(heard, LoopEvent.RunReceived(message, atMs)) else emptyList()
                    else -> emptyList()
                }
            }
        }
    }

    /**
     * One frame of the reply channel ([WatchPaths.ASK_REPLY]). [inFlightRequestId] is the ask the
     * stream is answering (the last id the stream itself named, else the ask in flight when it
     * opened). A frame that does not decode means a piece of the reply is missing, and **a reply with
     * a hole in it must not be shown as complete**, so it damages the stream rather than being skipped.
     */
    fun routeReplyFrame(result: DecodeResult, sourceNodeId: String, atMs: Long, inFlightRequestId: String?): List<WatchEvent> =
        when (result) {
            is DecodeResult.Decoded -> when (val message = result.message) {
                is AskChunk -> listOf(LinkEvent.PhoneHeard(sourceNodeId), AskEvent.ChunkReceived(message, atMs))
                is AskError -> listOf(LinkEvent.PhoneHeard(sourceNodeId), AskEvent.PhoneRefused(message))
                else -> emptyList()
            }
            is DecodeResult.Incompatible ->
                listOfNotNull(LinkEvent.EnvelopeIncompatible(sourceNodeId, result.mismatch), inFlightRequestId?.let { AskEvent.StreamDamaged(it) })
            is DecodeResult.Malformed -> listOfNotNull(inFlightRequestId?.let { AskEvent.StreamDamaged(it) })
        }

    private val MESSAGE_PATHS = setOf(WatchPaths.HELLO, WatchPaths.TASKS, WatchPaths.TASK_TOGGLE_RESULT, WatchPaths.LOOPS)
}
