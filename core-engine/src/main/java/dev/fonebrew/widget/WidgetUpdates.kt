package dev.fonebrew.widget

import android.content.Context
import dev.fonebrew.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Push, not poll: watches the app's own stores and redraws a widget when the data behind it changes.
 * Started only once a widget is placed (from [FonebrewWidgets.onProcessStart] or a provider's
 * update), so a phone with no Fonebrew widget pays nothing. Process-scoped, like the stores it watches.
 *
 * Each watch reduces its source to what the widget actually depends on, drops repeats, and is rate
 * limited (the latest value always wins), so a model download's per-chunk progress becomes at most one
 * redraw every [DOWNLOAD_INTERVAL_MS], and only when a whole percent changes.
 *
 * What it watches (all already process-scoped in [AppContainer]; nothing new is persisted):
 *  - Next up: the Room task table (`TaskStore.tasks`), so Studio's lenses writing the same table count too;
 *  - Model: the chat's [dev.fonebrew.data.GenerationStatus], the active-model setting, the model list
 *    (downloads, cloud providers, Gemini Nano) and how many downloads run;
 *  - Model downloads: `DownloadCenter.active`;
 *  - Agent review: the Agent tab's mirror ([dev.fonebrew.data.AgentReviewHolder]: pending proposal, the
 *    running model's provenance), agent jobs, and whether the first Git host has a token;
 *  - Loop runs: loop jobs starting and finishing (the run log and ledger are written before a job
 *    finishes) and the loops' names (a rename or delete in the Loop room).
 */
class WidgetUpdates(private val app: Context, private val c: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        watch(WidgetKind.NEXT_UP, c.taskStore.tasks)
        watch(
            WidgetKind.MODEL,
            combine(
                listOf<Flow<Any?>>(
                    c.generationStatus.state,
                    c.sessionStore.activeModelId,
                    c.sessionStore.aiCoreEnabled,
                    c.localModelStore.models.map { models -> models.size },
                    c.providerStore.providers,
                    c.downloadCenter.active.map { active -> active.values.count { it.running } },
                ),
            ) { it.toList() },
        )
        watch(
            WidgetKind.DOWNLOADS,
            c.downloadCenter.active.map { Downloads.build(FonebrewWidgets.downloadInputs(it)).signature },
            DOWNLOAD_INTERVAL_MS,
        )
        watch(
            WidgetKind.AGENT_REVIEW,
            combine(
                c.agentReview.pending.map { p -> p?.let { it.proposedAt to it.changeCount } },
                c.agentReview.runningOnDevice,
                c.backgroundJobs.jobs.map { jobs -> jobs.filter { it.kind == AgentReview.AGENT_JOB_KIND } },
                // The same test the Agent tab uses (first host has a token); re-read whenever hosts change.
                c.gitHostStore.hosts.map { FonebrewWidgets.agentHasHost(c) },
            ) { pending, running, jobs, hasHost -> listOf(pending, running, jobs, hasHost) },
        )
        watch(
            WidgetKind.LOOP_RUNS,
            combine(
                c.backgroundJobs.jobs.map { jobs -> jobs.filter { it.kind == LoopRuns.LOOP_JOB_KIND } },
                // Finished rows take their loop's current name at draw time: a rename or delete redraws.
                c.loopStore.loops.map { loops -> loops.map { it.id to it.name } },
            ) { jobs, names -> jobs to names },
        )
        // Quick start has no data; it is drawn by its provider's own update only.
    }

    private fun watch(kind: WidgetKind, signal: Flow<Any?>, minIntervalMs: Long = MIN_INTERVAL_MS) {
        scope.launch {
            signal.distinctUntilChanged().conflate().collect {
                FonebrewWidgets.renderPlaced(app, kind)
                delay(minIntervalMs)
            }
        }
    }

    companion object {
        const val MIN_INTERVAL_MS: Long = 500L
        const val DOWNLOAD_INTERVAL_MS: Long = 2_000L
    }
}
