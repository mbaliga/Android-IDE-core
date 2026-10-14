package dev.fonebrew.watch.link

import android.content.SharedPreferences
import dev.fonebrew.watch.core.KeyValueStore

/** [KeyValueStore] over the app's private SharedPreferences. Device-local; nothing here is ever sent anywhere. */
internal class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
