package dev.fonebrew.ui.develop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fonebrew.data.AgentPublisher
import dev.fonebrew.data.AgentRepoRunner
import dev.fonebrew.domain.agent.DispatchPlanner
import dev.fonebrew.domain.agent.EngineBadge
import dev.fonebrew.domain.agent.PublishChoice
import dev.fonebrew.domain.agent.PublishPlanner
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.git.CiState
import dev.fonebrew.domain.git.GitHost
import kotlinx.coroutines.launch

/**
 * Where the approved change goes. Nothing is selected by default and nothing is sent until the
 * user picks an option and taps the explicit button; the plan is built only by [PublishPlanner]
 * from a non-empty approved set. After publishing: an explicit Check CI (ONE request per tap, no
 * polling) and an explicit Run workflow that names the workflow and asks for confirmation. Host
 * behaviour is owner-verified.
 */
@Composable
fun AgentPublishSheet(
    runner: AgentRepoRunner,
    host: GitHost,
    approved: ChangeSet,
    objective: String,
    engine: EngineBadge,
    summary: String?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = remember(host, objective) { PublishPlanner.sheet(host, objective, java.util.UUID.randomUUID().toString().take(6)) }
    var choice by remember { mutableStateOf<PublishChoice?>(null) }
    var branchName by remember { mutableStateOf(model.suggestedBranch) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<AgentPublisher.Outcome?>(null) }
    var ci by remember { mutableStateOf<CiState?>(null) }
    var ciNote by remember { mutableStateOf<String?>(null) }
    var workflow by remember { mutableStateOf("") }
    var confirmText by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<dev.fonebrew.domain.agent.DispatchRequest?>(null) }
    var workflowNote by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text("Where should this change go?", style = MaterialTheme.typography.titleSmall)
        Hint("${approved.effective.size} approved file change(s) on ${dev.fonebrew.domain.git.AgentTarget.label(host)}. Choose one; nothing is sent until you tap Publish.")
        val done = outcome != null
        Spacer(Modifier.height(6.dp))
        WireButton(
            (if (choice is PublishChoice.Direct) "● " else "○ ") + model.direct.title,
            selected = choice is PublishChoice.Direct, enabled = !busy && !done,
            onClick = { choice = PublishChoice.Direct },
        )
        Hint(model.direct.detail)
        WireButton(
            (if (choice is PublishChoice.BranchAndPr) "● " else "○ ") + model.branchPr.title,
            selected = choice is PublishChoice.BranchAndPr, enabled = !busy && !done,
            onClick = { choice = PublishChoice.BranchAndPr(branchName) },
        )
        Hint(model.branchPr.detail)
        if (choice is PublishChoice.BranchAndPr) {
            dev.aarso.hyle.cells.HyleField(
                branchName, { branchName = it; choice = PublishChoice.BranchAndPr(it) },
                label = "New branch name (base: ${model.baseBranch})", modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        val plan = PublishPlanner.plan(choice, approved, host)
        (plan as? PublishPlanner.PlanResult.NotReady)?.let { if (choice != null) Hint(it.reason) }
        (plan as? PublishPlanner.PlanResult.Ready)?.let { Hint("Will do: " + it.plan.describe()) }
        if (!done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WireButton(
                    if (busy) "Publishing…" else "Publish",
                    enabled = !busy && plan is PublishPlanner.PlanResult.Ready,
                    onClick = {
                        val p = (plan as PublishPlanner.PlanResult.Ready).plan
                        busy = true; error = null
                        scope.launch {
                            runner.publish(p, PublishPlanner.commitMessage(objective), engine, summary).fold(
                                { outcome = it }, { error = "publish failed: ${it.message}" },
                            )
                            busy = false
                        }
                    },
                )
                WireButton("Cancel", enabled = !busy, onClick = onDone)
            }
        }
        error?.let { Hint(it) }
        outcome?.let { o ->
            Spacer(Modifier.height(6.dp))
            Text("✓ Published to ${o.branch}", style = MaterialTheme.typography.labelMedium)
            Hint("commit(s): " + o.commitIds.joinToString(", ") { it.take(10) })
            o.pullRequest?.let { Hint("pull request #${it.number}${if (it.draft) " (draft)" else ""}: ${it.htmlUrl}") }
            o.pullRequestProblem?.let { Hint("⚠ $it") }
            Spacer(Modifier.height(6.dp))
            WireButton("Check CI (one request)", enabled = !busy && o.headSha != null, onClick = {
                val sha = o.headSha ?: return@WireButton
                busy = true; ciNote = null
                scope.launch {
                    runner.checkCi(host, sha).fold(
                        { ci = it }, { ciNote = "could not read CI: ${it.message}" },
                    )
                    busy = false
                }
            })
            ci?.let { Hint("${it.glyph} ${it.label}" + if (it.failingNames.isNotEmpty()) " - failing: " + it.failingNames.joinToString(", ") else "") }
            ciNote?.let { Hint(it) }
            Hint("CI is read only when you tap; the app never polls. No automatic fix-until-green loop.")
            Spacer(Modifier.height(6.dp))
            dev.aarso.hyle.cells.HyleField(workflow, { workflow = it }, label = "Workflow file to run (e.g. ci.yml)", modifier = Modifier.fillMaxWidth())
            WireButton("Run workflow…", enabled = !busy && workflow.isNotBlank(), onClick = {
                when (val r = DispatchPlanner.prepare(host, workflow, o.branch)) {
                    is DispatchPlanner.Result.Ready -> { pending = r.request; confirmText = r.request.confirmationText }
                    is DispatchPlanner.Result.Invalid -> workflowNote = r.reason
                }
            })
            workflowNote?.let { Hint(it) }
            confirmText?.let { t ->
                Hint(t)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireButton("Confirm: run it", enabled = !busy, onClick = {
                        val req = pending ?: return@WireButton
                        confirmText = null; pending = null; busy = true
                        scope.launch {
                            runner.runWorkflow(req, confirmed = true).fold(
                                { workflowNote = "✓ workflow ${req.workflow} was requested on ${req.ref}; check CI to see it" },
                                { workflowNote = "✗ ${it.message}" },
                            )
                            busy = false
                        }
                    })
                    WireButton("Don't run", onClick = { confirmText = null; pending = null })
                }
            }
            Spacer(Modifier.height(6.dp))
            WireButton("Done", onClick = onDone)
        }
    }
}
