package dev.fonebrew.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.DownloadCenter
import dev.fonebrew.data.watch.WristSnapshots
import dev.fonebrew.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

/**
 * Fonebrew's home-screen widgets (docs/widgets.md): reading the app's real data, rendering, and
 * pushing to the launcher. Standard App Widgets, so any launcher hosts them.
 *
 * - **Reads** come from the process's [AppContainer] stores, off the main thread, under
 *   [RENDER_TIMEOUT_MS]; a read that fails or overruns draws the error state, never stale numbers.
 * - **Updates are pushed, not polled** (`updatePeriodMillis = 0`): [WidgetUpdates] redraws a widget
 *   when the data behind it changes, and Next up schedules one inexact alarm for the moment a due label
 *   changes. Nothing here touches the network, a key, or the message text.
 * - Nothing is cached: every render reads live state, so there is no snapshot to leak into a backup.
 */
object FonebrewWidgets {
    private const val TAG = "Fonebrew"

    /** Well under the 10 s a broadcast receiver gets with goAsync(). */
    const val RENDER_TIMEOUT_MS: Long = 8_000L

    /** The one-shot alarm Next up sets for its next due boundary (explicit to its own provider). */
    const val ACTION_TICK: String = "dev.fonebrew.widget.action.TICK"

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun providerClass(kind: WidgetKind): Class<out AppWidgetProvider> = when (kind) {
        WidgetKind.NEXT_UP -> NextUpWidgetProvider::class.java
        WidgetKind.MODEL -> ModelWidgetProvider::class.java
        WidgetKind.DOWNLOADS -> DownloadsWidgetProvider::class.java
        WidgetKind.AGENT_REVIEW -> AgentReviewWidgetProvider::class.java
        WidgetKind.LOOP_RUNS -> LoopRunsWidgetProvider::class.java
        WidgetKind.QUICK_START -> QuickStartWidgetProvider::class.java
    }

