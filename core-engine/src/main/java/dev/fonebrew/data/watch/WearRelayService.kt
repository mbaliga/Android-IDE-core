package dev.fonebrew.data.watch

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.fonebrew.FonebrewApp
import dev.fonebrew.watchbridge.WatchPaths

/**
 * The phone-side WatchBrew relay's Android entry point. **Thin on purpose and untested on the JVM
 * (device-only):** it resolves the relay through the container's lazy seam
 * ([dev.fonebrew.di.AppContainer.wearRelay]) and hands each Data Layer message to
 * [WearRelay.onMessage]. Every decision (what a path means, how a turn becomes chunks, what a toggle
 * does, what to say to a newer watch) is in [WearRelayPresenter], tested without this class.
 *
 * Declared in this library's manifest, so the manifest merger carries it into `:app`; it is `exported`
 * because the Data Layer (Play services) binds it from outside the process, and it receives only
 * messages the Data Layer routes to this app id from the user's paired watch. The intent filter is
 * scoped to [WatchPaths.PREFIX] and nothing wider.
 *
 * Nothing is constructed at app start: the relay is a `by lazy` property, first touched when the first
 * watch message arrives. If the container never came up (init failure), the message is dropped.
 */
class WearRelayService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        if (!WatchPaths.isWatchBrewPath(path)) return

        val relay = resolveRelay() ?: return
        relay.onMessage(
            sourceNodeId = event.sourceNodeId,
            path = path,
            payload = event.data ?: ByteArray(0),
            transport = GmsWearTransport(applicationContext, relay.scope),
        )
    }

    private fun resolveRelay(): WearRelay? =
        try {
            (applicationContext as? FonebrewApp)?.container?.wearRelay
        } catch (t: Throwable) {
            // lateinit container never initialised, or the lazy seam failed to build. Class name only:
            // an exception message could carry anything.
            Log.w("Fonebrew", "WatchBrew relay unavailable: ${t.javaClass.simpleName}")
            null
        }
}
