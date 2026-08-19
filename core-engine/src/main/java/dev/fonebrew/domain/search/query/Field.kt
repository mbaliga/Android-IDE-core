package dev.fonebrew.domain.search.query

/**
 * The facet field vocabulary (Doc `FONEBREW_SEARCH_SPEC.md` §6.1). The parser recognizes every
 * field here — grammar completeness, so chips round-trip and nothing degrades to "unknown field"
 * that shouldn't — but [isBacked] decides whether a given (field, value) pair actually compiles
 * to a real filter or degrades to [dev.fonebrew.domain.search.query.Diagnostic.UnindexedFacet]
 * ("this filter isn't indexed yet", an honest zero — never a silent no-op).
 *
 * Per the build plan's facet-data reality check: `tool:`/`file:`/`build:`/`loop:`/`room:`/
 * `tag:`/`lang:` assume domain concepts (tool-call capture, file/build linkage, tags, language
 * detection) that don't exist anywhere in this codebase, so they're always not-yet-indexed.
 * `is:`/`has:` are split per-value: `starred`/`archived`/`orphan` and `image`/`code` are real
 * (backed by `SessionStore`/`ConversationsStore`/`SearchProjector`); `unread`/`failed` and
 * `attachment`/`artifact`/`error` are not.
 */
enum class Field(val key: String) {
    IN("in"),
    IS("is"),
    HAS("has"),
    PROJECT("project"),
    MODEL("model"),
    TAG("tag"),
    ROOM("room"),
    FILE("file"),
    TOOL("tool"),
    BUILD("build"),
    BRANCH("branch"),
    TURNS("turns"),
    COST("cost"),
    BEFORE("before"),
    AFTER("after"),
    DURING("during"),
    LANG("lang"),
    LOOP("loop"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): Field? = byKey[key.lowercase()]

        /** All recognized field keys, for "did you mean" suggestions on an unknown field. */
        val allKeys: List<String> = entries.map { it.key }
    }
}

private val BACKED_IS_VALUES = setOf("starred", "archived", "orphan")
private val BACKED_HAS_VALUES = setOf("image", "code")

/** Whether this exact (field, value) pair compiles to a real filter today. See [Field]'s KDoc. */
fun isBacked(field: Field, value: String): Boolean = when (field) {
    Field.IS -> value.lowercase() in BACKED_IS_VALUES
    Field.HAS -> value.lowercase() in BACKED_HAS_VALUES
    Field.IN, Field.PROJECT, Field.MODEL,
    Field.BEFORE, Field.AFTER, Field.DURING,
    Field.TURNS, Field.BRANCH, Field.COST,
    -> true
    Field.TAG, Field.ROOM, Field.FILE, Field.TOOL, Field.BUILD, Field.LANG, Field.LOOP -> false
}
