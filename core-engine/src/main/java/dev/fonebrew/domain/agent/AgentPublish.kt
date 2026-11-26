package dev.fonebrew.domain.agent

import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.git.AgentTarget
import dev.fonebrew.domain.git.GitBranchApi
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind

/** Pre-run size meter for the picked context. Glyph + label, never hue. Token figure is always an estimate. */
object ContextMeter {
    enum class Level(val glyph: String, val label: String) {
        FITS("○", "fits the context window"),
        NEAR("◐", "close to the context window"),
        OVER("✗", "over the context window"),
    }

    data class Reading(val bytes: Long, val unknownFolders: Int, val estTokens: Long, val level: Level, val text: String)

    /** About 4 bytes per token: a rough rule of thumb, shown flagged as an estimate. */
    fun assess(knownBytes: Long, unknownFolders: Int, windowTokens: Int, budgetBytes: Int = AgentBudgets().maxContextBytes): Reading {
        val est = knownBytes / 4
        val level = when {
            knownBytes > budgetBytes || est >= windowTokens -> Level.OVER
            est * 10 >= windowTokens * 6L -> Level.NEAR
            else -> Level.FITS
        }
        val folders = if (unknownFolders > 0) " + $unknownFolders folder(s) of unknown size" else ""
        val text = "${level.glyph} ${knownBytes} bytes ≈ $est tokens (estimated)$folders of $windowTokens-token window: ${level.label}"
        return Reading(knownBytes, unknownFolders, est, level, text)
    }
}

/** Where an approved change set goes. Never defaulted: the sheet starts with nothing selected. */
sealed interface PublishChoice {
    data object Direct : PublishChoice
    data class BranchAndPr(val branchName: String) : PublishChoice
}

/** What the commit/PR executor may run. Can only be built by [PublishPlanner.plan]. */
class PublishPlan internal constructor(
    val host: GitHost,
    val choice: PublishChoice,
    /** Branch the commit lands on. */
    val targetBranch: String,
    /** Branch a new branch forks from / the PR targets; same as [targetBranch] for a direct commit. */
    val baseBranch: String,
    val draft: Boolean,
    val approved: ChangeSet,
) {
    val isDirect: Boolean get() = choice is PublishChoice.Direct

    fun describe(): String = when (choice) {
        is PublishChoice.Direct -> "Direct commit to ${host.owner}/${host.repo} @ $targetBranch on ${host.displayName}"
        is PublishChoice.BranchAndPr ->
            "New branch $targetBranch from $baseBranch, then a ${if (draft) "draft " else ""}pull request into $baseBranch (${host.owner}/${host.repo} on ${host.displayName})"
    }
}

object PublishPlanner {
    data class Option(val title: String, val detail: String)

    data class SheetModel(
        val direct: Option,
        val branchPr: Option,
        val suggestedBranch: String,
        val baseBranch: String,
        val draft: Boolean,
        /** Always null: the user must pick. */
        val selected: PublishChoice? = null,
    )

    sealed interface PlanResult {
        data class Ready(val plan: PublishPlan) : PlanResult
        data class NotReady(val reason: String) : PlanResult
    }

    /** Draft flag is not available on Gitea/Forgejo (A1b host matrix): non-draft there, said plainly. */
    fun draftSupported(host: GitHost) = host.kind == GitHostKind.GITHUB

    fun sheet(host: GitHost, objective: String, shortId: String): SheetModel {
        val draft = draftSupported(host)
        val where = AgentTarget.label(host)
        return SheetModel(
            direct = Option(
                "Commit directly to ${host.branch}",
                "A direct commit: the change lands on ${host.branch} at once, with no pull request. $where",
            ),
            branchPr = Option(
                "New branch + ${if (draft) "draft " else ""}pull request",
                "Commits to a new branch from ${host.branch}, then opens a ${if (draft) "draft " else ""}pull request into ${host.branch}." +
                    if (draft) "" else " ${host.kind.label} has no draft flag, so the pull request is not a draft.",
            ),
            suggestedBranch = GitBranchApi.suggestName(objective, shortId),
            baseBranch = host.branch,
            draft = draft,
        )
    }

    fun plan(choice: PublishChoice?, approved: ChangeSet, host: GitHost): PlanResult {
        if (choice == null) return PlanResult.NotReady("choose where the change goes first")
        if (approved.isEmpty) return PlanResult.NotReady("no approved changes: review and approve at least one hunk first")
        return when (choice) {
            is PublishChoice.Direct -> PlanResult.Ready(PublishPlan(host, choice, host.branch, host.branch, false, approved))
            is PublishChoice.BranchAndPr -> {
                val name = choice.branchName.trim()
                GitBranchApi.validate(name)?.let { return PlanResult.NotReady("branch name: $it") }
                if (name == host.branch) return PlanResult.NotReady("the new branch must differ from ${host.branch}")
                PlanResult.Ready(PublishPlan(host, choice, name, host.branch, draftSupported(host), approved))
            }
        }
    }

    fun commitMessage(objective: String): String = "Agent: ${objective.trim().lineSequence().firstOrNull().orEmpty().take(60)}"
}

/** A workflow dispatch the user has not yet confirmed. Names the workflow, the ref and the repo. */
class DispatchRequest internal constructor(val host: GitHost, val workflow: String, val ref: String) {
    val confirmationText: String
        get() = "Run workflow $workflow on branch $ref in ${host.owner}/${host.repo} (${host.displayName})? " +
            "This starts a real run on the host and may use its CI minutes."
}

object DispatchPlanner {
    sealed interface Result {
        data class Ready(val request: DispatchRequest) : Result
        data class Invalid(val reason: String) : Result
    }

    fun prepare(host: GitHost, workflow: String, ref: String): Result {
        val w = workflow.trim()
        if (w.isEmpty()) return Result.Invalid("type the workflow file name, for example ci.yml")
        if (w.contains('/') || w.contains(' ')) return Result.Invalid("the workflow must be a file name such as ci.yml")
        GitBranchApi.validate(ref)?.let { return Result.Invalid("branch: $it") }
        return Result.Ready(DispatchRequest(host, w, ref))
    }
}
