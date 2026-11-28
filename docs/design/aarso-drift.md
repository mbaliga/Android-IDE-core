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

## Resolved in the second round (2026-11-28)

| Parameter | Owner's choice |
|---|---|
| Window definition | **Both:** time-based by default (recent vs the period before), widening automatically until a minimum amount of text is reached |
| Stylometric feature list | **The starter set, accepted:** sentence length (mean and spread), vocabulary range, average word length, paragraph length, punctuation rates (commas, dashes, semicolons, exclamation marks, ellipses), function-word rates. Each is its own series; any can be dropped later |
| "LLM register" reference | **Both:** (a) feature-space distance to the assistant turns in the user's own logs, and (b) a local embedder, chosen below |
| Absorbed-phrases source | **Fonebrew conversations plus conversations the user chooses to import** |
| Languages | **Each language measured separately** — a language switch must never read as a change of voice |

### Embedder: chosen — Qwen3-Embedding-0.6B (Apache-2.0), with EmbeddingGemma-300M as the light alternative

The owner asked for the embedder to be chosen now; the choice and its reasons, so it can be overturned:

- **Qwen3-Embedding-0.6B** — Apache-2.0 (no gated download, no use-policy attached), 100+ languages,
  a published MTEB-multilingual mean of ~64 (the figure reported on its model card — not re-run here),
  1024-dim with Matryoshka truncation, GGUF builds that run under llama.cpp with `--pooling last`
  (the model card lists a ~378 MB GGUF; exact size depends on the quantisation chosen).
- **EmbeddingGemma-300M** — smaller (308M, under ~200 MB RAM quantised per Google), 100+ languages,
  MTEB-multilingual ~61, runs under llama.cpp — but under the Gemma terms with a gated download.
  Kept as the low-memory option for weak hosts (the HP / the 2015 MacBook).
- **Reversible by construction:** `Embedder.id` is stored beside every vector, so changing the model
  means re-embedding, not a migration.

**Caveats, stated plainly.**
1. *Neither model's quality on Konkani or Odia is known to me.* Hindi, Urdu and Bengali are well covered
   by multilingual benchmarks; Konkani and Odia are thin in them. A small owner-run check on his own
   text should precede trusting any semantic number in those languages.
2. *A semantic embedder is a weak style measure.* Embedding models are trained to place paraphrases
   together, i.e. to be **insensitive to style**. Distance to "LLM register" via an embedder will mostly
   track topic and meaning; the feature-space measure (a) is the real register signal. Both are shown,
   labelled, and neither is merged into a score.
3. *The engine does not embed yet.* Neither `InferenceEngine` nor `LlamaCppEngine` has an embedding path
   (core's only `Embedder` is `PlaceholderEmbedder`). It needs a JNI addition to `llama_jni.cpp`
   (embedding mode, pooling) — real native work, not started.

### Still open (and how it is handled)

- **Minimum text per window — derived from the owner's own writing (2026-11-28), replacing my 1,000-word
  placeholder.** The rule is **per feature, not per window**: a series is drawn only once the window holds
  enough text for that feature's relative standard error to be at most 20%; until then that one series says
  "not enough yet" and shows the word count it is waiting for. The window still widens automatically (the
  owner's choice); features simply switch on as it widens. Derived minimums (English, rounded up):

  | Feature | Words per window | Basis |
  |---|---|---|
  | Function-word rate, word length, vocabulary range | 150 | converge almost at once; 150 is a floor set by the smallest draw tested, not a measured limit |
  | Sentence length, mean | 400 | fitted 386 |
  | Comma rate | 450 | fitted 423 |
  | Sentence length, spread | 550 | fitted 513 |
  | Dash rate | 1,200 | fitted 1,188 |
  | Paragraph length | not used for chat turns | turns are single paragraphs (fitted ~1,800); long-form only |
  | Question-mark rate | 7,000 | fitted 6,904 |
  | Semicolon, exclamation, ellipsis | **count-gated: at least 25 occurrences in the window** | none appeared in the sample, so no word count can be fitted; a Poisson count of 25 is about 20% error |

  **How it was derived.** Aggregates only; no text is committed. The owner's human-authored turns from the
  13 most recent claude.ai chats plus this programme's session messages (38 turns, about 1,550 words) were
  pooled; windows of 100–500 words were drawn by resampling whole turns, 1,500 draws per size; each feature's
  relative standard error was measured and extrapolated with error ∝ 1/√words (fitted slopes −0.35 to −0.50).
  The script is `scripts/baseline_bootstrap.py` in the Aarso repo.

  **Read these numbers as lower bounds.** (1) 1,550 words is small, and I read only the opening turns of the
  longer chats. (2) The sample is one register: short, imperative prompts to an assistant, not long-form
  writing. (3) Resampling inside one pool leaves out topic drift between real windows, so true window-to-window
  error is larger. (4) English only; Hindi, Urdu, Bengali, Konkani and Odia have no sample here and need
  their own run once he has text in them. (5) At roughly 170 words a day (a low estimate, from partial reads)
  the 550-word tier takes about three days, 1,200 about a week, 7,000 about six weeks. Re-run the script as
  more chats are imported; the table should be replaced by the new fit, not patched.

  **Seed values, not constants.** Pooled English point estimates, for sanity-checking the lens only (the real
  baseline stays rolling self-vs-self): sentence length 15.5 words (spread 13.2), word length 4.26 characters,
  commas 47.7 per 1,000 words, dashes 18.7, question marks 3.2, function-word share 0.65 (stop-word proxy).
- **Per-language function-word lists — built (2026-11-28), with gaps.** Data lives in the Aarso repo
  (`data/function-words/`; MANIFEST pins source commit and hashes), not in core, so `domain/mirror` stays
  inert. Covered: the 12 most-spoken languages by total speakers (English, Mandarin, Hindi, Spanish, Arabic,
  French, Bengali, Portuguese, Russian, Urdu, Indonesian, German) plus Japanese and Marathi. They are
  **stop-word lists used as a proxy for function words**, not linguist-curated lists. **No list exists for
  Konkani or Odia; none was invented**, so word-level features are off for both (Marathi is recorded as a
  close relative, never as a stand-in label). Mandarin and Japanese are off until a word segmenter exists;
  Arabic matching under-counts because of attached clitics.
- **Language detection** — per-language windows need a detector; undecided (a small on-device one, or
  script-based for the Indic scripts and a model for the rest).

## What can be built now

Plumbing only, still inert by default: a windowed reader over the existing append-only event log; an
edit-delta reader (measure 3), which needs no word lists; and the empty per-feature series container.
That would not cross rule 4, because it computes nothing the owner has not defined. It is **not started.**

## Where this surfaces

The *Me · Myself · I* profile (header avatar → profile) is the host surface. Today it ships inert.
Series are drawn with the same viz rules as the rest of the app: no red/green meaning, glyph or label
alongside colour.
