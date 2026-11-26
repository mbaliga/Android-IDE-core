package dev.fonebrew.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import dev.fonebrew.core_engine.R
import dev.fonebrew.data.GenerationStatus
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.ui.MainActivity
import java.util.Locale

/**
 * The Android half of the home-screen widgets: turns the pure models of WidgetModels.kt into
 * RemoteViews. Every word comes from a string resource and every colour from a day/night colour
 * resource; nothing here decides what to show (that is the models' job, and it is JVM-tested).
 *
 * Each widget is sent as one size-mapped RemoteViews (API 31+, this app's minSdk), so the launcher
 * picks the small, medium or large layout itself as the widget is resized, without waking the app.
 *
 * Shapes that carry a state are text glyphs next to a word, the same vocabulary the app uses
 * (⌂ on-device, ☁ cloud, ▲ overdue): colour only ever adds emphasis, it never carries the meaning.
 */
internal object WidgetRender {

    // ---- glyphs (shapes; the word next to each carries the meaning) ------------------------------
    private const val G_OPEN = "○"
    private const val G_DOING = "◐"
    private const val G_BLOCKED = "■"
    private const val G_OVERDUE = "▲"
    private const val G_WITHHELD = "◌"
    private const val G_CLEAR = "✓"
    private const val G_RUNNING = "▶"
    private const val G_LOADING = "◌"
    private const val G_READY = "●"
    private const val G_NONE = "○"
    private const val G_ON_DEVICE = "⌂"
    private const val G_CLOUD = "☁"
    private const val G_DOWN = "↓"
    private const val G_PAUSED = "‖"
    private const val G_FAILED = "✗"
    private const val G_REVIEW = "◆"
    private const val G_COMPLETE = "✓"
    private const val G_STOPPED = "■"
    private const val G_OTHER = "◇"

    private fun padGlyph(pad: PadAction): String = when (pad) {
        PadAction.NEW_CHAT -> "+"
        PadAction.TASKS -> "✓"
        PadAction.AGENT -> "‹›"
        PadAction.LOOPS -> "↻"
        PadAction.MODELS -> "▤"
        PadAction.CHATS -> "☰"
    }

    private data class RowIds(@IdRes val row: Int, @IdRes val glyph: Int, @IdRes val title: Int, @IdRes val meta: Int)

    private val ROWS = listOf(
        RowIds(R.id.row1, R.id.row1_glyph, R.id.row1_title, R.id.row1_meta),
        RowIds(R.id.row2, R.id.row2_glyph, R.id.row2_title, R.id.row2_meta),
        RowIds(R.id.row3, R.id.row3_glyph, R.id.row3_title, R.id.row3_meta),
        RowIds(R.id.row4, R.id.row4_glyph, R.id.row4_title, R.id.row4_meta),
        RowIds(R.id.row5, R.id.row5_glyph, R.id.row5_title, R.id.row5_meta),
    )

    private data class DownloadIds(@IdRes val group: Int, @IdRes val name: Int, @IdRes val meta: Int, @IdRes val bar: Int)

    private val DOWNLOADS = listOf(
        DownloadIds(R.id.dl1, R.id.dl1_name, R.id.dl1_meta, R.id.dl1_bar),
        DownloadIds(R.id.dl2, R.id.dl2_name, R.id.dl2_meta, R.id.dl2_bar),
        DownloadIds(R.id.dl3, R.id.dl3_name, R.id.dl3_meta, R.id.dl3_bar),
    )

    private val PADS = listOf(R.id.pad1, R.id.pad2, R.id.pad3, R.id.pad4, R.id.pad5, R.id.pad6)

    /** One list row as text: [emphasize] paints the glyph and meta in the accent (overdue only). */
    private data class RowText(val glyph: String, val title: String, val meta: String, val emphasize: Boolean = false) {
        val spoken: String get() = listOf(title, meta).filter { it.isNotBlank() }.joinToString(", ")
    }

    // ---- shared plumbing ---------------------------------------------------------------------------

    private fun sized(kind: WidgetKind, build: (SizeBreak) -> RemoteViews): RemoteViews =
        RemoteViews(kind.sizes.associate { SizeF(it.widthDp, it.heightDp) to build(it) })

    private fun views(context: Context, @LayoutRes layout: Int) = RemoteViews(context.packageName, layout)

