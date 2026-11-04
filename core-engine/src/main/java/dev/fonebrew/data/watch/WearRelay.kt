package dev.fonebrew.data.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What [dev.fonebrew.di.AppContainer.wearRelay] hands the listener service: the [presenter] plus the
 * process-lifetime [scope] its work runs in.
 *
 * Why a process scope and not the service's own: a `WearableListenerService` is bound by Play services
 * only while it is delivering events and is destroyed shortly after the callback returns, but a
 * generation takes seconds to minutes. Work launched in the service's scope would be cancelled mid-answer.
 * The scope here lives with the app process (the same lifetime the phone's chat generations have, given
 * the foreground boost the runner asks for on a local model), and a supervisor job keeps one failed
 * question from cancelling the next.
 *
 * Thin by design: the one decision it owns is "launch, don't block the callback thread".
 */
class WearRelay(
    val presenter: WearRelayPresenter,
    val scope: CoroutineScope,
) {
    /** Starts handling one Data Layer message and returns immediately. */
    fun onMessage(sourceNodeId: String, path: String, payload: ByteArray, transport: WearTransport): Job =
        scope.launch { presenter.onMessage(sourceNodeId, path, payload, transport) }
}
