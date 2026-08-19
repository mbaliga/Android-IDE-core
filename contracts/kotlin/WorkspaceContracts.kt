// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// WorkspaceContracts.kt — the "workspace" domain's shared wire-shape + provider-interface
// data classes. Mirrors, field-for-field where a JSON Schema counterpart exists,
// schemas/workspace/*.schema.json:
//   Workspace           -> workspace.schema.json
//   Project              -> project.schema.json
//   ResourceUri           -> resource-uri.schema.json
//   DocumentBuffer         -> document-buffer.schema.json
//   RecoverySnapshot        -> recovery-snapshot.schema.json
//   BufferJournalEntry       -> buffer-journal-entry.schema.json
//   ReconnectToken           -> reconnect-token.schema.json
// Three further object-model types this domain owns per FB-RAT-WS-001 (RepositoryState,
// LanguageSession, TaskDefinition) are modeled below too, but do NOT have a JSON Schema
// counterpart in this work package — see docs/ratified/WORKSPACE_KERNEL_SPEC.md §5's scope
// note. The WP-1 task brief's explicit schema-file list named exactly the seven files above;
// this Kotlin file goes one step further and covers the full object model given in that
// brief so the domain's shape is self-consistent end to end, but only the seven wire shapes
// above are schema-validated + fixture-tested this pass.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant, plus kotlinx-coroutines Flow for WorkspaceProvider.watch()
// (the one named exception in the WP-1 task brief's shared rules). No kotlinx-serialization,
// no kotlinx-datetime, no Android imports. Imports dev.fonebrew.contracts.common (this same
// contracts/kotlin/ source set's foundations domain, CommonContracts.kt) for
// IntegrityRef/ErrorEnvelope — first-party sibling code, not a third-party dependency.
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for the
// next session with Gradle available to confirm.
//
// Design note on sealed interfaces vs the wire schema: DocumentBufferState and
// WorkspaceProviderState are the two explicit state machines this domain's task brief names
// (see WORKSPACE_KERNEL_SPEC.md §4/§6 for the from-state/event/to-state tables). Both are
// modeled here as sealed interfaces per the brief's instruction ("sealed interfaces for
// state machines"), and DocumentBufferState goes one step further than its wire
// counterpart: the wire shape (document-buffer.schema.json) has to represent
// state/dirty/conflictState/lastSaveError as four separate fields pinned together with
// `allOf`/`if`/`then`, because JSON has no tagged-union primitive — that is exactly why
// fixtures/workspace/invalid/document-buffer-dirty-state-contradiction.invalid.json and
// .../document-buffer-conflict-state-mismatch.invalid.json exist as fixtures at all: the
// wire format CAN represent that contradiction, and something has to reject it. The Kotlin
// sealed interface below cannot represent it in the first place — `Conflicted`/`Merging`
// carry their `BufferConflict` payload directly, `SaveFailed` carries its `ErrorEnvelope`
// directly, and `dirty` is a computed property derived from which state variant is present,
// not a separately stored field that could drift out of sync. This is a genuine advantage
// of the Kotlin encoding over the wire encoding, not an oversight in either direction — the
// wire format's extra invariant-checking machinery exists because JSON needs it; Kotlin's
// type system makes the same class of bug unrepresentable instead.

package dev.fonebrew.contracts.workspace

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import dev.fonebrew.contracts.common.ErrorEnvelope
import dev.fonebrew.contracts.common.IntegrityRef

// ---------------------------------------------------------------------------------------
// ResourceUri — schemas/workspace/resource-uri.schema.json (FB-RAT-WS-002)
// ---------------------------------------------------------------------------------------

/** FB-RAT-WS-002: the closed provider vocabulary a ResourceUri is qualified by. */
enum class ResourceProvider { LOCAL, SAF, SSH, SNAPSHOT, VIRTUAL }