    /** An explicit, immutable intent to MainActivity naming the destination (no URL scheme). */
    fun openIntent(context: Context, widgetId: Int, slot: Int, route: WidgetRoute): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(WidgetRoutes.ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(WidgetRoutes.EXTRA_DESTINATION, route.destination.name)
        route.taskId?.let { intent.putExtra(WidgetRoutes.EXTRA_TASK_ID, it) }
        return PendingIntent.getActivity(
            context,
            WidgetRoutes.requestCode(widgetId, slot),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** "Tue 14:05" in the user's locale and 12/24-hour setting: stays true without a redraw. */
    fun clock(context: Context, at: Long): String {
        val skeleton = if (DateFormat.is24HourFormat(context)) "EEEHm" else "EEEhm"
        return DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), at).toString()
    }

    private fun asOf(context: Context, now: Long) = context.getString(R.string.widget_as_of, clock(context, now))

    private fun sep(context: Context, vararg parts: String?): String =
        parts.filterNot { it.isNullOrBlank() }.map { it!! }.reduceOrNull { a, b -> context.getString(R.string.widget_separator, a, b) }.orEmpty()

    /** Provenance as a shape and a word (binding rule 2: a cloud model is always marked a watched object). */
    private fun provenanceMark(context: Context, onDevice: Boolean): String =
        (if (onDevice) "$G_ON_DEVICE " else "$G_CLOUD ") +
            context.getString(if (onDevice) R.string.widget_provenance_on_device else R.string.widget_provenance_cloud)

    /** Day and night values of a colour resource, for RemoteViews.setColorInt (resolved by the launcher). */
    private fun dayNight(context: Context, @ColorRes color: Int): Pair<Int, Int> {
        fun inMode(night: Int): Int {
            val config = Configuration(context.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            return context.createConfigurationContext(config).getColor(color)
        }
        return inMode(Configuration.UI_MODE_NIGHT_NO) to inMode(Configuration.UI_MODE_NIGHT_YES)
    }

    @Volatile private var accent: Pair<Int, Int>? = null

    private fun emphasize(context: Context, rv: RemoteViews, @IdRes vararg ids: Int) {
        val (day, night) = accent ?: dayNight(context, R.color.widget_accent).also { accent = it }
        ids.forEach { rv.setColorInt(it, "setTextColor", day, night) }
    }

    private fun opens(context: Context, rv: RemoteViews, widgetId: Int, slot: Int, route: WidgetRoute, spoken: String) {
        rv.setOnClickPendingIntent(android.R.id.background, openIntent(context, widgetId, slot, route))
        rv.setContentDescription(android.R.id.background, context.getString(R.string.widget_spoken_open, spoken, destinationName(context, route.destination)))
    }

    private fun destinationName(context: Context, d: WidgetDestination): String = context.getString(
        when (d) {
            WidgetDestination.NEW_CHAT, WidgetDestination.CHAT -> R.string.widget_dest_chat
            WidgetDestination.CHATS -> R.string.widget_pad_chats
            WidgetDestination.TASKS -> R.string.widget_dest_tasks
            WidgetDestination.AGENT_REVIEW -> R.string.widget_dest_agent
            WidgetDestination.LOOPS -> R.string.widget_dest_loops
            WidgetDestination.MODELS -> R.string.widget_dest_models
        },
    )

    fun label(context: Context, kind: WidgetKind): String = context.getString(
        when (kind) {
            WidgetKind.NEXT_UP -> R.string.widget_next_up_label
            WidgetKind.MODEL -> R.string.widget_model_label
            WidgetKind.DOWNLOADS -> R.string.widget_downloads_label
            WidgetKind.AGENT_REVIEW -> R.string.widget_agent_label
            WidgetKind.LOOP_RUNS -> R.string.widget_loops_label
            WidgetKind.QUICK_START -> R.string.widget_quick_label
        },
    )

    private fun homeRoute(kind: WidgetKind): WidgetRoute = WidgetRoute(
        when (kind) {
            WidgetKind.NEXT_UP -> WidgetDestination.TASKS
            WidgetKind.MODEL -> WidgetDestination.CHAT
            WidgetKind.DOWNLOADS -> WidgetDestination.MODELS
            WidgetKind.AGENT_REVIEW -> WidgetDestination.AGENT_REVIEW
            WidgetKind.LOOP_RUNS -> WidgetDestination.LOOPS
            // The error state of Quick start opens the chat, never "New chat": no side effect.
            WidgetKind.QUICK_START -> WidgetDestination.CHAT
        },
    )

    /** The error state: says so in words, names the app (from app_name), and still opens it. */
    fun error(context: Context, widgetId: Int, kind: WidgetKind): RemoteViews {
        val rv = views(context, R.layout.widget_loading)
        val title = label(context, kind)
        val body = context.getString(R.string.widget_error, context.getString(R.string.app_name))
        rv.setTextViewText(R.id.loading_title, title)
        rv.setTextViewText(R.id.loading_body, body)
        opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, homeRoute(kind), "$title. $body")
        return rv
    }

