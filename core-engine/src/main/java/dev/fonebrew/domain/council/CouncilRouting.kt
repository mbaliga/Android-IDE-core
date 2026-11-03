package dev.fonebrew.domain.council

/**
 * Resolves the `@`-addressee sigil: when a council turn opens with `@Name`, the reply comes
 * from that one participant instead of the whole roster fanning out (handoff §4a follow-up —
 * previously `@` only inserted text into the composer). Pure + JVM-tested so the routing rule
 * itself doesn't need a ViewModel/Android harness to verify.
 */
object CouncilRouting {

    private val TRAILING_PUNCTUATION = charArrayOf(',', '.', ':', ';', '!', '?')

    /** The addressed participant's name, if [text] opens with `@Name` matching one of
     *  [participantNames] (case-insensitive) — null if there's no leading mention, or the
     *  mentioned name isn't a current participant (falls back to the full fan-out, same as
     *  today, rather than silently dropping the turn). Only a *leading* mention routes: `@`
     *  appearing mid-message is prose, not an address (mirrors slash commands being a
     *  whole-input token, not something typed mid-sentence). A trailing `,`/`.`/`:`/`;`/`!`/`?`
     *  right after the name (`"@Judge, thoughts?"`) is stripped before matching — punctuation
     *  a person would naturally type after addressing someone, not part of the name. */
    fun addressee(text: String, participantNames: List<String>): String? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("@")) return null
        val token = trimmed.substring(1).takeWhile { !it.isWhitespace() }.trimEnd(*TRAILING_PUNCTUATION)
        if (token.isEmpty()) return null
        return participantNames.firstOrNull { it.equals(token, ignoreCase = true) }
    }
}
