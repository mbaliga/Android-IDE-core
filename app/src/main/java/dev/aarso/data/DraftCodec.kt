package dev.aarso.data

/**
 * Pure encode/decode/update logic behind [SessionStore]'s `drafts` map (daily-driver.md W3).
 * Factored out of [SessionStore] so the round-trip + keying behaviour is JVM-testable without a
 * real `Context`/`SharedPreferences` — this codebase's JVM gate has no Robolectric/instrumented
 * test support (see `TaskStoreTest`'s fake-DAO precedent for the same reasoning applied to
 * Room), so a class that can only exist with a real `Context` factors its actual logic out into
 * something that doesn't need one. Same "rootId\u0001value" persisted-`StringSet` shape as
 * [SessionStore]'s `conversationProjects`/`conversationOpens`.
 */
object DraftCodec {

    /** [drafts] -> the `StringSet` shape [SessionStore] hands to `SharedPreferences.Editor`. */
    fun encode(drafts: Map<String, String>): Set<String> =
        drafts.entries.map { "${it.key}\u0001${it.value}" }.toSet()

    /** The persisted `StringSet` -> a drafts map. An entry missing the separator (corrupt/
     *  foreign data) is skipped rather than fatal — this reads persisted prefs, and a cold
     *  start must never crash on them. */
    fun decode(raw: Set<String>): Map<String, String> =
        raw.mapNotNull { e -> e.split('\u0001', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

    /** Applies [SessionStore.setDraft]'s exact semantics to [drafts]: a blank [text] (empty or
     *  whitespace-only) clears [key] entirely (no blank entries persisted), anything else sets/
     *  overwrites it. */
    fun update(drafts: Map<String, String>, key: String, text: String): Map<String, String> =
        if (text.isBlank()) drafts - key else drafts + (key to text)
}
