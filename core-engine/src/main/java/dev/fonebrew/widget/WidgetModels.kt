package dev.fonebrew.widget

import dev.fonebrew.data.AgentReviewHolder
import dev.fonebrew.data.BackgroundJobs
import dev.fonebrew.data.GenerationStatus
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.model.DefaultModelPolicy
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.tasks.TaskSource
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.data.watch.WristSnapshots
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.StateGlyphKeys
import java.math.BigDecimal
import java.util.Currency

/*
 * The pure half of Fonebrew's home-screen widgets (docs/widgets.md): what each widget shows, decided
 * from already-read app state. No Android, no clock, no I/O, so all of it is JVM-tested
 * (src/test/.../widget/). The Android half (WidgetRender.kt) only turns these models into RemoteViews
 * and string resources; it makes no decisions of its own.
 *
 * Privacy, decided here and nowhere else: no conversation text, no typed input, no file paths, no
 * keys. A task whose title was cut from a chat turn is withheld; the agent's objective, its file list
 * and the repo name never reach a model; a download's error message is reduced to "failed".
 */

// ---- sizes ---------------------------------------------------------------------------------------

/** The layouts a widget can switch between as the launcher resizes it. */
enum class WidgetSize { SMALL, WIDE, MEDIUM, LARGE }

/**
 * One size layout and the smallest launcher size (dp) it is designed for. Android 12+ launchers
 * receive every layout at once (`RemoteViews(Map<SizeF, RemoteViews>)`) and pick the one that fits
 * the widget's current size, without waking the app.
 */
data class SizeBreak(val size: WidgetSize, val widthDp: Float, val heightDp: Float)

object WidgetSizes {
    // Android's cell formula, 70n - 30 dp: 1 cell = 40 dp, 2 = 110, 3 = 180, 4 = 250.
    val SMALL = SizeBreak(WidgetSize.SMALL, 110f, 40f)
    val MEDIUM = SizeBreak(WidgetSize.MEDIUM, 110f, 110f)
    val LARGE = SizeBreak(WidgetSize.LARGE, 110f, 180f)

    /**
     * Pads are derived from the 48 dp touch target, not from cell counts: a row of pads needs
     * 48 dp + 2 x 4 dp gap + 2 x 4 dp widget padding = 64 dp of height, two rows need 120 dp. Width:
     * a pad shows its glyph over its label, and "New chat" needs about 64 dp plus its gaps, so three
     * pads need 250 dp and a two-wide grid needs 160 dp. Below a breakpoint the launcher falls back to
     * a smaller layout, down to [SMALL]: one New chat pad, with the whole widget as its tap target.
     */
    val PAD_WIDE = SizeBreak(WidgetSize.WIDE, 250f, 64f)
    val PAD_MEDIUM = SizeBreak(WidgetSize.MEDIUM, 160f, 120f)
    val PAD_LARGE = SizeBreak(WidgetSize.LARGE, 250f, 120f)

    /** list / stat / progress / card: one-line summary, then three rows, then five. */
    val STACKED: List<SizeBreak> = listOf(SMALL, MEDIUM, LARGE)

    /** One pad (2x1), a row of three (4x1), a 2x2 grid of four, a 3x2 grid of six. */
    val PADS: List<SizeBreak> = listOf(SMALL, PAD_WIDE, PAD_MEDIUM, PAD_LARGE)
}

/** Every widget this app ships, with the template it follows (strategy §5.3) and its size layouts. */
enum class WidgetKind(val template: String, val sizes: List<SizeBreak>) {
    NEXT_UP("list", WidgetSizes.STACKED),
    MODEL("stat", WidgetSizes.STACKED),
    DOWNLOADS("progress", WidgetSizes.STACKED),
    AGENT_REVIEW("card", WidgetSizes.STACKED),
    LOOP_RUNS("list", WidgetSizes.STACKED),
    QUICK_START("pads", WidgetSizes.PADS),
}

/** Collapse whitespace and control characters to single spaces and cap the length with an ellipsis. */
internal fun oneLine(text: String, maxChars: Int): String {
    val flat = text.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }
        .joinToString("").replace(Regex(" {2,}"), " ").trim()
    return if (flat.length > maxChars) flat.take(maxChars - 1).trimEnd() + "…" else flat
}

// ---- Next up (list) ------------------------------------------------------------------------------

