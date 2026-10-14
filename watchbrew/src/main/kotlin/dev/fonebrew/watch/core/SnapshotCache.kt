package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.TaskSnapshot
import dev.fonebrew.watchbridge.WatchBridgeCodec

/** The smallest key-value surface the cache needs; `null` removes. Backed by SharedPreferences on the watch. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * The last-known facts, kept so the Tile and the complication — which the system can ask for while the
 * app's process is cold — have something honest to show, and so the app opens on the last confirmed
 * list instead of a blank. Everything is stored **in the wire contract's own encoding** and read back
 * through the same strict decoder, so a corrupt or foreign entry is simply ignored (all-or-nothing, the
 * contract's rule) rather than half-trusted. Each entry keeps the time the watch *received* it, so the
 * age shown after a restart is the real age, not "just now".
 *
 * Device-local SharedPreferences only: this file is on the watch and goes nowhere (binding rule 1).
 */
class SnapshotCache(private val kv: KeyValueStore) {

    fun saveTasks(snapshot: TaskSnapshot, receivedAtMs: Long) {
        kv.put(KEY_TASKS, stamp(receivedAtMs, WatchBridgeCodec.encode(snapshot)))
    }

    fun loadTasks(): Pair<TaskSnapshot, Long>? =
        unstamp(kv.get(KEY_TASKS))?.let { (at, message) -> (message as? TaskSnapshot)?.let { it to at } }

    fun saveLoops(runs: List<LoopEntry>) {
        runs.forEachIndexed { i, run -> kv.put("$KEY_LOOP$i", stamp(run.receivedAtMs, WatchBridgeCodec.encode(run.snapshot))) }
        kv.put(KEY_LOOP_COUNT, runs.size.toString())
    }

    fun loadLoops(): List<LoopEntry> {
        val count = kv.get(KEY_LOOP_COUNT)?.toIntOrNull()?.coerceIn(0, LoopsState.MAX_RUNS) ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            unstamp(kv.get("$KEY_LOOP$i"))?.let { (at, message) -> (message as? LoopRunSnapshot)?.let { LoopEntry(it, at) } }
        }
    }

    /**
     * The reply is stored as one *terminal* [AskChunk] (the contract's own shape for "a finished reply"),
     * with its text capped at [MAX_CACHED_REPLY_CHARS] and an explicit "…" if it was cut.
     */
    fun saveLastReply(reply: ReplyRecord) {
        val chunk = AskChunk.terminal(reply.requestId, capText(reply.text), reply.engineId, reply.watchedObject, reply.usage)
        kv.put(KEY_REPLY, stamp(reply.completedAtMs, WatchBridgeCodec.encode(chunk)))
    }

    fun loadLastReply(): ReplyRecord? =
        unstamp(kv.get(KEY_REPLY))?.let { (at, message) ->
            (message as? AskChunk)?.takeIf { it.terminal }?.let { chunk ->
                ReplyRecord(chunk.requestId, chunk.text, chunk.engineId, chunk.watchedObject, checkNotNull(chunk.usage), at)
            }
        }

    fun hydration(): Hydrated = Hydrated(loadTasks(), loadLoops(), loadLastReply())

    private fun stamp(atMs: Long, encoded: String): String = "$atMs\n$encoded"

    private fun unstamp(raw: String?): Pair<Long, dev.fonebrew.watchbridge.WatchMessage>? {
        if (raw == null) return null
        val newline = raw.indexOf('\n')
        if (newline <= 0) return null
        val at = raw.substring(0, newline).toLongOrNull() ?: return null
        return when (val decoded = WatchBridgeCodec.decode(raw.substring(newline + 1))) {
            is DecodeResult.Decoded -> at to decoded.message
            else -> null
        }
    }

    internal companion object {
        const val MAX_CACHED_REPLY_CHARS = 2_000
        private const val KEY_TASKS = "cache.tasks"
        private const val KEY_LOOP = "cache.loop."
        private const val KEY_LOOP_COUNT = "cache.loops"
        private const val KEY_REPLY = "cache.reply"

        fun capText(text: String): String {
            if (text.length <= MAX_CACHED_REPLY_CHARS) return text
            var cut = MAX_CACHED_REPLY_CHARS - 1
            if (text[cut - 1].isHighSurrogate()) cut--
            return text.substring(0, cut) + "…"
        }
    }
}
