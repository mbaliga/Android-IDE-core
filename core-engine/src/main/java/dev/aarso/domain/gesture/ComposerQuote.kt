package dev.aarso.domain.gesture

/**
 * Pure text transforms for the "quote/reply into composer" gestures (STUDIO_UX_SPEC.md §4.6's
 * "Quote in composer," previously flagged not-built in `ChatScreen.kt`'s `TurnActionsSheet` KDoc;
 * `docs/THREAD_TOPOLOGY_PLAN.md` WP4 builds it, plus a distinct "reply" framing for the pull-LEFT
 * gesture — a recorded divergence from the spec, documented in `docs/design/gestures.md`). Kept
 * out of `ChatViewModel`/`ChatScreen.kt` because the composer's `input` field is plain
 * `remember { mutableStateOf("") }` UI state, not view-model state — these are the pure functions
 * both [dev.aarso.ui.MessageGestures]'s drag callbacks and `TurnActionsSheet`'s tappable-parity
 * rows call to produce the new composer text; nothing here touches the message tree.
 */
object ComposerQuote {

    private const val MAX_EXCERPT_CHARS = 240

    /** Pull-RIGHT-and-release / TurnActionsSheet "Quote in composer": prefixes a plain quote
     *  block ahead of whatever the user was already typing. */
    fun quote(existingInput: String, messageContent: String): String =
        prefixQuote(existingInput, messageContent, label = null)

    /** Pull-LEFT-and-release / TurnActionsSheet "Reply": the same quote block, framed with a
     *  "Replying to" header so what gets sent reads as a response rather than a bare citation. */
    fun reply(existingInput: String, messageContent: String): String =
        prefixQuote(existingInput, messageContent, label = "Replying to")

    private fun prefixQuote(existingInput: String, messageContent: String, label: String?): String {
        val trimmed = messageContent.trim()
        val excerpt = if (trimmed.length > MAX_EXCERPT_CHARS) {
            trimmed.take(MAX_EXCERPT_CHARS).trimEnd() + "…"
        } else {
            trimmed
        }
        val quoted = excerpt.lineSequence().joinToString("\n") { line -> "> $line" }
        val header = if (label != null) "$label:\n" else ""
        val block = "$header$quoted\n\n"
        return if (existingInput.isBlank()) block else "$block$existingInput"
    }
}