/**
 * A single resource location, provider-qualified per FB-RAT-WS-002 — local/SAF/SSH/
 * snapshot/virtual MUST NOT share ambiguous raw paths. `raw` is the sole authoritative
 * identity string; `displayPath` is UI-only and MUST NOT be used for equality/dedup (see
 * fixtures/workspace/adversarial/resource-uri-cross-provider-ambiguity.adversarial.json).
 *
 * @param raw MUST start with `"${provider.name.lowercase()}://"` — enforced in `init{}`,
 *   mirroring resource-uri.schema.json's per-provider `if`/`then` pattern pin.
 */
data class ResourceUri(
    val provider: ResourceProvider,
    val raw: String,
    val displayPath: String? = null
) {
    init {
        val expectedPrefix = "${provider.name.lowercase()}://"
        require(raw.startsWith(expectedPrefix)) {
            "ResourceUri.raw must start with '$expectedPrefix' for provider ${provider.name} " +
                "(FB-RAT-WS-002) — got '$raw'."
        }
        require(raw.length > expectedPrefix.length) {
            "ResourceUri.raw must have content after the '$expectedPrefix' scheme prefix."
        }
    }
}

// ---------------------------------------------------------------------------------------
// WorkspaceProviderState — WORKSPACE_KERNEL_SPEC.md §6
// UNCONFIGURED -> CONNECTING -> READY <-> DEGRADED -> DISCONNECTED | AUTH_REQUIRED | FAILED
// ---------------------------------------------------------------------------------------

/**
 * Connection state of one configured WorkspaceProvider (WORKSPACE_KERNEL_SPEC.md §6). Each
 * non-terminal-looking variant's optional `detail` mirrors the wire shape's
 * `providerConnections[].detail` string field (workspace.schema.json) — FB-RAT-COM-009
 * textual semantics for a non-READY connection, independent of any color-only status dot a
 * UI might also render.
 */
sealed interface WorkspaceProviderState {
    object Unconfigured : WorkspaceProviderState
    object Connecting : WorkspaceProviderState
    object Ready : WorkspaceProviderState
    data class Degraded(val detail: String? = null) : WorkspaceProviderState
    data class Disconnected(val detail: String? = null) : WorkspaceProviderState
    data class AuthRequired(val detail: String? = null) : WorkspaceProviderState
    data class Failed(val detail: String? = null) : WorkspaceProviderState
}

/** One entry of `Workspace.providerConnections` (workspace.schema.json). */
data class ProviderConnection(
    val providerId: String,
    val providerKind: ResourceProvider,
    val state: WorkspaceProviderState,
    val lastTransitionAtUtc: Instant
) {
    init {
        require(providerId.isNotBlank()) { "ProviderConnection.providerId must be non-blank." }
    }
}

// ---------------------------------------------------------------------------------------
// Workspace — schemas/workspace/workspace.schema.json (FB-RAT-WS-001)
// ---------------------------------------------------------------------------------------

/**
 * FB-RAT-WS-001: the durable object the Workspace Kernel is authoritative for — projects,
 * files, buffers, indexes, tasks, language sessions, launch state, recovery.
 *
 * @param workspaceId Globally unique stable ID (FB-RAT-COM-002), independent of `displayName`.
 * @param activeProjectId SHOULD be a member of `projectIds` — not constructor-enforced (a
 *   Workspace instance is frequently constructed incrementally by a repository/store layer
 *   before both sides are known); callers assembling a complete Workspace SHOULD validate
 *   this themselves, mirroring workspace.schema.json's documented (non-structural) MUST.
 */
data class Workspace(
    val workspaceId: String,
    val displayName: String,
    val createdAtUtc: Instant,
    val projectIds: List<String> = emptyList(),
    val activeProjectId: String? = null,
    val providerConnections: List<ProviderConnection> = emptyList(),
    val recoverySnapshotId: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(workspaceId.isNotBlank()) { "Workspace.workspaceId must be non-blank (FB-RAT-COM-002)." }
        require(displayName.isNotBlank()) { "Workspace.displayName must be non-blank." }
    }
}

// ---------------------------------------------------------------------------------------
// Project — schemas/workspace/project.schema.json (FB-RAT-WS-001)
// ---------------------------------------------------------------------------------------

