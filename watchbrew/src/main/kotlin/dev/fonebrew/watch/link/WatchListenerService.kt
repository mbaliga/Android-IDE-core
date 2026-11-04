package dev.fonebrew.watch.link

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.fonebrew.watch.WatchApp
import dev.fonebrew.watchbridge.WatchPaths

/**
 * Phone -> watch delivery. The system starts this service when a Data Layer message or channel
 * addressed to [WatchPaths.PREFIX] arrives, even if the app is not running, then hands the payload to
 * the process-wide [dev.fonebrew.watch.runtime.WatchController].
 *
 * Callbacks here run on a worker thread, so reading the reply channel **blocks inside the callback on
 * purpose**: it keeps this service (and so the process) alive for as long as the reply is streaming.
 * Whether that holds up for a long reply on a real watch is owner-verified (watchbrew/OWNER_VERIFY.md);
 * nothing here has run on one.
 */
internal class WatchListenerService : WearableListenerService() {

    private val controller get() = (application as WatchApp).runtime.controller

    override fun onMessageReceived(event: MessageEvent) {
        if (!WatchPaths.isWatchBrewPath(event.path)) return
        controller.onPhoneMessage(event.sourceNodeId, event.path, event.data)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchPaths.ASK_REPLY) return
        val client = Wearable.getChannelClient(this)
        try {
            val input = Tasks.await(client.getInputStream(channel))
            input.use { controller.onReplyChannel(channel.nodeId, it) }
        } catch (e: Exception) {
            // Could not even open the stream: the in-flight ask (if any) lost its link.
            controller.onReplyChannelFailed(channel.nodeId)
        } finally {
            runCatching { client.close(channel) }
        }
    }
}
