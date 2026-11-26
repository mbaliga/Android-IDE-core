package dev.fonebrew.domain.loop.authoring

import dev.fonebrew.domain.loop.ConditionSyntax
import dev.fonebrew.domain.loop.LoopCondition

/**
 * The structured chip/field state behind the gateway condition editor (replaces the free-text
 * field). Every kind is one of the CLOSED set [ConditionSyntax] evaluates; there is no free-form
 * entry point, and [build] returns `null` until the inputs are valid, so the editor can only ever
 * commit something the runner will actually evaluate. Glyph + label per kind — never colour.
 */
enum class ConditionKind(
    val glyph: String,
    val label: String,
    val takesText: Boolean = false,
    val takesNumber: Boolean = false,
    /** True when the case-sensitive/-insensitive choice applies (always shown explicitly). */
    val hasCase: Boolean = false,
) {
    APPROVED("✓", "approved"),
    NOT_APPROVED("✗", "not approved"),
    CONTAINS("⊃", "contains", takesText = true, hasCase = true),
    NOT_CONTAINS("⊅", "does not contain", takesText = true, hasCase = true),
    EQUALS("＝", "equals", takesText = true, hasCase = true),
    STARTS_WITH("⇤", "starts with", takesText = true, hasCase = true),
    LENGTH_GREATER("＞", "longer than", takesNumber = true),
    LENGTH_LESS("＜", "shorter than", takesNumber = true),
    IS_EMPTY("○", "is empty"),
    IS_NOT_EMPTY("●", "is not empty"),
}

data class ConditionDraft(
    val kind: ConditionKind? = null,
    val text: String = "",
    val ignoreCase: Boolean = false,
) {
    fun build(): LoopCondition? {
        val k = kind ?: return null
        val c: LoopCondition = when (k) {
            ConditionKind.APPROVED -> LoopCondition.Approved
            ConditionKind.NOT_APPROVED -> LoopCondition.NotApproved
            ConditionKind.CONTAINS -> LoopCondition.Contains(text, ignoreCase, negated = false)
            ConditionKind.NOT_CONTAINS -> LoopCondition.Contains(text, ignoreCase, negated = true)
            ConditionKind.EQUALS -> LoopCondition.Equals(text, ignoreCase)
            ConditionKind.STARTS_WITH -> LoopCondition.StartsWith(text, ignoreCase)
            ConditionKind.LENGTH_GREATER -> LoopCondition.LengthGreater(text.toIntOrNull() ?: return null)
            ConditionKind.LENGTH_LESS -> LoopCondition.LengthLess(text.toIntOrNull() ?: return null)
            ConditionKind.IS_EMPTY -> LoopCondition.IsEmpty
            ConditionKind.IS_NOT_EMPTY -> LoopCondition.IsNotEmpty
        }
        // Round-trip through the one parser so the editor can never produce a string the runner
        // cannot read (empty/over-long text, out-of-range N).
        return ConditionSyntax.parse(ConditionSyntax.format(c))
    }

    /** The saved condition string, or `null` when the draft is incomplete/invalid. */
    fun toText(): String? = build()?.let(ConditionSyntax::format)

    /** Why the draft cannot be committed yet (for the field's helper line); null when it can. */
    fun problem(): String? = when {
        kind == null -> null
        build() != null -> null
        kind.takesText -> "Enter 1-${ConditionSyntax.MAX_TEXT} characters of literal text (no patterns)."
        kind.takesNumber -> "Enter a whole number from 0 to ${ConditionSyntax.MAX_LENGTH_N}."
        else -> null
    }

    companion object {
        /** Re-open a saved condition in the editor; `null` when it is not one of the closed set. */
        fun from(condition: String?): ConditionDraft? = when (val c = ConditionSyntax.parse(condition)) {
            null -> null
            LoopCondition.Approved -> ConditionDraft(ConditionKind.APPROVED)
            LoopCondition.NotApproved -> ConditionDraft(ConditionKind.NOT_APPROVED)
            is LoopCondition.Contains -> ConditionDraft(if (c.negated) ConditionKind.NOT_CONTAINS else ConditionKind.CONTAINS, c.text, c.ignoreCase)
            is LoopCondition.Equals -> ConditionDraft(ConditionKind.EQUALS, c.text, c.ignoreCase)
            is LoopCondition.StartsWith -> ConditionDraft(ConditionKind.STARTS_WITH, c.text, c.ignoreCase)
            is LoopCondition.LengthGreater -> ConditionDraft(ConditionKind.LENGTH_GREATER, c.n.toString())
            is LoopCondition.LengthLess -> ConditionDraft(ConditionKind.LENGTH_LESS, c.n.toString())
            LoopCondition.IsEmpty -> ConditionDraft(ConditionKind.IS_EMPTY)
            LoopCondition.IsNotEmpty -> ConditionDraft(ConditionKind.IS_NOT_EMPTY)
        }
    }
}
