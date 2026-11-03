package dev.fonebrew.domain.loop.authoring

/**
 * §13, `LOOP_PHONE_AUTHORING_SPEC.md` ("Persistence, interruption, and recovery"), made real. This
 * table is intentionally partial, matching the source document's own framing -- it specifies what
 * happens to an in-flight edit or a long operation under process death and journal-driven recovery,
 * not a complete authoring-session lifecycle. In particular, entry into a long-operation state
 * ([LongOperationKind]) is owned by whatever runs that operation (validator, packager, simulator,
 * `LoopRunDriver`), not by this table -- §13 only says what happens to that state if the process
 * dies while it is in flight, so [DraftLifecycleMachine] only models exactly that.
 */
enum class LongOperationKind { VALIDATING, BUILDING_PACKAGE, SIMULATING, RUNNING }

sealed interface DraftLifecycleState {
    object DraftClean : DraftLifecycleState
    object DirtyJournaled : DraftLifecycleState
    data class InProgress(val operation: LongOperationKind) : DraftLifecycleState
    /** "The UI MUST show which specific operation was interrupted on relaunch" -- never guessed as still-running. */
    data class Interrupted(val operation: LongOperationKind) : DraftLifecycleState
    object PackageExportComplete : DraftLifecycleState
    object NoPublishedPackage : DraftLifecycleState
}

object DraftLifecycleMachine {

    sealed interface Event {
        /** User edits a Node Sheet or Stage View field -- journaled immediately, per §13, not deferred to an eventual commit. */
        object Edit : Event
        /** Navigation away, explicit save, or an equivalent commit event. */
        object AcceptEdit : Event
        object ProcessDeath : Event
        /** "Package export MUST be atomic: it either completes fully or leaves no published package." */
        data class ResumePackageBuild(val succeeded: Boolean) : Event
    }

    sealed interface Result {
        data class Advanced(val state: DraftLifecycleState) : Result
        data class Rejected(val reason: String) : Result
    }

    fun apply(state: DraftLifecycleState, event: Event): Result = when (state) {
        DraftLifecycleState.DraftClean -> when (event) {
            Event.Edit -> Result.Advanced(DraftLifecycleState.DirtyJournaled)
            else -> reject(state, event)
        }
        DraftLifecycleState.DirtyJournaled -> when (event) {
            Event.AcceptEdit -> Result.Advanced(DraftLifecycleState.DraftClean)
            // "On relaunch, the same draft and the same view focus MUST be restored from the
            // journal" -- modeled as a self-transition: DIRTY_JOURNALED survives process death
            // unchanged, because it was already durably journaled before the death occurred.
            Event.ProcessDeath -> Result.Advanced(DraftLifecycleState.DirtyJournaled)
            else -> reject(state, event)
        }
        is DraftLifecycleState.InProgress -> when (event) {
            Event.ProcessDeath -> Result.Advanced(DraftLifecycleState.Interrupted(state.operation))
            else -> reject(state, event)
        }
        is DraftLifecycleState.Interrupted -> when (event) {
            is Event.ResumePackageBuild ->
                if (state.operation != LongOperationKind.BUILDING_PACKAGE) {
                    Result.Rejected("DraftLifecycleMachine: ResumePackageBuild is only legal from Interrupted(BUILDING_PACKAGE), was Interrupted(${state.operation}).")
                } else if (event.succeeded) {
                    Result.Advanced(DraftLifecycleState.PackageExportComplete)
                } else {
                    Result.Advanced(DraftLifecycleState.NoPublishedPackage)
                }
            else -> reject(state, event)
        }
        DraftLifecycleState.PackageExportComplete, DraftLifecycleState.NoPublishedPackage -> reject(state, event)
    }

    private fun reject(state: DraftLifecycleState, event: Event): Result.Rejected = Result.Rejected(
        "DraftLifecycleMachine: event ${event::class.simpleName} is not legal from state $state (LOOP_PHONE_AUTHORING_SPEC.md §13)."
    )
}

