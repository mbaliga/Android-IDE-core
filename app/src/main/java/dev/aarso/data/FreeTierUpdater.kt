package dev.aarso.data

import android.content.Context
import dev.aarso.domain.cost.FreeTierCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Refreshes the free-tier guide from an online source — **only ever on an explicit, consented
 * action** (manual "Update now", or auto-update the user opted into). On-device is the default;
 * this is the lone, user-visible network reach for this feature (binding rules 1 & 2), to a URL
 * shown in the UI. On success it writes the override `filesDir/free_tiers.json` that
 * [FreeTierStore] prefers. Runtime is owner-verified (no network in CI).
 */
class FreeTierUpdater(
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
                val catalog = FreeTierCodec.decode(body) // parse = validation
                require(catalog.providers.isNotEmpty()) { "no providers in the fetched list" }
                File(appContext.filesDir, "free_tiers.json").writeText(body)
                catalog.lastUpdated.ifBlank { "updated" }
            }
        }
    }
}
