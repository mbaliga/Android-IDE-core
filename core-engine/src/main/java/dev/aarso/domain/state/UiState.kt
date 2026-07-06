package dev.aarso.domain.state

/**
 * The **state matrix** — the one contract Doc 00 §3.8 requires *every* surface's
 * ViewModel to expose. The rule is legibility over the happy path: a surface that can
 * only render "ready" is lying about the world it lives in. So every screen enumerates
 * the **same seven states** and renders each one honestly, with guidance, instead of a
 * spinner that never resolves or a blank that looks like a bug.
 *
 * The states (mutually exclusive, exhaustive):
 *  - [Loading]            — work in flight, nothing to show yet.
 *  - [Empty]              — the *useful* empty: there is genuinely nothing here, and the
 *                           surface should teach the user how to make something. This is
 *                           **distinct from [Ready] holding an empty list** — that latter
 *                           is "a filter matched zero rows", a normal data shape; [Empty]
 *                           is "you have no projects yet, here's how to start one".
 *  - [Partial]            — some data loaded, a slice failed or is still pending. Render
 *                           what arrived and mark the rest — never discard good data because
 *                           one source was slow or down.
 *  - [Ready]              — the full, current value.
 *  - [Error]              — something failed with a human cause; names the **watched object**
 *                           (the external cloud/provider/host) when the failure is external.
 *  - [Offline]            — a network/cloud feature is offline; surfaces the **on-device
 *                           alternative** when one exists (on-device is always the default).
 *  - [PermissionBlocked]  — the path is gated: key missing, host not connected, module not
 *                           installed. States the **unblock path**, never just "denied".
 *
 * Pure domain (no Android, no coroutines). Combinators ([map], [fold], [fromResult],
 * [valueOrNull]) keep the per-surface ViewModels small and the matrix uniform across
 * screens, JVM-tested.
 *
 * @param T the ready/partial payload type. Covariant so a `UiState<Dog>` is a `UiState<Animal>`.
 */
sealed interface UiState<out T> {

    /** Work in flight; nothing to render yet. The honest spinner. */
    data object Loading : UiState<Nothing>

    /**
     * The *useful* empty — there is nothing here and the surface should guide the user
     * to create the first thing. **Not** the same as [Ready] over an empty collection
     * (a filter that matched nothing); this state means "render onboarding guidance".
     */
    data object Empty : UiState<Nothing>

    /**
     * Some data loaded, a slice failed or is still pending. Render [value] (what arrived)
     * and mark the missing [failedSlice] so the user sees the gap rather than a silent
     * half-truth.
     *
     * @param value the partial-but-usable payload that did load.
     * @param failedSlice a human label for the slice that failed/pends (e.g. `"cloud history"`),
     *   or null if the partiality is unlabelled.
     */
    data class Partial<out T>(val value: T, val failedSlice: String? = null) : UiState<T>

    /** The full, current value. The terminal success of the matrix. */
    data class Ready<out T>(val value: T) : UiState<T>

    /**
     * A failure with a human-readable [cause]. When the failure is external, [watchedObject]
     * names the watched object (the cloud provider / git host / device) that failed, so the
     * UI can mark it — cloud is always a visibly *watched* dependency. [retryable] tells the
     * surface whether to offer a Retry affordance.
     *
     * @param cause human-readable description of what went wrong.
     * @param watchedObject the external dependency that failed, or null if the fault is local.
     * @param retryable whether retrying could succeed (false for e.g. a malformed request).
     */
    data class Error(
        val cause: String,
        val watchedObject: String? = null,
        val retryable: Boolean = true,
    ) : UiState<Nothing>

    /**
     * A network/cloud feature is offline. [onDeviceAlternative], when present, names the
     * on-device path the user can fall back to — on-device is the default, so offline is
     * a re-route, not a dead end.
     *
     * @param onDeviceAlternative the on-device alternative to surface, or null if none.
     */
    data class Offline(val onDeviceAlternative: String? = null) : UiState<Nothing>

