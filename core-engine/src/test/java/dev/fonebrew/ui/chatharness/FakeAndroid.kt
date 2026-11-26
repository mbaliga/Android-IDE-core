package dev.fonebrew.ui.chatharness

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files

/**
 * The narrow slice of `android.content.Context` the Context-backed stores in `dev.fonebrew.data`
 * touch at construction: `applicationContext`, `getSharedPreferences`, `filesDir`, `cacheDir`.
 * Everything else keeps the unit-test android.jar default (throws "not mocked") so a store that
 * starts reaching for more fails loudly in the harness instead of silently returning null.
 *
 * Preferences are in-memory and per-name; the dirs are a fresh temp directory per instance so
 * tests never see each other's files.
 */
internal class FakeContext : ContextWrapper(null) {
    private val prefs = mutableMapOf<String, InMemoryPrefs>()
    private val root: File = Files.createTempDirectory("chatharness").toFile().also { it.deleteOnExit() }
    private val files = File(root, "files").apply { mkdirs() }
    private val cache = File(root, "cache").apply { mkdirs() }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getPackageName(): String = "dev.fonebrew.test"
    override fun getContentResolver(): ContentResolver? = null

    @Synchronized
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { InMemoryPrefs() }

    /** Raw value of a stored preference, for tests that assert on what was persisted. */
    @Synchronized
    fun rawPref(file: String, key: String): Any? = prefs[file]?.all?.get(key)
}

/** In-memory [SharedPreferences]; `apply()` and `commit()` are both immediate. */
internal class InMemoryPrefs : SharedPreferences {
    private val map = LinkedHashMap<String, Any?>()

    @Synchronized override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
    @Synchronized override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            synchronized(this@InMemoryPrefs) {
                if (clear) map.clear()
                removed.forEach { map.remove(it) }
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
            return true
        }
        override fun apply() { commit() }
    }
}