/**
 * FB-RAT-WS-001: a project the Workspace Kernel is authoritative for.
 *
 * @param repositoryStateId Forward-pointing link to this project's RepositoryState, when its
 *   root is a git-tracked working tree (see [RepositoryState] below — no standalone JSON
 *   Schema for it this pass, see the file header note).
 * @param taskDefinitionIds Forward-pointing links to this project's [TaskDefinition]s.
 * @param languageSessionIds Forward-pointing links to this project's [LanguageSession]s.
 */
data class Project(
    val projectId: String,
    val workspaceId: String,
    val displayName: String,
    val rootUri: ResourceUri,
    val repositoryStateId: String? = null,
    val taskDefinitionIds: List<String> = emptyList(),
    val languageSessionIds: List<String> = emptyList(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(projectId.isNotBlank()) { "Project.projectId must be non-blank (FB-RAT-COM-002)." }
        require(workspaceId.isNotBlank()) { "Project.workspaceId must be non-blank." }
        require(displayName.isNotBlank()) { "Project.displayName must be non-blank." }
    }
}

// ---------------------------------------------------------------------------------------
// DocumentBufferState + DocumentBuffer — schemas/workspace/document-buffer.schema.json
// WORKSPACE_KERNEL_SPEC.md §4:
//   CLOSED -> OPEN_CLEAN -> OPEN_DIRTY -> SAVING -> OPEN_CLEAN | SAVE_FAILED
//   OPEN_DIRTY -> CONFLICTED -> MERGING -> OPEN_DIRTY
// FB-RAT-WS-003/004/005/009
// ---------------------------------------------------------------------------------------

/** Conflict details, carried directly by the Conflicted/Merging DocumentBufferState variants. */
data class BufferConflict(
    val remoteRevision: String,
    val detectedAtUtc: Instant,
    val remoteSummary: String? = null
) {
    init {
        require(remoteRevision.isNotBlank()) { "BufferConflict.remoteRevision must be non-blank (FB-RAT-WS-004)." }
    }
}

/** Line-ending convention for a DocumentBuffer. MIXED = more than one convention present. */
enum class LineEndings { LF, CRLF, CR, MIXED }

/**
 * DocumentBufferState (WORKSPACE_KERNEL_SPEC.md §4). `Conflicted`/`Merging` carry their
 * [BufferConflict] directly and `SaveFailed` carries its [ErrorEnvelope] directly — see the
 * file-header design note on why this is stronger than the wire shape's separate
 * `conflictState`/`lastSaveError` fields.
 */
sealed interface DocumentBufferState {
    object Closed : DocumentBufferState
    object OpenClean : DocumentBufferState
    object OpenDirty : DocumentBufferState
    object Saving : DocumentBufferState
    data class SaveFailed(val error: ErrorEnvelope) : DocumentBufferState
    data class Conflicted(val conflict: BufferConflict) : DocumentBufferState
    data class Merging(val conflict: BufferConflict) : DocumentBufferState
}

/**
 * FB-RAT-WS-003: dirty buffers survive Activity recreation/process death/reboot until
 * explicit save or discard. FB-RAT-WS-004: optimistic remote writes — `baseRevision` is the
 * `expectedRevision` a save presents to [WorkspaceProvider.write]. FB-RAT-WS-005: this
 * buffer's edit history (both human and agent) lives in ONE journal — see
 * [BufferJournalEntry] — not a second hidden path.
 *
 * @param dirty Computed, not stored — cannot drift from `state` the way the wire shape's
 *   separately-stored `dirty` field structurally could (see fixtures/workspace/invalid/
 *   document-buffer-dirty-state-contradiction.invalid.json for the wire-level version of the
 *   bug this design prevents by construction).
 * @param journalSequence Highest [BufferJournalEntry.sequence] applied so far (FB-RAT-COM-004).
 */
