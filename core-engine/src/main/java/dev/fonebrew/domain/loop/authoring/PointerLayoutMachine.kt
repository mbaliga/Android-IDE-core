package dev.fonebrew.domain.loop.authoring

/**
 * `FB-RAT-LBX-006` (§10, `LOOP_PHONE_AUTHORING_SPEC.md`) -- the pointer / external-display layout
 * state table, made real. The spec's two rows are the only two transitions here. Pointer mode adds
 * speed, not capability, so the machine deliberately has no state, field or event that exists only
 * under [PointerLayout.POINTER_LAYOUT]: everything the author is working on lives in
 * [AuthoringContinuity], which both layouts share and neither transition touches.
 *
 * Both events are idempotent (connecting while already in pointer layout, or disconnecting while
 * already in phone layout, returns the same value), so a flaky hot-plug that repeats an event can
 * never lose data or flip the layout.
 */
enum class PointerLayout { PHONE_LAYOUT, POINTER_LAYOUT }

/** What the spec says must carry over unchanged in both directions. */
data class AuthoringContinuity(
    val selectedNodeId: String? = null,
    val draftRevision: Long = 0L,
    val viewportFocusNodeId: String? = null,
    /** Opaque to this machine; the editor owns its meaning. Never read or rewritten here. */
    val unsavedEditorState: String? = null,
)

data class PointerLayoutState(
    val layout: PointerLayout = PointerLayout.PHONE_LAYOUT,
    val continuity: AuthoringContinuity = AuthoringContinuity(),
)

object PointerLayoutMachine {

    sealed interface Event {
        object PointerConnected : Event
        object PointerDisconnected : Event
    }

    fun start(continuity: AuthoringContinuity = AuthoringContinuity()): PointerLayoutState =
        PointerLayoutState(PointerLayout.PHONE_LAYOUT, continuity)

    fun apply(state: PointerLayoutState, event: Event): PointerLayoutState = when (event) {
        Event.PointerConnected -> state.copy(layout = PointerLayout.POINTER_LAYOUT)
        Event.PointerDisconnected -> state.copy(layout = PointerLayout.PHONE_LAYOUT)
    }
}
