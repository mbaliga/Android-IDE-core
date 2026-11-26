package dev.fonebrew.data

import dev.fonebrew.domain.agent.DispatchRequest
import dev.fonebrew.domain.agent.EngineBadge
import dev.fonebrew.domain.agent.PublishChoice
import dev.fonebrew.domain.agent.PublishPlan
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.git.CiState
import dev.fonebrew.domain.git.GitActionsApi
import dev.fonebrew.domain.git.GitApiResult
import dev.fonebrew.domain.git.GitBranchApi
import dev.fonebrew.domain.git.GitBuild
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.git.GitPullRequestApi
import dev.fonebrew.domain.git.GitRequest
import dev.fonebrew.domain.git.GitTreeApi

/**
 * The write side of the agent, behind the user's explicit choices: commit (direct or onto a new
 * branch), open a pull request, check CI once, start a workflow once. Plain deps (transport,
 * tree store, clock) so a JVM test drives it with a fake transport. It never decides anything:
 * it is handed a [PublishPlan] (built only from an explicit choice and a non-empty approved set)
 * or a confirmed [DispatchRequest]. Host behaviour is owner-verified.
 */
class AgentPublisher(
    private val transport: GitTransport,
    private val treeRepository: MessageTreeRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    data class Outcome(
        val branch: String,
        val commitIds: List<String>,
        val pullRequest: GitPullRequestApi.PullRequest?,
        /** Set when the commit landed but the pull request could not be opened. */
        val pullRequestProblem: String?,
    ) {
        /** The commit to check CI for: the last commit made. */
        val headSha: String? get() = commitIds.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    /** One squashed commit (per-file fallback) on [host]'s branch, then the tree anchors. */
    suspend fun commitTo(host: GitHost, token: String, changeSet: ChangeSet, message: String): List<String> {
        val ids = AgentRepoRunner.squashedCommit(transport, host, token, changeSet, message)
            .getOrElse { AgentRepoRunner.perFileCommit(transport, host, token, changeSet, message).getOrThrow() }
        AgentRepoRunner.recordCommitAnchors(treeRepository, host, ids, message, clock, idGen)
        return ids
    }

    /**
     * Direct = one commit on the host's branch. BranchAndPr = create the branch from the base head,
     * commit onto it, open the pull request. A failure midway states exactly what already exists on
     * the host; nothing is rolled back or deleted automatically.
     */
    suspend fun publish(host: GitHost, token: String, plan: PublishPlan, message: String, engine: EngineBadge, summary: String?): Outcome =
        when (plan.choice) {
            is PublishChoice.Direct -> Outcome(host.branch, commitTo(host, token, plan.approved, message), null, null)
            is PublishChoice.BranchAndPr -> {
                val name = plan.targetBranch
                val head = AgentRepoRunner.parseRefSha(exec(GitTreeApi.getRef(host, token)))
                val created = send(GitBranchApi.createBranch(host, name, head, token))
                when (val r = GitBranchApi.parseCreated(host, created.code, created.body)) {
                    is GitApiResult.Err -> error("could not create branch $name: ${r.error.kind} ${r.error.message}".trim())
                    is GitApiResult.Ok -> Unit
                }
                val ids = try {
                    commitTo(host.copy(branch = name), token, plan.approved, message)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error("branch $name was created on ${host.displayName} but the commit failed (${e.message?.take(160)}); the empty branch is left in place")
                }
                val body = GitPullRequestApi.body(engine.name, engine.cloud, summary)
                val (pr, problem) = try {
                    val resp = send(GitPullRequestApi.create(host, message.take(200), body, name, plan.baseBranch, token, draft = plan.draft))
                    when (val r = GitPullRequestApi.parseCreated(resp.code, resp.body)) {
                        is GitApiResult.Ok -> r.value to null
                        is GitApiResult.Err -> null to "${r.error.kind} ${r.error.message}".trim()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null to (e.message?.take(160) ?: "request failed")
                }
                Outcome(name, ids, pr, problem?.let { "branch $name and its commit exist, but the pull request was not opened: $it" })
            }
        }

    /**
     * ONE explicit CI check (the user tapped Check CI): never polls. GitHub: check runs and the
     * combined commit status (two read-only GETs, folded by [GitActionsApi.ciState]); Gitea/Forgejo:
     * combined status only (no check-runs API).
     */
    suspend fun checkCi(host: GitHost, token: String, sha: String): CiState {
        val checks = if (host.kind == GitHostKind.GITHUB) {
            val resp = send(GitActionsApi.checkRuns(host, sha, token))
            (GitActionsApi.parseCheckRuns(resp.code, resp.body) as? GitApiResult.Ok)?.value
                ?: error("could not read check runs (HTTP ${resp.code})")
        } else null
        val resp = send(GitActionsApi.combinedStatus(host, sha, token))
        val statuses = (GitActionsApi.parseCombinedStatus(resp.code, resp.body) as? GitApiResult.Ok)?.value
            ?: error("could not read commit status (HTTP ${resp.code})")
        return GitActionsApi.ciState(checks, statuses)
    }

    suspend fun runWorkflow(request: DispatchRequest, token: String, confirmed: Boolean) {
        check(confirmed) { "workflow run not confirmed" }
        val resp = send(GitActionsApi.dispatch(request.host, request.workflow, request.ref, token))
        when (val r = GitActionsApi.parseDispatch(resp.code, resp.body)) {
            is GitApiResult.Ok -> Unit
            is GitApiResult.Err -> error("workflow not started: ${r.error.kind} ${r.error.message}".trim())
        }
    }

    private suspend fun send(b: GitBuild): GitTransport.Resp = when (b) {
        is GitBuild.Ready -> transport.execute(b.request)
        is GitBuild.Invalid -> error(b.reason)
        is GitBuild.Unsupported -> error(b.message)
    }

    private suspend fun exec(req: GitRequest): String {
        val r = transport.execute(req)
        if (r.code !in 200..299) error("HTTP ${r.code}: ${r.body.take(180)}")
        return r.body
    }
}
