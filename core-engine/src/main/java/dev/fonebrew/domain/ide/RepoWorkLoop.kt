package dev.fonebrew.domain.ide

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.cost.CardDecision
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.pm.BoardCard
import java.util.UUID

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

/**
 * The outcome of a run — the proposal, whether it was committed, and a legible reason.
 *
 * @property commitAnchor **Graph-wave lane D.** When [committed], a ready-to-insert
 *   [MessageNode] carrying [CommitAnchor.SHA_KEY]/[CommitAnchor.REPO_KEY] metadata for [commitId]
 *   — see [CommitAnchor]'s own KDoc for the "pure function, caller inserts" divide this follows.
 *   Null whenever [committed] is false (nothing was minted, since nothing happened) — never
 *   fabricated. **Honest gap:** [RepoWorkLoop] has no live production caller today (grep confirms
 *   only its own test constructs one), so this node is built but nothing yet inserts it into a
 *   real [dev.fonebrew.data.MessageTreeRepository] — wiring a live caller is a follow-up, not
 *   something this pure orchestration class should reach out of its three-seam design to do
 *   itself (see the class KDoc: "Pure orchestration... so the whole flow is JVM-testable").
 */
data class RepoWorkResult(
    val proposed: ChangeSet,
    val committed: Boolean,
    val commitId: String?,
    val reason: String,
    val commitAnchor: MessageNode? = null,
)

class RepoWorkLoop(
    private val reader: RepoReader,
    private val proposer: ChangeProposer,
    private val committer: ChangeCommitter,
    private val now: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Run the loop for [card] over the candidate [paths]. [approve] is consulted only when there
     * is a non-empty proposal; returning false leaves the repo untouched (a no-op). The commit
     * message is derived from the card so the history references the issue.
     *
     * @param repoRef **Graph-wave lane D**, optional: the connected host's "owner/repo@branch"
     *   label (see [CommitAnchor.repoRef]) — [RepoWorkLoop] itself has no [dev.fonebrew.domain.
     *   git.GitHost] to derive one from (only the concrete [ChangeCommitter] wired in knows),
     *   so the caller supplies it when known. Null is never fabricated into a placeholder — it
     *   simply means [RepoWorkResult.commitAnchor] carries no repo label, exactly like
     *   [dev.fonebrew.domain.thread.ThreadGraphNode.repoRef]'s own "null when not resolvable".
     *   Placed *before* [approve] (not after) so [approve] stays the function's last parameter —
     *   every existing caller passes it as a trailing lambda, which Kotlin binds to whichever
     *   parameter is declared last, not to whichever positional slot is next; a defaulted
     *   parameter added after [approve] would silently break every one of those call sites.
     */
    suspend fun run(
        card: BoardCard,
        paths: List<String>,
        repoRef: String? = null,
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
            onSuccess = { id ->
                val anchor = CommitAnchor.node(id = idGen(), sha = id, repoRef = repoRef, createdAt = now(), label = message)
                RepoWorkResult(proposal, committed = true, commitId = id, reason = "committed", commitAnchor = anchor)
            },
            onFailure = { e -> RepoWorkResult(proposal, committed = false, commitId = null, reason = e.message ?: "commit failed") },
        )
    }

    /** A conventional commit subject that references the issue number. */
    private fun commitMessage(card: BoardCard): String =
        "Address #${card.number}: ${card.title}".trim()
}
