package dev.fonebrew.watch

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.fonebrew.watch.complication.PendingTasksComplicationService
import dev.fonebrew.watch.core.SnapshotCache
import dev.fonebrew.watch.link.DataLayerPhoneLink
import dev.fonebrew.watch.link.PrefsKeyValueStore
import dev.fonebrew.watch.runtime.WatchController
import dev.fonebrew.watch.runtime.WatchEffects
import dev.fonebrew.watch.tile.WatchTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * Manual DI, one process-wide [WatchRuntime] (the project's idiom: no Hilt). The Activity, the
 * listener service, the Tile and the complication all run in this one process, so they share one
 * [WatchController] and one state.
 */
internal class WatchApp : Application() {
    val runtime: WatchRuntime by lazy { WatchRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        // Touch it now so the cache is hydrated before any Tile/complication request on a cold start.
        runtime.controller
    }
}

internal class WatchRuntime(context: Context) {
    private val appContext = context.applicationContext

    val controller: WatchController = WatchController(
        link = DataLayerPhoneLink(appContext),
        cache = SnapshotCache(PrefsKeyValueStore(appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE))),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clock = System::currentTimeMillis,
        newRequestId = { UUID.randomUUID().toString() },
        effects = object : WatchEffects {
            override fun tasksChanged() {
                runCatching {
                    ComplicationDataSourceUpdateRequester
                        .create(appContext, ComponentName(appContext, PendingTasksComplicationService::class.java))
                        .requestUpdateAll()
                }
            }

            override fun lastReplyChanged() {
                runCatching { TileService.getUpdater(appContext).requestUpdate(WatchTileService::class.java) }
            }
        },
    ).also { it.hydrate() }

    private companion object {
        const val CACHE_PREFS = "watchbrew_last_known"
    }
}

/** Reads the runtime from any Context in this process (Activity, Service). */
internal val Context.watchRuntime: WatchRuntime get() = (applicationContext as WatchApp).runtime