data class DocumentBuffer(
    val bufferId: String,
    val resourceUri: ResourceUri,
    val state: DocumentBufferState,
    val baseRevision: String?,
    val contentRevision: String,
    val encoding: String,
    val lineEndings: LineEndings,
    val journalSequence: Long,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    val dirty: Boolean
        get() = state !is DocumentBufferState.Closed && state !is DocumentBufferState.OpenClean

    init {
        require(bufferId.isNotBlank()) { "DocumentBuffer.bufferId must be non-blank (FB-RAT-COM-002)." }
        require(contentRevision.isNotBlank()) { "DocumentBuffer.contentRevision must be non-blank." }
        require(encoding.isNotBlank()) { "DocumentBuffer.encoding must be non-blank." }
        require(journalSequence >= 0) { "DocumentBuffer.journalSequence must be >= 0." }
    }
}

// ---------------------------------------------------------------------------------------
// RecoverySnapshot — schemas/workspace/recovery-snapshot.schema.json (FB-RAT-WS-001/003)
// ---------------------------------------------------------------------------------------

/** Why a RecoverySnapshot was taken. */
enum class RecoverySnapshotReason { PERIODIC_CHECKPOINT, PRE_OPERATION, PROCESS_DEATH_CAPTURE, MANUAL }

/** One buffer's captured state within a RecoverySnapshot. */
data class BufferSnapshotEntry(
    val bufferId: String,
    val resourceUri: ResourceUri,
    val journalSequence: Long,
    val dirty: Boolean,
    val encoding: String,
    val lineEndings: LineEndings,
    val contentDigest: IntegrityRef
) {
    init {
        require(bufferId.isNotBlank()) { "BufferSnapshotEntry.bufferId must be non-blank." }
        require(journalSequence >= 0) { "BufferSnapshotEntry.journalSequence must be >= 0." }
        require(encoding.isNotBlank()) { "BufferSnapshotEntry.encoding must be non-blank." }
    }
}

/**
 * FB-RAT-WS-003: what [WorkspaceJournal.checkpoint]/[WorkspaceJournal.restore] produce/
 * consume so dirty buffers survive Activity recreation/process death/reboot until explicit
 * save or discard.
 *
 * @param lastJournalSequence The global journal watermark this snapshot represents
 *   (FB-RAT-COM-004). Every [BufferSnapshotEntry.journalSequence] MUST be <= this value —
 *   not constructor-enforced here (would require iterating `bufferSnapshots` in `init{}`;
 *   left as a documented invariant matching recovery-snapshot.schema.json's prose, which also
 *   does not structurally enforce it for the same reason JSON Schema 2020-12 cannot compare
 *   across array items without a `$data` extension this constellation does not depend on).
 */
data class RecoverySnapshot(
    val snapshotId: String,
    val workspaceId: String,
    val takenAtUtc: Instant,
    val reason: RecoverySnapshotReason,
    val lastJournalSequence: Long,
    val bufferSnapshots: List<BufferSnapshotEntry> = emptyList(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(snapshotId.isNotBlank()) { "RecoverySnapshot.snapshotId must be non-blank (FB-RAT-COM-002)." }
        require(workspaceId.isNotBlank()) { "RecoverySnapshot.workspaceId must be non-blank." }
        require(lastJournalSequence >= 0) { "RecoverySnapshot.lastJournalSequence must be >= 0." }
    }
}

// ---------------------------------------------------------------------------------------
// BufferJournalEntry — schemas/workspace/buffer-journal-entry.schema.json
// Named but never specified in the source pack; designed per the WP-1 task brief.
// FB-RAT-WS-005: human and agent edits share ONE journal.
// ---------------------------------------------------------------------------------------

/** The edit or lifecycle operation a BufferJournalEntry records. */
enum class JournalOpType { INSERT, DELETE, REPLACE, SET_FULL_CONTENT, OPEN, CLOSE, DISCARD }

