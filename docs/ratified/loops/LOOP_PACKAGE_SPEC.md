# Loop Package Specification

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own sections), with two named exceptions carried at their
true status: `FB-RAT-PKG-003` is **REJECTED** (`docs/non_ratified/REJECTED_ALTERNATIVES.md`) and
`FB-RAT-PKG-010` is **EXPERIMENTAL** (`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`). Both are
called out inline where they appear — §6 and §13 respectively. **Scope:** the byte-and-policy
contract for the `.floop` LoopPackage as a distributable, importable object — package identity,
content policy, package-safety adversarial classes, portable node identity, binding placeholders,
public-release completeness, signatures, versioning, and resource limits. This document is the
citation target for `FB-RAT-PKG-001` through `FB-RAT-PKG-010` and, filed here rather than under
its own `WEB` numbering because binding placeholders are package-content substance and not
browser-UI substance, `FB-RAT-WEB-005` (§9).

This document does **not** redefine the `.floop` container's bytes, path rules, or digest
algorithms — those are frozen at `schemas/loops/registries/floop-container-format.v1.json`,
`semantic-digest.v1.json`, and `canonicalization.v1.json` (indexed by `docs/ratified/loops/
LOOP_FROZEN_CONCEPTS_WP1L_G0.md`) — nor the two-surface architecture that makes a portable package
necessary in the first place (`docs/ratified/loops/LOOP_DUAL_SURFACE_ARCHITECTURE.md`,
`FB-RAT-LBX-001`/`FB-RAT-LBX-002`). Both are cross-referenced throughout and never re-derived; a
conflict between this document's prose and a frozen registry's data is this document's error, and
the registry wins.

## Note on this document's structure

