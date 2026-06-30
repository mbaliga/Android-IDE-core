package dev.aarso.domain.ide

import dev.aarso.domain.cost.CardDecision
import dev.aarso.domain.diff.ChangeSet
import dev.aarso.domain.pm.BoardCard

/**
 * The agentic loop on an **existing** repo, end to end (docs/build-plan.md, Sprint 5): take an
 * issue (a [BoardCard]) → derive an objective → read the relevant files → have the agent propose
 * a [ChangeSet] → **stop for the user's approval** → only on approval, commit. This is the chain
 * that ties together pieces that already exist (Board, Git read/write, diff); the agent's output
 * is a material diff you approve, never a silent write (THE LAW; binding rule 6).
 *
 * Pure orchestration over three seams (a reader, a proposer, a committer), so the whole flow is
 * JVM-testable with fakes. Real Git/CI calls live behind the seams and are owner-verified (📱).
 */

/** Reads a file's current contents from the repo. Returns null when the path doesn't exist. */
fun interface RepoReader {
    suspend fun read(path: String): String?
}

/** Proposes a change set for [objective], given the current contents of the [context] files. */
fun interface ChangeProposer {
    suspend fun propose(objective: String, context: Map<String, String>): ChangeSet
}

/** Commits an approved change set with [message]; returns the new commit id on success. */
fun interface ChangeCommitter {
    suspend fun commit(changeSet: ChangeSet, message: String): Result<String>
}

/** The outcome of a run — the proposal, whether it was committed, and a legible reason. */
data class RepoWorkResult(
    val proposed: ChangeSet,
    val committed: Boolean,
    val commitId: String?,
    val reason: String,
)

class RepoWorkLoop(
    private val reader: RepoReader,
    private val proposer: ChangeProposer,
    private val committer: ChangeCommitter,
) {
    /**
     * Run the loop for [card] over the candidate [paths]. [approve] is consulted only when there
     * is a non-empty proposal; returning false leaves the repo untouched (a no-op). The commit
     * message is derived from the card so the history references the issue.
     */
    suspend fun run(
        card: BoardCard,
        paths: List<String>,
        approve: suspend (ChangeSet) -> Boolean,
    ): RepoWorkResult {
        val objective = CardDecision.objective(card)

        val context = LinkedHashMap<String, String>()
        for (p in paths) reader.read(p)?.let { context[p] = it }

        val proposal = proposer.propose(objective, context)
        if (proposal.isEmpty) {
            return RepoWorkResult(proposal, committed = false, commitId = null, reason = "no change proposed")
        }

        if (!approve(proposal)) {
            return RepoWorkResult(proposal, committed = false, commitId = null, reason = "rejected by reviewer")
        }

        val message = commitMessage(card)
        return committer.commit(proposal, message).fold(
            onSuccess = { id -> RepoWorkResult(proposal, committed = true, commitId = id, reason = "committed") },
            onFailure = { e -> RepoWorkResult(proposal, committed = false, commitId = null, reason = e.message ?: "commit failed") },
        )
    }

    /** A conventional commit subject that references the issue number. */
    private fun commitMessage(card: BoardCard): String =
        "Address #${card.number}: ${card.title}".trim()
}
