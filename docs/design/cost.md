# The Cost epic — what acting on advice actually costs

> **Epic, not a story.** A feature-family: model the *true*, multi-dimensional,
> risk-adjusted cost of a decision — including the cost of asking the model and the
> expected cost of the model being wrong. On-thesis: it makes the price of trusting AI
> **legible**, the model's own price included.

## The story that prompted it

A chat model advised salvaging a BlackBerry keyboard to attach to the RedMagic. What
the model's "≤ ₹500" estimate missed:

1. **Point estimate vs reality.** The seller wanted ₹1000. Truth is a *range*, not a
   number; confident point estimates lie.
2. **Transaction cost that recurs per attempt.** ₹250–300 to travel there and back —
   so going *back* to close at a better price nets the *same* total. Per-attempt costs
   don't amortise into the prize.
3. **The cost of being wrong.** ~₹3000 went on an apparatus that turned out unusable.
   Error has an expected value: `chance × impact`. It must be a first-class input, not
   a footnote.
4. **The cost of the advice itself** (the model's blind spot about itself): the tokens
   / money / time spent consulting, **plus** the expected cost of the advice being
   wrong — added as an explicit risk against the recommendation.

## Dimensions (kept separate — legibility)

| Axis | Holds | Notes |
|---|---|---|
| **Money** | infra + purchases | smallest unit (paise/cents) |
| **Time** | manpower + opportunity | minutes; "manpower abstracts to infra" via a rate |
| **Tokens** | the LLM I/O | the model's own cost — money via pricing, and the raw tokens |

They are never silently collapsed. A `Valuation` (value-of-an-hour, price-per-1k-tokens)
gives a single comparable figure shown *alongside* the breakdown, never instead of it.

## The model (built, headless, JVM-tested — `domain/cost/DecisionCost.kt`)

- `CostVector(moneyMinor, minutes, tokens)` — add + scale.
- `RiskedOutcome(label, chance, extra)` — the error EV term.
- `Decision(onSuccess, perAttempt, successChance, risks, adviceCost)`.
- `DecisionCost.forecast(d, maxAttempts)` → `CostForecast(expected, worst,
  expectedAttempts, successProbability, riskContribution)`. Attempts are geometric
  (attempt *k* happens iff the first *k−1* failed); the forecast returns a **band**
  (expected **and** worst case), because the lesson is that a point estimate lies.
- `LlmAdvice.cost(tokensIn, tokensOut, pricing, readingMinutes)` — the model's own cost.
- `Cost.toCostVector()` bridges the loop's internal `Cost` (council/Escalation) into a
  decision's advice cost — one cost language across the loop *and* the world.

The worked BlackBerry case forecasts **~₹2213 expected / ₹4750 worst** vs the naive
"₹500" — and that gap is the whole point.

## Sovereignty stance (binding)

The engine supplies the *framework*; it **never invents the numbers**. Chances, impacts,
and rates are user/observed inputs (a model may *propose* them, but they're shown as the
model's claim, with its own advice-cost and advice-error counted). Consistent with the
"don't default measurement methodology" rule (Issue #2): decision theory (expected value)
is standard, but the parameters are the user's.

## Where it connects

- **Loops / Escalation.** `Escalation` already gates a step by its `Cost`/`Budget`. The
  decision forecast is the same idea pointed at the *world*: an action's risk-adjusted
  cost, with the user as terminal authority over big spends.
- **LLM usage.** Token I/O (and, later, provider-reported usage) feeds `adviceCost`, so
  every recommendation can carry "and this cost N tokens / ₹X to produce."

## UI (open — see `open-ux-decisions.md`, item G)

A wireframe **Cost facet** in the Develop room lets you enter a decision and read the
forecast. Whether Cost should also attach inline to a chat turn ("this advice cost X;
acting on it risks Y") is the open call.
