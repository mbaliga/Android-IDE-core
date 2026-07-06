package dev.aarso.data

import android.content.Context
import dev.aarso.domain.cost.FreeTierCatalog
import dev.aarso.domain.cost.FreeTierCodec
import java.io.File

/**
 * Loads the free-tier guide. Prefers a synced override in filesDir (written by the update
 * pipeline / a future in-app sync) and falls back to the bundled `assets/free_tiers.json`. No
 * network here — the refresh is a CI job that updates the data file, never the app phoning home
 * (binding rule 1).
 */
class FreeTierStore(context: Context) {

    private val appContext = context.applicationContext

    fun catalog(): FreeTierCatalog {
        val json = runCatching {
            val override = File(appContext.filesDir, OVERRIDE)
            if (override.exists()) override.readText()
            else appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return FreeTierCatalog("", emptyList())
        return runCatching { FreeTierCodec.decode(json) }.getOrElse { FreeTierCatalog("", emptyList()) }
    }

    private companion object {
        const val ASSET = "free_tiers.json"
        const val OVERRIDE = "free_tiers.json"
    }
}