/** The due buckets the app's own To-do row uses ([dev.fonebrew.domain.tasks.TaskDue]), same thresholds. */
enum class DueKind { OVERDUE, SOON, TODAY, IN_DAYS, NONE }

data class NextUpRow(
    val taskId: String,
    /** Null when withheld: the title was cut from a chat turn, which is conversation text. */
    val title: String?,
    val state: TaskState,
    val due: DueKind,
    /** Whole days until due, for [DueKind.IN_DAYS]; 0 otherwise. */
    val inDays: Int = 0,
) {
    val withheld: Boolean get() = title == null
}

data class NextUpModel(
    val rows: List<NextUpRow>,
    /** Every task that isn't done (not only the listed ones). */
    val openCount: Int,
    /** Open tasks already past their due time. */
    val overdueCount: Int,
    /** Open tasks due within the next day (not yet overdue). */
    val dueCount: Int,
    /** The next instant a listed label would change (a task falls due); null if none ever will. */
    val nextBoundaryAt: Long?,
)

object NextUp {
    const val MAX_ROWS: Int = 5
    const val MAX_TITLE_CHARS: Int = 80
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS

    /**
     * [tasks] in the store's own order (manual To-do order, as `TaskDao.observeAll` returns them).
     * Open tasks only: those due within a day (overdue first, then the soonest) come first, then the
     * rest in the user's own To-do order, so the widget is the top of the list the app shows.
     */
    fun build(tasks: List<TaskEntity>, now: Long, maxRows: Int = MAX_ROWS): NextUpModel {
        val open = tasks.filter { it.state != TaskState.DONE && it.id.isNotBlank() }
        val dueSoon = open.filter { it.dueAt != null && it.dueAt - now < DAY_MS }.sortedBy { it.dueAt }
        val dueIds = dueSoon.mapTo(HashSet()) { it.id }
        val ordered = dueSoon + open.filterNot { it.id in dueIds }
        return NextUpModel(
            rows = ordered.take(maxRows.coerceAtLeast(0)).map { row(it, now) },
            openCount = open.size,
            overdueCount = dueSoon.count { (it.dueAt ?: now) < now },
            dueCount = dueSoon.count { (it.dueAt ?: now) >= now },
            nextBoundaryAt = open.mapNotNull { t -> t.dueAt?.let { boundaryAfter(it, now) } }.minOrNull(),
        )
    }

    private fun row(task: TaskEntity, now: Long): NextUpRow {
        val (kind, days) = dueKind(task.dueAt, now)
        return NextUpRow(
            taskId = task.id,
            title = if (task.source == TaskSource.CHAT) null else oneLine(task.title, MAX_TITLE_CHARS),
            state = task.state,
            due = kind,
            inDays = days,
        )
    }

    /** Mirrors `TaskDue.label`: overdue < 0, due soon < 1 h, today < 24 h, else whole days. */
    fun dueKind(dueAt: Long?, now: Long): Pair<DueKind, Int> {
        if (dueAt == null) return DueKind.NONE to 0
        val diff = dueAt - now
        return when {
            diff < 0 -> DueKind.OVERDUE to 0
            diff < HOUR_MS -> DueKind.SOON to 0
            diff < DAY_MS -> DueKind.TODAY to 0
            else -> DueKind.IN_DAYS to (diff / DAY_MS).toInt()
        }
    }

    /** The first instant after [now] at which [dueKind] of [dueAt] changes, or null once overdue. */
    fun boundaryAfter(dueAt: Long, now: Long): Long? {
        val diff = dueAt - now
        val threshold = when {
            diff < 0 -> return null
            diff < HOUR_MS -> 0L
            diff < DAY_MS -> HOUR_MS
            else -> (diff / DAY_MS) * DAY_MS
        }
        return dueAt - threshold + 1
    }
}

// ---- Model (stat) --------------------------------------------------------------------------------

enum class ModelPhase { NO_MODEL, READY, LOADING, GENERATING }

/** The chat's active model as the widget may show it: a display name and where it runs. */
data class ActiveModel(val name: String, val onDevice: Boolean)

data class ModelStatusModel(
    val phase: ModelPhase,
    val mode: GenerationStatus.Mode,
    val model: ActiveModel?,
    /**
     * Where the turn in flight runs when that isn't simply [model] (an image, a 3D object or a
     * Council): false means a cloud model or provider is part of it. Null when idle, or when the turn
     * runs on [model].
     */
    val runOnDevice: Boolean?,
    val since: Long?,
    val lastEndedAt: Long?,
    val modelsOnDevice: Int,
    val downloadsRunning: Int,
)