    /**
     * The surface is gated and states how to ungate it: [what] is missing/unconnected
     * (e.g. `"Anthropic API key"`), and [unblockHint] is the concrete unblock path
     * (e.g. `"Add a key in Settings → Text"`). Never a bare "denied".
     *
     * @param what the thing that is missing / not connected / not installed.
     * @param unblockHint the concrete action that unblocks the surface.
     */
    data class PermissionBlocked(val what: String, val unblockHint: String) : UiState<Nothing>
}

/**
 * The payload if one exists — [UiState.Ready] and [UiState.Partial] both carry a value;
 * every other state has nothing to hand back, so this returns null.
 */
fun <T> UiState<T>.valueOrNull(): T? = when (this) {
    is UiState.Ready -> value
    is UiState.Partial -> value
    else -> null
}

/**
 * True only for [UiState.Ready] — the single state that means "the full, current value is
 * here". [UiState.Partial] is deliberately excluded: partial success is not terminal, the
 * surface still owes the user the missing slice.
 */
val UiState<*>.isTerminalSuccess: Boolean
    get() = this is UiState.Ready

/**
 * Transform the payload of a value-bearing state, passing every other case through unchanged.
 * [UiState.Ready] and [UiState.Partial] are mapped (Partial keeps its [UiState.Partial.failedSlice]);
 * [UiState.Loading]/[UiState.Empty]/[UiState.Error]/[UiState.Offline]/[UiState.PermissionBlocked]
 * are returned as-is — they carry no payload to map.
 */
fun <T, R> UiState<T>.map(f: (T) -> R): UiState<R> = when (this) {
    is UiState.Ready -> UiState.Ready(f(value))
    is UiState.Partial -> UiState.Partial(f(value), failedSlice)
    UiState.Loading -> UiState.Loading
    UiState.Empty -> UiState.Empty
    is UiState.Error -> this
    is UiState.Offline -> this
    is UiState.PermissionBlocked -> this
}

/**
 * Lift a [Result] into the matrix. A success becomes [UiState.Empty] when [emptyWhen] judges
 * the value empty (the *useful* empty — render guidance), else [UiState.Ready]. A failure
 * becomes [UiState.Error] carrying the throwable's message (or its class name when blank).
 *
 * Only the two cases a [Result] can distinguish are produced here; the other matrix states
 * (offline, permission-blocked, partial, loading) are surfaced by the caller, which has the
 * context to tell them apart.
 *
 * @param emptyWhen predicate that decides whether a successful value should render as [UiState.Empty].
 */
fun <T> fromResult(result: Result<T>, emptyWhen: (T) -> Boolean = { false }): UiState<T> =
    result.fold(
        onSuccess = { value -> if (emptyWhen(value)) UiState.Empty else UiState.Ready(value) },
        onFailure = { e ->
            val message = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown error"
            UiState.Error(message)
        },
    )

/**
 * Exhaustively collapse a [UiState] to a single value by handling every case — the spine of
 * a ViewModel→UI mapping. Because every branch is required, a new matrix state would force a
 * compile error here, keeping Doc 00 §3.8's "enumerate every state" rule machine-enforced.
 */
fun <T, R> UiState<T>.fold(
    onLoading: () -> R,
    onEmpty: () -> R,
    onPartial: (value: T, failedSlice: String?) -> R,
    onReady: (value: T) -> R,
    onError: (cause: String, watchedObject: String?, retryable: Boolean) -> R,
    onOffline: (onDeviceAlternative: String?) -> R,
    onPermissionBlocked: (what: String, unblockHint: String) -> R,
): R = when (this) {
    UiState.Loading -> onLoading()
    UiState.Empty -> onEmpty()
    is UiState.Partial -> onPartial(value, failedSlice)
    is UiState.Ready -> onReady(value)
    is UiState.Error -> onError(cause, watchedObject, retryable)
    is UiState.Offline -> onOffline(onDeviceAlternative)
    is UiState.PermissionBlocked -> onPermissionBlocked(what, unblockHint)
}
