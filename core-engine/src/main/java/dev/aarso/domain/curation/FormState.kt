package dev.aarso.domain.curation

/**
 * Stored answers for an interactive questionnaire message (the AskUserQuestion-style forms),
 * enabling "questionnaire rewind" (STUDIO_UX_SPEC.md §4.5's killer case): rewinding to a
 * questionnaire message reopens the original form with these answers pre-filled and editable,
 * instead of a blank form.
 *
 * [schemaJson]/[answersJson] are opaque JSON payloads at this layer (mirrors
 * [dev.aarso.domain.MessageNode.metadata]'s "free-form, persisted as JSON, parsed by the UI
 * layer that knows the concrete question schema" split) — the domain layer doesn't need to
 * understand a form's shape to store and round-trip it.
 */
data class FormState(
    val msgId: String,
    val schemaJson: String,
    val answersJson: String,
)
