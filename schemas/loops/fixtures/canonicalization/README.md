# Canonicalization golden vectors (fb-loop-canon-1)

Six hand-authored vectors proving the `canonicalization.v1.json` (`fb-loop-canon-1`) profile
against a reference implementation (`scripts/canon_reference.py`, this directory's generator,
mirrored at the repo root under `scripts/`). Each vector `<name>` has four files:

- `<name>.input.json` — the raw input (pretty-printed for readability; whitespace is not
  semantic).
- `<name>.canonical.txt` — the canonical UTF-8 form (no trailing newline).
- `<name>.digest.txt` — `sha256:<hex>` of the canonical UTF-8 bytes.
- `<name>.note.txt` — what the vector proves.

**Determinism proof (WP-1L gate: "golden vectors reproduce byte-identically across two
runs"):** the generator was run twice independently; the six `.digest.txt` outputs were
byte-identical across both runs (verified via `md5sum` over the six files, same checksum both
times). This is a reference-implementation determinism proof, not yet a Kotlin-implementation
conformance proof — WP-1L must add a Kotlin test that reads these same `.input.json` files,
runs the real `fb-loop-canon-1` implementation, and asserts its output matches
`.canonical.txt` and `.digest.txt` byte-for-byte. That Kotlin implementation does not exist yet
(WP-1L, not WP-1L-G0) — this fixture corpus is the target it must satisfy.

## Known gap (documented honestly, not silently skipped)

All six vectors use Basic Multilingual Plane (BMP) characters only. `canonicalization.v1.json`
specifies key sorting "as a sequence of UTF-16 code units" (RFC 8785 §3.2.3), which differs from
naive Unicode code-point sorting only for characters outside the BMP (supplementary planes,
encoded as UTF-16 surrogate pairs). The Python reference generator here uses code-point sort
order via `json.dumps(sort_keys=True)`, which is equivalent to UTF-16 code-unit order for
BMP-only content but has NOT been exercised against a supplementary-plane test case. **A
follow-up vector with an emoji or other supplementary-plane key is a known gap for whoever
picks up WP-1L** — do not assume UTF-16 vs code-point ordering is proven equivalent in general
from this corpus alone.

## What is intentionally NOT covered here

- `packageContentDigest` (the whole-archive digest over the canonical file list) — needs a real
  `.floop` archive fixture, not just JSON canonicalization. That belongs to WP-1L's package
  safety corpus, alongside the adversarial archive suite (traversal, symlink escape, etc.).
- `semanticDigest` (canonicalization + the presentation-field exclusion list applied together)
  — a separate, smaller fixture set should pair a `definition.json` containing `presentation`/
  `layout`/`x`/`y`/`notes` fields with the exclusion applied, proving the excluded fields do not
  affect the digest. Not built in WP-1L-G0; flagged for WP-1L.
