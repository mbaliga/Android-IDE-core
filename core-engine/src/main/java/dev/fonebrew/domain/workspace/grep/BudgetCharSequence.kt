package dev.fonebrew.domain.workspace.grep

/** Thrown from inside the regex engine when a line has used up its character-access budget. */
class RegexBudgetExceeded : RuntimeException("regex execution budget exceeded", null, false, false)

/**
 * A [CharSequence] that counts every `charAt` the regex engine makes and aborts by throwing
 * [RegexBudgetExceeded] once the count passes [budget]. This is how a catastrophic-backtracking
 * pattern (`(a+)+$` on `aaaa...!`) is stopped: the engine cannot be interrupted any other way,
 * but it can only make progress by reading characters. [onTick] runs every 1024 accesses so a
 * cancelled coroutine also stops a long match. The exception is stackless (cheap to throw).
 */
class BudgetCharSequence(
    private val base: CharSequence,
    private val budget: Long,
    private val onTick: (() -> Unit)? = null,
) : CharSequence {
    var used: Long = 0
        private set

    override val length: Int get() = base.length

    override fun get(index: Int): Char {
        used++
        if (used > budget) throw RegexBudgetExceeded()
        if (onTick != null && (used and 1023L) == 0L) onTick.invoke()
        return base[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = base.subSequence(startIndex, endIndex)

    override fun toString(): String = base.toString()
}
