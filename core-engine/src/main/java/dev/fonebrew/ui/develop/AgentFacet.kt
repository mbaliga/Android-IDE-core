package dev.fonebrew.ui.develop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.AgentRepoRunner
import dev.fonebrew.domain.agent.AgentBudgets
import dev.fonebrew.domain.agent.AgentObserver
import dev.fonebrew.domain.agent.AgentStep
import dev.fonebrew.domain.agent.CancelToken
import dev.fonebrew.domain.agent.ContextPick
import dev.fonebrew.domain.agent.EngineBadge
import dev.fonebrew.domain.agent.RunTotals
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.format.LocaleFormat
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The multi-step agent tab (docs/design/agent-multistep.md). Pick the target and the engine,
 * optionally seed context from the repo tree, set the step cap, Run. The agent only reads and
 * proposes; every step is logged live; the proposal goes through per-hunk review; the user then
 * chooses where it goes. All of it is review-first. Network, generation and feel are owner-verified.
 */
@Composable
fun AgentFacet() {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val runner = container.agentRepoRunner
    val scope = rememberCoroutineScope()
    val runnable = remember { container.modelRegistry.allSpecs().filter { container.engineProvider.isRunnable(it) } }
    val currencyCode by container.pricingStore.currencyCode.collectAsState()
    val format: (Long) -> String = { LocaleFormat.currencyMinor(it, currencyCode, Locale.getDefault()) }

    var objective by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(runnable.firstOrNull()?.id) }
    var maxSteps by remember { mutableStateOf(AgentBudgets.DEFAULT_STEPS) }
    val picks = remember { mutableStateListOf<ContextPick>() }
    var status by remember { mutableStateOf<String?>(null) }
    var proposal by remember { mutableStateOf<AgentRepoRunner.Proposal?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var approved by remember { mutableStateOf<ChangeSet?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf<CancelToken?>(null) }
    val liveSteps = remember { mutableStateListOf<AgentStep>() }
    var liveTotals by remember { mutableStateOf(RunTotals()) }
    var showLog by remember { mutableStateOf(false) }

    val hosts by container.gitHostStore.hosts.collectAsState()
    var chosenId by remember { mutableStateOf(runner.chosenHostId) }
    val target = dev.fonebrew.domain.git.AgentTarget.resolve(hosts, chosenId)
    val resolvedHost = (target as? dev.fonebrew.domain.git.AgentTarget.Resolution.Resolved)?.host
    val targetLabel = resolvedHost?.let { dev.fonebrew.domain.git.AgentTarget.label(it) }

    // The home-screen "Agent review" widget follows this tab through AgentReviewHolder (a mirror; the
    // proposal itself stays here, bound to this screen and the host it was read from). When the tab
    // leaves composition its proposal and run are gone, so the mirror is cleared with them.
    DisposableEffect(Unit) {
        onDispose { container.agentReview.clear() }
    }

    if (hosts.isEmpty()) {
        Hint("Connect a Git host (Settings → Global → Git & coding) - the agent reads your repo and proposes changes for you to review.")
        return
    }
    Text("Agent", style = MaterialTheme.typography.titleSmall)
    Hint(
        "A multi-step agent: it lists, reads and searches your repo, then proposes changes. It never writes " +
            "on its own: you review per-hunk, then choose where the change goes. A cloud engine is a watched object.",
    )
    if (runnable.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Hint("No runnable model. Download one (Models) or add a cloud provider (Settings → Text).")
        return
    }
    Spacer(Modifier.height(8.dp))
    if (hosts.size > 1) {
        Text("Git host", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            hosts.forEach { h ->
                WireButton(
                    (if (h.id == resolvedHost?.id) "● " else "○ ") + dev.fonebrew.domain.git.AgentTarget.label(h),
                    selected = h.id == resolvedHost?.id, enabled = !busy,
                    onClick = { chosenId = h.id; runner.chosenHostId = h.id; proposal = null; approved = null; status = null; picks.clear(); container.agentReview.clear() },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    Hint(targetLabel?.let { "Will read from: $it" } ?: "Several Git hosts are connected - choose which one the agent targets before Run.")
    Spacer(Modifier.height(6.dp))
    dev.aarso.hyle.cells.HyleField(objective, { objective = it }, label = "Objective", singleLine = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    Text("Model", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        runnable.forEach { s ->
            WireButton((if (s.isOnDevice) "⌂ " else "☁ ") + s.displayName, selected = s.id == modelId, enabled = !busy, onClick = { modelId = s.id })
        }
    }
    val spec = runnable.firstOrNull { it.id == modelId }
    spec?.let { Hint(EngineBadge(it.displayName, cloud = !it.isOnDevice).let { b -> "${b.glyph} ${b.label}" }) }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Step cap: $maxSteps (max ${AgentBudgets.HARD_MAX_STEPS})", style = MaterialTheme.typography.labelMedium)
        WireButton("−", enabled = !busy && maxSteps > 1, onClick = { maxSteps-- })
        WireButton("+", enabled = !busy && maxSteps < AgentBudgets.HARD_MAX_STEPS, onClick = { maxSteps++ })
    }
    Spacer(Modifier.height(6.dp))
    if (!busy) AgentContextPicker(if (resolvedHost != null) runner.repoView() else null, picks, spec?.contextWindow ?: 4096)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WireButton(
            if (busy) "Working…" else "Run",
            enabled = !busy && objective.isNotBlank() && modelId != null && resolvedHost != null,
            onClick = {
                val mid = modelId ?: return@WireButton
                val token = CancelToken()
                cancel = token; busy = true; proposal = null; approved = null; status = "running…"
                val onDevice = container.modelRegistry.byId(mid)?.isOnDevice == true
                container.agentReview.started(onDevice)
                liveSteps.clear(); liveTotals = RunTotals(); showLog = true
                scope.launch {
                    val jobId = container.backgroundJobs.start("Agent: ${objective.take(40)}${if (objective.length > 40) "…" else ""}", "agent")
                    val result = try {
                        runner.runAgent(
                            objective, picks.toList(), mid,
                            budgets = AgentBudgets(maxSteps = maxSteps), cancel = token,
                            observer = object : AgentObserver {
                                override fun onStepStart(number: Int) { status = "step $number…" }
                                override fun onStep(step: AgentStep, totals: RunTotals) { liveSteps += step; liveTotals = totals }
                            },
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Leaving this room cancels the run; without this the job would read "running"
                        // forever (Chat's background strip, the Agent review widget).
                        container.agentReview.finished()
                        container.backgroundJobs.finish(jobId, failed = true)
                        throw e
                    }
                    result.fold(
                        { p ->
                            proposal = p
                            container.agentReview.offer(p, onDevice)
                            status = (p.log?.outcome?.let { dev.fonebrew.domain.agent.StepLogPresenter.stop(it) } ?: "done") +
                                " - ${p.changeSet.effective.size} change(s) proposed"
                            container.backgroundJobs.finish(jobId)
                        },
                        { status = "failed: ${it.message}"; container.agentReview.finished(); container.backgroundJobs.finish(jobId, failed = true) },
                    )
                    busy = false; cancel = null
                }
            },
        )
        if (busy) WireButton("■ Stop", onClick = { cancel?.cancel(); status = "stopping after the current step…" })
    }
    status?.let { Spacer(Modifier.height(8.dp)); Hint(it) }
    val badge = spec?.let { EngineBadge(it.displayName, cloud = !it.isOnDevice) }
    if (busy && badge != null) AgentRunLogView(badge, emptyList(), liveSteps, liveTotals, null, format)
    proposal?.let { p ->
        Spacer(Modifier.height(6.dp))
        p.log?.let { log ->
            WireButton(if (showLog) "▾ How the agent got here" else "▸ How the agent got here", onClick = { showLog = !showLog })
            if (showLog) AgentRunLogView(log, format)
        }
        if (approved == null) WireButton("Review ${p.changeSet.effective.size} change(s) ▸", enabled = !p.changeSet.isEmpty, onClick = { reviewing = true })
    }

    val ap = approved
    if (ap != null && resolvedHost != null && !ap.isEmpty) {
        Spacer(Modifier.height(10.dp))
        AgentPublishSheet(
            runner, resolvedHost, ap, objective,
            proposal?.log?.engine ?: badge ?: EngineBadge("unknown", cloud = false),
            proposal?.rawModelOutput,
            onDone = { approved = null; proposal = null; status = null; container.agentReview.clear() },
        )
    }

    if (reviewing) {
        val p = proposal
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { reviewing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                dev.fonebrew.ui.ide.ReviewSheet(
                    changeSet = p?.changeSet ?: ChangeSet(emptyList()),
                    title = "Review - agent changes" + (targetLabel?.let { " from $it" } ?: ""),
                    commitLabel = "Approve, then choose where it goes",
                    busy = false,
                    onCancel = { reviewing = false },
                    onCommit = { a -> if (a.isEmpty) status = "nothing approved; nothing was sent" else approved = a; reviewing = false },
                )
            }
        }
    }
}
