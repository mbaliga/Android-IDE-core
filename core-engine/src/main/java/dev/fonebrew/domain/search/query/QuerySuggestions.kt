package dev.fonebrew.domain.search.query

/**
 * As-you-type completions for the search box (owner ask: *"Does it have autocomplete?
 * Suggestions?"* — it had neither; the grammar's 18 fields were discoverable only from the
 * always-on cheat-sheet the same ask told us to hide).
 *
 * Pure, clock-free, I/O-free, same convention as the rest of `domain/search/query/` — the
 * ViewModel supplies the live facet values, this decides what to offer, and it's JVM-testable
 * without a device.
 *
 * ### What it will and won't offer
 * - **Nothing at all** unless a token is actually being typed. An empty box, or a box whose text
 *   ends in a space, gets zero suggestions. Search that greets you with a list of every field is
 *   the wall-of-help problem in a different costume.
 * - **Field names** while a bare word is being typed (`pro` → `project:`), from [Field.allKeys].
 * - **Real values** once a recognized field and its `:` are typed (`is:` → `starred`, `orphan`;
 *   `model:` → the model ids actually present in the index). This is the half that couldn't
 *   exist before: `is:` and `model:` used to complete to silence.
 * - **Only values that can actually return something.** `is:` offers `starred`/`orphan`, not
 *   `unread`/`failed` (no data) and not `archived` (a real predicate over a column nothing ever
 *   writes — see [unbackedReason]); completing someone into a filter guaranteed to return
 *   nothing would be a worse lie than offering nothing. The parser still *accepts* those words
 *   typed by hand, and still explains itself.
 *
 * ### The cursor caveat
 * The token being completed is taken as the run after the last whitespace, i.e. **the caret is
 * assumed to be at the end of the text**. That is true for typing, which is the case this
 * serves; editing mid-string offers completions for the last token instead of the one under the
 * caret. Hyle's field exposes no cursor position (`value: String`, not `TextFieldValue`), so
 * honouring a mid-string caret would mean changing a shared design-system component — deferred
 * rather than faked. A quoted value containing spaces is likewise treated as two tokens.
 */
object QuerySuggestions {

    /** How many rows the popup shows at most — a phone-sized list, not a scrollable catalogue. */
    const val DEFAULT_LIMIT = 6

    enum class Kind {
        /** Completes to `field:` and leaves the caret ready for a value (no trailing space). */
        FIELD,

        /** Completes to a whole `field:value` and adds the trailing space to move on. */
        VALUE,
    }

    /**
     * @property insertText what replaces the in-progress token.
     * @property label the row's leading text.
     * @property detail the row's trailing explanation — for a value that oversells itself this is
     *   its [facetCaveat], so the caveat is visible at the moment of choosing, not afterwards.
     */
    data class Suggestion(
        val insertText: String,
        val label: String,
        val detail: String,
        val kind: Kind,
    )

    /** The facet values actually present in the index right now — distinct `project_id`s and the
     *  flattened distinct `model_ids`. Supplied by the caller (see
     *  `dev.fonebrew.ui.search.SearchViewModel`) because reading them is I/O, which this object
     *  deliberately can't do. */
    data class IndexedValues(
        val projects: List<String> = emptyList(),
        val models: List<String> = emptyList(),
    )

    /** Date expressions [RelativeDate] genuinely resolves — offered verbatim so a completion can
     *  never produce a `Diagnostic.InvalidDate`. */
    private val DATE_VALUES = listOf("today", "yesterday", "last-week", "-7d", "-30d")

    fun suggest(text: String, values: IndexedValues = IndexedValues(), limit: Int = DEFAULT_LIMIT): List<Suggestion> {
        val token = activeToken(text)
        if (token.isEmpty()) return emptyList()
        val negated = token.startsWith("-")
        val bare = if (negated) token.substring(1) else token
        if (bare.isEmpty()) return emptyList()
        val prefix = if (negated) "-" else ""

        val colon = bare.indexOf(':')
        return if (colon < 0) fieldSuggestions(bare, prefix, limit) else {
            val field = Field.fromKey(bare.substring(0, colon)) ?: return emptyList()
            valueSuggestions(field, bare.substring(colon + 1), prefix, values, limit)
        }
    }