/** Half-open [startByte, endByteExclusive) range, in the buffer's byte-offset space. */
data class ByteRange(val startByte: Long, val endByteExclusive: Long) {
    init {
        require(startByte >= 0) { "ByteRange.startByte must be >= 0." }
        require(endByteExclusive >= startByte) {
            "ByteRange.endByteExclusive (${endByteExclusive}) must be >= startByte (${startByte})."
        }
    }
}

/** Which lane produced a journal entry (FB-RAT-WS-005 — one shared journal, not two paths). */
enum class OriginKind { HUMAN, AGENT }

/**
 * One append-only entry in a buffer's edit journal (FB-RAT-COM-004 monotonic `sequence`,
 * FB-RAT-COM-008 `originPrincipal` provenance). Identity for this log entry is the
 * `(bufferId, sequence)` pair, not a separate `objectId` — an append-only journal entry's
 * position in the log IS its identity.
 *
 * A caller constructing a Kotlin instance of this type gets stronger typing than the wire
 * shape's `allOf`/`if`/`then` on `opType`/`content`/`byteRange` can offer directly: `content`
 * and `byteRange` are both still nullable here (unlike DocumentBufferState's sealed-interface
 * treatment above) because [JournalOpType] is a flat enum, not a sealed hierarchy — kept flat
 * intentionally, since a `sequence`-ordered log of many entries benefits from a simple
 * discriminant field callers can pattern-match/filter on without allocating a subtype per
 * entry; `init{}` below enforces the same per-opType invariants document-buffer's if/then
 * blocks enforce on the wire.
 */
data class BufferJournalEntry(
    val sequence: Long,
    val bufferId: String,
    val resourceUri: ResourceUri,
    val opType: JournalOpType,
    val recordedAtUtc: Instant,
    val originKind: OriginKind,
    val originPrincipal: String,
    val byteRange: ByteRange? = null,
    val content: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(sequence >= 0) { "BufferJournalEntry.sequence must be >= 0 (FB-RAT-COM-004)." }
        require(bufferId.isNotBlank()) { "BufferJournalEntry.bufferId must be non-blank." }
        require(originPrincipal.isNotBlank()) { "BufferJournalEntry.originPrincipal must be non-blank (FB-RAT-COM-008)." }

        val needsContent = opType in setOf(JournalOpType.INSERT, JournalOpType.REPLACE, JournalOpType.SET_FULL_CONTENT)
        require(needsContent == (content != null)) {
            "BufferJournalEntry.content must be non-null iff opType is INSERT/REPLACE/SET_FULL_CONTENT (got opType=$opType, content=$content)."
        }

        val needsByteRange = opType in setOf(JournalOpType.INSERT, JournalOpType.DELETE, JournalOpType.REPLACE)
        require(needsByteRange == (byteRange != null)) {
            "BufferJournalEntry.byteRange must be non-null iff opType is INSERT/DELETE/REPLACE (got opType=$opType, byteRange=$byteRange)."
        }
    }
}

// ---------------------------------------------------------------------------------------
// ReconnectToken — schemas/workspace/reconnect-token.schema.json
// Named but never specified in the source pack; designed per the WP-1 task brief.
// ---------------------------------------------------------------------------------------

/**
 * Opaque, provider-issued reconnection credential (see the header comment on
 * reconnect-token.schema.json and fixtures/workspace/adversarial/
 * reconnect-token-plaintext-secret-leak.adversarial.json). `tokenBytes` MUST be handled with
 * the same secret-handling discipline as an API key under binding rule 5 (Keystore-encrypted
 * at rest, never logged) — this type cannot enforce that at construction time, only document it.
 */
data class ReconnectToken(
    val providerId: String,
    val tokenBytes: String,
    val issuedAtUtc: Instant,
    val expiresAtUtc: Instant,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(providerId.isNotBlank()) { "ReconnectToken.providerId must be non-blank." }
        require(tokenBytes.isNotBlank()) { "ReconnectToken.tokenBytes must be non-blank." }
        require(expiresAtUtc.isAfter(issuedAtUtc)) {
            "ReconnectToken.expiresAtUtc must be after issuedAtUtc (issuedAtUtc=$issuedAtUtc, expiresAtUtc=$expiresAtUtc)."
        }
    }
}