/**
 * The §13 recovery banner ("an unsaved draft was left from before") should show exactly when
 * there is real content to restore/discard **and** [DraftLifecycleMachine] agrees the draft is
 * genuinely dirty-unresolved -- not [hasPendingRecovery] alone standing in, ad-hoc, for a state
 * decision the machine already models. [DraftLifecycleState.DirtyJournaled] is exactly right for
 * this: a leftover autosave is, definitionally, journaled content that was never accepted
 * (`AcceptEdit`) before the room closed. This also protects against a real hazard the ad-hoc
 * check alone couldn't: [LoopRoom][dev.fonebrew.ui.loops.LoopRoom]'s own live typing/autosave
 * cycle transiently visits `DirtyJournaled` too (see that file's debounced `LaunchedEffect`) --
 * conflating the two would make the recovery banner flash during ordinary edits. Requiring
 * [hasPendingRecovery] too is what keeps that safe. Pure so this decision is JVM-tested directly,
 * not only exercised (or not) inside a Composable.
 */
fun shouldShowRecoveryBanner(hasPendingRecovery: Boolean, lifecycle: DraftLifecycleState): Boolean =
    hasPendingRecovery && lifecycle is DraftLifecycleState.DirtyJournaled

/**
 * A deterministic idempotency key for one (fieldPath, value) journal write -- same field and
 * value always produce the same key, so a debounce that fires again over **unchanged** content
 * (e.g. the graph's nodes/edges moved but the objective text didn't) is a genuine FB-RAT-COM-006
 * retried-write no-op via [DraftEditJournal.append], not a fresh entry every time. A random key
 * per call (e.g. [java.util.UUID.randomUUID]) would defeat that contract outright -- every write
 * would look "new" to the journal regardless of content, which is the bug this function exists to
 * close. Relies on [String.hashCode]'s specified, JVM-stable algorithm (Java/Kotlin guarantee it
 * per the language spec, not merely observed behaviour), so the same inputs always produce the
 * same key across calls, recompositions, and process restarts alike.
 */
fun contentIdempotencyKey(fieldPath: String, newValueJson: String): String =
    "$fieldPath#${(fieldPath.hashCode() * 31 + newValueJson.hashCode())}"

/**
 * One field-level draft edit, ready to journal. §13: "A journal entry SHOULD carry an idempotency
 * key (`FB-RAT-COM-006`)... so a retried journal write is not double-applied."
 */
data class DraftEditEntry(val idempotencyKey: String, val fieldPath: String, val newValueJson: String, val sequence: Long)

/**
 * A minimal, real (not simulated) append-and-replay journal proving the FB-RAT-COM-006 idempotency
 * property this section requires: appending the same [idempotencyKey] twice is a no-op the second
 * time, not a duplicate entry. In-memory here, not Room-backed -- [dev.fonebrew.data.RoomWorkspaceJournal]
 * (WP-3) already proved the durable-append-and-replay-across-forced-kill pattern generically for a
 * different payload shape (buffer content journal entries); re-deriving that same 100-iteration
 * forced-kill proof for this narrower field-edit payload would be repetition, not new verification.
 * Wiring this journal to Room is a flagged follow-up, matching the "no persistence yet" pattern
 * every WP since WP-2 has left for a comparable new piece with no live consumer.
 */
class DraftEditJournal {
    private val appliedKeys = LinkedHashSet<String>()
    private val entries = mutableListOf<DraftEditEntry>()
    private var nextSequence = 0L

    /** Returns true if this write was newly applied, false if [idempotencyKey] had already been seen (a no-op retry). */
    fun append(idempotencyKey: String, fieldPath: String, newValueJson: String): Boolean {
        require(idempotencyKey.isNotBlank()) { "DraftEditJournal.append: idempotencyKey must be non-blank." }
        require(fieldPath.isNotBlank()) { "DraftEditJournal.append: fieldPath must be non-blank." }
        if (!appliedKeys.add(idempotencyKey)) return false
        entries += DraftEditEntry(idempotencyKey, fieldPath, newValueJson, nextSequence++)
        return true
    }

    fun entriesSoFar(): List<DraftEditEntry> = entries.toList()

    /** Deterministic replay: last write wins per field path, in append (sequence) order -- same "materialize" posture as [dev.fonebrew.domain.workspace.BufferReplay] (WP-3). */
    fun materialize(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (entry in entries) result[entry.fieldPath] = entry.newValueJson
        return result
    }
}
