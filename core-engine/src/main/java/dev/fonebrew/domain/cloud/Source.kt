package dev.fonebrew.domain.cloud

/**
 * One web result a provider's server-side search tool surfaced during a turn (W2,
 * `daily-driver.md`). Deliberately minimal — just enough for the sources footer
 * (`ChatScreen.kt` `MessageBubble`) to render a tappable link; provider-specific
 * extras (snippet, favicon, etc.) aren't modeled because neither Anthropic's
 * `web_search_tool_result` nor Gemini's `groundingMetadata` guarantee them.
 *
 * Pure Kotlin, same shape/precedent as [dev.fonebrew.domain.cost.UsageReport]: a plain
 * data class the per-provider [dev.fonebrew.inference.cloud.CloudEngine] subclasses parse
 * into from their own SSE event shape.
 */
data class Source(val title: String, val url: String)
