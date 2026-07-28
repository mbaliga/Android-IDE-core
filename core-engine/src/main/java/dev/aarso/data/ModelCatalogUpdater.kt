package dev.aarso.data

import android.content.Context
import dev.aarso.domain.catalog.RemoteCatalogCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Refreshes the model catalog from Nooz's `ai-catalogue/models.json` — **only ever on an
 * explicit, consented action** (manual "Update now" in the Models screen; same contract as
 * [FreeTierUpdater]). On-device is the default; this is a visible, opt-in network reach to a URL
 * shown in the UI (binding rules 1 & 2). On success it writes the override
 * `filesDir/model_catalog.json` that [ModelCatalogStore] prefers. Runtime is owner-verified (no
 * network in CI).
 */
class ModelCatalogUpdater(
    context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val appContext = context.applicationContext

    /** Fetch + validate + persist. Returns the new catalog's lastUpdated string. */
    suspend fun update(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            resp.use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body?.string()?.takeIf { it.isNotBlank() } ?: error("empty response")
                val catalog = RemoteCatalogCodec.decode(body) // parse = validation
                require(catalog.entries.isNotEmpty()) { "no models in the fetched list" }
                File(appContext.filesDir, "model_catalog.json").writeText(body)
                catalog.lastUpdated.ifBlank { "updated" }
            }
        }
    }
}
