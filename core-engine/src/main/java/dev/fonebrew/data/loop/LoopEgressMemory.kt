package dev.fonebrew.data.loop

import android.content.Context

/**
 * Remembers, per loop, which hosts the user last confirmed on the run-start sheet, so a host that
 * was not there last time is flagged NEW. App-local (SharedPreferences), never synced anywhere.
 * It is a convenience for the sheet only: the run-time gate lives in `GraphRunner`, not here, and a
 * lost or cleared memory just means every host shows as "first time". Owner-verified only.
 */
class LoopEgressMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("loop.egress.confirmed", Context.MODE_PRIVATE)

    fun lastConfirmed(loopKey: String?): Set<String>? =
        prefs.getString(key(loopKey), null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet()

    fun remember(loopKey: String?, hosts: Set<String>) {
        prefs.edit().putString(key(loopKey), hosts.joinToString("\n")).apply()
    }

    private fun key(loopKey: String?) = "loop." + (loopKey ?: "__unsaved__")
}