// ---------------------------------------------------------------------------------------
// RepositoryState — WORKSPACE_KERNEL_SPEC.md §5 object model + §5's operation-lock section.
// FB-RAT-WS-001/007. NOT schema-validated this pass — see file header note.
// ---------------------------------------------------------------------------------------

/**
 * Which git host a RepositoryState talks to. Provider-generic per the constellation's usual
 * ethos (CLAUDE.md binding rule 2 makes cloud MODEL providers provider-generic; this enum
 * applies the same instinct to git hosts, which is this file's own extrapolation, not a
 * cited FB-RAT decision — flagged as such, see WORKSPACE_KERNEL_SPEC.md §5). No JGit or any
 * git library exists as a dependency anywhere in this constellation today (WP0_SURVEY.md §3)
 * — a RepositoryState is populated by GitContentsApi/GitTreeApi REST calls, not by an
 * embedded git implementation walking `.git` on disk.
 */
enum class GitHostProvider { GITHUB, GITEA, OTHER_REST }

/** FB-RAT-WS-007: the kind of history-rewriting operation an operation lock guards. */
enum class GitOperationKind { REBASE, MERGE, CHERRY_PICK, RESET_HARD, HISTORY_REWRITE_OTHER }

/**
 * FB-RAT-WS-007: history-rewriting Git ops take an operation lock with progress/abort/
 * recovery. `recoverySnapshotId` points at the [RecoverySnapshot] (reason=PRE_OPERATION)
 * taken immediately before this operation began, so abort/recovery has something concrete to
 * restore to.
 */
data class GitOperationLock(
    val lockId: String,
    val operationKind: GitOperationKind,
    val acquiredAtUtc: Instant,
    val holder: String,
    val progress: Double,
    val abortable: Boolean,
    val recoverySnapshotId: String? = null
) {
    init {
        require(lockId.isNotBlank()) { "GitOperationLock.lockId must be non-blank." }
        require(holder.isNotBlank()) { "GitOperationLock.holder must be non-blank (FB-RAT-COM-008 initiating principal)." }
        require(progress in 0.0..1.0) { "GitOperationLock.progress must be within [0.0, 1.0], got $progress." }
    }
}

/** Ahead/behind tracking against a configured remote branch. */
data class UpstreamRef(
    val remoteName: String,
    val remoteBranch: String,
    val ahead: Int,
    val behind: Int
) {
    init {
        require(remoteName.isNotBlank()) { "UpstreamRef.remoteName must be non-blank." }
        require(remoteBranch.isNotBlank()) { "UpstreamRef.remoteBranch must be non-blank." }
        require(ahead >= 0) { "UpstreamRef.ahead must be >= 0." }
        require(behind >= 0) { "UpstreamRef.behind must be >= 0." }
    }
}

/**
 * FB-RAT-WS-001: a project's git working-tree state, when the project is git-tracked. See
 * WORKSPACE_KERNEL_SPEC.md §5 for the full field-by-field description and the "Proposed
 * decision (not self-ratified)" callout on which git library/lane a real implementation
 * routes through (JGit for read/status/commit, libgit2-JNI/git24j evaluated for merge/
 * rebase/conflict, history-rewriting possibly via the SSH lane) — that proposal is NOT
 * decided by this file and is not reflected in any field here; this type only carries state,
 * never how it was produced.
 */
data class RepositoryState(
    val repositoryStateId: String,
    val provider: GitHostProvider,
    val root: ResourceUri,
    val head: String,
    val branch: String? = null,
    val dirtyDigest: IntegrityRef? = null,
    val stagedDigest: IntegrityRef? = null,
    val upstream: UpstreamRef? = null,
    val operationLock: GitOperationLock? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(repositoryStateId.isNotBlank()) { "RepositoryState.repositoryStateId must be non-blank (FB-RAT-COM-002)." }
        require(head.isNotBlank()) { "RepositoryState.head must be non-blank." }
    }
}