    /** The placed instances of [kind]; empty when none (or when the widget service can't answer). */
    fun ids(context: Context, kind: WidgetKind): IntArray =
        try {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, providerClass(kind)))
        } catch (e: RuntimeException) {
            IntArray(0)
        }

    /** Redraw every placed widget of [kind] in the background; returns at once. */
    fun refresh(context: Context, kind: WidgetKind) {
        val app = context.applicationContext
        scope.launch { renderPlaced(app, kind) }
    }

    /**
     * Called from [FonebrewApp.onCreate]: if any widget is placed, start [WidgetUpdates] (whose first
     * pass redraws them all). That redraw is what corrects a widget left showing "Generating" by a
     * process that died: in a fresh process nothing is generating, and the widget says so.
     */
    fun onProcessStart(app: FonebrewApp) {
        scope.launch {
            val placed = WidgetKind.entries.filter { ids(app, it).isNotEmpty() }
            when {
                placed.isEmpty() -> Unit
                app.containerOrNull != null -> startUpdates(app)
                // The app failed to start: say so on the home screen rather than keep old numbers.
                else -> placed.forEach { renderPlaced(app, it) }
            }
        }
    }

    internal fun startUpdates(context: Context) {
        try {
            (context.applicationContext as? FonebrewApp)?.containerOrNull?.widgetUpdates?.ensureStarted()
        } catch (e: RuntimeException) {
            Log.w(TAG, "widget updates not started: ${e.javaClass.simpleName}")
        }
    }

    suspend fun renderPlaced(context: Context, kind: WidgetKind) {
        val ids = ids(context, kind)
        if (ids.isNotEmpty()) render(context, kind, ids)
    }

    /**
     * Renders can overlap (a provider's update or tick, and [WidgetUpdates]' pushes), and each reads
     * live state. So every render takes a ticket before it reads, and a widget only ever takes views
     * from a newer read than the one it shows: a slow, older read can never land last and leave a
     * widget showing state that has since changed. Only the apply step is under [applyLock]; reads run
     * in parallel and never wait on each other.
     */
    private val readTicket = AtomicLong(0)
    private val applyLock = Mutex()

    /** Per widget id, the ticket of the read it shows; per kind, of the read that set its alarm. Under [applyLock]. */
    private val shownTicket = HashMap<Int, Long>()
    private val tickTicket = HashMap<WidgetKind, Long>()

    /** What one read produced: how to draw each widget id, and (Next up) when its next due label changes. */
    private class Gathered(val draw: (Int) -> RemoteViews, val reschedule: Boolean = false, val tickAt: Long? = null)

    suspend fun render(context: Context, kind: WidgetKind, ids: IntArray) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val ticket = readTicket.incrementAndGet()
        val now = System.currentTimeMillis()
        val gathered: Gathered = try {
            val container = (context.applicationContext as? FonebrewApp)?.containerOrNull
                ?: throw IllegalStateException("app container not initialised")
            withTimeout(RENDER_TIMEOUT_MS) { gather(context, container, kind, now) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "widget ${kind.name}: read timed out")
            Gathered(errorViews(context, kind))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: an exception message could carry anything.
            Log.w(TAG, "widget ${kind.name}: ${e.javaClass.simpleName}")
            Gathered(errorViews(context, kind))
        }
        applyLock.withLock {
            if (gathered.reschedule && ticket > (tickTicket[kind] ?: 0L)) {
                tickTicket[kind] = ticket
                scheduleTick(context, kind, gathered.tickAt)
            }
            for (id in ids) {
                // A newer read already drew this widget: this older one would only take it back.
                if (ticket <= (shownTicket[id] ?: 0L)) continue
                val views = try {
                    gathered.draw(id)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "widget ${kind.name} views: ${e.javaClass.simpleName}")
                    WidgetRender.error(context, id, kind)
                }
                try {
                    manager.updateAppWidget(id, views)
                    shownTicket[id] = ticket
                } catch (e: RuntimeException) {
                    Log.w(TAG, "widget ${kind.name} update: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun errorViews(context: Context, kind: WidgetKind): (Int) -> RemoteViews = { id -> WidgetRender.error(context, id, kind) }

    /** Reads what [kind] needs (once), and returns how to draw it for a given widget id. */
    private suspend fun gather(context: Context, c: AppContainer, kind: WidgetKind, now: Long): Gathered =
        when (kind) {
            WidgetKind.NEXT_UP -> {
                val model = NextUp.build(c.taskStore.tasks.first(), now)
                Gathered(draw { id -> WidgetRender.nextUp(context, id, model, now) }, reschedule = true, tickAt = model.nextBoundaryAt)
            }
            WidgetKind.MODEL -> {
                val status = c.generationStatus.state.value
                val specs = c.modelRegistry.allSpecs()
                val model = ModelStatus.build(
                    status = status,
                    active = ModelStatus.activeModel(specs, status, c.sessionStore.activeModelId.value),
                    modelsOnDevice = ModelStatus.modelsOnDevice(specs),
                    downloadsRunning = c.downloadCenter.active.value.values.count { it.running },
                )
                Gathered(draw { id -> WidgetRender.model(context, id, model, now) })
            }
            WidgetKind.DOWNLOADS -> {
                val model = Downloads.build(downloadInputs(c.downloadCenter.active.value))
                Gathered(draw { id -> WidgetRender.downloads(context, id, model, now) })
            }
            WidgetKind.AGENT_REVIEW -> {
                val model = AgentReview.build(
                    hasHost = agentHasHost(c),
                    pending = c.agentReview.pending.value?.let(PendingSummary::of),
                    jobs = c.backgroundJobs.jobs.value,
                    runningOnDevice = c.agentReview.runningOnDevice.value,
                )
                Gathered(draw { id -> WidgetRender.agentReview(context, id, model, now) })
            }
            WidgetKind.LOOP_RUNS -> {
                val rows = LoopRuns.build(
                    WristSnapshots.loopRuns(
                        tree = c.repository.tree(),
                        ledger = c.ledgerStore.entries().first(),
                        jobs = c.backgroundJobs.jobs.value,
                        loopName = { loopId -> loopId?.let { c.loopStore.get(it)?.name } },
                        currencyCode = c.pricingStore.currencyCode.value,
                        maxRuns = LoopRuns.MAX_ROWS,
                    ),
                )
                Gathered(draw { id -> WidgetRender.loopRuns(context, id, rows, now) })
            }
            // Pads carry no data: only the destinations they open.
            WidgetKind.QUICK_START -> Gathered(draw { id -> WidgetRender.quickStart(context, id) })
        }

    /**
     * The agent can run, as the Develop room's Agent tab decides it (`AgentRepoRunner.hasHost`): the
     * first host has a token. A presence check on the stored entry: the token is never decrypted here.
     */
    internal fun agentHasHost(c: AppContainer): Boolean =
        c.gitHostStore.hosts.value.firstOrNull()?.let { c.gitHostStore.hasToken(it.id) } == true

    /** Names the per-widget-id drawing step (and keeps a bare lambda from binding to the line above). */
    private fun draw(views: (Int) -> RemoteViews): (Int) -> RemoteViews = views

    internal fun downloadInputs(active: Map<String, DownloadCenter.State>): List<DownloadInput> =
        active.values.map { s ->
            DownloadInput(
                id = s.request.id,
                fileName = s.request.fileName,
                downloadedBytes = s.progress.downloadedBytes,
                totalBytes = s.progress.totalBytes,
                done = s.progress.done,
                failed = s.failed,
                paused = s.paused,
            )
        }

    /** One inexact, non-waking alarm (no exact-alarm permission): a late tick only delays a label. */
    private fun scheduleTick(context: Context, kind: WidgetKind, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, providerClass(kind)).setAction(ACTION_TICK)
        val pending = PendingIntent.getBroadcast(
            context,
            kind.ordinal,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) alarms.cancel(pending) else alarms.set(AlarmManager.RTC, at, pending)
    }

    internal fun cancelTick(context: Context, kind: WidgetKind) = scheduleTick(context, kind, null)
}

/**
 * The shared provider: every Fonebrew widget redraws in the background (goAsync, under the render
 * timeout) on the launcher's update, on its own boundary tick, and when the clock, time zone or
 * language changes (those three are delivered to manifest receivers, and each changes a printed time
 * or word).
 */
abstract class FonebrewWidgetProvider : AppWidgetProvider() {
    protected abstract val kind: WidgetKind

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        val pending = goAsync()
        FonebrewWidgets.scope.launch {
            try {
                FonebrewWidgets.startUpdates(app)
                FonebrewWidgets.render(app, kind, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        FonebrewWidgets.startUpdates(context)
    }

    override fun onDisabled(context: Context) {
        if (kind == WidgetKind.NEXT_UP) FonebrewWidgets.cancelTick(context, kind)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FonebrewWidgets.ACTION_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> {
                val app = context.applicationContext
                val pending = goAsync()
                FonebrewWidgets.scope.launch {
                    try {
                        FonebrewWidgets.renderPlaced(app, kind)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }
}

// Class names are part of every placed widget's identity: renaming one orphans it on the home screen.
class NextUpWidgetProvider : FonebrewWidgetProvider() {
    override val kind = WidgetKind.NEXT_UP
}

class ModelWidgetProvider : FonebrewWidgetProvider() {
    override val kind = WidgetKind.MODEL
}

class DownloadsWidgetProvider : FonebrewWidgetProvider() {
    override val kind = WidgetKind.DOWNLOADS
}

class AgentReviewWidgetProvider : FonebrewWidgetProvider() {
    override val kind = WidgetKind.AGENT_REVIEW
}

class LoopRunsWidgetProvider : FonebrewWidgetProvider() {
    override val kind = WidgetKind.LOOP_RUNS
}

class QuickStartWidgetProvider : FonebrewWidgetProvider() {
    override val kind = WidgetKind.QUICK_START
}
