# HANDOFF_STATE.md — android-ide-core

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

Resume seam for the Fonebrew handoff-pack build-out (`fonebrew_handoff/` pack, prepared
2026-08-07). Read this file first in any future session touching this work before re-reading the
whole pack.

## WPs done, this repo

- **WP-0 (preflight survey)** — DONE. `docs/WP0_SURVEY.md`. Read-only, no code changes. Key
  corrections to the handoff pack found: real app code lives under `core-engine/`, not `app/`
  (both `CLAUDE.md` files in this constellation are stale on this point); on-device search is
  already fully wired into the UI (the pack's own context doc claimed it was not); the real CI
  gate is `./gradlew --no-daemon :core-engine:testFullDebugUnitTest :core-engine:testPlayDebugUnitTest :core-engine:checkLicense`
  (differs from both CLAUDE.md files); no JGit dependency exists yet (pure REST git APIs).
- **WP-1L-G0 (freeze the four blocking loop concepts)** — DONE. `docs/ratified/loops/
  LOOP_FROZEN_CONCEPTS_WP1L_G0.md` + `schemas/loops/registries/{floop-container-format,
  semantic-digest,canonicalization,loop-validation-rules,capability-ids,
  model-capability-vocabulary}.v1.json` + golden canonicalization vectors at
  `schemas/loops/fixtures/canonicalization/` (6 vectors, real computed SHA-256 digests, proven
  deterministic across two independent generator runs). This gate is declared "absolute" by the
  handoff pack's master prompt — nothing loop-related is testable without it. Kotlin contracts
  consuming these registries do NOT exist yet — that is WP-1L proper, not this gate.
- **WP-1 (non-loop contract corpus)** — DONE, gate **YELLOW** (green on every testable item, 2
  named non-blocking gaps). Full detail: `docs/WP1_GATE_REPORT.md`. Summary: 207 files (12
  ratified specs, 4 non-ratified registers, 30 JSON schemas across 6 domains — common, workspace,
  execution, authority, devices, integrations — 6 Kotlin contract files, 155 fixture files).
  30/30 schemas structurally valid, 88/88 cross-file `$ref`s resolve, 130/130 fixtures behave
  exactly as required, traceability matrix regenerated with 101/101 in-scope decision IDs
  assigned to exactly one artifact (0 dangling, 0 double-owned). One real Kotlin lexical bug
  found and fixed (`IntegrationContracts.kt`: a KDoc comment containing a literal `*/` sequence
  in its own prose prematurely closed the doc comment — fixed by adding a space). **Two items
  UNVERIFIED, honestly, both structural sandbox limits, not silently skipped:** Kotlin
  compilation (no `kotlinc`/Gradle toolchain in this sandbox — run
  `./gradlew :core-engine:compileFullDebugKotlin` or equivalent against
  `contracts/kotlin/*.kt` once real Gradle is available); unknown-field round-trip preservation
  (needs a real Kotlin deserializer to prove; structural design evidence — matching
  `unknownFields` fields on both schema and Kotlin sides — is present but not proof). **One named
  non-blocking format gap:** the 5 integration-domain docs cite decision IDs as short-form
  `INT-NNN` instead of the corpus-standard `FB-RAT-INT-NNN`; content coverage is complete, only
  the citation string format is inconsistent — left unfixed to avoid mis-editing ~90 citation
  instances under time pressure; flagged for the next session.

## What is NOT done yet in this repo

- **WP-1L (loop contract corpus proper)** — the 12 dual-surface loop specs still need
  de-duplication (each is a first draft with a contradictory second pass appended, per
  `10_DUAL_VALIDATION_ADDENDUM.md` §B2), the 18+3 loop JSON schemas, the 8 loop Kotlin files
  (`LoopDefinitionContracts.kt` etc.; `LoopContracts.kt` from the first pack is superseded and
  must be deleted, not kept alongside), and `AMENDMENTS.md` (carrying the `FB-RAT-INT-003`
  supersession line for `FB-RAT-MKT-001`, the three undeclared `LOOP-001..006` deltas in v2.1,
  the `Write = {MODIFY_DRAFT, EXECUTE_REVERSIBLE}` mapping row, and the `derivesFrom` column for
  ~13 restated decisions). WP-1L-G0 (the four frozen concepts) is done and is the precondition
  for this.
- **WP-2 through WP-11** — not started. See `fonebrew_handoff/06_WORK_PACKAGES.md` for the full
  staged plan and `fonebrew_handoff/06_WORK_PACKAGES.md`'s "Sizing honesty" section for the
  expected multi-session shape of this build-out.

## Deviations from the master prompt worth knowing about

- The differentiator-first reordering (`06_WORK_PACKAGES.md`'s alternate ordering,
  WP-0→WP-1→WP-1L→WP-2→WP-8→WP-8a→WP-8b) was **not** adopted — this session followed the default
  substrate-first numbered order, since the reordering is explicitly an owner decision
  (`08_OPEN_QUESTIONS.md` §E.7), not the session's to make. State this choice here as instructed:
  **substrate-first ordering is in effect; nobody has decided otherwise.**
- No hosted marketplace service, accounts, reviews, or moderation storage has been built or
  planned as buildable — out of scope for this entire build-out per the master prompt.
- No owner-reserved decision in `08_OPEN_QUESTIONS.md` has been decided by this session. Where
  work touched one (e.g. the JGit-vs-libgit2-JNI git-library choice, proposed as
  `FB-RAT-WS-NEW-1` in `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`), it is recorded as a
  **proposal**, not a ruling.

## Open threads for the next session

1. Read `docs/WP1_GATE_REPORT.md` in full before touching anything WP-1 produced.
2. Fix the `INT-NNN` → `FB-RAT-INT-NNN` citation-format gap in the 5 integration docs (mechanical,
   low-risk once done carefully with full-document review, not sed-across-the-corpus).
3. Get a real Kotlin/Gradle toolchain available and run the two UNVERIFIED checks (compilation,
   round-trip) from §WP-1 above.
4. Proceed to WP-1L (loop contract corpus) — inputs are `fonebrew_handoff/09_DUAL_SURFACE_LOOP_BUILDER.md`,
   `10_DUAL_VALIDATION_ADDENDUM.md` §E (merged artifact list), `inputs/dual_surface/specs/` (12
   extracted spec texts), and the six registries already frozen under `schemas/loops/registries/`.
5. A targeted grep for "CSApp"/"Assay" across all four constellation repos was recommended by
   `docs/WP0_SURVEY.md` §1(f) but not yet run — do this before assuming the integration lanes are
   fully greenfield.

## Commits

See git log on branch `claude/fonebrew-development-clzu43` for the `[WP0]`/`[WP1L-G0]`/`[WP1]`
prefixed commits implementing this state.
