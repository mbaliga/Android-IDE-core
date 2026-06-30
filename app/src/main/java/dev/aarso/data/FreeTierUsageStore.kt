package dev.aarso.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Calendar

/** A provider's usage in the current day and month (auto-reset when the period rolls over). */
data class ProviderUsage(
    val requestsToday: Int = 0,
    val tokensToday: Long = 0,
    val tokensThisMonth: Long = 0,
)

/**
 * Tracks per-provider free-tier usage (owner ask: "see free-tier usage across each of those").
 * Counts requests + tokens against the current day and month, resetting automatically when the
 * date rolls over. Local-first (private prefs), no network. Keyed by the app's CloudProvider id;
 * the Free-tiers screen compares it to the catalog's published limit when one is known.
 */
class FreeTierUsageStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.freetierusage", Context.MODE_PRIVATE)

    private val _usage = MutableStateFlow(load())
    val usage: StateFlow<Map<String, ProviderUsage>> = _usage.asStateFlow()

    /** Record one cloud turn's usage against [providerId]. */
    fun record(providerId: String, inputTokens: Long, outputTokens: Long) {
        rolloverIfNeeded()
        val tokens = inputTokens + outputTokens
        val cur = _usage.value[providerId] ?: ProviderUsage()
        val next = cur.copy(
            requestsToday = cur.requestsToday + 1,
            tokensToday = cur.tokensToday + tokens,
            tokensThisMonth = cur.tokensThisMonth + tokens,
        )
        val map = _usage.value + (providerId to next)
        persist(map)
        _usage.value = map
    }

    /** Reset day/month buckets when the calendar period changes since the last write. */
    private fun rolloverIfNeeded() {
        val (day, month) = todayKeys()
        val storedDay = prefs.getString(KEY_DAY, day)
        val storedMonth = prefs.getString(KEY_MONTH, month)
        if (storedDay == day && storedMonth == month) return
        val dayReset = storedDay != day
        val monthReset = storedMonth != month
        val map = _usage.value.mapValues { (_, u) ->
            u.copy(
                requestsToday = if (dayReset) 0 else u.requestsToday,
                tokensToday = if (dayReset) 0 else u.tokensToday,
                tokensThisMonth = if (monthReset) 0 else u.tokensThisMonth,
            )
        }
        persist(map)
        _usage.value = map
    }

    private fun todayKeys(): Pair<String, String> {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH) + 1; val d = c.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d) to "%04d-%02d".format(y, m)
    }

    private fun persist(map: Map<String, ProviderUsage>) {
        val (day, month) = todayKeys()
        val o = JSONObject()
        for ((id, u) in map) o.put(id, JSONObject()
            .put("rt", u.requestsToday).put("tt", u.tokensToday).put("tm", u.tokensThisMonth))
        prefs.edit().putString(KEY_DAY, day).putString(KEY_MONTH, month).putString(KEY_DATA, o.toString()).apply()
    }

    private fun load(): Map<String, ProviderUsage> {
        val raw = prefs.getString(KEY_DATA, null) ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, ProviderUsage>()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            out[k] = ProviderUsage(e.optInt("rt"), e.optLong("tt"), e.optLong("tm"))
        }
        return out
    }

    private companion object {
        const val KEY_DAY = "day"; const val KEY_MONTH = "month"; const val KEY_DATA = "data"
    }
}
