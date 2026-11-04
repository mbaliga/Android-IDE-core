package dev.fonebrew.watch.core

/**
 * What the wrist is willing to send as a prompt. Checked *before* an `AskRequest` is built, because
 * the bridge's constructor (rightly) throws on a blank prompt or an unpaired surrogate — a throw on
 * the wrist would be a crash, so the user-facing rejection happens here, in words.
 */
object PromptPolicy {
    /** A wrist is for short asks; long prose belongs on the phone. */
    const val MAX_CHARS: Int = 2_000

    sealed interface Result {
        data class Ok(val prompt: String) : Result
        data class Rejected(val reason: String) : Result
    }

    fun check(raw: CharSequence?): Result {
        val prompt = raw?.toString()?.trim().orEmpty()
        return when {
            prompt.isEmpty() -> Result.Rejected("Nothing to send")
            prompt.length > MAX_CHARS -> Result.Rejected("Too long for the wrist (${prompt.length} of $MAX_CHARS characters)")
            !isWellFormedUtf16(prompt) -> Result.Rejected("Contains a character that can't be sent")
            else -> Result.Ok(prompt)
        }
    }

    /** Same rule as the bridge's wire-text check: an unpaired surrogate has no UTF-8 encoding. */
    internal fun isWellFormedUtf16(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
                    i += 2
                }
                c.isLowSurrogate() -> return false
                else -> i++
            }
        }
        return true
    }
}
