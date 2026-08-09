package dev.aarso.domain.workspace

import dev.aarso.contracts.workspace.BufferJournalEntry
import dev.aarso.contracts.workspace.JournalOpType
import dev.aarso.contracts.workspace.OriginKind
import dev.aarso.contracts.workspace.ResourceProvider
import dev.aarso.contracts.workspace.ResourceUri
import dev.aarso.contracts.workspace.WorkspaceJournal
import dev.aarso.domain.diff.ChangeSet
import dev.aarso.domain.ide.ChangeCommitter
import java.time.Instant

/**
 * FB-RAT-WS-005 ("human and agent edits share ONE transaction/journal/review/undo/conflict
 * model") made real, as an **adapter over the existing agent-edit path** rather than a rewrite of
 * it -- exactly the WP-3 brief's instruction ("hook point for existing editor/agent code
 * identified in WP-0, adapters only -- do not refactor the live app"). [dev.aarso.domain.ide.
 * RepoWorkLoop] (Sprint 5's agentic loop over an existing repo) is untouched; this class decorates
 * its [ChangeCommitter] seam so every already-approved [ChangeSet] a `RepoWorkLoop` commits also
 * lands in the Workspace Kernel's [WorkspaceJournal] with `originKind = AGENT`, using the exact
 * same `WorkspaceJournal.append` entry point a human edit would use (§4 FB-RAT-WS-005's "one
 * journal, one append() entry point" mechanism).
 *
 * Journaled as `SET_FULL_CONTENT` per file, mirroring [ChangeSet.FileChange]'s own whole-old-text/
 * whole-new-text shape (WORKSPACE_KERNEL_SPEC.md §6.3's per-opType design rationale: a
 * whole-buffer op needs only the new content, no byte range) -- `RepoWorkLoop`'s diff engine is
 * line-based, not byte-range-based, so re-deriving a byte range here would invent precision the
 * source data doesn't have.
 *
 * A `bufferId` is deterministically derived from each changed path so repeated edits to the same
 * file accumulate under one buffer's journal (`sequence` is per-`bufferId`, WORKSPACE_KERNEL_SPEC.md
 * §6.3) rather than minting a fresh, disconnected buffer identity per commit.
 */
class AgentEditJournalAdapter(
    private val delegate: ChangeCommitter,
    private val journal: WorkspaceJournal,
    private val originPrincipal: String,
    private val bufferIdForPath: (String) -> String = { path -> "buf_agent_" + path.hashCode().toUInt().toString(36) },
    private val nextSequence: suspend (bufferId: String) -> Long,
    private val now: () -> Instant = Instant::now,
) : ChangeCommitter {

    override suspend fun commit(changeSet: ChangeSet, message: String): Result<String> {
        val outcome = delegate.commit(changeSet, message)
        if (outcome.isSuccess) {
            for (change in changeSet.effective) {
                val bufferId = bufferIdForPath(change.path)
                val uri = ResourceUri(ResourceProvider.LOCAL, "local://${change.path}")
                val sequence = nextSequence(bufferId)
                journal.append(
                    BufferJournalEntry(
                        sequence = sequence,
                        bufferId = bufferId,
                        resourceUri = uri,
                        opType = JournalOpType.SET_FULL_CONTENT,
                        recordedAtUtc = now(),
                        originKind = OriginKind.AGENT,
                        originPrincipal = originPrincipal,
                        content = change.newText
                    )
                )
            }
        }
        return outcome
    }

    companion object {
        /** A ready-made [nextSequence] backed by an in-memory per-bufferId counter. */
        fun inMemorySequencer(): suspend (String) -> Long {
            val counters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
            return { bufferId ->
                counters.computeIfAbsent(bufferId) { java.util.concurrent.atomic.AtomicLong(-1) }
                    .incrementAndGet()
            }
        }
    }
}
