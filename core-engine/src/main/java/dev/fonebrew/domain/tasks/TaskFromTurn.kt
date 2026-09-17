package dev.fonebrew.domain.tasks

import dev.fonebrew.domain.markdown.SpeechText

/**
 * Pure title/body/provenance derivation for TurnActionsSheet's "Convert → Task" row (lane A3's
 * honest missing-list update — see `ChatScreen.kt`'s TurnActionsSheet KDoc). No Android, no
 * store, no clock — [dev.fonebrew.data.TaskStore.createFrom] does the actual write, taking
 * [TaskSource.CHAT] plus whatever this object derives.
 *
 * [sourceRef] mints the task's [dev.fonebrew.data.entity.TaskEntity.sourceRef] as a dotted key
 * path, `chat.node.<id>` — the same dotted-metadata idiom this codebase already uses for
 * insert-time provenance ([dev.fonebrew.domain.tree.TreeFork]'s `lineage.kind`/`lineage.srcNode`,
 * [dev.fonebrew.domain.ide.CommitAnchor]'s `run.commit.sha`), so a task's origin is
 * self-describing rather than a bare, unexplained id.
 */
object TaskFromTurn {

    /** Caps the derived title at the same 120 chars TurnActionsSheet's own turn preview
     *  (`step.node.content.take(120)`) uses, so a title never runs dramatically longer than the
     *  preview the row was picked from. */
    const val MAX_TITLE_LENGTH = 120

    /** Fallback for a turn whose first line carries no speakable text at all (e.g. an image-only
     *  turn, or one that is pure markdown structure with nothing left after stripping) — never
     *  an empty task title. */
    const val DEFAULT_TITLE = "Untitled turn"

    /**
     * The turn's first non-blank line, markdown-unwrapped via [SpeechText.lineToSpeech] (so a
     * title never reads a literal `#`/`**`/`` ` ``), narrowed to its first sentence when one
     * exists (`.`/`!`/`?`) so a multi-sentence opening line still yields a short title, and
     * truncated with a trailing `…` if it still runs past [MAX_TITLE_LENGTH].
     */
    fun title(turnContent: String): String {
        val firstRawLine = turnContent.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val cleaned = SpeechText.lineToSpeech(firstRawLine)
        if (cleaned.isEmpty()) return DEFAULT_TITLE

        val sentenceEnd = cleaned.indexOfFirst { it == '.' || it == '!' || it == '?' }
        val candidate = if (sentenceEnd >= 0) cleaned.substring(0, sentenceEnd + 1) else cleaned
        val chosen = candidate.trim().ifEmpty { cleaned }

        return if (chosen.length > MAX_TITLE_LENGTH) {
            chosen.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…"
        } else {
            chosen
        }
    }

    /**
     * Task body: the turn's raw content verbatim (markdown intact —
     * [dev.fonebrew.data.entity.TaskEntity.notes] renders plain, so, unlike [title], there is no
     * reason to lose the source's own formatting), then a blank line and a human-readable
     * provenance line naming [sourceRef] — the task's origin is visible to a person reading the
     * notes, not only to the machine-readable field.
     */
    fun notes(turnContent: String, nodeId: String): String =
        "${turnContent.trim()}\n\n— from chat message ${sourceRef(nodeId)}"

    /** The dotted source-reference this conversion mints for
     *  [dev.fonebrew.data.entity.TaskEntity.sourceRef] — see this object's own KDoc for the
     *  idiom it follows. */
    fun sourceRef(nodeId: String): String = "chat.node.$nodeId"
}