// ---------------------------------------------------------------------------------------
// LanguageSession — WORKSPACE_KERNEL_SPEC.md §5. FB-RAT-WS-001. NOT schema-validated this
// pass — named with zero further qualifier in the task brief's object model, minimally
// specified here (see file header note and WORKSPACE_KERNEL_SPEC.md §5).
// ---------------------------------------------------------------------------------------

/** Coarse lifecycle state for a language-service session. Intentionally minimal this pass. */
enum class LanguageSessionState { STARTING, READY, DEGRADED, STOPPED }

/**
 * FB-RAT-WS-001: an active language-service (e.g. LSP) session the Workspace Kernel is
 * authoritative for. Minimally specified — the source pack names this type with no field
 * qualifier at all (contrast [TaskDefinition] below, which the brief gives an explicit
 * qualifier for), and its full capability wiring depends on the Execution Contract +
 * Authority engine domain (WP-0 survey (c) — create-new, not built by this work package).
 * `capabilityManifestId` is a forward pointer to a `CapabilityManifest` (subjectKind=LSP,
 * schemas/common/capability-manifest.schema.json) — no CapabilityManifest fields are
 * invented here, only the stable-ID link.
 */
data class LanguageSession(
    val languageSessionId: String,
    val projectId: String,
    val languageId: String,
    val state: LanguageSessionState,
    val startedAtUtc: Instant,
    val capabilityManifestId: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(languageSessionId.isNotBlank()) { "LanguageSession.languageSessionId must be non-blank (FB-RAT-COM-002)." }
        require(projectId.isNotBlank()) { "LanguageSession.projectId must be non-blank." }
        require(languageId.isNotBlank()) { "LanguageSession.languageId must be non-blank." }
    }
}

// ---------------------------------------------------------------------------------------
// TaskDefinition — WORKSPACE_KERNEL_SPEC.md §5. FB-RAT-WS-001. NOT schema-validated this
// pass. Qualifier given in the task brief: typed command/tool, target constraints, problem
// matcher, authority requirements.
// ---------------------------------------------------------------------------------------

/** The typed command/tool family a TaskDefinition invokes. */
enum class TaskCommandKind { SHELL, GRADLE, ARDUINO_CLI, SSH_REMOTE, OTHER }

/** Target constraints a TaskDefinition's execution is bound to. */
data class TaskTargetConstraints(
    val requiredCapabilities: List<String> = emptyList(),
    val requiredDeviceId: String? = null,
    val workingDirectory: String? = null
)

/** A single problem matcher: a named regex used to parse tool output into problems. */
data class ProblemMatcher(
    val name: String,
    val pattern: String
) {
    init {
        require(name.isNotBlank()) { "ProblemMatcher.name must be non-blank." }
        require(pattern.isNotBlank()) { "ProblemMatcher.pattern must be non-blank." }
    }
}

/**
 * FB-RAT-WS-007-adjacent: authority requirements a TaskDefinition declares up front, so an
 * Execution Contract + Authority engine (WP-0 survey (c), not built by this work package)
 * has something concrete to gate against once it exists. Declared here as plain booleans,
 * not wired to any enforcement — this domain does not itself grant or check authority.
 */
data class TaskAuthorityRequirements(
    val requiresNetworkAccess: Boolean = false,
    val requiresFilesystemWrite: Boolean = false,
    val requiresDeviceExecution: Boolean = false,
    val requiresHistoryRewrite: Boolean = false
)

/**
 * FB-RAT-WS-001: a typed, repeatable task the Workspace Kernel is authoritative for (e.g. a
 * Gradle invocation, a device flash recipe). Qualifier from the task brief: typed command/
 * tool, target constraints, problem matcher, authority requirements — all four present below.
 */