    private fun bindList(
        context: Context,
        rv: RemoteViews,
        header: String,
        headerMeta: String,
        rows: List<RowText>,
        slots: Int,
        emptyText: String,
    ) {
        rv.setTextViewText(R.id.header_title, header)
        rv.setTextViewText(R.id.header_meta, headerMeta)
        if (rows.isEmpty()) {
            rv.setTextViewText(R.id.empty_text, emptyText)
            rv.setViewVisibility(R.id.empty_text, View.VISIBLE)
        }
        ROWS.take(slots).forEachIndexed { i, ids ->
            val row = rows.getOrNull(i)
            if (row == null) {
                rv.setViewVisibility(ids.row, View.GONE)
            } else {
                rv.setTextViewText(ids.glyph, row.glyph)
                rv.setTextViewText(ids.title, row.title)
                rv.setTextViewText(ids.meta, row.meta)
                if (row.emphasize) emphasize(context, rv, ids.glyph, ids.meta)
            }
        }
    }

    private fun spokenList(header: String, rows: List<RowText>, emptyText: String, take: Int): String =
        (listOf(header) + rows.take(take).map { it.spoken }.ifEmpty { listOf(emptyText) }).joinToString(". ")

    // ---- Next up -----------------------------------------------------------------------------------

    private fun taskRow(context: Context, row: NextUpRow): RowText {
        val title = when {
            row.withheld -> context.getString(R.string.widget_task_withheld)
            row.title.isNullOrBlank() -> context.getString(R.string.widget_task_untitled)
            else -> row.title
        }
        val due = when (row.due) {
            DueKind.OVERDUE -> context.getString(R.string.widget_due_overdue)
            DueKind.SOON -> context.getString(R.string.widget_due_soon)
            DueKind.TODAY -> context.getString(R.string.widget_due_today)
            DueKind.IN_DAYS -> context.getString(R.string.widget_due_in_days, row.inDays)
            DueKind.NONE -> null
        }
        val state = when (row.state) {
            TaskState.DOING -> context.getString(R.string.widget_task_doing)
            TaskState.BLOCKED -> context.getString(R.string.widget_task_blocked)
            else -> null
        }
        val glyph = when {
            row.due == DueKind.OVERDUE -> G_OVERDUE
            row.withheld -> G_WITHHELD
            row.state == TaskState.DOING -> G_DOING
            row.state == TaskState.BLOCKED -> G_BLOCKED
            else -> G_OPEN
        }
        return RowText(glyph, title, sep(context, due, state), emphasize = row.due == DueKind.OVERDUE)
    }

