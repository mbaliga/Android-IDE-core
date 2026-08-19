package dev.fonebrew.domain.watch

/** What a [dev.fonebrew.data.entity.WatchedItemEntity] represents (CORE_PHASES.md P2 "Watch").
 *  Glyph mapping (RENEWAL ↻ / EXPIRY ⌛ / STATUS ◉) lives in the row UI — this enum only
 *  carries the stable, persisted kind. */
enum class WatchKind { RENEWAL, EXPIRY, STATUS }
