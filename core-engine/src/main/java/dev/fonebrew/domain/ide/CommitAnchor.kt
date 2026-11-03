package dev.fonebrew.domain.ide

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.git.GitHost

/**
 * **Graph-wave lane D.** Insert-time node-metadata keys recording a successful **agent-driven**
 * git commit onto a new message/run node — the piece [dev.fonebrew.domain.thread.
 * ThreadGraphProjector]'s own 1.2.0 KDoc (graph-wave lane A) named as missing: "no producer mints
 * a COMMIT node yet." [dev.fonebrew.data.AgentRepoRunner.commit]'s own 2026-08-29 audit note said
 * it plainly — a commit sha returned from a successful agent action "ends there... no persistent,
 * queryable anchor back into the message tree." This file is that anchor.
 *
 * Follows the exact "insert-time-only, never edited after" idiom [dev.fonebrew.domain.tree.
 * TreeFork.LINEAGE_KIND_KEY] / [dev.fonebrew.domain.loop.RunLog.TAG_RUN] already use (binding
 * constraint 5, append-only tree): [node] mints [SHA_KEY]/[REPO_KEY] onto a **brand-new** node at
 * the moment a commit succeeds. It is never retrofitted onto a node that already exists — a
 * historical node minted before this file shipped simply carries no such metadata, and
 * [dev.fonebrew.domain.thread.ThreadGraphProjector.project] projects nothing for it (absence is
 * absence, never fabricated — the same rule that file's dangling-edge cases already follow).
 *
 * Descriptive only, well inside the Issue #2 boundary: a sha and a repo/branch label are recorded
 * facts (what happened), never an interpretation of the commit's content or of the user's intent
 * in asking for it.
 *
 * Two call sites mint this today — see each one's own KDoc for how it's wired in:
 * [dev.fonebrew.data.AgentRepoRunner.commit] (the live Develop → Agent squashed/per-file commit
 * path) and [dev.fonebrew.domain.ide.RepoWorkLoop.run] (the headless issue-driven loop, not yet
 * wired to a live caller — see its own KDoc for that honestly-named gap).
 */
object CommitAnchor {

    /** The git commit's own sha — the ONE identity fact (mirrors
     *  [dev.fonebrew.domain.thread.ThreadGraphNode.sha]'s own KDoc: "the node's real stable
     *  identity fact"). */
    const val SHA_KEY = "run.commit.sha"

    /** Optional "owner/repo@branch" display label — see [repoRef]. Convenience only, never the
     *  identity field; omitted (never fabricated) when a caller can't name one. */
    const val REPO_KEY = "run.commit.repo"

    /** The short "owner/repo@branch" label [ThreadGraphNode.repoRef][dev.fonebrew.domain.thread.
     *  ThreadGraphNode.repoRef]'s own KDoc names as the intended shape (e.g.
     *  "mbaliga/android-ide-core@main") — built from real, already-known [GitHost] fields, never
     *  a guess. */
    fun repoRef(host: GitHost): String = "${host.owner}/${host.repo}@${host.branch}"

    /** The metadata map to fold into a new node's own `metadata` at construction time. [sha] must
     *  be non-blank (it is the node's real identity fact); [repoRef] is folded in only when
     *  non-blank — never a blank placeholder. */
    fun metadata(sha: String, repoRef: String?): Map<String, String> {
        require(sha.isNotBlank()) { "CommitAnchor.metadata: sha must not be blank" }
        return buildMap {
            put(SHA_KEY, sha)
            if (!repoRef.isNullOrBlank()) put(REPO_KEY, repoRef)
        }
    }

    /**
     * Builds ONE new, self-contained [MessageNode] recording that [sha] was committed, ready for
     * the caller to insert (same "pure function, caller does the actual store write" divide
     * [dev.fonebrew.domain.tree.TreeFork.copySubtree] and [dev.fonebrew.domain.loop.RunLog.toNodes]
     * already use — this object never touches a store itself).
     *
     * One call per commit id: a squashed commit returns exactly one sha; a per-file fallback
     * commit returns one per file, so a caller invokes this once per sha rather than losing all
     * but one to a single-valued metadata key.
     *
     * [parentId] defaults to `null` (a fresh, self-standing root) because neither of today's two
     * call sites has an existing conversation/run node to hang the record off of — see
     * [CommitAnchor]'s own class KDoc. A future chat-integrated commit path (the shape
     * `fixtures/thread/valid/thread-graph-derivation-and-commit-anchor-valid.json` actually
     * illustrates — the metadata sitting on an ordinary reply-chain message) can pass an existing
     * node id here instead; [dev.fonebrew.domain.thread.ThreadGraphProjector] doesn't care which —
     * it reads the metadata off whatever node carries it.
     */
    fun node(
        id: String,
        sha: String,
        repoRef: String?,
        createdAt: Long,
        parentId: String? = null,
        label: String? = null,
    ): MessageNode = MessageNode(
        id = id,
        parentId = parentId,
        role = Role.SYSTEM,
        content = label?.takeIf { it.isNotBlank() } ?: "Committed $sha",
        createdAt = createdAt,
        metadata = metadata(sha, repoRef),
    )
}