data class TaskDefinition(
    val taskDefinitionId: String,
    val projectId: String,
    val displayName: String,
    val commandKind: TaskCommandKind,
    val command: String,
    val targetConstraints: TaskTargetConstraints = TaskTargetConstraints(),
    val problemMatchers: List<ProblemMatcher> = emptyList(),
    val authorityRequirements: TaskAuthorityRequirements = TaskAuthorityRequirements(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(taskDefinitionId.isNotBlank()) { "TaskDefinition.taskDefinitionId must be non-blank (FB-RAT-COM-002)." }
        require(projectId.isNotBlank()) { "TaskDefinition.projectId must be non-blank." }
        require(displayName.isNotBlank()) { "TaskDefinition.displayName must be non-blank." }
        require(command.isNotBlank()) { "TaskDefinition.command must be non-blank." }
    }
}

// ---------------------------------------------------------------------------------------
// Provider contracts — WORKSPACE_KERNEL_SPEC.md §7. Exact signatures per the WP-1 task
// brief's object model. FB-RAT-WS-004 (optimistic remote writes) is why `write()` returns a
// sealed ResourceWrite rather than a bare success type or a thrown exception — a conflict is
// a typed, expected result the caller MUST handle, not a silent overwrite and not merely an
// exceptional control-flow path a caller could accidentally swallow.
// ---------------------------------------------------------------------------------------

/** Result of [WorkspaceProvider.stat]. */
data class ResourceStat(
    val uri: ResourceUri,
    val exists: Boolean,
    val revision: String?,
    val sizeBytes: Long?,
    val lastModifiedUtc: Instant?,
    val isDirectory: Boolean
)

/** Result of [WorkspaceProvider.read]. */
data class ResourceRead(
    val uri: ResourceUri,
    val revision: String,
    val bytes: ByteArray,
    val encoding: String
)

/**
 * FB-RAT-WS-004: result of [WorkspaceProvider.write]. A `Conflict` is the structural
 * encoding of "MUST NOT silently overwrite changed content" — there is no code path in this
 * sealed hierarchy that returns a bare "it worked" for a write whose `expectedRevision`
 * turned out to be stale.
 */
sealed interface ResourceWrite {
    data class Success(
        val uri: ResourceUri,
        val newRevision: String,
        val digest: IntegrityRef? = null
    ) : ResourceWrite

    data class Conflict(
        val uri: ResourceUri,
        val expectedRevision: String?,
        val actualRevision: String,
        val remoteDigest: IntegrityRef? = null
    ) : ResourceWrite
}

/** What kind of change [WorkspaceProvider.watch] observed. */
enum class ResourceChangeKind { CREATED, MODIFIED, DELETED, REVISION_CHANGED }

/** One change event from [WorkspaceProvider.watch]. */
data class ResourceChange(
    val uri: ResourceUri,
    val changeKind: ResourceChangeKind,
    val newRevision: String?,
    val observedAtUtc: Instant
)

/** One page of [WorkspaceProvider.list] results. */
data class ResourcePage(
    val items: List<ResourceStat>,
    val nextCursor: String? = null
)

/**
 * FB-RAT-WS-001/002/004: the provider seam every ResourceUri-addressed operation goes
 * through, regardless of which provider family (LOCAL/SAF/SSH/SNAPSHOT/VIRTUAL) backs it.
 * Exact signatures per the WP-1 task brief.
 */
interface WorkspaceProvider {
    suspend fun stat(uri: ResourceUri): ResourceStat

    suspend fun read(uri: ResourceUri, expectedRevision: String? = null): ResourceRead

    suspend fun write(uri: ResourceUri, bytes: ByteArray, expectedRevision: String?): ResourceWrite

    fun watch(scope: ResourceUri): Flow<ResourceChange>

    suspend fun list(scope: ResourceUri, cursor: String? = null): ResourcePage
}

/**
 * FB-RAT-WS-003/005: the single append-only journal + checkpoint/restore seam every buffer
 * edit (human or agent) goes through. Exact signatures per the WP-1 task brief.
 */
interface WorkspaceJournal {
    suspend fun append(change: BufferJournalEntry)

    suspend fun checkpoint(workspaceId: String): RecoverySnapshot

    suspend fun restore(workspaceId: String): RecoverySnapshot?
}
