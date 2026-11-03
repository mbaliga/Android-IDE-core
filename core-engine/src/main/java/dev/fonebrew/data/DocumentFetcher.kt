package dev.fonebrew.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One-shot GET for the "distill a Loop from a URL" intake (docs/design/loop-distillation.md) —
 * fetches a page the user typed in themselves, right now, nothing kept. Same OkHttp shape as
 * the other one-shot fetches in this layer ([FreeTierUpdater], [GitTransport]): bounded
 * timeouts, no caching to disk, no logging of the fetched content (binding rule 1). A
 * browser-ish `User-Agent` is set because some sites reject requests with no/generic UA — this
 * is a user-initiated fetch of a page the user chose, not scraping at scale.
 */
class DocumentFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val len = r.header("Content-Length")?.toLongOrNull()
                if (len != null && len > MAX_CONTENT_LENGTH) {
                    error("response too large ($len bytes)")
                }
                r.body?.string()?.takeIf { it.isNotEmpty() } ?: error("empty response")
            }
        }
    }

    private companion object {
        const val MAX_CONTENT_LENGTH = 10L * 1024 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