The extracted first-pass/second-pass draft this document replaces used a 1–16 section sequence
that **restarted numbering mid-document**: it reaches `### 11. Deterministic build gate`, then
begins a second pass at `### 11. Required package layout` — the same numeral used for two entirely
different topics — and continues 12–16 as an overlapping second draft of the whole file
(`10_DUAL_VALIDATION_ADDENDUM.md` §B2: *"`LOOP_PACKAGE_SPEC` has two §11 and two canonicalization
sections"*). Four topics are stated twice under different numbers, not just the literal §11
collision: canonicalization (draft §6 and draft §12 — the second §B2 finding), allowed/forbidden
content (draft §4 and draft §14), signature detail (draft §7 and draft §13), and resource limits
(draft §10 and draft §15). A fifth pair — draft §3's archive-layout tree and draft §11(second)'s
required-layout list — describes the same package-completeness concept from two different angles
rather than restating it verbatim, and is merged for the same reason. Every one of the 16 draft
sections is folded into exactly one section below; nothing is dropped. §7 (package-safety
adversarial classes), §9 (binding placeholders), and the state tables throughout are new normative
text that did not exist in the draft at all, or existed only as prose — each is flagged inline
where it appears.

Any other document in this handoff pack that cites this spec by a draft section number — the
dual-surface register's `PKG §2` (`FB-RAT-PKG-001`), `PKG §3` (`FB-RAT-PKG-008`), `PKG §4`
(`FB-RAT-PKG-002`), `PKG §5` (`FB-RAT-PKG-009`), `PKG §6` (`FB-RAT-PKG-004`), `PKG §7`
(`FB-RAT-PKG-005`), `PKG §8` (`FB-RAT-PKG-006`), `PKG §9` (`FB-RAT-PKG-007`), `PKG §10`
(`FB-RAT-PKG-010`), and the Web Studio register's `WEB §6` (`FB-RAT-WEB-005`) — should re-anchor
against this table, not the draft:

| Draft § | Draft topic | This document's § |
|---|---|---|
| 1 | Purpose | §1 |
| 2 | Package identity | §2 |
| 3 | Recommended archive layout | §3, §10 |
| 4 | Allowed and forbidden content | §6 |
| 5 | Stable identity | §8 |
| 6 | Canonicalization (first pass) | §4 |
| 7 | Signatures and trust (first pass) | §11 |
| 8 | Versioning | §12 |
| 9 | Privacy and secret scanning | §7 |
| 10 | Resource limits (first pass) | §13 |
| 11 (first) | Deterministic build gate | §5 |
| 11 (second) | Required package layout | §10 |
| 12 | Canonicalization (second pass) | §4 |
| 13 | Signature scope (second pass) | §11 |
| 14 | Payload policy (second pass) | §6 |
| 15 | Package limits (second pass) | §13 |
| 16 | Build receipt | §5 |
| — | (not in draft — see `LOOP_WEB_STUDIO_SPEC.md` draft §6) | §9 (`FB-RAT-WEB-005`) |

**Corrections applied, beyond de-duplication (detailed where they appear):**

1. **Three incompatible `.floop` layouts collapsed to one, at the registry, not here.** Draft §3's
   tree placed `definition.bpmn` at the archive root and fixtures under `tests/fixtures/`; draft
   §11(second)'s optional-directory list separately named both `fixtures/` and `bpmn/`, implying
   `definition.bpmn` might instead live under `bpmn/`. Because `packageContentDigest` is computed
   over **sorted normalized paths**, this was not a stylistic disagreement — it was
   digest-affecting. `floop-container-format.v1.json` (frozen at WP-1L-G0) resolves it:
   `fixtures/`, never `tests/fixtures/`; `definition.bpmn` at the archive root, classified
   `derivedFile` and excluded from the digest regardless of where it sits. §3 restates this
   resolution for readability; it does not re-decide it.
2. **"Declarative = safe" is corrected, not merely qualified.** The draft's §4/§14 pairing implies
   that rejecting executable payloads is sufficient defense. §7 states plainly that it is not, and
   bakes in the three P0 controls frozen as `LOOP-PKG-006`/`LOOP-PKG-007`/`LOOP-PKG-008` in
   `loop-validation-rules.v1.json` — none of which existed anywhere in the source pack.
3. **`FB-RAT-WEB-005` is ratified in this document, not `LOOP_WEB_STUDIO_SPEC.md`.** The dual-surface
   register cites its source passage as `WEB §6` (the Web Studio draft's own numbering), but its
   canonical home was always assigned to this file — binding placeholders are what a *package*
   declares, independent of which surface authored it. §9 is that content, drawn from the Web
   Studio draft's §6 since this file's own draft never mentioned binding placeholders at all.

---

## 1. Purpose

A LoopPackage MUST be a safe, deterministic, portable, inspectable archive that transports a loop
without transporting local authority, secrets, or executable extensions. Every section below is
what makes that one sentence testable: identity and boundary (§2–§3), the digests that make
identity exact (§4–§5), what content is and is not permitted (§6–§7), what stays stable across
surfaces (§8–§9), what a public release must contain (§10), how trust is established (§11–§12),
and how size is bounded (§13).

## 2. Canonical package boundary and package identity

**`FB-RAT-PKG-001` — ACCEPTED.** *"Use an immutable content-addressed LoopPackage as the
browser-to-phone, phone-to-phone, Git, and marketplace boundary."* Every transfer channel
enumerated in `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §7 (`.floop` file, share sheet, marketplace/
unlisted release URL, QR code carrying a URL plus expected digest, user-owned Git repository)
carries a LoopPackage, never a live reference into either surface's private state. This is the
boundary `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §10 calls the "canonical compiler boundary": each
surface's local authoring model is private; only the compiled package crosses it.

A package has two identities, and they MUST NOT be conflated:

- **Human/version identity** — `loopId` + `semanticVersion` (§12). Stable across content changes
  within a version lineage; this is what a person recognizes and what an update check compares.
- **Exact content identity** — the digest(s) defined in §4. Two packages with the same `loopId`
  and `semanticVersion` but different bytes are different content; the digest, not the version
  string, is what a verifier, a cache, or a signature actually binds to.

This decision specializes `FB-RAT-COM-002` (stable identity, `docs/ratified/COMMON_CONVENTIONS.md`)
for package-transported objects specifically: `FB-RAT-COM-002`'s general "globally unique stable ID
independent of display name and path" becomes, for a package, the digest-vs-version split above.

## 3. Container format and archive layout

The `.floop` container's bytes — ZIP with only `STORED`/`DEFLATE`, no directory entries, no extra
fields, UTF-8 NFC entry names, forward-slash relative paths, no symlinks, local/central-directory
agreement, and the full adversarial-rejection list (path traversal, duplicate normalized paths,
decompression-ratio bombs, declared/actual length or digest mismatches) — are frozen at
`floop-container-format.v1.json` (`FB-RAT-LBX-010`, `FB-RAT-PKG-004`) and are **not restated here**.
This section gives the resolved archive layout for readability only; `floop-container-format.v1.json`
is the normative source, and this tree supersedes both of the draft's disagreeing trees per
correction 1 above:

```
<loop-id>-<version>.floop
├── manifest.json          REQUIRED — identity, capability requests, compatibility, inventory, signature ref
├── definition.json        REQUIRED — canonical LoopDefinition; sole source of semantic identity (§4)
├── definition.bpmn        OPTIONAL, DERIVED — regenerable BPMN 2.0 export; excluded from every digest
├── schemas/                OPTIONAL — input/output/form JSON Schemas referenced by definition.json
├── fixtures/                OPTIONAL — simulation/replay/fault-injection fixtures (NEVER tests/fixtures/)
├── docs/                    OPTIONAL — README and other human-readable documentation
├── assets/                  OPTIONAL — non-executable static assets (icons, media)
├── forms/                   OPTIONAL — form-rendering hints for schemas/ (see note below)
├── LICENSE                  OPTIONAL — license text
├── compatibility.json      (public release: REQUIRED — §10)
├── provenance.json         (public release: REQUIRED — §10)
└── signatures/
    └── publisher-signature.json   loop-package-signature.v1 (§11)
```

Every top-level entry, enumerated in `floop-container-format.v1.json` or not, MUST be classified in
`manifest.json`'s per-file inventory as exactly one of `semanticFile`, `derivedFile`, or
`signatureFile` (that registry's `packageInventory` section). `forms/` is not separately enumerated
in the frozen registry's `requiredTopLevelLayout`; it is permitted under this general inventory
rule and classified `semanticFile` — it is authored content, not regenerable. An unknown but
otherwise-safe file MAY be preserved on import, but it is never trusted or executed by virtue of
being present, per §6.

## 4. Canonicalization and digest algorithms

*(Merges draft §6 and draft §12 — the pack's second literal duplicate-numbered pair per
`10_DUAL_VALIDATION_ADDENDUM.md` §B2 — which stated the same canonicalization requirement twice at
two levels of detail.)*

**`FB-RAT-PKG-004` — ACCEPTED.** *"Define deterministic path ordering, UTF-8 normalization, line
endings, JSON canonicalization, and digest computation so both surfaces produce the same package
digest."* The concrete profile is frozen at `canonicalization.v1.json` (`fb-loop-canon-1`) and is
**not restated here**: RFC 8785 (JCS) as a baseline, IEEE-754 doubles forbidden entirely (integers
or constrained decimal strings only), NFC-normalized strings and keys, UTF-16-code-unit key sort,
no insignificant whitespace, LF-only text, array order preserved. The draft's escape hatch —
*"RFC 8785-style ... or a versioned equivalent"* — is deleted; no implementation may claim
conformance to a variant profile under the `fb-loop-canon-1` name.

Two digests are built on that one canonicalization profile, and this document draws the line
between them exactly where `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §2.1 does — they MUST NOT be
conflated:

| Digest | Covers | Frozen at | Excludes |
|---|---|---|---|
| `packageContentDigest` | The canonical file list — `(normalizedPath, byteLength, sha256)` per `semanticFile` | `floop-container-format.v1.json` | `derivedFile` entries (`definition.bpmn`), `signatureFile` entries, archive/ZIP metadata (timestamps, compression method, member order) |
| `semanticDigest` | `definition.json` only, minus the versioned exclusion list (`presentation`, `layout`, `x`, `y`, `notes`) | `semantic-digest.v1.json` | Everything `packageContentDigest` covers except `definition.json` itself, plus the excluded presentation-only keys |

A layout-only edit (moving a card, renaming a file's on-disk position without changing its bytes)
MUST change `packageContentDigest` (paths participate) but MUST NOT change `semanticDigest`
(presentation keys are excluded). This is the P0 conformance rule `semantic-digest.v1.json` states
and the golden-vector corpus at `schemas/loops/fixtures/canonicalization/` proves for the
canonicalization step in isolation.

This decision specializes `FB-RAT-COM-005` (integrity — "digest artifacts, manifests, and receipts
with SHA-256 or stronger and record byte length," `docs/ratified/COMMON_CONVENTIONS.md`) for
packages specifically: SHA-256 is the algorithm both frozen registries use, and both record byte
length in the canonical file list.

## 5. Deterministic build gate and build receipt

*(Merges draft §11's first occurrence, "Deterministic build gate," with draft §16, "Build
receipt" — closely related but not duplicate topics: one states the determinism invariant, the
other states the record that proves it was met. Giving each its own new section number, as here,
is what resolves the draft's literal §11 numeral collision; see correction 1's framing above and
the mapping table.)*

Two independent builds from byte-identical canonical source MUST produce the same
`packageContentDigest` and the same `semanticDigest`. This is not a target — it is
`semantic-digest.v1.json`'s stated conformance rule, machine-proven for the reference
canonicalization implementation over 1,000 repeated runs (`docs/ratified/loops/
LOOP_FROZEN_CONCEPTS_WP1L_G0.md`), and it is what `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9's
cross-surface golden conformance checklist (steps 3 and 5) checks between the phone and browser
golden builders specifically.

| From state | Event | To state | Notes |
|---|---|---|---|
| `BUILD_STARTED` | A surface's compiler canonicalizes the source draft and computes `packageContentDigest` + `semanticDigest` | `DIGESTS_COMPUTED` | Uses `fb-loop-canon-1` (§4); no partial-digest state is observable outside the compiler. |
| `DIGESTS_COMPUTED` | The other surface's golden builder computes both digests over the same canonical source | `DIGESTS_COMPARED` | Golden-vector comparison, not a live cross-surface call — `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §10 forbids a shared runtime between surfaces. |
| `DIGESTS_COMPARED` | Both digests match | `BUILD_VERIFIED_DETERMINISTIC` | Satisfies the conformance rule above. |
| `DIGESTS_COMPARED` | Either digest differs | `BUILD_NONDETERMINISM_DETECTED` | A conformance failure to fix, never a warning to suppress — a mismatch means the two compilers disagree about semantics, not that one build is "newer." |
| `BUILD_VERIFIED_DETERMINISTIC` or `BUILD_NONDETERMINISM_DETECTED` | Build receipt emitted | `RECEIPT_RECORDED` | The receipt records the outcome either way (fields below) — a non-deterministic build is not silently discarded, it is recorded as such. |

The build receipt MUST record: the source draft revision; `semanticDigest` and its
`semanticDigestVersion`; the validation `rulesetVersion` applied (`loop-validation-rules.v1.json`)
and its findings; test-suite results; the `canonicalizationVersion` applied
(`canonicalization.v1.json`); the file-inventory digest and `packageContentDigest`; the signature
action taken, if any (§11); the builder's implementation identity and version; and any warnings.
Browser and phone build receipts MUST be comparable using only these fields, without exposing
private draft content — a receipt is evidence of what was built, not a copy of the draft itself.

## 6. Declarative-only content policy

*(Merges draft §4, "Allowed and forbidden content," with draft §14, "Payload policy" — the same
allow/forbid boundary stated twice under different numbers.)*

**`FB-RAT-PKG-002` — ACCEPTED.** *"A package MAY contain graphs, prompts, schemas, forms,
fixtures, tests, documentation, metadata, and assets, but not executable application code."*
Concretely, allowed content is inert declarative data only: UTF-8 text, JSON, BPMN XML, Markdown,
images with declared MIME types, JSON Schemas, forms, fixture data, mock outputs, documentation,
license text, and signatures.

**`FB-RAT-PKG-003` — REJECTED.** *"Reject DEX, JAR, native libraries, binaries, scripts intended
for direct execution, and hidden plugin installers in marketplace packages."* This is the rejected
alternative on record, not a live proposal — a package MUST NOT contain: DEX or JAR files, native
libraries, executable binaries, scripts marked or intended for execution, package managers or
installers, JavaScript embedded in documentation, macro-enabled office files, device nodes, or
encrypted opaque payloads (a blob validation cannot classify is treated as a forbidden payload, not
as unknown-but-safe). Symlink entries and path-traversal entries are forbidden too, but that
prohibition is already normative at the container level
(`floop-container-format.v1.json`'s `archiveFormat.symlinks` and `pathRules
.adversarialClasses_MUST_reject`) and is cross-referenced, not re-derived, here.

This is the acceptance/rejection boundary implemented by rule code `LOOP-PKG-002` in
`loop-validation-rules.v1.json` (*"File is a forbidden executable payload... Marketplace packages
are declarative-only; reference a capability ID instead"*) — that rule's `sourceObligation`
explicitly names `FB-RAT-PKG-003`'s rejection. `10_DUAL_VALIDATION_ADDENDUM.md` §A identifies this
as the single most articulable safety differentiator against the nearest mobile precedent (Acode's
plugin store ships *executable* plugins — precisely what `FB-RAT-PKG-003` rejects).

A plain-text snippet shown as documentation, or as a typed tool argument, remains data under this
policy: it MAY be displayed, and it MAY be passed as a value, but it MUST NOT be invoked except
through a node's declared, capability-gated execution path (`fb.*` capability IDs,
`capability-ids.v1.json`). Declarative content that merely *describes* an action is never itself
that action — which is exactly the boundary §7 exists to say is necessary but not sufficient.

## 7. Package-safety adversarial classes — the declarative boundary is not a safety boundary

*(Draft §9, "Privacy and secret scanning," forms this section's base; the framing and the three new
controls below are new normative text per this work package's file-specific correction — none of it
existed in the source pack.)*

**Say plainly: the declarative boundary of §6 is an arbitrary-code-execution boundary, not a
safety boundary.** Rejecting executable payloads (`LOOP-PKG-002`, §6) closes off one attack class —
a package can never *run* code on the device merely by being imported — but it does not close off
misuse of the model-directed, prompt-and-tool-argument content that §6 explicitly allows as data.
`10_DUAL_VALIDATION_ADDENDUM.md` §B3 documents why this matters concretely, not hypothetically:
markdown instruction files are exactly the payload class flagged in the Snyk ToxicSkills study
(3,984 skills scanned, 36.82% with at least one security flaw); direct-README prompt injection
succeeds roughly 84% of the time across shipping coding agents, and 93% of human reviewers in that
study failed to spot the embedded attack on manual review — which is why a Preview step alone
(`LOOP-PKG-004`, "import never executes package content") is not itself a sufficient defense; it
stops execution-before-activation, not misuse-after-activation of authority the user has already
granted.

**`FB-RAT-PKG-007` — ACCEPTED.** *"Package validation MUST reject secret-like fields, private
keys, raw credentials, and local credential aliases."* Validation MUST scan every file for JSON
keys, text patterns, entropy signatures, known credential formats, private-key blocks, local
filesystem paths, hostnames, and credential aliases, using a named tool (gitleaks, per
`02_DECISION_ANNOTATIONS.md` §4b's correction to `FB-RAT-MKT-007`). Findings MUST be explainable —
a rejection names the matched pattern class, not just "secret found." The system MUST NOT upload
package content for scanning without an explicit publication action initiated by the user; scanning
for import/build is local. This is rule code `LOOP-PKG-001` in `loop-validation-rules.v1.json`
(*"File matches a secret-like pattern... secrets are resolved by reference at runtime, never
embedded"*) — the binding-placeholder mechanism (§9) is precisely that reference-by-name
alternative.

Three further controls are frozen as first-class rule codes in `loop-validation-rules.v1.json`,
none of which existed in the source pack at all (`10_DUAL_VALIDATION_ADDENDUM.md` §B3):

1. **`LOOP-PKG-006` — invisible-Unicode rejection, non-dismissible.** Every model-visible or
   human-visible string in a package (prompts, docs, labels, descriptions, fixture text, manifest
   strings) MUST be rejected if it contains Unicode Tags (`U+E0000`–`U+E007F`), bidi controls
   (`U+202A`–`U+202E`, `U+2066`–`U+2069`), zero-width/format characters (`U+200B`–`U+200F`,
   `U+FEFF`), or Private Use Area codepoints — unless each occurrence is individually declared and
   justified in the manifest. NFC normalization (§4) does **not** remove any of these codepoints,
   so canonicalization alone is not a defense here. A survivor MUST render with a visible badge and
   an escape view, and the finding MUST NOT be dismissible as an ordinary warning — this is the
   control that would have caught GlassWorm-style PUA payloads and Rules-File-Backdoor-style bidi/
   zero-width smuggling in a `.cursor/rules`-shaped declarative config, both cited precedent in
   `10_DUAL_VALIDATION_ADDENDUM.md` §B3.
2. **`LOOP-PKG-007` — manifest egress allowlist.** A node requesting the `fb.network.egress`
   capability (`capability-ids.v1.json`) MUST declare its exact destination host(s) in the
   manifest; the phone enforces a deny-by-default allowlist scoped to those hosts. A granted
   `External` capability is never a general network grant — this is the control that covers what
   `10_DUAL_VALIDATION_ADDENDUM.md` §B3 identifies as the dominant real failure mode, misuse of
   authority the user already granted, which capability gating alone does not touch.
3. **`LOOP-PKG-008` — prompt-risk static scanning.** Every model-directed string MUST be scanned at
   build and at import for model-directed imperatives, exfiltration verb+destination pairs,
   sensitive-path references (`~/.ssh`, `.env`), and markdown/HTML-comment-smuggled instructions
   (the OWASP MCP03:2025 indicator list). This is a `WARNING`-severity heuristic flag requiring
   human review before publish, not proof of malice — it MUST NOT auto-reject a package on its own.

## 8. Portable node identity

**`FB-RAT-PKG-009` — ACCEPTED.** *"Node IDs and edge labels are stable across surfaces,
canonicalization, import, export, and semantic-preserving layout changes."* Node, port, edge,
gateway, schema, binding-slot (§9), fixture, verifier, and test IDs MUST all be stable under this
rule — it is not limited to nodes and edges despite the decision's short name. Presentation
coordinates (the `presentation`/`layout`/`x`/`y` keys `semantic-digest.v1.json` excludes, §4) MAY
change freely without any semantic identity change. Deleting an object and recreating one that
looks the same MUST be treated as a new object with a new ID — identity is never inferred from
resemblance. This is what makes `LOOP-ID-001` (`loop-validation-rules.v1.json`, duplicate-identity
detection) and the fork/lineage diff machinery in the forthcoming `LOOP_FORK_LINEAGE_CONTRACT.md`
possible: a semantic diff between two revisions is only meaningful if the same object carries the
same ID across both.

This decision specializes `FB-RAT-COM-002` (stable identity, `docs/ratified/COMMON_CONVENTIONS.md`)
for the loop object graph specifically, the same way §2 specializes it for the package as a whole —
`10_DUAL_VALIDATION_ADDENDUM.md` §F records `PKG-009≡COM-002` as one of roughly thirteen decisions
that specialize a `COMMON_CONVENTIONS.md` parent rather than duplicating it; this document treats
`FB-RAT-COM-002` as the normative parent and `FB-RAT-PKG-009` as its package-object-graph instance,
not as two independent rules that could drift.

## 9. Binding placeholders

**`FB-RAT-WEB-005` — ACCEPTED.** *"Packages declare typed binding slots such as model,
repository, CI, device, and secret references; Fonebrew resolves them locally."* This decision's
source passage is the Web Studio draft's §6 ("Binding slots and secrets"), not this file's own
draft — it is ratified here, not in `LOOP_WEB_STUDIO_SPEC.md`, because a binding placeholder is
package content: whichever surface authors a package, the placeholder travels inside it and is
resolved the same way on import, per correction 3 above.

A package MUST declare its binding requirements as **named abstract placeholders**, never as
resolved values — for example `model.writer`, `model.critic`, `repository.primary`, `ci.default`,
`device.target`, `secret.release_signing`. Each placeholder is one of the stable binding-slot IDs
§8 covers: a placeholder's name and type are semantic identity, and (per §4) do not change under
layout-only edits.

Resolution MUST happen locally, on the importing phone, never inside the package and never
server-side: `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §3 lists "model, repository, target, device, and
secret binding" among the phone's exclusive responsibilities, and §6 of that same document lists
"local binding profiles" among the content a surface MUST NOT transmit to the other surface or to
any network endpoint by default. A binding placeholder is therefore a name, and resolution is a
local lookup against the user's own bound models, repositories, CI targets, devices, and Keystore
secrets — never a value carried by the package.

**Raw secrets, phone Keystore aliases, private host keys, and provider credentials are forbidden
package content**, full stop — this is the same prohibition §6 and §7 (`FB-RAT-PKG-007`,
`LOOP-PKG-001`) state for secret-like material generally, restated here because it is binding
placeholders that make the prohibition livable: a package never needs to embed a secret, because it
can always name a `secret.*` placeholder and let the phone resolve it via `fb.secret.use`
(`capability-ids.v1.json` — "use a Keystore-held secret by purpose-bound handle... never returns
the raw value to model context or logs"). If a future browser-accounts feature allows private
drafts (`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §12's server-side draft privacy, P1-scope), server-side
draft storage does not relax this rule — the prohibition is on package *content*, independent of
where the package is stored.

A bound value MAY only **narrow** the authority the package's placeholder declared, never widen it
— this is `LOOP-CAP-002` (`loop-validation-rules.v1.json`), the loop-specific instance of the
narrowing invariant `docs/ratified/CAPABILITY_AUTHORITY_MODEL.md` §6 (`FB-RAT-AUTH-004`, no
implicit privilege expansion) states generally; that document's §10.1 already cross-references
`LOOP-CAP-002` as its own loop-engine instance, and this section does not re-decide it, only names
it at the point a binding is actually made.

## 10. Package completeness for public release

*(Merges draft §3's archive-layout tree with draft §11(second)'s "Required package layout" list —
two overlapping descriptions of what a complete package contains, at two levels of abstraction.)*

**`FB-RAT-PKG-008` — ACCEPTED.** *"A public release includes manifest, definition, schemas,
documentation, compatibility declaration, license, provenance, tests or an explicit test-coverage
declaration, and signature."* This is a **public-release completeness bar**, distinct from — and
strictly stricter than — the byte-level container requirement in §3: `floop-container-format
.v1.json` marks only `manifest.json` and `definition.json` REQUIRED at the container level (a
private or draft package need not carry documentation or a license), while this decision adds, for
any package a producer intends to publish:

- `manifest.json` and `definition.json` (already container-REQUIRED, §3);
- `schemas/` (input/output schemas the package's typed ports declare);
- `docs/` (human-readable documentation, at minimum a README);
- `compatibility.json` (the compatibility declaration `LOOP_COMPATIBILITY_CONTRACT.md` governs in
  detail — not restated here);
- `LICENSE`;
- `provenance.json`;
- tests **or** an explicit test-coverage declaration — resolved in favor of fixtures for P0 by
  `10_DUAL_VALIDATION_ADDENDUM.md` §F ("LOOP-004 vs PKG-008... LOOP-004 (fixtures) wins for P0; the
  bare declaration is allowed only for marketplace listings, surfaced as a visible no-tests
  badge"), which is exactly what rule code `LOOP-TEST-001` (`loop-validation-rules.v1.json`)
  enforces;
- a signature (§11).

A package missing any of the above MAY still be a valid, importable LoopPackage under §3's minimal
container rule — it simply MUST NOT be published to the marketplace or an unlisted release channel
until this bar is met. `compatibility.json` and `provenance.json` are not separately enumerated in
`floop-container-format.v1.json`'s `requiredTopLevelLayout` map; per that registry's general
`packageInventory` rule (§3), both are classified `semanticFile` in the manifest inventory — they
are authored declarations, not regenerable.

## 11. Signatures and trust

*(Merges draft §7, "Signatures and trust," with draft §13, "Signature scope" — the same signing
requirement stated first at the level of the overall trust posture, then again at the level of
exactly what bytes a signature binds.)*

**`FB-RAT-PKG-005` — ACCEPTED.** *"Published releases MUST be signed by a publisher key; local
unsigned packages remain importable only with a visible warning and narrower default authority."*
The signature suite is Ed25519 over the content-digest-plus-release-identity tuple below, per
`floop-container-format.v1.json`'s `signatures/` entry and `10_DUAL_VALIDATION_ADDENDUM.md` §D; the
detached signature file is `loop-package-signature.v1` (schema not yet written as of this document
— WP-1L schema-authoring scope beyond this file).

A release signature MUST bind exactly: `loopId`, `semanticVersion`, `packageContentDigest` (§4),
`canonicalizationVersion` (§4), the publisher's key fingerprint, and the release channel. It MUST
NOT cover mutable listing content (star counts, review text, download counts) — those change after
publication and are never part of what a signature attests to. Key rotation MUST preserve
verification of prior signatures through either a signed key history or a marketplace transparency
record; a revoked key MUST NOT retroactively alter bytes already installed locally, but every
installation surface MUST show revocation status and require an explicit user choice before any
*new* activation under a revoked key.

| From state | Event | To state | Notes |
|---|---|---|---|
| `PACKAGE_ACQUIRED` | Package carries a `signatures/` entry | `SIGNATURE_PRESENT` | |
| `PACKAGE_ACQUIRED` | Package carries no `signatures/` entry | `UNSIGNED` | |
| `SIGNATURE_PRESENT` | Verifier checks the signature against the bound tuple above, device is online | `SIGNATURE_VALID_REVOCATION_CHECKED` or `SIGNATURE_INVALID` | Revocation status is checked whenever connectivity allows it. |
| `SIGNATURE_PRESENT` | Same check, device is offline | `SIGNATURE_VALID_REVOCATION_UNKNOWN` | Import MAY proceed, but revocation freshness is recorded as unknown — never asserted good. |
| `SIGNATURE_INVALID` | (terminal for this import attempt) | `REJECTED` | A signature that fails cryptographic verification is rejected outright — it is never treated as equivalent to `UNSIGNED`. |
| `UNSIGNED` | User is shown the unsigned-package warning and proceeds | `IMPORTED_UNSIGNED_NARROWED_GRANTS` | Default authority grants are narrowed and origin is recorded as unsigned (`CAPABILITY_AUTHORITY_MODEL.md` §5, default deny, is the general mechanism this narrows against — not restated here). |
| `SIGNATURE_VALID_REVOCATION_CHECKED` | Key found revoked | `IMPORTED_REVOKED_KEY_FLAGGED` | Local bytes already installed under a prior activation are unaffected; a *new* activation attempt MUST show revocation status and require explicit user choice. |
| `SIGNATURE_VALID_REVOCATION_CHECKED` | Key not revoked | `IMPORTED_SIGNED_VERIFIED` | |
| `SIGNATURE_VALID_REVOCATION_UNKNOWN` | User proceeds | `IMPORTED_SIGNED_REVOCATION_UNKNOWN` | Distinct from `IMPORTED_SIGNED_VERIFIED` — the UI MUST NOT present these as equivalent trust levels. |

## 12. Versioning and immutable releases

**`FB-RAT-PKG-006` — ACCEPTED.** *"A published LoopRelease is immutable; fixes or changes require
a new semantic version and digest."* Semantic versioning governs public releases. Package schema
version and loop-engine compatibility (`engineVersion`, `capability-ids.v1.json`) are versioned
independently of each other and of the package's own `semanticVersion` — a package MAY bump its own
version without any `engineVersion` range change, and vice versa. A breaking change to input/
output contracts, node semantics, requested authority, or result shape MUST bump the major version,
unless an explicit, machine-checkable migration contract proves compatibility across the boundary.

This is `LOOP-ID-002` (`loop-validation-rules.v1.json`: *"Attempted mutation of installed package
bytes... installed releases are immutable; create a forked draft to edit"*) at the install-time
enforcement layer, and `LOOP-COMPAT-002` (*"Update widens authority, weakens a verifier, adds an
external target, adds a secret slot, increases budget, or adds a destructive path without fresh
approval"*) at the update-time enforcement layer — an update that would otherwise look like a
same-major-version patch still requires fresh user approval if it crosses any of those lines,
exactly as `LOOP_DUAL_SURFACE_ARCHITECTURE.md`'s cross-references already establish. Neither rule
is re-decided here; both are cited because §11's binding narrowing (§9) and this section's
immutability are the two invariants an update MUST satisfy simultaneously.

## 13. Package and resource limits

*(Merges draft §10, "Resource limits," with draft §15, "Package limits" — the same size/count
ceiling requirement stated twice under different numbers.)*

**`FB-RAT-PKG-010` — EXPERIMENTAL, not yet ratified as a fixed policy** (`docs/non_ratified/
EXPERIMENTAL_DECISIONS.md`). *"Treat package and asset limits as measured marketplace policy, not
hard semantic constraints, until real packages are observed."* This document does not hard-code a
marketplace size limit. What it does state as **normative regardless of `FB-RAT-PKG-010`'s
experimental status**: every importer MUST enforce configurable safe limits for total size, file
count, nested schema depth, decompressed size, image dimensions, and text length, derived from
device resources and policy; a package exceeding a local safety limit MUST be blocked with an
actionable report, never partially imported. The parser MUST always defend against zip bombs and
resource exhaustion — that structural obligation is not calibratable and does not wait on
`FB-RAT-PKG-010`'s graduation.

The concrete numeric ceilings already frozen at the container level —
`maxCompressionRatioPerEntry` (100), `maxUncompressedTotalBytes` (512 MiB), `maxEntryCount`
(20,000) in `floop-container-format.v1.json` — are exactly the kind of calibratable parameter
`FB-RAT-PKG-010` describes: that registry's own `notes` field says as much (*"the ratio/total/count
ceilings are calibratable... the STRUCTURAL rules above them... are normative and not
calibratable"*), which is the same normative/calibratable split this section draws for marketplace
policy generally. Public marketplace-specific limits (as opposed to the container-level parser
defenses above) MAY evolve as real packages are observed, per `FB-RAT-PKG-010`'s calibration
condition; the structural defenses MUST NOT wait for that data.

## 14. Cross-references and open items

**Decision IDs cited in this document:** `FB-RAT-PKG-001` (§2), `FB-RAT-PKG-002` (§6),
`FB-RAT-PKG-003` (§6, REJECTED), `FB-RAT-PKG-004` (§4), `FB-RAT-PKG-005` (§11), `FB-RAT-PKG-006`
(§12), `FB-RAT-PKG-007` (§7), `FB-RAT-PKG-008` (§10), `FB-RAT-PKG-009` (§8), `FB-RAT-PKG-010` (§13,
EXPERIMENTAL), `FB-RAT-WEB-005` (§9). `FB-RAT-LBX-010` (§3) and `FB-RAT-LBX-001`/`FB-RAT-LBX-002`
(header) are cited as already-ratified context, not re-decided here. `FB-RAT-COM-002` (§2, §8) and
`FB-RAT-COM-005` (§4) are cited as the `COMMON_CONVENTIONS.md` parents this document's package- and
node-identity rules specialize, per `10_DUAL_VALIDATION_ADDENDUM.md` §F's duplicate-decision
reconciliation guidance — not restated or re-ratified here.

**Registries reconciled, not redefined:** `floop-container-format.v1.json`,
`semantic-digest.v1.json`, `canonicalization.v1.json` (§3–§5); `loop-validation-rules.v1.json`
(rule codes `LOOP-ID-001`/`002`, `LOOP-PKG-001`/`002`/`003`/`004`/`006`/`007`/`008`,
`LOOP-COMPAT-002`, `LOOP-TEST-001`, `LOOP-CAP-002` — §6–§8, §10, §12); `capability-ids.v1.json`
(`fb.network.egress`, `fb.secret.use`, `engineVersion` — §7, §9, §12). This document does not
duplicate their contents; a conflict between this document's prose and a registry's data is this
document's error, and the registry wins, per `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`.

**Sibling documents this document defers to and does not restate:**
`LOOP_DUAL_SURFACE_ARCHITECTURE.md` (§2–§3, §9–§10 of this document); `CAPABILITY_AUTHORITY_MODEL.md`
(§9, §11 of this document); `LOOP_IMPORT_ACTIVATION_CONTRACT.md`, `LOOP_COMPATIBILITY_CONTRACT.md`,
`LOOP_MARKETPLACE_CONTRACT.md`, and `LOOP_FORK_LINEAGE_CONTRACT.md` — none of these existed under
`docs/ratified/loops/` as of this document; a reader who needs their content should look for them as
separate WP-1L outputs, not expect this document to have absorbed them.

**Not yet written, forward-pointed only:** the `loop-package-manifest` and
`loop-package-signature.v1` JSON Schemas and `LoopPackageContracts.kt`
(`inputs/dual_surface/DUAL_TRACEABILITY_MATRIX.md` names all three as this decision family's
implementation artifacts) — schema and Kotlin-contract authoring is WP-1L scope beyond this
document, not performed here.

**No new decision ID is proposed by this document.** Every gap this file needed to close — the
three incompatible layouts, the declarative-safety correction, `FB-RAT-WEB-005`'s misplaced source
citation — was closable by cross-referencing an already-frozen registry or an already-ratified
decision; none required inventing a `FB-RAT-PKG-NEW-*` slot.
