# Aarso — §5b drift and §5c idiolect: owner decisions

> **Status:** owner answers recorded 2026-11-28 in reply to four questions. **No metric code is
> built.** Binding rule 4 ("the idiolect baseline is owner-only input — do not invent it") still
> holds: the *shape* of the measurement is now decided; its *numbers and word lists* are still the
> owner's. Aarso remains a separate module; core keeps only the inert `domain/mirror/` seam.

## Decisions (verbatim choices)

| Question | Owner's choice |
|---|---|
| What defines "your voice" (§5c baseline)? | **Rolling self-vs-self.** No fixed corpus: the recent window is compared with the earlier one. |
| What does §5b drift measure? | **All four:** stylometric features · distance to LLM register · edit behaviour · phrases absorbed. |
| How is it presented? | **Features, no single score.** Per-feature series over time with raw values; the user draws the conclusion. |
| Where may the data live? | **Opt-in sync to the user's private git repo** (off by default, switched on per repo). |

## What these decisions fix

- **The baseline is relative, not absolute.** A "window" is compared with an earlier window of the same
  person. There is no imported corpus and no universal "human" reference — which is also what makes
  rule 4 tractable: the baseline is *produced by the user's own writing*, not supplied by us.
- **No composite number.** The surface is a set of independent series. Nothing in core may compute,
  store or display a single drift score. (Rule 4's honesty clause: an inert or absent lens renders
  "no lens installed," never a fake figure.)
- **Four measures, four evidence kinds**, each separately labelled:
  1. *Stylometric features* — transparent counts from the user's own text (e.g. sentence length and its
     variance, punctuation habits, function-word and vocabulary-range rates). Computable with no model.
  2. *Distance to LLM register* — needs a reference notion of "typical LLM prose." Requires a local
     embedder or a reference set. **Not decided** (see below); the existing `PlaceholderEmbedder` is not
     a real one.
  3. *Edit behaviour* — how much of an LLM draft the user rewrites versus accepts. The write side already
     exists: `AarsoEventKind.COMPOSER_EDIT_DELTA` is logged today and read by nothing.
  4. *Phrases absorbed* — specific wording that appeared in LLM output and later in the user's own text,
     shown as **examples**, not a tally.
- **Sync is opt-in and per repo.** This amends the spec line "excluded from sync by default": the
  default stays *excluded*; the user may switch it on for a private repo, like the git-recorded logs.
  Never a public repo; never silently. (`docs/design/platforms.md` §5.)

## Still owner input (not invented here)

These parameters change what the numbers mean, so they are the owner's to set:

1. **Window size and step** — e.g. "last 30 days vs the 30 before," or by number of turns.
2. **The feature list** — which stylometric features count. A proposed starting set can be offered for
   ratification; it is a proposal, not a decision.
3. **The LLM-register reference** — which local embedder (or reference set) defines "LLM register."
   Until chosen, measure 2 stays unbuilt and says so.
4. **The absorbed-phrases source** — which model outputs count as the source (only the user's own
   Fonebrew conversations? imported ones?).
5. **Minimum evidence** — how few words before a window is "too thin to show" (the lens must say "not
   enough yet" rather than draw a line from a handful of sentences).
6. **Language handling** — the owner writes in more than one language; features are per-language or
   language-agnostic?

## What can be built without those answers

Plumbing only, still inert by default: a windowed reader over the existing append-only event log; an
edit-delta reader (measure 3), which needs no word lists; and the empty per-feature series container.
That would not cross rule 4, because it computes nothing the owner has not defined. It is **not started.**

## Where this surfaces

The *Me · Myself · I* profile (header avatar → profile) is the host surface. Today it ships inert.
Series are drawn with the same viz rules as the rest of the app: no red/green meaning, glyph or label
alongside colour.
