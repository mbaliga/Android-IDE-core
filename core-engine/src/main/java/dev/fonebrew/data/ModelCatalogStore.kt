package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.domain.catalog.RemoteCatalogCodec
import dev.fonebrew.domain.catalog.RemoteModelCatalog
import java.io.File

/**
 * Loads the shared model catalog. Prefers a synced override in filesDir (written by
 * [ModelCatalogUpdater]) and falls back to the bundled `assets/model_catalog.json` snapshot — a
 * mirror of Nooz's `ai-catalogue/models.json` at bundle time. No network here — the refresh is a
 * consented fetch triggered from the Models screen, never the app phoning home (binding rule 1).
 */
class ModelCatalogStore(context: Context) {

    private val appContext = context.applicationContext

    fun catalog(): RemoteModelCatalog {
        val json = runCatching {
            val override = File(appContext.filesDir, OVERRIDE)
            if (override.exists()) override.readText()
            else appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return RemoteModelCatalog(schemaVersion = 1, lastUpdated = "", entries = emptyList())
        return runCatching { RemoteCatalogCodec.decode(json) }
            .getOrElse { RemoteModelCatalog(schemaVersion = 1, lastUpdated = "", entries = emptyList()) }
    }

    private companion object {
        const val ASSET = "model_catalog.json"
        const val OVERRIDE = "model_catalog.json"
    }
}
