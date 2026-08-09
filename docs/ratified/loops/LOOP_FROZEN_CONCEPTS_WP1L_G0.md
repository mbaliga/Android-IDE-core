# Loop Frozen Concepts — WP-1L-G0

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** GATE CLEARED, 2026-08-07. **Gate:** WP-1L-G0, declared absolute in the Fonebrew
handoff pack's master prompt — "nothing downstream is testable until these are written down"
(`10_DUAL_VALIDATION_ADDENDUM.md` §B1). This document and the five registries it indexes are
that freeze.

## Why this exists

The dual-surface loop-builder pack (`inputs/dual_surface/` in the handoff pack) uses four
concepts as load-bearing normative requirements without ever defining them: the `.floop`
container format, the "semantic digest," stable validation rule codes, and capability IDs. Every
downstream gate — package digest determinism, validation parity across the phone and Web Studio
surfaces, authority-widening detection, marketplace compatibility — depends on all four existing
as concrete, versioned data, not prose. This document freezes them.

## The four registries (and two supporting ones needed to make them concrete)

| # | Concept | File | Frozen as |
|---|---|---|---|
| 1 | `.floop` container format | `schemas/loops/registries/floop-container-format.v1.json` | ZIP (stored/deflate only), canonical path rules, required top-level layout, package-content-digest algorithm |
| 2 | Semantic digest | `schemas/loops/registries/semantic-digest.v1.json` | `definition.json` minus a versioned exclusion list, canonicalized, SHA-256 |
| 3 | Validation rule-code registry | `schemas/loops/registries/loop-validation-rules.v1.json` | 24 stable codes across the 10 declared namespaces (LOOP-ID/GRAPH/SCHEMA/CAP/BUDGET/VERIFY/PKG/COMPAT/TEST/LINEAGE) |
| 4 | Capability-ID namespace | `schemas/loops/registries/capability-ids.v1.json` | 20 reverse-DNS `fb.*` IDs, each cross-referenced to an authority-ladder rung, plus the `engineVersion` definition |
| — | Canonicalization profile (both 1 and 2 depend on this) | `schemas/loops/registries/canonicalization.v1.json` | `fb-loop-canon-1`: JCS/RFC 8785 baseline, IEEE-754 doubles forbidden |
| — | Model capability vocabulary (referenced by capability-ids' compatibility use) | `schemas/loops/registries/model-capability-vocabulary.v1.json` | 14 model-feature tags feeding `LOOP-COMPAT-001` |

Every registry carries `status: "FROZEN"`, a `registryVersion`/`rulesetVersion`/
`canonicalizationVersion` field, and `decisionRefs` pointing at the `FB-RAT-*` decision IDs it
implements. None of them are owner-reserved decisions (`08_OPEN_QUESTIONS.md` does not list any
of the four) — WP-1L-G0 explicitly authorizes the implementing session to make this call, and the
`.floop` **extension string itself** stays open (`FB-RAT-LBX-010`, EXPERIMENTAL, pending the
owner's naming/branding pass — see `08_OPEN_QUESTIONS.md` §E.3) while the **bytes** underneath it
are now pinned.

## What changed from the source pack (corrections applied per `10_DUAL_VALIDATION_ADDENDUM`)

- **Deleted the canonicalization escape hatch.** The source pack said "RFC 8785-style... or a
  versioned equivalent." `canonicalization.v1.json` pins exactly one profile, `fb-loop-canon-1`,
  with no equivalent-profile clause.
- **Forbade IEEE-754 doubles.** The JVM cannot reach ECMA-262 §7.1.12.1 number serialization with
  `Double.toString`, and kotlinx-serialization has no canonical mode. Rather than work around
  this, the profile forbids fractional JSON numbers outright: decimals are constrained decimal
  strings. This deletes the hazard rather than mitigating it.
- **`definition.bpmn` is a derived, digest-excluded export**, not part of package identity —
  resolves the "three conflicting `.floop` layouts" finding (§B2) by making the BPMN file's
  location a non-issue: it never participates in the digest regardless of where it sits, so this
  registry places it at the archive root for simplicity.
- **`fixtures/` not `tests/fixtures/`** — resolves the other layout conflict from §B2.
- **Package content digest is computed over the canonical file list** (path, length, SHA-256 per
  file), never raw archive bytes — this was already the pack's better instinct (per
  `10_DUAL_VALIDATION_ADDENDUM` §A "better than what most shipping registries do"); this registry
  just makes it precise and testable.
- **Three new P0 validation controls added as first-class rule codes** (`10` §B3, not present in
  the source pack at all): `LOOP-PKG-006` (invisible-Unicode rejection, non-dismissible),
  `LOOP-PKG-007` (manifest egress allowlist), `LOOP-PKG-008` (prompt-risk heuristic scan). A
  fourth new code, `LOOP-TEST-002`, implements the fixture-staleness rule from `10` §D (a prompt
  edit marks a fixture STALE with its own rule ID rather than silently missing or matching).

## Proof so far (WP-1L-G0 scope) vs what's still owed (WP-1L scope)

**Done in this gate:**
- Six hand-authored canonicalization golden vectors (`schemas/loops/fixtures/canonicalization/`,
  generator at `scripts/canon_reference.py`) covering key-order sorting, decimal-as-string number
  policy, NFC normalization, array-order preservation, recursive nested sorting, and
  whitespace-freedom.
- Determinism proven for the *reference* (Python) implementation: the generator was run twice
  independently and produced byte-identical digests both times (`md5sum` over all six
  `.digest.txt` files matched across runs).

**Not yet done (explicitly flagged, belongs to WP-1L proper, not this gate):**
- No Kotlin implementation of `fb-loop-canon-1` exists yet. The golden vectors are the target it
  must reproduce byte-for-byte; WP-1L must write that implementation and the test that checks it
  against these fixtures.
- No `packageContentDigest` golden vector exists (needs a real minimal `.floop` archive, not just
  JSON canonicalization) — WP-1L's package safety corpus.
- No `semanticDigest` golden vector (canonicalization + exclusion-list applied together) exists
  yet — WP-1L.
- The adversarial archive corpus (traversal, symlink escape, decompression bombs, duplicate
  normalized paths, forbidden payloads) required by `07_TEST_CONFORMANCE_PLAN.md` §4b does not
  exist yet — WP-1L / WP-8a.
- A supplementary-plane (non-BMP) canonicalization vector is a known gap — see the fixtures
  README's "Known gap" note. UTF-16 code-unit sort order vs Unicode code-point sort order is
  unproven to be equivalent beyond the BMP-only vectors here.
- The 8 loop Kotlin contract files (`LoopDefinitionContracts.kt` etc.) that would consume these
  registries as typed constants do not exist yet — WP-1L.

## Next reader

WP-1 and WP-1L should treat these six registries as fixed inputs and build the Kotlin contract
layer, the remaining 18 loop JSON Schemas, and the adversarial/golden fixture corpus against
them — not re-derive the four frozen concepts. If a future session finds a genuine defect in one
of these registries (not just an extension), bump to `v2` alongside a documented migration, per
each registry's own `bumpRule`/versioning language — never edit a `v1` registry's already-shipped
semantics in place.
