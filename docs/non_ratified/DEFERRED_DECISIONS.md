# Deferred Decisions Register

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**What this is.** The stable-ID record of decisions the Fonebrew ratification register has
explicitly declined to make yet, each with the concrete condition that has to become true before
it can be picked back up. "Deferred" is not "rejected" (see `REJECTED_ALTERNATIVES.md`) and not
"experimental" (see `EXPERIMENTAL_DECISIONS.md`) — it means the register judges the question
genuinely not yet answerable, usually because an upstream contract, a real-device measurement, or
an owner-only judgment call (e.g. legal review) has to land first.

**Per FB-RAT-COM-002:** every row below carries a globally unique stable ID, independent of
display topic — do not rename or renumber an ID even if its topic label is later reworded. An ID
whose blocking condition resolves gets a new ACCEPTED/REJECTED entry in the appropriate register;
the deferred ID itself is never deleted or reused, per the "decision IDs are never reused or
renumbered" rule this register operates under.

## Register

| ID | Topic | Decision | Unblock condition | Status |
|---|---|---|---|---|
| `FB-RAT-PORT-004` | Broader Studio expansion | Defer broader publishing, asset, call, Orrery, and general cross-app coordination until P0 workspace and execution contracts are stable. | P0 workspace and execution gates green. | DEFERRED |
| `FB-RAT-PORT-005` | ASOM carve | Defer public ASOM carve until interface stable. | — | **OBE** (see note below — not waiting on anything) |
| `FB-RAT-PORT-009` | Extension marketplace | Defer a general extension ecosystem until provider contracts and signing/permission rules are stable. | Capability and signature contracts landed and exercised by at least one real provider. | DEFERRED |
| `FB-RAT-EXE-011` | Release signing route | Defer the final release-signing architecture until on-phone hardware-backed signing and CI secret options are separately evaluated. | An owner-directed research spike comparing Keystore/StrongBox signing vs CI-held keys. | DEFERRED |
| `FB-RAT-LOOP-007` | Council decision-science expansion | Defer formal disagreement taxonomy, blind-evaluation weighting, and judge-bias controls to the Council/Roundtable ratification. | Loop v1 telemetry exists. | DEFERRED |
| `FB-RAT-LOOP-008` | Automatic model routing | Defer automatic model selection; v1 routing recommendations remain visible and user-confirmed. | Same as `FB-RAT-LOOP-007` — loop v1 telemetry exists. | DEFERRED |
| `FB-RAT-DEV-011` | Embedded debugger release | Defer production embedded debugging until Device Broker and flash safety contracts pass real-device gates. | `FB-RAT-DEV-008` real-board gates pass AND a research spike on NDK+libusb CMSIS-DAP support lands (zero precedent today). | DEFERRED |
| `FB-RAT-INT-017` | CSApp return packet | Defer the optional Studio-to-CSApp resolution-summary export until the inbound contract is stable. | R1–R4 (integration release sequence) land and are exercised in production use. | DEFERRED |
| `FB-RAT-INT-027` | Optional return packets | Defer R5 manual remediation/resolution packets until all inbound receipts are stable. | Same as `FB-RAT-INT-017`. | DEFERRED |
| `FB-RAT-DIST-003` | Toolchain license policy | Defer final policy for separately executed copyleft toolchains versus linked runtime dependencies pending explicit legal review. | Real legal review, owner-only, cannot be done in-session. | DEFERRED |

## OBE correction — `FB-RAT-PORT-005`

`FB-RAT-PORT-005` ("defer public ASOM carve until interface stable") is **OBE (overtaken by
events)**. ASOM already **is** a separate public repo — `asystemofcells/asystemofmodels`,
Apache-2.0 — with a working `127.0.0.1:11435` daemon and AIDL-verified pairing. This is not
"still blocked" and MUST NOT be described that way going forward: the carve already happened
**outside** this decision, so the decision has nothing left to gate. It is kept in this register
only for the stable-ID record (per the "never reused or renumbered" rule), with status `OBE`
rather than `DEFERRED`, and it is not "waiting" on anything.

The live remaining work is a **different, still-open piece of work**: a Fonebrew-to-ASOM
**contract** (`ModelDescriptor`, `RoutingDecision`, shared residency protocol, pairing
capability). That contract work is **P1** and is not blocked by `FB-RAT-PORT-005` at all — it
never was gated by the public-repo question this decision was actually about; it is gated by
whatever the contract's own design work requires, which is out of scope for this register entry.
Do not cite `FB-RAT-PORT-005` as a reason the Fonebrew-to-ASOM contract can't proceed.

## Proposed new decisions (`FB-RAT-*-NEW`)

See `EXPERIMENTAL_DECISIONS.md` §"Proposed new decisions" for the open `FB-RAT-*-NEW` section.
This register does not maintain its own copy of that section — proposals belonging conceptually
to "deferred" rather than "experimental" still get logged there, since it is the one place this
work package was asked to set up as the canonical proposals location; a later session may split
it out if the section grows large enough to warrant a dedicated deferred-proposals list.
