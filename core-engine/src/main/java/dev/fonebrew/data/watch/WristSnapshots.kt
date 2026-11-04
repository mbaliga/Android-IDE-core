package dev.fonebrew.data.watch

import dev.fonebrew.data.BackgroundJobs
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.cost.CurrencyPref
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.loop.GraphRunLog
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.StateGlyphKeys
import dev.fonebrew.watchbridge.TaskEntry
import dev.fonebrew.watchbridge.TaskSnapshot
import dev.fonebrew.watchbridge.WatchTaskState

/**
 * Pure assembly of what the phone shows the wrist about tasks and loop runs (docs/design/watchbrew.md
 * section 3, surfaces 2 and 3). Takes already-read phone state, returns contract messages; no I/O, no
 * clock, JVM-tested. Nothing here is invented: every figure is a ledger row or a task row, or absent.
 */
object WristSnapshots {

    /** The Data Layer's message limit (~100 KB) is why a snapshot lists a bounded window while [TaskSnapshot.pendingCount] stays the true total. */
    const val MAX_LISTED_TASKS: Int = 40

    /** Of those, at most this many are already-done rows (so the wrist can un-check a mistake), after all pending ones. */
    const val MAX_LISTED_DONE: Int = 5

    /** Titles are cut here with a visible ellipsis; a wrist row is not the place for a paragraph. */
    const val MAX_TITLE_CHARS: Int = 120

    /** How many loop runs one sync sends (running and failed-to-start first, then the newest finished). */
    const val MAX_LOOP_RUNS: Int = 5

    /** The kind [BackgroundJobs] tags a Loop run with (LoopRoom). */
    private const val LOOP_JOB_KIND = "loop"

    /** The tag `GraphRunLedger` entries carry (`LedgerCapture.loopStep`). */
    private const val LOOP_SURFACE = "loop"

    /** Shown when a run has no saved loop behind it (an ad-hoc graph run) or the loop was since deleted. */
    const val UNSAVED_LOOP_NAME: String = "Unsaved loop"

    /** The open glyph key for a finished run whose stop reason this phone cannot classify (see [glyphForStop]). */
    const val ENDED_GLYPH_KEY: String = "ended"

    // ---- tasks ---------------------------------------------------------------------------------

    /**
     * The tasks snapshot for [rows], which must be in the store's own order (manual order, as the phone
     * lists them). Not-done rows come first, in that order, up to [MAX_LISTED_TASKS]; then up to
     * [MAX_LISTED_DONE] of the most recently completed. [TaskSnapshot.pendingCount] is the phone's
     * TRUE not-done total, independent of how many are listed, so the complication stays right when the list is windowed.
     */
    fun tasks(rows: List<TaskEntity>): TaskSnapshot {
        val usable = rows.filter { it.id.isNotBlank() }
        val pending = usable.filter { it.state != TaskState.DONE }
        val done = usable.filter { it.state == TaskState.DONE }.sortedByDescending { it.doneAt ?: it.updatedAt }

        val listedPending = pending.take(MAX_LISTED_TASKS)
        val listedDone = done.take(minOf(MAX_LISTED_DONE, MAX_LISTED_TASKS - listedPending.size))
        return TaskSnapshot(
            tasks = (listedPending + listedDone).map(::entryOf),
            pendingCount = pending.size,
        )
    }

    private fun entryOf(row: TaskEntity): TaskEntry =
        TaskEntry(
            id = row.id.toWireSafe(),
            title = row.title.toWireTruncated(MAX_TITLE_CHARS),
            state = when (row.state) {
                TaskState.TODO -> WatchTaskState.TODO
                TaskState.DOING -> WatchTaskState.DOING
                TaskState.BLOCKED -> WatchTaskState.BLOCKED
                TaskState.DONE -> WatchTaskState.DONE
            },
        )

    // ---- loop runs -----------------------------------------------------------------------------