object ModelStatus {
    const val MAX_NAME_CHARS: Int = 60

    /**
     * The chat's active model. Once a chat screen has reported in this process its own choice wins
     * (the user may have switched to a cloud model this session); before that, the same
     * [DefaultModelPolicy] the chat starts from, which never resolves a cloud model.
     */
    fun activeModel(specs: List<ModelSpec>, status: GenerationStatus.Snapshot, persistedId: String?): ActiveModel? {
        val spec = if (status.reported) {
            status.activeModelId?.let { id -> specs.firstOrNull { it.id == id } }
        } else {
            DefaultModelPolicy.resolveActive(specs, persistedId)
        }
        return spec?.let { ActiveModel(oneLine(displayName(it), MAX_NAME_CHARS), it.isOnDevice) }
    }

    /**
     * A cloud spec's display name already starts with the cloud glyph (`CloudProvider.toSpec`); the
     * widget draws provenance itself (glyph and word), so the name is shown without it.
     */
    private fun displayName(spec: ModelSpec): String =
        if (spec.isOnDevice) spec.displayName else spec.displayName.trimStart().removePrefix(CLOUD_GLYPH).trimStart()

    private const val CLOUD_GLYPH = "☁"

    /** Whether what the widget describes involves a cloud model: the turn in flight, else the active model. */
    fun usesCloud(m: ModelStatusModel): Boolean = m.runOnDevice?.not() ?: (m.model?.onDevice == false)

    /** On-device models a chat can use: downloaded GGUFs and Gemini Nano. The dev echo stand-ins don't count. */
    fun modelsOnDevice(specs: List<ModelSpec>): Int =
        specs.count { it.runtime == Runtime.LOCAL_GGUF || it.runtime == Runtime.AICORE_NANO }

    fun build(
        status: GenerationStatus.Snapshot,
        active: ActiveModel?,
        modelsOnDevice: Int,
        downloadsRunning: Int,
    ): ModelStatusModel =
        ModelStatusModel(
            phase = when (status.phase) {
                GenerationStatus.Phase.LOADING -> ModelPhase.LOADING
                GenerationStatus.Phase.GENERATING -> ModelPhase.GENERATING
                GenerationStatus.Phase.IDLE -> if (active == null) ModelPhase.NO_MODEL else ModelPhase.READY
            },
            mode = status.mode,
            model = active,
            runOnDevice = status.runOnDevice.takeIf { status.phase != GenerationStatus.Phase.IDLE },
            since = status.since,
            lastEndedAt = status.lastEndedAt,
            modelsOnDevice = modelsOnDevice,
            downloadsRunning = downloadsRunning,
        )
}

// ---- Model downloads (progress) ------------------------------------------------------------------

/** One `DownloadCenter` entry, reduced to what a widget may use (no URL, no error message). */
data class DownloadInput(
    val id: String,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val done: Boolean,
    val failed: Boolean,
    val paused: Boolean,
)

enum class DownloadPhase { RUNNING, PAUSED, FAILED }

data class DownloadRow(
    val id: String,
    val name: String,
    val phase: DownloadPhase,
    val downloadedBytes: Long,
    /** <= 0 when the server sent no length: the bar is hidden rather than guessed. */
    val totalBytes: Long,
) {
    val percent: Int? get() = Downloads.percent(downloadedBytes, totalBytes)
}

data class DownloadsModel(
    val rows: List<DownloadRow>,
    val runningCount: Int,
    /** Across running downloads with a known length, like the app's own top dock; null if none has one. */
    val overallPercent: Int?,
) {
    /**
     * What a redraw depends on: whole percents, not bytes, so a fast download doesn't redraw per chunk.
     * A download whose server sent no length has no percent, so its bytes count in steps of
     * [Downloads.UNKNOWN_LENGTH_STEP_BYTES] instead (its "X so far" line still moves).
     */
    val signature: List<Any?>
        get() = rows.map {
            listOf(it.id, it.phase, it.percent, if (it.percent == null) it.downloadedBytes / Downloads.UNKNOWN_LENGTH_STEP_BYTES else null)
        }
}

object Downloads {
    const val MAX_NAME_CHARS: Int = 60