    fun nextUp(context: Context, widgetId: Int, m: NextUpModel, now: Long): RemoteViews {
        val header = context.getString(R.string.widget_next_up_label)
        val asOf = asOf(context, now)
        val empty = context.getString(R.string.widget_next_up_empty)
        val rows = m.rows.map { taskRow(context, it) }
        val counts = sep(
            context,
            context.resources.getQuantityString(R.plurals.widget_tasks_open, m.openCount, m.openCount),
            m.overdueCount.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.widget_tasks_overdue, it, it) },
            m.dueCount.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.widget_tasks_due, it, it) },
        )
        val route = WidgetRoute(WidgetDestination.TASKS)
        return sized(WidgetKind.NEXT_UP) { br ->
            when (br.size) {
                WidgetSize.SMALL, WidgetSize.WIDE -> views(context, R.layout.widget_list_small).also { rv ->
                    val first = rows.firstOrNull()
                    rv.setTextViewText(R.id.small_glyph, first?.glyph ?: G_CLEAR)
                    rv.setTextViewText(R.id.small_title, first?.title ?: empty)
                    rv.setTextViewText(R.id.small_meta, sep(context, first?.meta, asOf))
                    if (first?.emphasize == true) emphasize(context, rv, R.id.small_glyph, R.id.small_meta)
                    // The one task shown is the target: the To-do list opens on it, highlighted.
                    val target = WidgetRoute(WidgetDestination.TASKS, m.rows.firstOrNull()?.taskId)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_SMALL, target, "$header. ${first?.spoken ?: empty}. $asOf")
                }
                WidgetSize.MEDIUM -> views(context, R.layout.widget_list_medium).also { rv ->
                    bindList(context, rv, header, asOf, rows, 3, empty)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, spokenList(header, rows, empty, 3) + ". $asOf")
                }
                WidgetSize.LARGE -> views(context, R.layout.widget_list_large).also { rv ->
                    bindList(context, rv, header, asOf, rows, 5, empty)
                    rv.setTextViewText(R.id.footer_text, counts)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, spokenList(header, rows, empty, 5) + ". $counts. $asOf")
                }
            }
        }
    }

    // ---- Model -------------------------------------------------------------------------------------

    fun model(context: Context, widgetId: Int, m: ModelStatusModel, now: Long): RemoteViews {
        val header = context.getString(R.string.widget_model_label)
        val asOf = asOf(context, now)
        val active = m.phase == ModelPhase.LOADING || m.phase == ModelPhase.GENERATING
        val mode = when (m.mode) {
            GenerationStatus.Mode.TEXT -> null
            GenerationStatus.Mode.COUNCIL -> context.getString(R.string.widget_mode_council)
            GenerationStatus.Mode.IMAGE -> context.getString(R.string.widget_mode_image)
            GenerationStatus.Mode.OBJECT_3D -> context.getString(R.string.widget_mode_object3d)
        }
        val (glyph, word) = when (m.phase) {
            ModelPhase.GENERATING -> G_RUNNING to sep(context, context.getString(R.string.widget_model_generating), mode)
            ModelPhase.LOADING -> G_LOADING to sep(context, context.getString(R.string.widget_model_loading), mode)
            ModelPhase.READY -> G_READY to context.getString(R.string.widget_model_ready)
            ModelPhase.NO_MODEL -> G_NONE to context.getString(R.string.widget_model_none)
        }
        val value = "$glyph $word"
        // An image, 3D or Council turn may not run on the chat's text model at all: then the line says
        // where the turn itself runs, so cloud work is never shown under an on-device model's name.
        val run = m.runOnDevice
        val runWords = run?.let { context.getString(if (it) R.string.widget_model_run_on_device else R.string.widget_model_run_cloud) }
        val modelLine = when {
            run != null -> (if (run) "$G_ON_DEVICE " else "$G_CLOUD ") + runWords
            m.model != null -> (if (m.model.onDevice) "$G_ON_DEVICE " else "$G_CLOUD ") + m.model.name
            else -> context.getString(R.string.widget_model_none_hint)
        }
        val provenanceOnDevice = run ?: m.model?.onDevice
        val provenance = provenanceOnDevice?.let {
            context.getString(if (it) R.string.widget_provenance_on_device else R.string.widget_provenance_cloud)
        }
        // The small layout has room for one mark: cloud, the watched case, is never left out.
        val smallCaption = if (ModelStatus.usesCloud(m)) sep(context, provenanceMark(context, onDevice = false), asOf) else asOf
        val time = when {
            active && m.since != null -> context.getString(R.string.widget_since, clock(context, m.since))
            !active && m.lastEndedAt != null -> context.getString(R.string.widget_model_last_ended, clock(context, m.lastEndedAt))
            else -> null
        }
        val onDeviceCount = context.resources.getQuantityString(R.plurals.widget_model_on_device_count, m.modelsOnDevice, m.modelsOnDevice)
        val downloads = if (m.downloadsRunning == 0) {
            context.getString(R.string.widget_model_no_downloads)
        } else {
            context.resources.getQuantityString(R.plurals.widget_model_downloads_running, m.downloadsRunning, m.downloadsRunning)
        }
        val route = WidgetRoute(if (m.phase == ModelPhase.NO_MODEL) WidgetDestination.MODELS else WidgetDestination.CHAT)
        val spokenModel = when {
            runWords != null -> "$runWords, $provenance"
            m.model != null -> context.getString(R.string.widget_model_chats_use, m.model.name) + ", " + provenance
            else -> modelLine
        }
        val base = "$header. $word. $spokenModel"
        return sized(WidgetKind.MODEL) { br ->
            when (br.size) {
                WidgetSize.SMALL, WidgetSize.WIDE -> views(context, R.layout.widget_stat_small).also { rv ->
                    rv.setTextViewText(R.id.stat_value, value)
                    rv.setTextViewText(R.id.stat_caption, smallCaption)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, sep(context, base, time) + ". $asOf")
                }
                WidgetSize.MEDIUM -> views(context, R.layout.widget_stat_medium).also { rv ->
                    rv.setTextViewText(R.id.header_title, header)
                    rv.setTextViewText(R.id.header_meta, asOf)
                    rv.setTextViewText(R.id.stat_value, value)
                    rv.setTextViewText(R.id.stat_line1, modelLine)
                    rv.setTextViewText(R.id.stat_line2, sep(context, provenance, time))
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, sep(context, base, time) + ". $asOf")
                }
                WidgetSize.LARGE -> views(context, R.layout.widget_stat_large).also { rv ->
                    rv.setTextViewText(R.id.header_title, header)
                    rv.setTextViewText(R.id.header_meta, asOf)
                    rv.setTextViewText(R.id.stat_value, value)
                    rv.setTextViewText(R.id.stat_line1, modelLine)
                    rv.setTextViewText(R.id.stat_line2, sep(context, provenance, time))
                    rv.setTextViewText(R.id.stat_line3, onDeviceCount)
                    rv.setTextViewText(R.id.stat_line4, downloads)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, sep(context, base, time) + ". $onDeviceCount. $downloads. $asOf")
                }
            }
        }
    }

    // ---- Model downloads ---------------------------------------------------------------------------

    private fun downloadPhase(context: Context, phase: DownloadPhase): Pair<String, String> = when (phase) {
        DownloadPhase.RUNNING -> G_DOWN to context.getString(R.string.widget_download_running)
        DownloadPhase.PAUSED -> G_PAUSED to context.getString(R.string.widget_download_paused)
        DownloadPhase.FAILED -> G_FAILED to context.getString(R.string.widget_download_failed)
    }

    /** The words of a download's meta line, without its glyph (for TalkBack). */
    private fun downloadWords(context: Context, row: DownloadRow): String {
        val bytes = if (row.totalBytes > 0) {
            context.getString(
                R.string.widget_download_bytes,
                Formatter.formatShortFileSize(context, row.downloadedBytes),
                Formatter.formatShortFileSize(context, row.totalBytes),
            )
        } else {
            context.getString(R.string.widget_download_bytes_unknown, Formatter.formatShortFileSize(context, row.downloadedBytes))
        }
        return sep(context, downloadPhase(context, row.phase).second, row.percent?.let { context.getString(R.string.widget_download_percent, it) }, bytes)
    }

    private fun downloadMeta(context: Context, row: DownloadRow): String =
        downloadPhase(context, row.phase).first + " " + downloadWords(context, row)

    private fun bindDownload(rv: RemoteViews, ids: DownloadIds, row: DownloadRow?, meta: String?) {
        if (row == null) {
            rv.setViewVisibility(ids.group, View.GONE)
            return
        }
        rv.setTextViewText(ids.name, row.name)
        rv.setTextViewText(ids.meta, meta)
        val percent = row.percent
        if (percent == null) {
            // No length from the server: no bar, rather than a guessed or animated one.
            rv.setViewVisibility(ids.bar, View.GONE)
        } else {
            rv.setProgressBar(ids.bar, 100, percent, false)
        }
    }

    fun downloads(context: Context, widgetId: Int, m: DownloadsModel, now: Long): RemoteViews {
        val header = context.getString(R.string.widget_downloads_label)
        val asOf = asOf(context, now)
        val empty = context.getString(R.string.widget_downloads_empty)
        val metas = m.rows.map { downloadMeta(context, it) }
        val count = context.resources.getQuantityString(R.plurals.widget_downloads_count, m.rows.size, m.rows.size)
        val route = WidgetRoute(WidgetDestination.MODELS)
        val spokenRows = m.rows.map { r -> "${r.name}, ${downloadWords(context, r)}" }
        return sized(WidgetKind.DOWNLOADS) { br ->
            when (br.size) {
                WidgetSize.SMALL, WidgetSize.WIDE -> views(context, R.layout.widget_progress_small).also { rv ->
                    val (glyph, word) = when {
                        m.rows.isEmpty() -> G_NONE to context.getString(R.string.widget_downloads_none_short)
                        m.runningCount > 0 && m.overallPercent != null -> G_DOWN to context.getString(R.string.widget_download_percent, m.overallPercent)
                        else -> downloadPhase(context, m.rows.first().phase)
                    }
                    val meta = if (m.rows.isEmpty()) asOf else sep(context, count, asOf)
                    rv.setTextViewText(R.id.small_title, "$glyph $word")
                    rv.setTextViewText(R.id.small_meta, meta)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, "$header. $word. $meta")
                }
                WidgetSize.MEDIUM -> views(context, R.layout.widget_progress_medium).also { rv ->
                    rv.setTextViewText(R.id.header_title, header)
                    rv.setTextViewText(R.id.header_meta, asOf)
                    if (m.rows.isEmpty()) {
                        rv.setTextViewText(R.id.empty_text, empty)
                        rv.setViewVisibility(R.id.empty_text, View.VISIBLE)
                    }
                    bindDownload(rv, DOWNLOADS[0], m.rows.firstOrNull(), metas.firstOrNull())
                    val spoken = (listOf(header) + spokenRows.take(1).ifEmpty { listOf(empty) } + listOf(asOf)).joinToString(". ")
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, spoken)
                }
                WidgetSize.LARGE -> views(context, R.layout.widget_progress_large).also { rv ->
                    rv.setTextViewText(R.id.header_title, header)
                    rv.setTextViewText(R.id.header_meta, asOf)
                    if (m.rows.isEmpty()) {
                        rv.setTextViewText(R.id.empty_text, empty)
                        rv.setViewVisibility(R.id.empty_text, View.VISIBLE)
                    }
                    DOWNLOADS.forEachIndexed { i, ids -> bindDownload(rv, ids, m.rows.getOrNull(i), metas.getOrNull(i)) }
                    val more = m.rows.size - DOWNLOADS.size
                    if (more > 0) rv.setTextViewText(R.id.footer_text, context.getString(R.string.widget_downloads_more, more))
                    val spoken = (listOf(header) + spokenRows.ifEmpty { listOf(empty) } + listOf(asOf)).joinToString(". ")
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, spoken)
                }
            }
        }
    }

    // ---- Agent review ------------------------------------------------------------------------------

    fun agentReview(context: Context, widgetId: Int, m: AgentReviewModel, now: Long): RemoteViews {
        val header = context.getString(R.string.widget_agent_label)
        val asOf = asOf(context, now)
        val p = m.pending
        val (glyph, word) = when (m.phase) {
            AgentPhase.READY -> G_REVIEW to context.resources.getQuantityString(R.plurals.widget_agent_ready, p?.changeCount ?: 0, p?.changeCount ?: 0)
            AgentPhase.WORKING -> G_LOADING to context.getString(R.string.widget_agent_working)
            AgentPhase.FAILED -> G_FAILED to context.getString(R.string.widget_agent_failed)
            AgentPhase.NOTHING_PROPOSED -> G_NONE to context.getString(R.string.widget_agent_nothing)
            AgentPhase.IDLE -> G_NONE to context.getString(R.string.widget_agent_idle)
            AgentPhase.NO_HOST -> G_NONE to context.getString(R.string.widget_agent_no_host)
        }
        val title = "$glyph $word"
        val body = context.getString(
            when (m.phase) {
                AgentPhase.READY -> R.string.widget_agent_ready_body
                AgentPhase.WORKING -> R.string.widget_agent_working_body
                AgentPhase.FAILED -> R.string.widget_agent_failed_body
                AgentPhase.NOTHING_PROPOSED, AgentPhase.IDLE -> R.string.widget_agent_idle_body
                AgentPhase.NO_HOST -> R.string.widget_agent_no_host_body
            },
        )
        val time = m.at?.let { context.getString(R.string.widget_since, clock(context, it)) }
        // Provenance of the model behind this state (a run in progress, or the proposal), at every size.
        val prov = m.onDevice?.let { provenanceMark(context, it) }
        val detail = if (m.phase == AgentPhase.READY && p != null) {
            sep(
                context,
                context.getString(R.string.widget_agent_lines, p.linesAdded, p.linesRemoved),
                context.resources.getQuantityString(R.plurals.widget_agent_files_read, p.filesRead, p.filesRead),
                (if (p.onDevice) "$G_ON_DEVICE " else "$G_CLOUD ") +
                    context.getString(if (p.onDevice) R.string.widget_agent_by_on_device else R.string.widget_agent_by_cloud),
            )
        } else {
            prov
        }
        val smallMeta = if (m.onDevice == false) sep(context, prov, asOf) else asOf
        val action = context.getString(if (m.phase == AgentPhase.READY) R.string.widget_agent_action_review else R.string.widget_agent_action_open)
        val route = WidgetRoute(WidgetDestination.AGENT_REVIEW)
        val spoken = sep(context, "$header. $word", prov, time)
        return sized(WidgetKind.AGENT_REVIEW) { br ->
            when (br.size) {
                WidgetSize.SMALL, WidgetSize.WIDE -> views(context, R.layout.widget_card_small).also { rv ->
                    rv.setTextViewText(R.id.card_title, title)
                    // The snapshot time, always (it stays true if this frame outlives the process),
                    // after the cloud mark when a cloud model is behind this state.
                    rv.setTextViewText(R.id.card_meta, smallMeta)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, "$spoken. $asOf")
                }
                WidgetSize.MEDIUM -> views(context, R.layout.widget_card_medium).also { rv ->
                    rv.setTextViewText(R.id.header_title, header)
                    rv.setTextViewText(R.id.header_meta, asOf)
                    rv.setTextViewText(R.id.card_title, title)
                    rv.setTextViewText(R.id.card_body, sep(context, prov, time, body))
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, "$spoken. $body. $asOf")
                }
                WidgetSize.LARGE -> views(context, R.layout.widget_card_large).also { rv ->
                    rv.setTextViewText(R.id.header_title, header)
                    rv.setTextViewText(R.id.header_meta, asOf)
                    rv.setTextViewText(R.id.card_title, title)
                    rv.setTextViewText(R.id.card_body, body)
                    val detailLine = sep(context, detail, time)
                    if (detailLine.isBlank()) rv.setViewVisibility(R.id.card_detail, View.GONE) else rv.setTextViewText(R.id.card_detail, detailLine)
                    rv.setTextViewText(R.id.card_action, action)
                    rv.setContentDescription(R.id.card_action, action)
                    rv.setOnClickPendingIntent(R.id.card_action, openIntent(context, widgetId, WidgetRoutes.SLOT_ACTION, route))
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, sep(context, "$spoken. $body", detail.takeIf { m.phase == AgentPhase.READY }) + ". $asOf")
                }
            }
        }
    }

    // ---- Loop runs ---------------------------------------------------------------------------------

    private fun loopState(context: Context, row: LoopRunRow): Pair<String, String> = when (row.state) {
        LoopRunState.RUNNING -> G_RUNNING to context.getString(R.string.widget_loop_running)
        LoopRunState.COMPLETE -> G_COMPLETE to context.getString(R.string.widget_loop_complete)
        LoopRunState.STOPPED -> G_STOPPED to context.getString(R.string.widget_loop_stopped)
        LoopRunState.FAILED -> G_FAILED to context.getString(R.string.widget_loop_failed)
        // A stop reason this phone can't classify: a neutral shape and the run log's own word.
        LoopRunState.OTHER -> G_OTHER to row.stateKey
    }

    private fun spendText(context: Context, spend: SpendText?): String? = spend?.let {
        when {
            it.minorUnits -> context.getString(R.string.widget_loop_spend_minor, it.amount)
            it.estimated -> context.getString(R.string.widget_loop_spend_estimated, it.amount)
            else -> it.amount
        }
    }

    fun loopRuns(context: Context, widgetId: Int, runs: List<LoopRunRow>, now: Long): RemoteViews {
        val header = context.getString(R.string.widget_loops_label)
        val asOf = asOf(context, now)
        val empty = context.getString(R.string.widget_loops_empty)
        fun name(r: LoopRunRow) = r.loopName ?: context.getString(R.string.widget_loop_unsaved)
        val rows = runs.map { r ->
            val (glyph, word) = loopState(context, r)
            RowText(glyph, name(r), word)
        }
        val richRows = runs.map { r ->
            val (glyph, word) = loopState(context, r)
            RowText(glyph, name(r), sep(context, word, spendText(context, r.spend)))
        }
        val route = WidgetRoute(WidgetDestination.LOOPS)
        return sized(WidgetKind.LOOP_RUNS) { br ->
            when (br.size) {
                WidgetSize.SMALL, WidgetSize.WIDE -> views(context, R.layout.widget_list_small).also { rv ->
                    val first = runs.firstOrNull()
                    val row = rows.firstOrNull()
                    rv.setTextViewText(R.id.small_glyph, row?.glyph ?: G_NONE)
                    rv.setTextViewText(R.id.small_title, row?.title ?: empty)
                    val meta = sep(context, row?.meta, first?.step, asOf)
                    rv.setTextViewText(R.id.small_meta, meta)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, "$header. ${row?.title ?: empty}. $meta")
                }
                WidgetSize.MEDIUM -> views(context, R.layout.widget_list_medium).also { rv ->
                    bindList(context, rv, header, asOf, rows, 3, empty)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, spokenList(header, rows, empty, 3) + ". $asOf")
                }
                WidgetSize.LARGE -> views(context, R.layout.widget_list_large).also { rv ->
                    bindList(context, rv, header, asOf, richRows, 5, empty)
                    val footer = if (runs.isEmpty()) "" else context.resources.getQuantityString(R.plurals.widget_loops_count, runs.size, runs.size)
                    rv.setTextViewText(R.id.footer_text, footer)
                    opens(context, rv, widgetId, WidgetRoutes.SLOT_MAIN, route, spokenList(header, richRows, empty, 5) + ". $asOf")
                }
            }
        }
    }

    // ---- Quick start -------------------------------------------------------------------------------

    private fun padLabel(context: Context, pad: PadAction): String = context.getString(
        when (pad) {
            PadAction.NEW_CHAT -> R.string.widget_pad_new_chat
            PadAction.TASKS -> R.string.widget_pad_tasks
            PadAction.AGENT -> R.string.widget_pad_agent
            PadAction.LOOPS -> R.string.widget_pad_loops
            PadAction.MODELS -> R.string.widget_pad_models
            PadAction.CHATS -> R.string.widget_pad_chats
        },
    )

    fun quickStart(context: Context, widgetId: Int): RemoteViews =
        sized(WidgetKind.QUICK_START) { br ->
            val layout = when (br.size) {
                WidgetSize.SMALL -> R.layout.widget_pads_small
                WidgetSize.WIDE -> R.layout.widget_pads_wide
                WidgetSize.MEDIUM -> R.layout.widget_pads_medium
                WidgetSize.LARGE -> R.layout.widget_pads_large
            }
            views(context, layout).also { rv ->
                QuickStart.pads(br.size).forEachIndexed { i, pad ->
                    val id = PADS[i]
                    val label = padLabel(context, pad)
                    // One pad fills a strip: glyph and label on one line. In a grid or row, the glyph sits
                    // over the label so the label gets the pad's whole width.
                    val text = if (br.size == WidgetSize.SMALL) "${padGlyph(pad)}  $label" else "${padGlyph(pad)}\n$label"
                    val intent = openIntent(context, widgetId, WidgetRoutes.padSlot(pad), WidgetRoute(pad.destination))
                    rv.setTextViewText(id, text)
                    rv.setContentDescription(id, label)
                    rv.setOnClickPendingIntent(id, intent)
                    // The one-pad layout: the whole widget is the target, so it is never smaller than
                    // the launcher's own cell, however short the strip is.
                    if (br.size == WidgetSize.SMALL) {
                        rv.setOnClickPendingIntent(android.R.id.background, intent)
                        rv.setContentDescription(android.R.id.background, label)
                    }
                }
            }
        }
}
