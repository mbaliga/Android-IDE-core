package dev.aarso.domain.cost

import dev.aarso.domain.council.Cost
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * **The Cost epic** — multi-dimensional, risk-adjusted decision cost.
 *
 * Motivating case (the owner's): a chat model advised salvaging a BlackBerry keyboard
 * for the RedMagic. The naive estimate ("≤ ₹500") was wrong on three counts the model
 * never surfaced:
 *  1. **Point estimate vs reality** — the seller wanted ₹1000; the true cost is a
 *     *range*, not a number.
 *  2. **Transaction cost that recurs per attempt** — ₹250–300 to travel there and back,
 *     so a "successful" second trip nets the *same* price, not a better one.
 *  3. **The cost of being wrong** — ~₹3000 was spent on an apparatus that turned out
 *     unusable. Error has an *expected value*: `chance × impact`.
 *
 * And a fourth the model should count against *itself*: **the cost of the advice** —
 * the tokens/money/time spent consulting the model, plus the **expected cost of the
 * advice being wrong** (an explicit risk). Making *that* legible is on-thesis: you see
 * the true price of trusting the model, including the model's own price.
 *
 * Dimensions stay separate (legibility — never silently collapse them); [Valuation]
 * gives an optional single-number rollup, always shown *alongside* the breakdown.
 * Manpower is modelled as time (→ opportunity cost via the hourly rate). Pure domain,
 * JVM-tested. Parameters (chances, impacts, rates) are **user/observed inputs** — this
 * engine supplies the framework, never invents the numbers.
 */
data class CostVector(
    /** Direct money in the smallest currency unit (paise / cents). Infra + purchases. */
    val moneyMinor: Long = 0,
    /** Time spent or forgone, in minutes. Manpower + opportunity cost. */
    val minutes: Long = 0,
    /** LLM I/O — the cost of asking the model itself, in tokens. */
    val tokens: Long = 0,
) {
    operator fun plus(o: CostVector) =
        CostVector(moneyMinor + o.moneyMinor, minutes + o.minutes, tokens + o.tokens)

    /** Scale every dimension (for expected attempts / probabilities). */
    fun scaled(f: Double) =
        CostVector((moneyMinor * f).roundToLong(), (minutes * f).roundToLong(), (tokens * f).roundToLong())

    companion object {
        val ZERO = CostVector()
        fun money(minor: Long) = CostVector(moneyMinor = minor)
        fun time(minutes: Long) = CostVector(minutes = minutes)
    }
}

/** Converts the non-money axes into money so vectors can be ranked by one figure. */
data class Valuation(
    /** Value of an hour (opportunity cost of your time), in the minor unit. */
    val moneyPerHour: Long = 0,
    /** Price of 1k tokens, in the minor unit (the model's own money cost). */
    val moneyPer1kTokens: Long = 0,
) {
    /** A single comparable figure — surfaced WITH the breakdown, never instead of it. */
    fun toMinor(v: CostVector): Long =
        v.moneyMinor + v.minutes * moneyPerHour / 60 + v.tokens * moneyPer1kTokens / 1000
}

/**
 * A possible bad outcome: with probability [chance] (0..1) it adds [extra] cost.
 * The "unusable apparatus" line in the story is `RiskedOutcome("unusable", 0.3, ₹3000)`.
 */
data class RiskedOutcome(val label: String, val chance: Double, val extra: CostVector)

/**
 * A decision to model. [successChance] drives how many attempts you expect to make;
 * [perAttempt] is paid every try win-or-lose (the travel); [onSuccess] is the thing's
 * price, realised only if you eventually succeed; [risks] are the error EV; [adviceCost]
 * is what consulting the model cost (and you can add advice-being-wrong as a [risks] row).
 */
data class Decision(
    val label: String,
    val onSuccess: CostVector = CostVector.ZERO,
    val perAttempt: CostVector = CostVector.ZERO,
    val successChance: Double = 1.0,
    val risks: List<RiskedOutcome> = emptyList(),
    val adviceCost: CostVector = CostVector.ZERO,
)

/**
 * The forecast for a [Decision]. Both an **expected** vector and a **worst case** are
 * given, because the lesson of the story is that a point estimate lies — you need the
 * band. [riskContribution] is surfaced on its own so the cost-of-being-wrong is legible.
 */
data class CostForecast(
    val expected: CostVector,
    val worst: CostVector,
    val expectedAttempts: Double,
    val successProbability: Double,
    val riskContribution: CostVector,
)

object DecisionCost {

    /**
     * Forecast [d], allowing up to [maxAttempts] tries. Attempts are modelled
     * geometrically: attempt k happens iff the first k−1 failed, so the expected number
     * of attempts is Σ (1−p)^(k−1) over k=1..M, and you eventually succeed with
     * probability 1−(1−p)^M.
     */
    fun forecast(d: Decision, maxAttempts: Int = 3): CostForecast {
        val p = d.successChance.coerceIn(0.0, 1.0)
        val m = maxAttempts.coerceAtLeast(1)
        val q = 1.0 - p

        var expectedAttempts = 0.0
        var reachChance = 1.0 // chance of reaching attempt k = q^(k-1)
        for (k in 1..m) {
            expectedAttempts += reachChance
            reachChance *= q
        }
        val successProbability = 1.0 - q.pow(m.toDouble())

        val riskContribution = d.risks.fold(CostVector.ZERO) { acc, r ->
            acc + r.extra.scaled(r.chance.coerceIn(0.0, 1.0))
        }

        val expected = d.perAttempt.scaled(expectedAttempts) +
            d.onSuccess.scaled(successProbability) +
            riskContribution +
            d.adviceCost

        val worst = d.perAttempt.scaled(m.toDouble()) +
            d.onSuccess +
            d.risks.fold(CostVector.ZERO) { acc, r -> acc + r.extra } +
            d.adviceCost

        return CostForecast(expected, worst, expectedAttempts, successProbability, riskContribution)
    }
}

/** Bridge: the loop's internal [Cost] (tokens/seconds/money) as advice cost on a decision. */
fun Cost.toCostVector(): CostVector =
    CostVector(moneyMinor = moneyCents, minutes = seconds / 60, tokens = tokens)

/** Price token I/O into a [CostVector] — the model's own cost, money + the tokens. */
object LlmAdvice {
    fun cost(
        tokensIn: Long,
        tokensOut: Long,
        moneyPer1kIn: Long,
        moneyPer1kOut: Long,
        readingMinutes: Long = 0,
    ): CostVector = CostVector(
        moneyMinor = tokensIn * moneyPer1kIn / 1000 + tokensOut * moneyPer1kOut / 1000,
        minutes = readingMinutes,
        tokens = tokensIn + tokensOut,
    )
}
