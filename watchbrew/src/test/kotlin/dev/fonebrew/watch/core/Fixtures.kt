package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.TaskEntry
import dev.fonebrew.watchbridge.TaskSnapshot
import dev.fonebrew.watchbridge.Usage
import dev.fonebrew.watchbridge.WatchTaskState

/** Small builders shared by the core tests. No clocks, no randomness. */
internal object Fx {
    const val ID = "req-1"
    const val ENGINE = "local-engine"
    const val CLOUD_ENGINE = "cloud-model-x"

    val phone = PhoneNode("node-1", "Pixel")
    val observed = ObservedNode("node-1", "Pixel", nearby = true)

    fun token(text: String, id: String = ID, engine: String = ENGINE, watched: Boolean = false) =
        AskChunk.token(id, text, engine, watched)

    fun terminal(
        text: String = "",
        id: String = ID,
        engine: String = ENGINE,
        watched: Boolean = false,
        usage: Usage = Usage.engineReported(3, 5),
    ) = AskChunk.terminal(id, text, engine, watched, usage)

    fun task(id: String, title: String = "task $id", state: WatchTaskState = WatchTaskState.TODO) = TaskEntry(id, title, state)

    fun snapshot(vararg tasks: TaskEntry, pending: Int? = null) =
        TaskSnapshot(tasks.toList(), pending ?: tasks.count { it.state != WatchTaskState.DONE })

    fun run(id: String, name: String = "loop $id", glyph: String = "running", step: String? = null, spend: Spend? = null) =
        LoopRunSnapshot(id, name, glyph, step, spend)
}