    /** Replaces the in-progress token in [text] with [suggestion]'s insertion. */
    fun apply(text: String, suggestion: Suggestion): String {
        val head = text.substring(0, text.indexOfLast { it.isWhitespace() } + 1)
        return head + suggestion.insertText + if (suggestion.kind == Kind.VALUE) " " else ""
    }

    /** The run after the last whitespace — see the class KDoc's cursor caveat. */
    private fun activeToken(text: String): String = text.substring(text.indexOfLast { it.isWhitespace() } + 1)

    private fun fieldSuggestions(typed: String, prefix: String, limit: Int): List<Suggestion> {
        val lower = typed.lowercase()
        // Field.entries rather than Field.allKeys: same vocabulary (allKeys is built from it),
        // but each row keeps the Field itself, so describe() needs no lookup-and-assert.
        return Field.entries.asSequence()
            .filter { it.key.startsWith(lower) }
            .map { field ->
                Suggestion(
                    insertText = "$prefix${field.key}:",
                    label = "${field.key}:",
                    detail = describe(field),
                    kind = Kind.FIELD,
                )
            }
            .take(limit)
            .toList()
    }

    private fun valueSuggestions(
        field: Field,
        typed: String,
        prefix: String,
        values: IndexedValues,
        limit: Int,
    ): List<Suggestion> {
        // A comparison operator means the user is typing a number or a date bound by hand
        // (`turns:>5`, `after:>=2026-01-01`); there is no closed set to offer for that.
        if (typed.startsWith(">") || typed.startsWith("<")) return emptyList()
        val candidates = when (field) {
            Field.IS -> BACKED_IS_VALUES.sorted()
            Field.HAS -> BACKED_HAS_VALUES.sorted()
            Field.IN, Field.PROJECT -> values.projects
            Field.MODEL -> values.models
            Field.BEFORE, Field.AFTER, Field.DURING -> DATE_VALUES
            // Numeric fields have no enumerable value set, and the unbacked fields deliberately
            // offer nothing rather than completing into a guaranteed empty result.
            Field.TURNS, Field.BRANCH, Field.COST,
            Field.TAG, Field.ROOM, Field.FILE, Field.TOOL, Field.BUILD, Field.LANG, Field.LOOP,
            -> emptyList()
        }
        return candidates.asSequence()
            .filter { it.isNotBlank() && it.startsWith(typed, ignoreCase = true) }
            // Screened by unbackedReason, not by the BACKED_* sets: `is:archived` has a perfectly
            // good predicate behind it (so it is "backed") and no data whatsoever (so completing
            // someone into it would be a guaranteed dead end).
            .filter { unbackedReason(field, it) == null }
            .distinct()
            .map { value ->
                val rendered = if (value.contains(' ')) "\"$value\"" else value
                Suggestion(
                    insertText = "$prefix${field.key}:$rendered",
                    label = "${field.key}:$rendered",
                    detail = facetCaveat(field, value) ?: describe(field),
                    kind = Kind.VALUE,
                )
            }
            .take(limit)
            .toList()
    }

    /** One line per field, plain-language. Doubles as the completion row's right-hand text, so
     *  the field vocabulary is learned by using the box instead of by reading a cheat-sheet. */
    fun describe(field: Field): String = when (field) {
        Field.IN, Field.PROJECT -> "in a project"
        Field.IS -> "starred, unassigned"
        Field.HAS -> "contains an image or code"
        Field.MODEL -> "answered by a model"
        Field.TURNS -> "message count"
        Field.BRANCH -> "branch count"
        Field.COST -> "estimated cost"
        Field.BEFORE -> "last active before a date"
        Field.AFTER -> "last active after a date"
        Field.DURING -> "last active on a date"
        Field.TAG, Field.ROOM, Field.FILE, Field.TOOL, Field.BUILD, Field.LANG, Field.LOOP ->
            "recognized, not indexed yet"
    }
}