    /**
     * Up to [maxRuns] loop-run glances, newest-relevant first:
     *
     *  1. runs still going ([BackgroundJobs] jobs of kind "loop" with no `finishedAt`): glyph `running`,
     *     no step and **no spend** (the ledger is written when a run ends, so a live run has no figure;
     *     `null` is "unknown", not 0);
     *  2. runs that died before writing anything (a finished job with `failed = true`): glyph `failed`;
     *  3. finished runs, newest first, read from the run log ([GraphRunLog] root nodes in [tree]) and
     *     the ledger ([GraphRunLedger] rows, `surface = "loop"`, `chatId == runId`).
     *
     * **State is read, not guessed:** a finished run's glyph comes from the `stoppedBecause` the run log
     * recorded ([glyphForStop]); a reason this phone cannot classify becomes the open key
     * [ENDED_GLYPH_KEY], which the contract allows (the watch renders an unknown key as a generic shape
     * plus the key as its label) rather than being forced into "complete" or "failed".
     *
     * **Spend is the ledger's, summed, in the user's display currency** ([currencyCode], a label only:
     * no exchange rate is ever applied). A run with no ledger rows has `spend = null`. The `estimated`
     * flag is `true` only if some NON-on-device step's cost was itself an estimate; an all-on-device run
     * is a real zero (no money changed hands), the contract's own example of a real 0.
     */
    fun loopRuns(
        tree: MessageTree,
        ledger: List<LedgerEntry>,
        jobs: List<BackgroundJobs.Job>,
        loopName: (loopId: String?) -> String?,
        currencyCode: String,
        maxRuns: Int = MAX_LOOP_RUNS,
    ): List<LoopRunSnapshot> {
        // The label only; no rate is applied. normalize() guarantees a valid 3-letter upper-case ISO code.
        val currency = CurrencyPref.normalize(currencyCode)
        val loopJobs = jobs.filter { it.kind == LOOP_JOB_KIND }
        val running = loopJobs.filter { it.finishedAt == null }.sortedByDescending { it.startedAt }
            .map { jobSnapshot(it, StateGlyphKeys.RUNNING) }
        val diedEarly = loopJobs.filter { it.finishedAt != null && it.failed }.sortedByDescending { it.finishedAt }
            .map { jobSnapshot(it, StateGlyphKeys.FAILED) }

        val spendByRun: Map<String, List<LedgerEntry>> =
            ledger.filter { it.surface == LOOP_SURFACE }.groupBy { it.chatId }

        val finished = tree.roots()
            .filter { it.metadata.containsKey(GraphRunLog.TAG_RUN) }
            .sortedByDescending { it.createdAt }
            .mapNotNull { root ->
                val runId = root.metadata[GraphRunLog.TAG_RUN]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val loopId = root.metadata[GraphRunLog.TAG_LOOP]
                val last = tree.descendToLeaf(root.id)?.takeIf { it != root.id }?.let(tree::node)
                LoopRunSnapshot(
                    runId = runId.toWireSafe(),
                    loopName = displayName(loopName(loopId)),
                    stateGlyphKey = glyphForStop(root.metadata[GraphRunLog.TAG_STOPPED]),
                    stepName = last?.metadata?.get(GraphRunLog.TAG_ROLE)?.toWireTruncated(MAX_TITLE_CHARS)?.takeIf { it.isNotBlank() },
                    spend = spendOf(spendByRun[runId], currency),
                )
            }

        return (running + diedEarly + finished).take(maxRuns)
    }

    private fun jobSnapshot(job: BackgroundJobs.Job, glyph: String): LoopRunSnapshot =
        LoopRunSnapshot(
            runId = job.id.toWireSafe().ifBlank { "job" },
            loopName = displayName(job.label),
            stateGlyphKey = glyph,
            stepName = null,
            spend = null,
        )

    private fun displayName(name: String?): String =
        name?.toWireTruncated(MAX_TITLE_CHARS)?.takeIf { it.isNotBlank() } ?: UNSAVED_LOOP_NAME

    /**
     * Maps `GraphRunResult.stoppedBecause` (as `GraphRunLog` stored it on the run's root) to a glyph key.
     * The reasons are the ones `GraphRunner` emits; anything else is [ENDED_GLYPH_KEY], not a guess.
     */
    fun glyphForStop(stoppedBecause: String?): String = when {
        stoppedBecause == null -> ENDED_GLYPH_KEY
        stoppedBecause == "reached end" -> StateGlyphKeys.COMPLETE
        stoppedBecause == "cancelled" ||
            stoppedBecause.startsWith("hit step cap") ||
            stoppedBecause.startsWith("budget:") -> StateGlyphKeys.STOPPED
        stoppedBecause.startsWith("missing params:") ||
            stoppedBecause == "no start event" ||
            stoppedBecause == "no outgoing edge" -> StateGlyphKeys.FAILED
        else -> ENDED_GLYPH_KEY
    }

    private fun spendOf(entries: List<LedgerEntry>?, currencyCode: String): Spend? {
        if (entries.isNullOrEmpty()) return null
        return Spend(
            amountMinor = entries.sumOf { it.estCostMinor.coerceAtLeast(0) },
            currency = currencyCode,
            estimated = entries.any { it.tier != Tier.ON_DEVICE && it.estimated },
        )
    }
}
