# Loop Builder End-to-End Journeys

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** WORKED EXAMPLES — this document originates no `FB-RAT-*` decision of its own (per
the WP-1L assignment note for this file: *"this file is worked example journeys illustrating the
other specs' decisions in sequence, not itself a decision source"*). It is normative only in the
narrow sense that every state name, object name, and decision citation below MUST match what the
sibling documents it illustrates already ratify or freeze — a journey that drifted from that
vocabulary would silently document a product that does not exist. **Scope:** twenty-two concrete,
end-to-end walkthroughs of the dual-surface loop builder, each instantiating one or more of the
frozen/ratified state machines and decisions this corpus defines elsewhere, so a reader can see
those decisions operate in sequence rather than only as isolated rules.

This document does **not** define the eleven-state import/activation machine, the run-state
machine, the tap connection grammar, the compatibility outcome levels, the package build/signing
pipeline, the moderation state table, the result-sharing pipeline, or the phone persistence/pointer
tables it walks through below — every one of those is owned, defined, and tabled in full by the
sibling document named at first use, and is only *instantiated* here with one journey's concrete
data. A conflict between this document's telling of a journey and a sibling document's own
definition of the mechanism that journey exercises is this document's error, and the sibling
document wins.

## 1. Purpose and how to read this document

Each journey below answers one question: *given the objects and state machines this corpus already
ratifies, what does one concrete person actually do, in order, and what does the system do back?*
A journey is not a new specification surface — it is evidence that the specification surfaces
already written compose into a usable sequence. Where a journey's own central mechanism is a state
machine already frozen elsewhere, that journey renders **its own concrete path** through that
machine as an explicit from-state/event/to-state table (never a bare arrow chain, per this source
pack's own docx-conversion note carried forward from every sibling document in this corpus) — the
table is this journey's *instance* of the machine, not a second definition of it. Where a journey
touches a second frozen mechanism only in passing, that mechanism is named and cited, not
re-tabulated, for the same reason `LOOP_COMPATIBILITY_CONTRACT.md` §1 declines to re-derive the
import/activation machine it feeds: duplicating another section's table here would be exactly the
kind of second, competing restatement this work package's other corrections spend their effort
removing.

## 2. Note on this document's structure

The extracted first-pass/second-pass draft this document replaces does not carry this corpus's
usual defect (duplicate section numbers, or duplicate topics restated under a later, unrelated
numeral). Per `10_DUAL_VALIDATION_ADDENDUM.md` §B2, this file's specific defect is **two colliding
numbering schemes**: a first pass labelling fourteen journeys `J1`–`J14` (with an incidental
docx-list-numbering artifact — steps numbered 99–124 — running across two of those fourteen
entries, `J1` and `J3`, unrelated to any cross-reference elsewhere in this pack — the same kind of
whole-document list-numbering artifact `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9 already documents and
resolves for its own golden-conformance checklist), followed by a second pass labelling eight
further journeys `Journey 9`–`Journey 16` — a second scheme that restarts its own count at 9 rather
than at 1, so `J9`–`J14` and `Journey 9`–`Journey 14` each separately claim labels 9 through 14 for
six pairs of **different** journeys. Both schemes are deleted below in favor of one sequential
`Journey 1`–`Journey 22`.

**Item order in the source is intact; only the labels are corrupted.** Per this work package's
file-specific instruction, this document does not reorder content and does not merge journeys that
cover overlapping ground — that merge treatment is what sibling documents in this corpus apply to a
literal duplicate-numbered *section* (the same topic restated under two numbers, nothing new
between the two passes); this file's defect is a labelling collision over twenty-two genuinely
distinct worked examples, five pairs of which happen to dramatize the same underlying scenario a
second time with more concrete detail. All twenty-two are kept, in the order the source presents
them, renumbered sequentially:

| Source label | Source pass | Topic | This document's journey | Overlaps with |
|---|---|---|---|---|
| `J1` | first (§ list, steps 99–111) | Phone: describe and create | **Journey 1** | Journey 15 |
| `J2` | first | Phone: manual branching loop | **Journey 2** | — |
| `J3` | first (§ list, steps 112–124) | Browser to phone transfer | **Journey 3** | Journey 16 |
| `J4` | first | Marketplace browse and install | **Journey 4** | — |
| `J5` | first | Missing capability recovery | **Journey 5** | Journey 22 (different axis — capability vs. model class) |
| `J6` | first | Offline file import | **Journey 6** | — |
| `J7` | first | Update with authority widening | **Journey 7** | — |
| `J8` | first | Fork and upstream comparison | **Journey 8** | Journey 18 |
| `J9` | first | Share a result | **Journey 9** | Journey 19 |
| `J10` | first | Malicious package | **Journey 10** | Journey 21 |
| `J11` | first | Marketplace outage | **Journey 11** | Journey 20 (different unavailable service — marketplace vs. browser) |
| `J12` | first | Interrupted import | **Journey 12** | — |
| `J13` | first | Phone process death during authoring | **Journey 13** | — |
| `J14` | first | External display pointer mode | **Journey 14** | — |
| `Journey 9` | second | Phone-only loop creation in a moving day | **Journey 15** | Journey 1 |
| `Journey 10` | second | Web-authored hardware loop transferred to phone | **Journey 16** | Journey 3 |
| `Journey 11` | second | Marketplace release delisted after a safety report | **Journey 17** | — (genuinely new — no first-pass counterpart) |
| `Journey 12` | second | Upstream change applied to a local fork | **Journey 18** | Journey 8 |
| `Journey 13` | second | Result sharing with deliberate redaction | **Journey 19** | Journey 9 |
| `Journey 14` | second | Browser disappears after transfer | **Journey 20** | Journey 11 (different unavailable service) |
| `Journey 15` | second | Malicious archive rejected safely | **Journey 21** | Journey 10 |
| `Journey 16` | second | Model substitution cannot preserve semantics | **Journey 22** | — (genuinely new — no first-pass counterpart) |

No other document in this corpus cites `LOOP_END_TO_END_JOURNEYS.md` by section or journey number
— it is not a citation target in `DUAL_RATIFICATION_REGISTER.md`, and this file itself carries no
internal cross-references to its own old numbering — so no dangling reference elsewhere in the
repository needs re-anchoring against the table above; the table exists for a reader tracing a
citation from the original handoff pack's Appendix E, not from this repository.

**Corrections applied, beyond the relabelling above:**

1. **The incidental step-number list (99–124) is removed.** Every journey's own step list restarts
   at 1; nothing outside this file ever cited those numbers (`10_DUAL_VALIDATION_ADDENDUM.md` §H
   documents the identical defect, and identical fix, for `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9's
   golden-conformance checklist).
2. **Every journey is re-told against this corpus's now-frozen vocabulary instead of the draft's
   ad hoc phrasing.** Concretely: the eleven-state import/activation machine
   (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3) for every journey that begins by acquiring a package
   from outside the phone; the run-state machine (`LOOP_ENGINEERING_SPEC_V2.1.md` §9) for every
   journey's execution phase, whether the loop was imported or authored on-phone; the five-view
   information architecture, tap connection grammar, and persistence/pointer tables
   (`LOOP_PHONE_AUTHORING_SPEC.md` §3, §7, §10, §13) for phone-authoring-centered journeys; the
   compatibility outcome levels and remediation enum (`LOOP_COMPATIBILITY_CONTRACT.md` §3, §11);
   the package build, signing, and moderation tables (`LOOP_WEB_STUDIO_SPEC.md` §9,
   `LOOP_PACKAGE_SPEC.md` §11, `LOOP_MARKETPLACE_CONTRACT.md` §10); and the result-sharing pipeline
   and verification-claim labels (`LOOP_RESULT_SHARING_CONTRACT.md` §3, §6). §3 below indexes each
   of these by name once rather than repeating the index at every journey.
3. **`hardware.serial.monitor` (`J5`) is corrected to the registry capability ID
   `fb.device.serial_read`** (`capability-ids.v1.json` — `EXECUTE_REVERSIBLE`) — the draft's phrase
   is not a valid capability ID under this corpus's frozen reverse-DNS namespace, and a node
   requesting an unregistered ID fails `LOOP-CAP-001` by construction.
4. **"Runtime-attested" (old `Journey 13`) is corrected to `SELF_SIGNED_RECEIPT`.**
   `LOOP_RESULT_SHARING_CONTRACT.md` §6 renames `RUNTIME_ATTESTED` to `SELF_SIGNED_RECEIPT` and
   narrows the claim it is permitted to carry (pseudonymous key continuity only, never runtime or
   device authenticity) — Journey 19 below uses the corrected label and the corrected claim
   boundary, not the draft's original wording.
5. **Free-prose recovery options (`J5`, old `Journey 16`) are mapped onto the closed
   `remediationType` enum** `LOOP_COMPATIBILITY_CONTRACT.md` §11 already defines
   (`BIND_RESOURCE`, `INSTALL_TRUSTED_CAPABILITY`, `SELECT_REMOTE_TARGET`,
   `CHOOSE_ALTERNATE_RELEASE`, `ABANDON_IMPORT`, and so on) rather than left as open text a reader
   cannot check against anything.
6. **Every bare narrative sequence is re-flowed into an explicit from-state/event/to-state table**,
   per the source pack's own docx-conversion note ("re-flow state machines into transition tables")
   that every sibling document in this corpus already applies.

## 3. Shared state machines and vocabulary (cross-referenced, not redefined)

| Mechanism | Owning document | Used by (this document's journeys) |
|---|---|---|
| Eleven-state import/activation machine (`ACQUIRING` … `INSTALLED`, terminals `CANCELLED`/`REJECTED_UNSAFE`/`REJECTED_POLICY`/`BLOCKED_INCOMPATIBLE`/`FAILED_SAFE`) | `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3 | 3, 4, 5, 6, 7, 8, 10, 12, 16, 17, 18, 21, 22 |
| Run-state machine (`CREATED`, `PREFLIGHT`, `WAITING_BINDING`, `WAITING_AUTHORITY`, `READY`, `RUNNING`, `WAITING_USER`, `SUSPENDED`, `CANCELLING`, `SUCCEEDED_VERIFIED`, `SUCCEEDED_UNVERIFIED`, `STOPPED_BUDGET`, `STOPPED_POLICY`, `FAILED_SAFE`, `FAILED_SIDE_EFFECTS_POSSIBLE`, `TARGET_STATE_UNKNOWN`, `CANCELLED`) | `LOOP_ENGINEERING_SPEC_V2.1.md` §9 (the general transition table between these named states is that document's own scope, not yet written as of this document — each journey below states only the concrete path it takes) | 1, 2, 6, 8, 9, 15, 16, 18, 19 |
| Five-view information architecture (Intent, Stage, Graph, Node Sheet, Run) | `LOOP_PHONE_AUTHORING_SPEC.md` §3 | 1, 2, 13, 14, 15 |
| Tap connection grammar (`IDLE` … `COMMITTED`) | `LOOP_PHONE_AUTHORING_SPEC.md` §7 | 2, 15 |
| Pointer/external-display layout toggle (`PHONE_LAYOUT` ⇄ `POINTER_LAYOUT`) | `LOOP_PHONE_AUTHORING_SPEC.md` §10 | 14 |
| Draft persistence/recovery (`DRAFT_CLEAN`, `DIRTY_JOURNALED`, `INTERRUPTED(<op>)`) | `LOOP_PHONE_AUTHORING_SPEC.md` §13 | 13 |
| Compatibility outcome levels (`COMPATIBLE` … `UNSUPPORTED`) and `remediationType` enum | `LOOP_COMPATIBILITY_CONTRACT.md` §3, §11 | 5, 16, 22 |
| Package signature/trust states | `LOOP_PACKAGE_SPEC.md` §11 | 3, 6, 10, 21 |
| Package build pipeline (`DRAFT` … `BUILD_COMPLETE`) | `LOOP_WEB_STUDIO_SPEC.md` §9 | 3, 16 |
| Marketplace moderation states (`PENDING` … `TAKEDOWN`/`KEY_REVOKED`) | `LOOP_MARKETPLACE_CONTRACT.md` §10 | 4, 17 |
| Result-sharing pipeline (`SHARE_INITIATED` … `RECEIPT_STORED`) and verification labels | `LOOP_RESULT_SHARING_CONTRACT.md` §3, §6 | 9, 19 |
| Dual-surface failure model (per-condition required behavior) | `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8 | 6, 11, 12, 16, 20 |
| Fork/lineage (identity, comparison, selective apply — decision text only; `LOOP_FORK_LINEAGE_CONTRACT.md` itself is a sibling WP-1L output not yet written as of this document) | `DUAL_RATIFICATION_REGISTER.md` `FB-RAT-LIN-001`–`007` | 8, 18, 20 |

---

## Journey 1 — Phone: describe and create

**Setting.** A solo developer creates a repository repair loop entirely on the phone, away from a
desk, with no import involved — the loop is authored fresh (`LOOP_PHONE_AUTHORING_SPEC.md` §6,
entry point 1, "Describing what is wanted, in natural language (Distiller)"). Because nothing is
imported, this journey exercises the **run-state machine**, not the import/activation machine —
the eleven-state machine's `FB-RAT-IMP-*` scope is the `PACKAGE_IMPORT` profile specifically
(`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §2); a loop that was never packaged has nothing to import.

| From state | Event | To state | Notes |
|---|---|---|---|
| *(none)* | User opens New Loop → Describe and states objective, repository class, a no-push constraint, and a test requirement (Intent View, `LOOP_PHONE_AUTHORING_SPEC.md` §3.1). Distiller proposes stages and typed slots as a draft plus explanation — it MUST NOT activate anything automatically (`LOOP_PHONE_AUTHORING_SPEC.md` §3.1, §6). | `CREATED` | Draft only; no capability request has been reviewed yet. |
| `CREATED` | User reviews the Distiller-proposed semantic diff and requested capabilities (`FB-RAT-PHN-006`'s `BASE_REVISION`→`PROPOSED`→`APPLIED` table, `LOOP_PHONE_AUTHORING_SPEC.md` §8 — not re-tabulated here) and approves it as one undoable transaction; opens Stage View and adds a human-decision node before the write stage; configures a bounded retry cycle on the diagnosis branch (`LOOP-GRAPH-004`, no cycle without a statically visible bound). | `CREATED` (draft revised) | Stage View is the primary editing surface for this (`FB-RAT-PHN-002`); the human gate sits before any `MODIFY_DRAFT`/`EXECUTE_REVERSIBLE`-rung write. |
| `CREATED` | User requests validation; the validator reports an unbound test-runner slot (a binding placeholder per `LOOP_PACKAGE_SPEC.md` §9, not yet resolved to a local resource) and the user fixes it; user runs the fixture simulation. | `PREFLIGHT` | Simulation uses fixtures, not a live target (`LOOP_PACKAGE_SPEC.md` §3 `fixtures/`) — evidence against the fixture only, never a live-target claim. |
| `PREFLIGHT` | User binds a local model and a remote CI target for the two remaining slots. | `WAITING_BINDING` → binding resolved | Local/cloud/remote provenance is recorded per binding (`FB-RAT-IMP-004`'s general local-resolution principle, applied here to a phone-authored draft rather than an imported package). |
| `WAITING_BINDING` | User reviews the authority request, grouped by bucket (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6): approves **Read** (`fb.repo.read`) and **Write** (`fb.repo.write_draft`/`fb.repo.commit`) for one project; explicitly denies **External** (`fb.repo.push_remote`, `EXECUTE_EXTERNAL`) — no push. | `WAITING_AUTHORITY` → `READY` | `FB-RAT-IMP-005`: a binding profile MAY grant less authority than requested but MUST NOT silently grant more; denial of an optional capability is a legitimate outcome, not an error. |
| `READY` | Because this loop requests write authority, first-activation simulation/preflight is required before the real run (`FB-RAT-PHN-009`). It already passed at `PREFLIGHT` above; the user activates and runs. | `RUNNING` | Run View shows the current node, completed nodes, retries, and receipts (`LOOP_PHONE_AUTHORING_SPEC.md` §3.5, §9). |
| `RUNNING` | User inspects the run timeline; a test failure triggers the bounded diagnosis retry (two attempts, per the cycle bound set above); a notification requests patch review; the user accepts selected hunks (a `PROPOSE`-rung, then `MODIFY_DRAFT`/`EXECUTE_REVERSIBLE`-rung action) and the loop reaches its declared verifier. | `SUCCEEDED_VERIFIED` | Every action above remains possible without a monitor, pointer, or browser (`FB-RAT-PHN-007`, §15.1). |

**Failure variants** (named in the source, preserved): Distiller output invalid or the local model
lacks structured output (Distiller MUST still produce an inspectable diff or fail visibly, never a
silent malformed draft, `FB-RAT-PHN-006`); CI target unavailable (binding stays unresolved,
`WAITING_BINDING`); user denies write (authority review routes to a narrower grant or, if write is
required, blocks activation, `FB-RAT-IMP-005`'s principle applied here); test verifier unavailable
(the run's `correctnessEvidence` records the verifier as `UNAVAILABLE`, never a guessed result —
`LOOP_RESULT_SHARING_CONTRACT.md` §6 states the same honesty rule generally).

**Cites:** `FB-RAT-PHN-001`, `-002`, `-006`, `-007`, `-009`; `LOOP_ENGINEERING_SPEC_V2.1.md` §9 run
states; `LOOP-GRAPH-004`; `fb.repo.read`/`fb.repo.write_draft`/`fb.repo.commit`/`fb.repo.push_remote`
(`capability-ids.v1.json`). See Journey 15 for the same scenario with a fuller graph.

## Journey 2 — Phone: manual branching loop

**Setting.** A user builds a branching repository-repair loop step by step, connecting nodes with
the tap grammar, with no Distiller involvement — the phone-completeness counter-example to Journey
1's AI-assisted path.

| From state | Event | To state | Notes |
|---|---|---|---|
| `IDLE` | User selects the "run tests" stage as the connection source. | `SOURCE_SELECTED` | `LOOP_PHONE_AUTHORING_SPEC.md` §7 (`FB-RAT-PHN-004`); no draft mutation yet. |
| `SOURCE_SELECTED` | User taps "Connect from here." | `AWAITING_DESTINATION` | |
| `AWAITING_DESTINATION` | User selects the gateway destination. | `DESTINATION_CHOSEN` | |
| `DESTINATION_CHOSEN` | User labels the outcome `tests_pass`. | `LABELED` | Repeated for `tests_fail` and `cannot_reproduce` as two further passes through this same table — not three separate mechanisms. |
| `LABELED` | Gateway requires a condition for `tests_fail`; user defines it. | `CONDITIONED` | Skipped for `tests_pass`/`cannot_reproduce` if no condition is required (falls through to `PREVIEWING`). |
| `CONDITIONED` | User requests preview of affected paths. | `PREVIEWING` | |
| `PREVIEWING` | User commits. | `COMMITTED` | New draft revision; the only mutating transition in the table (`LOOP_PHONE_AUTHORING_SPEC.md` §13). |

On the `tests_fail` branch the user adds a bounded diagnosis retry cycle; the UI MUST warn if the
three outcomes are non-exhaustive or omit a default/failure route (`LOOP-GRAPH-003`) or if the
retry cycle has no statically visible bound (`LOOP-GRAPH-004`). Drag-a-wire is never required to
complete this journey — it is rejected as the primary connection mechanism (`FB-RAT-PHN-003`) and,
where present at all, is strictly additive over the table above. Stage View MUST remain readable
and completable one-handed throughout (`FB-RAT-PHN-007` §15.3): taps, sheets, and text/voice input
only, no precision gesture or chorded shortcut.

**Cites:** `FB-RAT-PHN-003`, `-004`, `-007`; `LOOP-GRAPH-003`, `LOOP-GRAPH-004`;
`LOOP-VALIDATE-NO-CONCURRENCY` (single active token — the retry cycle transfers the token through
itself and back, never forking it).

## Journey 3 — Browser to phone transfer

**Setting.** A creator builds a draft in Web Studio and transfers the signed package to a phone by
QR. This is the canonical `PACKAGE_IMPORT` profile walkthrough — the eleven-state machine in full.

| From state | Event | To state | Receipt |
|---|---|---|---|
| *(none)* | Creator defines abstract `model`/`repository`/`ci` binding slots in Web Studio (`FB-RAT-WEB-005`), adds fixtures and fault injection (`FB-RAT-WEB-006`), and requests a build. The build pipeline runs `DRAFT`→`STATICALLY_VALIDATED`→`CANONICALIZED`→`FIXTURES_TESTED`→`DOCUMENTATION_CHECKED`→`CONTENT_SCANNED`→`ASSEMBLED`→`BUILD_COMPLETE` (`LOOP_WEB_STUDIO_SPEC.md` §9, not re-tabulated here). Creator signs the release on the phone (Web Studio does not sign for publication itself, §9) and publishes to an unlisted URL; the QR carries that URL plus the expected `packageContentDigest`, never the package bytes (`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §7). | *(package built, signed, published)* | build receipt |
| *(none — user action)* | Phone user scans the QR. | `ACQUIRING` | none yet |
| `ACQUIRING` | Phone downloads the exact bytes and computes `packageContentDigest`, comparing it against the QR-carried expected digest. | `SNAPSHOTTED` | lightweight event |
| `SNAPSHOTTED` | Container manifest, archive safety, digest, and signature are verified locally — `FB-RAT-IMP-003`: server/marketplace-side validation is advisory, never authoritative, so this runs regardless of how trusted the source URL looked. | `CONTAINER_VERIFIED` | lightweight event |
| `CONTAINER_VERIFIED` | Schemas, graph structure, and engine policy validate against `loop-validation-rules.v1.json`. | `PARSED_VALIDATED` | validation report |
| `PARSED_VALIDATED` | Compatibility is evaluated against this phone's engine version, capabilities, and distribution flavor. | `COMPATIBILITY_EVALUATED` | compatibility report |
| `COMPATIBILITY_EVALUATED` | Safe preview shown: identity, objective, stages, side effects, compatibility, publisher, tests. | `PREVIEWED` | none — read-only |
| `PREVIEWED` | User confirms; binds local resources for the abstract slots declared in Web Studio. | `WAITING_BINDINGS` → `WAITING_AUTHORITY` | binding profile digest |
| `WAITING_AUTHORITY` | User reviews and grants the requested authority. | `READY_TO_SIMULATE` | grant record(s) |
| `READY_TO_SIMULATE` | Simulation runs against the package's own fixtures. | `INSTALLABLE` | simulation result |
| `INSTALLABLE` | User confirms install. | `INSTALLED` | full activation receipt |
| `INSTALLED` | User runs the loop; Run View operates independent of any browser connection. | *(run-state machine, `LOOP_ENGINEERING_SPEC_V2.1.md` §9, `CREATED`→…→terminal)* | `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8: "Browser unavailable" never blocks an installed package. |

**Cites:** `FB-RAT-LBX-001`, `-002`; `FB-RAT-WEB-001`, `-005`, `-006`; `FB-RAT-PKG-001`, `-004`,
`-005`; `FB-RAT-IMP-001`–`003`; `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §7 transfer channels, §8 failure
model. See Journey 16 for the same transfer with a concrete hardware-loop example.

## Journey 4 — Marketplace browse and install

**Setting.** A user filters the marketplace for a Kotlin, repository-safe, local-drafting,
CI-verified, no-push loop and installs it.

| From state | Event | To state | Notes |
|---|---|---|---|
| *(none)* | User applies discovery filters — category, language, local/offline ability, CI requirement, read/write/destructive authority, engine version, test coverage — derived from declared manifest data, not free text (`LOOP_MARKETPLACE_CONTRACT.md` §7, `FB-RAT-MKT-011`). | *(browsing)* | no local state change |
| *(none)* | User inspects one listing: the immutable release, test coverage, capability classes (`capability-ids.v1.json` `category` enum), publisher key/verification level (`FB-RAT-MKT-005`), reviews (`FB-RAT-MKT-006`, where the optional backend carries them), and any shared results labelled per `LOOP_RESULT_SHARING_CONTRACT.md` §6's four-value scheme. | *(browsing)* | none |
| *(none — user action)* | User selects Import. | `ACQUIRING` | Publisher reputation never bypasses this — `FB-RAT-IMP-003`: every package is revalidated locally, including marketplace-signed ones. |
| `ACQUIRING` … `INSTALLED` | Import proceeds through the full eleven-state machine exactly as Journey 3's table, with no shortcut for a signed or highly-reviewed publisher. | `INSTALLED` | full activation receipt |

**Cites:** `FB-RAT-MKT-005`, `-006`, `-011`; `FB-RAT-IMP-001`–`003`; the eleven-state machine per
Journey 3 (not re-tabulated).

## Journey 5 — Missing capability recovery

**Setting.** A package requests `fb.device.serial_read` (`EXECUTE_REVERSIBLE`; the source draft's
`hardware.serial.monitor` is not a valid ID under this corpus's frozen namespace — see §2
correction 3), and this phone has no provider for it.

| From state | Event | To state | Notes |
|---|---|---|---|
| `PARSED_VALIDATED` | Compatibility evaluation finds the capability axis unsatisfiable locally — a required dependency missing, but potentially satisfiable by a local action. | `COMPATIBILITY_EVALUATED` (`BLOCKED`) | `FB-RAT-CMP-004`: `BLOCKED` is the recoverable half of a hard blocker, distinct from `UNSUPPORTED`. |
| `COMPATIBILITY_EVALUATED` | No local action is taken before the user decides. | `BLOCKED_INCOMPATIBLE` | terminal — `FB-RAT-IMP-007`; report names the axis and the missing capability, per `LOOP_COMPATIBILITY_CONTRACT.md` §11. |

`BLOCKED_INCOMPATIBLE` has no outgoing edge in the import machine itself — recovery restarts a
fresh import attempt with a changed local or package state. The user's options are exactly the
closed `remediationType` set `LOOP_COMPATIBILITY_CONTRACT.md` §11 already defines, not open prose:

| `remediationType` | What the user does |
|---|---|
| `INSTALL_TRUSTED_CAPABILITY` | Install a trusted `fb.device.serial_read` provider through a channel independent of this package — never something the package itself fetches or installs (`LOOP_PACKAGE_SPEC.md` §6, the declarative-only content policy). |
| `SELECT_REMOTE_TARGET` | Choose a declared remote substitute for the node, if the package's substitution policy (`FB-RAT-CMP-003`) names one. |
| *(no closed enum value — see note)* | Import the package as an editable local draft and remove the offending node there. This is a **new draft**, not an edited installed release (`FB-RAT-IMP-006` only forks an *installed* release; a `BLOCKED_INCOMPATIBLE` package never reaches `INSTALLED`), so the result is unsigned local content carrying none of the original publisher's signature or provenance — the UI MUST present it as such, not as a lightly modified copy of the original. |
| `ABANDON_IMPORT` | Cancel; no partial installation or grant exists (`FB-RAT-IMP-002`, no execution before `INSTALLED`). |

**Cites:** `FB-RAT-CMP-003`, `-004`; `FB-RAT-IMP-006`, `-007`; `fb.device.serial_read`
(`capability-ids.v1.json`). No package fetches code to resolve this itself — `FB-RAT-PKG-002`/`-003`
forbid that entirely. Compare Journey 22, the same `BLOCKED` outcome on the model-class axis
instead of the capability axis.

## Journey 6 — Offline file import

**Setting.** A user receives a `.floop` file through Nearby/USB/file manager while offline and
imports it without connectivity.

| From state | Event | To state | Notes |
|---|---|---|---|
| `SNAPSHOTTED` | Digest and signature are verified against a cached publisher key; the device is offline, so revocation status cannot be freshly checked. | `SIGNATURE_VALID_REVOCATION_UNKNOWN` | `LOOP_PACKAGE_SPEC.md` §11: import MAY proceed, but revocation freshness is recorded as unknown, never asserted good — distinct from `SIGNATURE_VALID_REVOCATION_CHECKED`/`IMPORTED_SIGNED_VERIFIED`, and the UI MUST NOT present the two as equivalent trust levels. |
| `CONTAINER_VERIFIED` … `WAITING_AUTHORITY` | Import proceeds through validation, compatibility, preview, binding, and authority review exactly as Journey 3's table, with every binding resolved to a **local-only** resource — no CI, no cloud model, since the device is offline. | `WAITING_AUTHORITY` → `READY_TO_SIMULATE` | binding profile digest scoped to local-only providers |
| `READY_TO_SIMULATE` | Simulation runs against packaged fixtures (still local-only). | `INSTALLABLE` → `INSTALLED` | `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8: "Phone offline: local authoring, validation, supported simulation, and compatible execution MUST continue." |
| `INSTALLED` | User runs the loop entirely offline. | run-state machine, local targets only | |

**Cites:** `FB-RAT-PKG-005` (offline revocation-unknown path); `LOOP_DUAL_SURFACE_ARCHITECTURE.md`
§7 (`.floop` file, share sheet as transfer channels), §8 (offline row).

## Journey 7 — Update with authority widening

**Setting.** Installed `v1.2` requests read-only repository access. `v1.3` adds file writes.

| From state | Event | To state | Notes |
|---|---|---|---|
| `INSTALLED` (v1.2, `READ`-only grant) | Update to `v1.3` is available; the update adds `fb.repo.write_draft`/`fb.repo.commit` (`MODIFY_DRAFT`/`EXECUTE_REVERSIBLE`), which `LOOP-COMPAT-002` classifies as authority widening. | *(update preview shown)* | `FB-RAT-IMP-008`: an update widening capabilities requires a fresh approval; it MUST NOT reuse the v1.2 grant. |
| *(update preview shown)* | Update preview separates semantic graph changes, authority/side-effect changes, binding changes, compatibility changes, and publisher/signing changes (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §9) — the widening is visually separated from ordinary graph edits, not interleaved with them. | *(user reviews)* | |
| *(user reviews)* | User re-enters `WAITING_AUTHORITY` for v1.3 specifically and grants the new `MODIFY_DRAFT`/`EXECUTE_REVERSIBLE`-rung request. | `WAITING_AUTHORITY` → `READY_TO_SIMULATE` → `INSTALLABLE` | Existing bindings from v1.2 are reused only if still compatible and not broadened by the update. |
| `INSTALLABLE` | v1.3 installs **beside** v1.2 — it does not silently replace the active installation. | `INSTALLED` (v1.3), `INSTALLED` (v1.2, dormant) | `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §9: rollback switches the active installation/profile pointer; historical runs stay attached to the version they actually ran against. |

**Cites:** `FB-RAT-IMP-008`; `LOOP-COMPAT-002`; `FB-RAT-PKG-006` (immutable releases — v1.2's bytes
are never mutated by the update).

## Journey 8 — Fork and upstream comparison

**Setting.** A user edits a marketplace release on the phone; Fonebrew creates fork lineage; the
parent later releases an update.

| From state | Event | To state | Notes |
|---|---|---|---|
| `INSTALLED` (parent release) | User edits the installed loop on the phone. | *(forked draft)* | `FB-RAT-IMP-006`: editing an installed release creates a forked local draft; the installed bytes are never mutated in place. `FB-RAT-LIN-001`: the fork preserves origin `loopId`, release version, and package digest as lineage, while the draft carries a new identity. |
| *(forked draft)* | Parent publisher releases an update. | *(upstream update available)* | |
| *(upstream update available)* | User requests a comparison. | *(comparison shown)* | `FB-RAT-LIN-004`: the comparison separates node/edge, schema, capability, authority, budget, test, documentation, and compatibility changes as distinct dimensions, not one undifferentiated diff. |
| *(comparison shown)* | User selectively applies the verifier improvement, rejects the new cloud-model requirement (an authority/model-class widening the user does not want), and this selection is highlighted independently of ordinary graph edits. | *(selection made)* | `FB-RAT-LIN-005`: authority-widening changes are highlighted independently. |
| *(selection made)* | Fonebrew applies only the selected operations to the fork; **no automatic merge occurs**. | *(draft revised)* | `FB-RAT-LIN-003`, `FB-RAT-LIN-007` (deferred): comparison and selective application only, never an automatic graph merge. |
| *(draft revised)* | Draft is revalidated against `loop-validation-rules.v1.json` and its fixtures re-run. | *(validated)* | |
| *(validated)* | User publishes the derivative with attribution to the parent release. | `PUBLISHED` | `FB-RAT-LIN-002`, `-006`: attribution and license provenance are retained; publication declares parent release, fork reason, and authorship contributions. |

**Cites:** `FB-RAT-LIN-001`–`007`; `FB-RAT-IMP-006`. See Journey 18 for the same scenario with
concrete versions and a named applied-operation record.

## Journey 9 — Share a result

**Setting.** A user opens a terminal run and selects Share result, adding one verifier artifact.

| From state | Event | To state | Notes |
|---|---|---|---|
| *(terminal run)* | User selects **Share result** — `LOOP_RESULT_SHARING_CONTRACT.md` §2: no background queue or automatic upload path exists; only this explicit action starts the pipeline. | `SHARE_INITIATED` | |
| `SHARE_INITIATED` | Builder starts from an empty document, adding only default-allowlisted fields (§4: package/version/digest, terminal state, bucketed duration, local/cloud ratio, capability categories). | `ALLOWLISTED_SUMMARY_BUILT` | never starts from a raw log and redacts down |
| `ALLOWLISTED_SUMMARY_BUILT` | User adds one verifier artifact deliberately; coarse bucketing is applied to duration/retry/token fields. | `UNAPPROVED_FIELDS_REMOVED` | |
| `UNAPPROVED_FIELDS_REMOVED` | The added artifact and every default field are scanned for secrets/identifiers (`LOOP-PKG-001`'s pattern class, reused here). | `SECRET_SCANNED` | |
| `SECRET_SCANNED` | Evidence digests are normalized and hashed. | `EVIDENCE_NORMALIZED_HASHED` → `PREVIEWED` | Preview shows every included field, every redaction, and the destination. |
| `PREVIEWED` | User reviews the redactions and signs. | `CONSENT_OBTAINED` → `RECEIPT_SIGNED` | Signed with the on-device, non-exportable share-receipt key (`LOOP_RESULT_SHARING_CONTRACT.md` §6) — a different key from the publisher signing key. |
| `RECEIPT_SIGNED` | Publish. | `UPLOADED` → `RECEIPT_STORED` | |

Repository name, prompts, logs, and device identity remain excluded by construction (§5's
denylist), never redacted after the fact. See Journey 19 for the fuller redaction walkthrough,
including the corrected verification-claim labels.

**Cites:** `FB-RAT-RES-001`, `-002`, `-003`, `-006`; `FB-RAT-MKT-008`.

## Journey 10 — Malicious package

**Setting.** A package contains a path-traversal entry, a disguised native binary, a secret-like
token, and a capability the phone's registry does not recognize.

| From state | Event | To state | Notes |
|---|---|---|---|
| `ACQUIRING` | Bytes snapshotted. | `SNAPSHOTTED` | `packageContentDigest` computed over the declared bytes regardless of what they contain. |
| `SNAPSHOTTED` | Container verification finds a path-traversal entry and a file matching the forbidden-executable-payload class (`LOOP-PKG-002`/`-003`, `floop-container-format.v1.json` adversarial classes) and a secret-like token (`LOOP-PKG-001`). Any one of these alone is sufficient to reject. | `REJECTED_UNSAFE` | terminal, reachable from `ACQUIRING`…`CONTAINER_VERIFIED`; rejection record names every matched check. |

`REJECTED_UNSAFE` fires **before** `PARSED_VALIDATED`/`COMPATIBILITY_EVALUATED` ever run — the
package's declared capability mismatch (which would otherwise fail `LOOP-CAP-001`, an unregistered
capability ID, at build/validation time) never gets the chance to independently register as a
finding, because the archive-safety rejection above already terminates the flow first. No content
executes at any point (`FB-RAT-IMP-002`); the parser produces stable, rule-coded findings, not
prose; the user MAY report the source (`LOOP_MARKETPLACE_CONTRACT.md` §10 reporting, where the
package arrived through a marketplace channel at all).

**Cites:** `FB-RAT-IMP-002`; `LOOP-PKG-001`, `-002`, `-003`; `LOOP-CAP-001`. See Journey 21 for the
container-level variant (decompression-ratio bomb, correct declared media type).

## Journey 11 — Marketplace outage

**Setting.** The marketplace service is unreachable; the user keeps working.

| Condition | Required behavior |
|---|---|
| Marketplace unavailable | Direct `.floop` file, Git, and QR/URL package transfer remain usable wherever the bytes are already available; listings and reviews are shown honestly as unavailable, never silently hidden as if nothing were missing. |

This is `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8's failure-model row, instantiated: the user continues
editing and running already-installed packages (run-state machine, unaffected), imports a package
by direct file/Git/QR transfer (Journey 3's eleven-state table, unaffected — none of its states
depend on marketplace reachability), and sees a clearly labelled "listings/reviews unavailable"
state rather than an empty or misleading browse screen. No local state is blocked by the outage.

**Cites:** `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8. Compare Journey 20, the browser-unavailable row
of the same table.

## Journey 12 — Interrupted import

**Setting.** A download stops midway through transfer.

| From state | Event | To state | Notes |
|---|---|---|---|
| `ACQUIRING` | Download stops before the full byte range is received. | `ACQUIRING` (incomplete, persisted) | The incomplete snapshot cannot progress — `SNAPSHOTTED` requires the full bytes and a matching digest, so an incomplete download never reaches it, and nothing downstream of `SNAPSHOTTED` is ever entered for this attempt. |
| `ACQUIRING` (incomplete) | User resumes. | `SNAPSHOTTED` (if the server and digest protocol support safe byte ranges and the resumed bytes match) **or** `ACQUIRING` (restart, otherwise) | `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §12: interrupted download resumes only under those conditions; otherwise it restarts from `ACQUIRING` rather than trusting a partial byte range. |

No partial installation and no grant exists at any point in this table — `LOOP_DUAL_SURFACE_
ARCHITECTURE.md` §8's "Transfer interrupted" row states the same guarantee generally: the receiving
surface MUST resume the transfer or restart from a verified snapshot, never leave a partial
installation reachable.

**Cites:** `LOOP_IMPORT_ACTIVATION_CONTRACT.md` §12; `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8.

## Journey 13 — Phone process death during authoring

**Setting.** The app process dies mid-edit.

| From state | Event | To state | Notes |
|---|---|---|---|
| `DIRTY_JOURNALED` | User is mid-edit in a Node Sheet field when the process dies. | `DIRTY_JOURNALED` (persisted) | `LOOP_PHONE_AUTHORING_SPEC.md` §13: the same draft, view focus, and unsaved field content MUST be restored on relaunch — zero lost edits across a forced process death, since the field-level edit was journaled immediately, not deferred to a commit. |
| `VALIDATING` / `SIMULATING` (whichever was in progress) | Process death mid-operation. | `INTERRUPTED(<operation>)` | The UI MUST show precisely which operation was interrupted and MUST NOT imply it is still running — an unknown state is reported as unknown, never guessed as still-completing or falsely marked complete. |

Selection, revision, and any unsaved node-sheet fields are recovered from the journal exactly as
they stood; simulation or export status is either recovered from a receipt that actually completed
or explicitly marked interrupted — never silently presented as finished when it was not.

**Cites:** `LOOP_PHONE_AUTHORING_SPEC.md` §13. (`FB-RAT-PHN-011`, an undo/redo model, is a related
but separate `PROPOSED`, not-yet-ratified decision over the same journal — not exercised by this
journey, which concerns durability of edits already made, not reversibility of them.)

## Journey 14 — External display pointer mode

**Setting.** A monitor, mouse, and keyboard connect mid-session; the user later disconnects.

| State | Event | State | Notes |
|---|---|---|---|
| `PHONE_LAYOUT` | Monitor/mouse/keyboard connect. | `POINTER_LAYOUT` | `LOOP_PHONE_AUTHORING_SPEC.md` §10 (`FB-RAT-LBX-006`): selection, draft revision, and viewport focus carry over unchanged. Hover, lasso selection, multi-pane test trace, and keyboard shortcuts become available as **accelerators** over commands already reachable by touch — never a second, competing information architecture. |
| `POINTER_LAYOUT` | Disconnect mid-edit. | `PHONE_LAYOUT` | MUST restore an understandable phone layout with no data loss, returning the user to Stage View at the previously selected node, with all edits intact. |

Detachable inspectors, lasso selection, and simultaneous graph/test panes remain
`FB-RAT-PHN-010`-EXPERIMENTAL — treated as pointer-mode experiments, not committed shape, until
owner verification on real external-display hardware; nothing in this journey depends on their
being stable.

**Cites:** `FB-RAT-LBX-006`; `FB-RAT-PHN-010` (EXPERIMENTAL).

## Journey 15 — Phone-only loop creation in a moving day

**Setting.** The fuller, second-pass telling of Journey 1: a user describes the objective by voice
while away from a desk, and Distiller proposes a seven-stage graph.

| From state | Event | To state | Notes |
|---|---|---|---|
| `CREATED` | User dictates the objective; Distiller proposes a seven-stage graph. The app shows an Intent summary and an authority summary before graph detail (`LOOP_PHONE_AUTHORING_SPEC.md` §3.1). Long-text fields (the dictated objective, any prompt content) use first-class dictation and the full-screen distraction-free editor where they exceed ~200 characters (§3.4). | `CREATED` (draft) | Distiller's output remains a draft plus explanation, never a live activation (§3.1, §6). |
| `CREATED` | User opens Stage View, changes one model-inference node to a deterministic-transformation node (two of the loop language's eight node categories, `LOOP_FROZEN_CONCEPTS_WP1L_G0.md`), adds a human gate before repository write, connects a test-failure branch through the tap connection grammar (Journey 2's table, not re-tabulated), and saves. | `CREATED` (draft revised) | |
| `CREATED` | Validation identifies an unbounded retry (`LOOP-GRAPH-004`); the user sets a bound of two attempts. A fixture simulation passes. | `PREFLIGHT` | |
| `PREFLIGHT` | User binds a repository and a CI provider; approves **Read**/**Write** (`fb.repo.read`, `fb.repo.write_draft`/`fb.repo.commit`) but explicitly not **External**/Push (`fb.repo.push_remote`). | `WAITING_BINDING` → `WAITING_AUTHORITY` → `READY` | Same bucket-to-rung mapping as Journey 1; `FB-RAT-IMP-005` applied to a phone-authored loop. |
| `READY` | User activates and runs; receives a notification for patch review; accepts selected hunks. | `RUNNING` → `SUCCEEDED_VERIFIED` | |

Every action above remains possible without a monitor, pointer, or browser (`FB-RAT-PHN-007`).

**Cites:** `FB-RAT-PHN-001`, `-002`, `-006`, `-007`; `LOOP-GRAPH-004`;
`LOOP_ENGINEERING_SPEC_V2.1.md` §9. Compare Journey 1 for the terser first-pass telling of the same
scenario.

## Journey 16 — Web-authored hardware loop transferred to phone

**Setting.** The fuller, second-pass telling of Journey 3, with a concrete Arduino firmware
example: abstract slots for repository, compiler target, board, flash transport, serial monitor
(`fb.device.serial_read`), and verifier.

| From state | Event | To state | Notes |
|---|---|---|---|
| *(Web Studio)* | Creator builds the graph with the slots above; the web test laboratory uses inert fixtures and MUST NOT flash real hardware (`LOOP_WEB_STUDIO_SPEC.md` §6, §7 — `FB-RAT-WEB-006`, `-003`; `FB-RAT-WEB-008` separately defers *browser* hardware execution entirely). Creator builds and signs the package, sends its QR. | *(package built, signed)* | |
| `ACQUIRING` … `PREVIEWED` | Phone verifies the digest (Journey 3's table), then reports that compilation will use CI while flashing is local USB — the compatibility report separates execution-target classes per axis (`LOOP_COMPATIBILITY_CONTRACT.md` §2, axis 6). | `PREVIEWED` | |
| `PREVIEWED` | Phone asks the user to choose the board and repository; displays the destructive device authority the flash step requires (`fb.device.flash`, `EXECUTE_DESTRUCTIVE`) with visible high-risk authority evidence (`FB-RAT-AUTH-006`, `CAPABILITY_AUTHORITY_MODEL.md` §8). | `WAITING_BINDINGS` → `WAITING_AUTHORITY` | |
| `WAITING_AUTHORITY` | User grants the destructive-flash authority explicitly. | `READY_TO_SIMULATE` | Because this loop requests destructive authority, simulation **plus** board-identity preflight is required before first activation (`FB-RAT-PHN-009`). |
| `READY_TO_SIMULATE` | Preflight confirms board identity; simulation passes against inert fixtures. | `INSTALLABLE` → `INSTALLED` | |
| `INSTALLED` | Real run: compiles remotely (CI target, `EXECUTE_EXTERNAL`), verifies the firmware digest, flashes locally via the phone's own USB path (`EXECUTE_DESTRUCTIVE`, never a browser-originated flash — `FB-RAT-WEB-008` stays deferred independent of this journey), reconnects the serial monitor (`fb.device.serial_read`, `EXECUTE_REVERSIBLE`), and records device evidence in the run receipt. | run-state machine → terminal | |

**Cites:** `FB-RAT-WEB-003`, `-006`, `-008`; `FB-RAT-PHN-009`; `FB-RAT-AUTH-006`;
`fb.device.flash`, `fb.device.serial_read`, `fb.ci.dispatch` (`capability-ids.v1.json`).

## Journey 17 — Marketplace release is delisted after a safety report

**Setting.** A user already has an immutable package installed. The marketplace delists the release
after a misleading authority claim is confirmed. This journey has no first-pass counterpart — it is
genuinely new content, not a second telling of an earlier journey.

| From state | Event | To state |
|---|---|---|
| `PUBLISHED` | A report is reviewed and substantiates removal grounds (a misleading capability declaration). | `DELISTED` |

(`LOOP_MARKETPLACE_CONTRACT.md` §10's full moderation table — `PENDING`→`PUBLISHED`→`LIMITED`/
`DELISTED`→`TAKEDOWN`, with `KEY_REVOKED` cross-cutting — is not re-tabulated here; the row above is
this journey's one concrete transition through it.)

Delisting prevents new discovery and download through the marketplace service; it MUST NOT
remotely delete the user's already-installed local package (`LOOP_MARKETPLACE_CONTRACT.md` §10).
When the user manually checks for updates or opens package details online, the app shows the
moderation notice, publisher/key status, affected versions, and recommended action — never pushed
silently. Existing runs remain inspectable (their receipts are local and unaffected by the
delisting). New activation is disabled by local policy **only if** the notice maps to a ratified
blocker; otherwise the user receives a strong warning and an explicit choice, never a silent block
and never a silent pass-through.

**Cites:** `FB-RAT-MKT-007` (as corrected). See §2's mapping table — this journey is a
second-pass-only addition with no `J`-numbered counterpart.

## Journey 18 — Upstream change applied to a local fork

**Setting.** The fuller, second-pass telling of Journey 8, with concrete versions: a user forked
release `1.2.0` and changed the critic model and retry count; parent `1.3.0` adds a verifier and
narrows repository access.

| From state | Event | To state | Notes |
|---|---|---|---|
| `INSTALLED` (`1.2.0`) | User changes the critic model and retry count on the phone. | *(forked draft)* | `FB-RAT-IMP-006`, `FB-RAT-LIN-001`. |
| *(forked draft)* | Parent publishes `1.3.0`: adds a verifier, narrows repository access. | *(upstream update available)* | Repository-access narrowing is itself a `FB-RAT-IMP-005`-consistent change (authority MAY narrow); the verifier addition is new, separately flagged content. |
| *(upstream update available)* | Comparison separates ordinary graph edits, authority narrowing, verifier strengthening, schema changes, and documentation as five distinct dimensions. | *(comparison shown)* | `FB-RAT-LIN-004`. |
| *(comparison shown)* | User selects the verifier and documentation updates; keeps the local critic-model and retry-count choices from the `1.2.0` fork. | *(selection made)* | `FB-RAT-LIN-005` flags the narrowed-authority dimension independently even though the user is accepting it here, not rejecting it — the highlighting requirement is unconditional on the user's eventual choice. |
| *(selection made)* | Fonebrew creates a new draft revision, records the applied-operation IDs for exactly the selected changes, revalidates, and reruns fixtures. | *(draft revised, validated)* | `FB-RAT-LIN-003`, `-007`: no automatic merge — this is a recorded, selective, re-validated application, not a three-way merge. |

**Cites:** `FB-RAT-IMP-005`, `-006`; `FB-RAT-LIN-001`, `-003`, `-004`, `-005`, `-007`. Compare
Journey 8 for the terser first-pass telling.

## Journey 19 — Result sharing with deliberate redaction

**Setting.** The fuller, second-pass telling of Journey 9. After a successful run, the user selects
Share Result.

| From state | Event | To state | Notes |
|---|---|---|---|
| *(terminal run)* | User selects Share Result. Builder starts **empty** and offers package/version, engine version, terminal state, duration, cost, target-class ratios, and verifier states as addable fields — not a raw log to redact down from. | `SHARE_INITIATED` → `ALLOWLISTED_SUMMARY_BUILT` | `FB-RAT-RES-001`, `-002`. |
| `ALLOWLISTED_SUMMARY_BUILT` | Prompts, file names, repository, host, device ID, logs, and artifacts remain excluded by construction — never present to redact in the first place. | `UNAPPROVED_FIELDS_REMOVED` | `FB-RAT-RES-003`. |
| `UNAPPROVED_FIELDS_REMOVED` | User adds one test-report artifact. | `SECRET_SCANNED` | Scanned and previewed independently of the default fields. |
| `SECRET_SCANNED` | Evidence normalized/hashed; preview shows the exact digest and the chosen public visibility. | `EVIDENCE_NORMALIZED_HASHED` → `PREVIEWED` | |
| `PREVIEWED` | User approves the exact previewed content and receives a consent receipt. | `CONSENT_OBTAINED` → `RECEIPT_SIGNED` → `UPLOADED` → `RECEIPT_STORED` | `LOOP_RESULT_SHARING_CONTRACT.md` §7. |

**The marketplace labels the result honestly, using the corrected four-label scheme
(`LOOP_RESULT_SHARING_CONTRACT.md` §6, `FB-RAT-RES-004`/`-005`), not the source draft's
"runtime-attested" language:**

| Label used here | What it proves |
|---|---|
| `SELF_SIGNED_RECEIPT` (draft said "runtime-attested" — corrected per §2 of this document) | The same on-device signing key produced this receipt and any others under it — pseudonymous continuity only. **Never** presented as proof of a genuine Fonebrew binary, a specific device, or an unmodified runtime. |
| `TARGET_VERIFIED` (draft said "target-verified" — label itself was already correct) | Declared real-target verifiers passed, evidence included. Never a claim of universal correctness across other targets, inputs, or environments. |

**Cites:** `FB-RAT-RES-001`–`006`; `FB-RAT-MKT-008`. Compare Journey 9 for the terser first-pass
telling (no corrected labels needed there, since the source draft did not use the "runtime-attested"
phrase in that entry).

## Journey 20 — Browser disappears after transfer

**Setting.** The user imports a package from an unlisted browser URL; the browser service then
becomes unavailable.

| Condition | Required behavior |
|---|---|
| Browser unavailable | The app MUST remain fully functional for installed packages and local drafts. |

(`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8 — the same failure-model table Journey 11 instantiates for
the marketplace row; this journey instantiates the browser row instead.) The local package
snapshot, bindings, grants, tests, documentation, and run history all continue to work. The user
edits the loop — creating a local forked draft (`FB-RAT-IMP-006`, `FB-RAT-LIN-001`) — exports it to
Git, and runs it offline wherever its declared capabilities allow (Journey 6's offline pattern, not
re-tabulated). No browser login or marketplace token is required for any of this, because none of
it was ever browser-dependent in the first place: the browser produced a package (Journey 3/16),
and a package, once installed, is a phone-local artifact.

**Cites:** `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §8; `FB-RAT-IMP-006`; `FB-RAT-LIN-001`. Compare
Journey 11, the marketplace-unavailable row of the same table.

## Journey 21 — Malicious archive rejected safely

**Setting.** The fuller, second-pass telling of Journey 10: a downloaded file declares the correct
media type but contains traversal paths, a high decompression ratio, and an executable native
payload.

| From state | Event | To state | Notes |
|---|---|---|---|
| `ACQUIRING` | Importer snapshots the bytes exactly as received — the declared media type is not trusted on its own. | `SNAPSHOTTED` | |
| `SNAPSHOTTED` | Container verification rejects the archive before any graph preview: path-traversal entries and a decompression-ratio bomb both match `floop-container-format.v1.json`'s adversarial classes; the payload matches the forbidden-executable-payload class (`LOOP-PKG-002`). | `REJECTED_UNSAFE` | A local failure receipt is created; unsafe entries are never extracted to produce it. |

The user is offered deletion of the rejected file. No package text, script, model instruction, or
thumbnail executes at any point — rejection happens at the container-verification step, strictly
before `PARSED_VALIDATED` (where prompt/documentation text would first be parsed) and strictly
before `PREVIEWED` (where a thumbnail or documentation excerpt would first render).

**Cites:** `FB-RAT-IMP-002`; `floop-container-format.v1.json` adversarial classes; `LOOP-PKG-002`.
Compare Journey 10 for the terser first-pass telling (which adds the secret-token and
capability-mismatch findings this journey's version does not restate).

## Journey 22 — Model substitution cannot preserve semantics

**Setting.** The fuller, second-pass telling of Journey 5, on the model-class axis instead of the
capability axis: a package requests structured, tool-capable text generation with a minimum context
and a verifier. This phone has only a small text-only local model.

| From state | Event | To state | Notes |
|---|---|---|---|
| `PARSED_VALIDATED` | Compatibility evaluation checks the model-inference node's required capability tags (`model-capability-vocabulary.v1.json`) against every locally available `ModelDescriptor`; none satisfies the required tag set. | `COMPATIBILITY_EVALUATED` (`BLOCKED`) | `FB-RAT-CMP-002`: the package declares capability tags, not a mandatory vendor model ID, so substitution is possible in principle — but no local model clears the bar. |
| `COMPATIBILITY_EVALUATED` | No local model resolves the requirement; this is a real block, not a target for silent degradation — `LOOP_COMPATIBILITY_CONTRACT.md` §3 is explicit that `DEGRADED` requires an *explicit, package-declared* fallback that preserves output/authority/verification semantics, and none exists here. | `BLOCKED_INCOMPATIBLE` | terminal; Fonebrew does not silently use the weaker on-device model and later blame the loop. |

Recovery is again the closed `remediationType` set, not open prose:

| `remediationType` | What the user does |
|---|---|
| `BIND_RESOURCE` | Bind a watched cloud model instead (`fb.model.cloud_inference`, `EXECUTE_EXTERNAL`) — remaining, per `CLAUDE.md` binding rule 2, a visibly marked "watched object," never a silent fallback. |
| `INSTALL_TRUSTED_CAPABILITY` | Install a trusted local model provider that actually carries the required tags. |
| `CHOOSE_ALTERNATE_RELEASE` | Select a different release of the same package that does not carry this requirement. |
| `ABANDON_IMPORT` | Abandon the import entirely. |

**Cites:** `FB-RAT-CMP-002`, `-004`; `CLAUDE.md` binding rule 2 (on-device default, cloud always a
watched object — cited the same way `PRODUCT_DIRECTION_AND_BENCHMARK_BASELINE.md` already cites it,
not as an `FB-RAT-*` ID). Compare Journey 5, the same `BLOCKED` treatment on the capability axis.

---

## Cross-references and open items

**Decision IDs cited in this document** (all already `ACCEPTED`/`REJECTED`/`DEFERRED`/
`EXPERIMENTAL` in `DUAL_RATIFICATION_REGISTER.md`; none re-decided or self-ratified here):
`FB-RAT-LBX-001`, `-002`, `-006`; `FB-RAT-PHN-001`–`-004`, `-006`, `-007`, `-009`, `-010`;
`FB-RAT-WEB-001`, `-002`, `-003`, `-005`, `-006`, `-008`; `FB-RAT-PKG-001`–`-006`; `FB-RAT-IMP-001`–
`-008`; `FB-RAT-MKT-005`, `-007`, `-008`, `-011`; `FB-RAT-RES-001`–`-006`; `FB-RAT-CMP-002`, `-003`,
`-004`; `FB-RAT-LIN-001`–`-007`; `FB-RAT-AUTH-006`. `FB-RAT-PHN-011` (undo/redo, `PROPOSED`, not yet
ratified) is named once, in Journey 13's notes, as related-but-not-exercised context — this document
does not treat it as decided.

**Registries reconciled, not redefined:** `capability-ids.v1.json` (`fb.repo.*`, `fb.device.*`,
`fb.ci.dispatch`, `fb.model.*`, `fb.marketplace.publish` and their `authorityRung`s — every journey
above); `loop-validation-rules.v1.json` (`LOOP-GRAPH-003`/`-004`, `LOOP-PKG-001`/`-002`/`-003`,
`LOOP-CAP-001`, `LOOP-COMPAT-002`); `floop-container-format.v1.json` (adversarial classes, Journeys
10 and 21); `model-capability-vocabulary.v1.json` (Journey 22).

**Sibling documents this document defers to and does not restate:** `LOOP_IMPORT_ACTIVATION_
CONTRACT.md` (the eleven-state machine's full transition table and terminal-state rules); `LOOP_
PHONE_AUTHORING_SPEC.md` (the five-view architecture, tap grammar, pointer and persistence tables
in full); `LOOP_COMPATIBILITY_CONTRACT.md` (the five outcome levels, precedence order, and
`remediationType` enum in full); `LOOP_PACKAGE_SPEC.md` (signature/trust states, canonicalization);
`LOOP_WEB_STUDIO_SPEC.md` (the build pipeline in full); `LOOP_MARKETPLACE_CONTRACT.md` (the
moderation state table in full); `LOOP_RESULT_SHARING_CONTRACT.md` (the share pipeline and
verification-label scheme in full); `LOOP_DUAL_SURFACE_ARCHITECTURE.md` (the failure-model table
and transfer channels); `LOOP_FORK_LINEAGE_CONTRACT.md` (a sibling WP-1L output not yet written as
of this document — Journeys 8, 18, and 20 cite only the already-ratified `FB-RAT-LIN-*` decision
text from the register, never a state machine that document has not yet defined); `LOOP_ENGINEERING
_SPEC_V2.1.md` (the run-state machine's full transition table, likewise not yet written under
`docs/ratified/loops/` as of this document — every journey above states only its own concrete path
through the named states, never the general table).

**No new decision ID is proposed by this document**, consistent with this file's assignment (no
`FB-RAT-*` IDs are this document's responsibility to cite as originating source). Every correction
applied in §2 — the numbering-scheme relabelling, the capability-ID and verification-label fixes,
the remediation-enum mapping — was closable by cross-referencing an already-frozen registry or an
already-ratified decision.