    /** Redraw step for a download with no known length: 8 MiB. */
    const val UNKNOWN_LENGTH_STEP_BYTES: Long = 8L shl 20

    fun percent(downloaded: Long, total: Long): Int? =
        if (total <= 0) null else ((downloaded.coerceAtLeast(0) * 100) / total).toInt().coerceIn(0, 100)

    /** Running first, then paused, then failed; finished downloads are not listed (the app drops them too). */
    fun build(inputs: List<DownloadInput>): DownloadsModel {
        val rows = inputs.filterNot { it.done && !it.failed }.map { input ->
            DownloadRow(
                id = input.id,
                name = oneLine(input.fileName.substringAfterLast('/'), MAX_NAME_CHARS),
                phase = when {
                    input.paused -> DownloadPhase.PAUSED
                    input.failed -> DownloadPhase.FAILED
                    else -> DownloadPhase.RUNNING
                },
                downloadedBytes = input.downloadedBytes,
                totalBytes = input.totalBytes,
            )
        }.sortedWith(compareBy<DownloadRow> { it.phase.ordinal }.thenBy { it.name })
        val running = rows.filter { it.phase == DownloadPhase.RUNNING }
        val known = running.filter { it.totalBytes > 0 }
        return DownloadsModel(
            rows = rows,
            runningCount = running.size,
            overallPercent = if (known.isEmpty()) null else percent(known.sumOf { it.downloadedBytes }, known.sumOf { it.totalBytes }),
        )
    }
}

// ---- Agent review (card) -------------------------------------------------------------------------

enum class AgentPhase { NO_HOST, IDLE, WORKING, READY, NOTHING_PROPOSED, FAILED }

/** What the widget may know about a pending proposal: counts and provenance, never paths or the objective. */
data class PendingSummary(
    val changeCount: Int,
    val filesRead: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val onDevice: Boolean,
    val proposedAt: Long,
) {
    companion object {
        fun of(pending: AgentReviewHolder.Pending): PendingSummary =
            PendingSummary(
                changeCount = pending.changeCount,
                filesRead = pending.proposal.filesRead.size,
                linesAdded = pending.lineStat.added,
                linesRemoved = pending.lineStat.removed,
                onDevice = pending.onDevice,
                proposedAt = pending.proposedAt,
            )
    }
}

data class AgentReviewModel(
    val phase: AgentPhase,
    val pending: PendingSummary? = null,
    /** When the shown phase began (a run started, a proposal landed, a run failed). */
    val at: Long? = null,
    /**
     * Where the model behind the shown state runs: the run in progress (WORKING) or the one that made
     * the proposal (READY, NOTHING_PROPOSED). False is a cloud model, a watched object. Null when no
     * model is involved or the Develop room didn't say.
     */
    val onDevice: Boolean? = null,
)

object AgentReview {
    /** The kind the Develop room's agent run registers with [BackgroundJobs]. */
    const val AGENT_JOB_KIND: String = "agent"

    /**
     * A run in progress wins (a new run clears the old proposal); then a waiting proposal; then the
     * newest finished run if it failed; otherwise idle, or "no Git host" when the agent has none it can
     * use ([hasHost]: the first host has a token, as `AgentRepoRunner.hasHost` requires).
     * A job's label (it carries the typed objective) is never read. [runningOnDevice] is where the run
     * in progress executes, as the Develop room recorded it when the run started.
     */
    fun build(
        hasHost: Boolean,
        pending: PendingSummary?,
        jobs: List<BackgroundJobs.Job>,
        runningOnDevice: Boolean? = null,
    ): AgentReviewModel {
        val agentJobs = jobs.filter { it.kind == AGENT_JOB_KIND }
        agentJobs.filter { it.finishedAt == null }.maxByOrNull { it.startedAt }?.let {
            return AgentReviewModel(AgentPhase.WORKING, at = it.startedAt, onDevice = runningOnDevice)
        }
        if (pending != null) {
            val phase = if (pending.changeCount > 0) AgentPhase.READY else AgentPhase.NOTHING_PROPOSED
            return AgentReviewModel(phase, pending, at = pending.proposedAt, onDevice = pending.onDevice)
        }
        val last = agentJobs.filter { it.finishedAt != null }.maxByOrNull { it.finishedAt ?: 0L }
        if (last != null && last.failed) return AgentReviewModel(AgentPhase.FAILED, at = last.finishedAt)
        return AgentReviewModel(if (hasHost) AgentPhase.IDLE else AgentPhase.NO_HOST)
    }
}

// ---- Loop runs (list) ----------------------------------------------------------------------------

enum class LoopRunState { RUNNING, COMPLETE, STOPPED, FAILED, OTHER }

/** A ledger spend for display: an amount string (currency code + major units) and whether it's an estimate. */
data class SpendText(val amount: String, val estimated: Boolean, val minorUnits: Boolean = false)

data class LoopRunRow(
    val runId: String,
    /** Null for a run with no saved loop behind it (shown as "Unsaved loop"). */
    val loopName: String?,
    val state: LoopRunState,
    /** The run log's own key, shown as the word for a state this phone can't classify. */
    val stateKey: String,
    /** The loop step's name (a node name from the loop editor), never a step's output. */
    val step: String?,
    val spend: SpendText?,
)

object LoopRuns {
    /** The kind the Loop room registers a run with in [BackgroundJobs] (`LoopRoom.startRun`). */
    const val LOOP_JOB_KIND: String = "loop"
    const val MAX_ROWS: Int = 5
    const val MAX_NAME_CHARS: Int = 60

    /**
     * Rows from the same assembly the watch uses ([WristSnapshots.loopRuns]: running runs, runs that
     * died early, then finished runs newest first, read from the run log and the ledger).
     */
    fun build(snapshots: List<LoopRunSnapshot>, max: Int = MAX_ROWS): List<LoopRunRow> =
        snapshots.take(max.coerceAtLeast(0)).map { s ->
            LoopRunRow(
                runId = s.runId,
                loopName = s.loopName.takeUnless { it == WristSnapshots.UNSAVED_LOOP_NAME || it.isBlank() }
                    ?.let { oneLine(it, MAX_NAME_CHARS) },
                state = when (s.stateGlyphKey) {
                    StateGlyphKeys.RUNNING -> LoopRunState.RUNNING
                    StateGlyphKeys.COMPLETE -> LoopRunState.COMPLETE
                    StateGlyphKeys.STOPPED -> LoopRunState.STOPPED
                    StateGlyphKeys.FAILED -> LoopRunState.FAILED
                    else -> LoopRunState.OTHER
                },
                stateKey = s.stateGlyphKey,
                step = s.stepName?.let { oneLine(it, MAX_NAME_CHARS) }?.takeIf { it.isNotBlank() },
                spend = s.spend?.let(::spend),
            )
        }

    /** The ledger's integer minor units as a decimal; an unknown currency stays in minor units, labelled. */
    fun spend(spend: Spend): SpendText {
        val digits = try {
            Currency.getInstance(spend.currency).defaultFractionDigits
        } catch (_: IllegalArgumentException) {
            -1
        }
        if (digits < 0) return SpendText("${spend.amountMinor} ${spend.currency}", spend.estimated, minorUnits = true)
        val major = BigDecimal.valueOf(spend.amountMinor).movePointLeft(digits).setScale(digits)
        return SpendText("${spend.currency} ${major.toPlainString()}", spend.estimated)
    }
}

// ---- Quick start (pads) --------------------------------------------------------------------------

/** A pad and the place in the app it opens. */
enum class PadAction(val destination: WidgetDestination) {
    NEW_CHAT(WidgetDestination.NEW_CHAT),
    TASKS(WidgetDestination.TASKS),
    AGENT(WidgetDestination.AGENT_REVIEW),
    LOOPS(WidgetDestination.LOOPS),
    MODELS(WidgetDestination.MODELS),
    CHATS(WidgetDestination.CHATS),
}

object QuickStart {
    /**
     * Below a pads breakpoint ([WidgetSizes.PADS]: where every pad gets its 48 dp) the widget is one pad,
     * New chat, rather than squeezed controls. A one-row strip has room for three pads with labels.
     */
    fun pads(size: WidgetSize): List<PadAction> = when (size) {
        WidgetSize.SMALL -> listOf(PadAction.NEW_CHAT)
        WidgetSize.WIDE -> listOf(PadAction.NEW_CHAT, PadAction.TASKS, PadAction.AGENT)
        WidgetSize.MEDIUM -> listOf(PadAction.NEW_CHAT, PadAction.TASKS, PadAction.AGENT, PadAction.LOOPS)
        WidgetSize.LARGE -> PadAction.entries.toList()
    }
}
