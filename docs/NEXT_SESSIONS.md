# Next Sessions — remaining scope after WP-0 through WP-10

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

Written at WP-11 closeout (2026-08-07). This is the resume seam for whoever picks up the Fonebrew
build-out next: what shipped is in `HANDOFF_STATE.md`; what's ratified but not yet implemented is
in each `docs/WP*_GATE_REPORT.md`'s own honest-gaps section; **this file is the layer above
both** — the named P0/P1 items `06_WORK_PACKAGES.md`'s own WP-11 entry asks for, each with a
concrete seam (not just a topic name).

Read `docs/TRACEABILITY_MATRIX.md` Part C before starting any of these — it maps every WP-2
through WP-10 artifact to the ratified spec it implements, so you know which document to read
first for each item below.

## P0 — deepen the substrate before adding more product surface

### 1. Git depth
**Gap:** all git integration in this constellation is pure REST request-builders
(`GitContentsApi.kt`/`GitTreeApi.kt`) — no embedded git library, so `WorkspaceProviderState`'s
`RepositoryState`/`operationLock` fields (`WORKSPACE_KERNEL_SPEC.md` §2.5) have no real merge/
rebase/conflict-resolution implementation behind them; `dirtyDigest`/`stagedDigest` can't be
computed against a real `.git` working tree today.
**Seam:** `FB-RAT-WS-NEW-1` (`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`) already names the
proposed shape — JGit for read/status/commit, evaluate libgit2-JNI (`git24j`) for merge/rebase/
conflict, route history-rewriting through the already-real SSH lane
(`core-engine/src/main/java/dev/aarso/domain/remote/`, adapted by WP-5's
`SshExecutionProvider`). This is a **PROPOSED, not owner-ratified** decision — the first step is
an owner call on adding a JGit dependency (a real change to "no third-party git library," WP-0's
recorded current state), not a unilateral implementation.

### 2. Debugger
**Gap:** WP-9 built `DapSessionMachine` (the DAP host contract's lifecycle + capability
negotiation, language-agnostic) and `BuiltInLanguagePacks.PYTHON`/`.TYPESCRIPT` both declare real
DAP capsule references (`capsule.debugpy`, `capsule.node-debug`) — but nothing spawns an actual
debug adapter subprocess or speaks the wire protocol. No real DAP client exists.
**Seam:** `docs/WP9_GATE_REPORT.md` §5. Start with Python (`debugpy` is the most turnkey on
ARM64 Android per `01_VALIDATION_REPORT.md`'s own evidence) — adapt `LocalProcessExecutionProvider`
(WP-4) the same way WP-5 adapted the SSH spine, driving `DapSessionMachine` around a real
subprocess. Rust/C++ stay blocked on `lldb-dap`'s missing turnkey ARM64-Android build (a
from-scratch vendoring effort, not a Kotlin-side gap).

### 3. Android lane
**Gap:** WP-9's four language-lane fixtures are TypeScript, Python, Rust, C++
(`BuiltInLanguagePacks`) — there is no Kotlin/Java/Android-SDK lane at all, despite this app
itself being an Android/Kotlin codebase a phone-native "build your own app" story would eventually
need to compile against.
**Seam:** extend `LanguageLaneContracts.kt`'s `BuiltInLanguagePacks` with a `kotlin`/`java`
`LanguagePackManifest`. Given the W^X/Play constraints `ToolchainDeliveryLegality`
(`FB-RAT-LANG-NEW-1`, also proposed not ratified) already models, a real Android Gradle/AGP
toolchain is heavy enough that `ToolchainDeliveryMechanism.REMOTE` (SSH/CI, same as Rust/C++ this
pass) is the realistic first cut, not a fully local on-device Android SDK.

### 4. Docked mode
**Gap:** `LOOP_PHONE_AUTHORING_SPEC.md` §10 ("Pointer and external-display mode," `FB-RAT-LBX-006`
ACCEPTED, `FB-RAT-PHN-010` EXPERIMENTAL) specifies a real state table
(`PHONE_LAYOUT ⇄ POINTER_LAYOUT`, "no reachable app state exists only under `POINTER_LAYOUT`")
that WP-8b did **not** implement — WP-8b's six files covered §3.2/§7/§8/§9/§13/§14 only; §10 was
read but deliberately left for a future pass, not silently forgotten.
**Seam:** `domain/loop/authoring/` (WP-8b's package) is the natural home for a
`PointerLayoutMachine.kt` mirroring `TouchConnectionGrammar`'s exact style against §10's table.
`CLAUDE.md`'s own docked-mode caveat applies too: Android 16 QPR3 desktop windowing is
hardware-gated (Pixel 8/9/10 + select Samsung flagships as of Aug 2026) — verify Fonebrew's actual
RedMagic dev target's dock behavior explicitly before assuming QPR3 desktop windowing exists
there, per `01_VALIDATION_REPORT.md` §D1.

## P1 — real but lower priority than the substrate above

### 5. Embedded debug (CMSIS-DAP)
**Gap:** `FB-RAT-DEV-011` DEFERRED, `DEVICE_STATE_AND_SAFETY_SPEC.md` §7 — CMSIS-DAP/OpenOCD has
**zero Android precedent**; `CMSIS_DAP` exists in `UsbFamily` as a target-matrix placeholder only.
**Seam:** explicitly a **research spike**, not a feature to schedule — a from-scratch
NDK+libusb HID stack with no existing reference implementation to adapt. `docs/DEVICE_GATE_CHECKLIST.md`
§1 already marks it N/A / out of scope for the real-hardware release gate.

### 6. Pi (deeper Raspberry Pi device operations)
**Gap:** WP-5's `SshExecutionProvider` already serves `RASPBERRY_PI` as an SSH-reachable
execution target (the same provider as `SSH_HOST` — "a Pi target is SSH-reachable, matching the
existing Arduino-via-Pi precedent"), so basic shell/Arduino-via-Pi execution is covered. What's
**not** covered: any Pi-specific device model (GPIO pin state, camera, I2C/SPI peripherals) beyond
generic shell exec — the `devices` domain (`DeviceIdentity`/`DeviceConnection`/etc., WP-10) is
scoped to USB-attached flash/serial targets, not GPIO-attached peripherals.
**Seam:** if GPIO/peripheral control is wanted, it's a new sub-domain, not an extension of WP-10's
existing `UsbFamily` enum (GPIO isn't a USB family at all) — scope it as its own work package
against real Pi hardware, not assumed to fold cheaply into the device-broker work already done.

### 7. Backup/sync
**Gap:** untouched by any WP this session. No ratified spec, no schema, no domain code, no
mention in any gate report as a "flagged, not built" item — this is a genuinely unstarted
capability, not a narrowed one.
**Seam:** none established yet. A future session needs to first determine whether this is a new
WP-1-style contract-authoring pass (no existing spec to implement against) or an extension of the
already-real `WorkspaceJournal`/`RoomWorkspaceJournal` (WP-3) — the append-only, idempotent-key
journal machinery WP-3 built is architecturally close to what a backup/sync log would need, but no
one has scoped whether reusing it directly is the right call.

### 8. ASOM contract
**Gap:** `03_CONSTELLATION_CONTEXT.md` (the authoritative ground-truth document, per
`00_MASTER_PROMPT.md`) confirms ASOM ("A System of Models," formerly "Urbana") is **already a
separate, shipped, public Apache-2.0 repo** (`asystemofcells/asystemofmodels`) with a real
OpenAI-compatible HTTP interface on `127.0.0.1:11435` and AIDL-verified pairing — `FB-RAT-PORT-005`
(DEFERRED, "defer the public ASOM carve") is **overtaken by events**, not a live blocker. The live
gap is narrower and still real: the *contract* between Fonebrew and ASOM (model descriptor,
routing-decision visibility, memory budget, shared model residency) — `05_INTEGRATION_CONTRACTS.md`
§9 names this as a missing contract, still P1, never authored by WP-1 (which scoped to
common/workspace/execution/authority/devices/integrations — not model-routing) or any later WP.
**Seam:** `05_INTEGRATION_CONTRACTS.md` §9 + the real, already-running ASOM daemon's own interface
— write the contract against what ASOM actually exposes today, not a hypothetical carve
(`02_DECISION_ANNOTATIONS.md`'s own correction). Also resolve the no-IPC-rule scope note while
here: `FB-RAT-INT-004` (REJECTED, app-to-app IPC) applies to the manual-import companion lanes
(CSApp/Assay/Studio) only — ASOM's AIDL pairing + localhost HTTP predates that rule and is a
daemon relationship, which `CAPABILITY_AUTHORITY_MODEL.md` should document as the explicit
exception.

### 9. Extensions
**Gap:** no WP this session touched an extension/plugin model for Fonebrew itself (as distinct
from a *loop package*, WP-8a's domain, which is declarative-only and explicitly not an executable
plugin surface — `PKG-003` REJECTED). "Extensions" here is the P1C "advanced ecosystem" tier
`09_DUAL_SURFACE_LOOP_BUILDER.md` §6 names as out of scope for this entire build-out's session
structure (P1A/P1B registry/reviews/moderation are explicitly deferred pending the owner's
bounded-backend call, §4 of that document) — extensions sit even further out than that.
**Seam:** genuinely not scoped yet at any level — the bounded-backend amendment (`MKT-001`) and
the differentiator-first-vs-substrate-first reordering (both still-open owner decisions, restated
below) would need resolving first, since an extension model's shape depends heavily on whether
Fonebrew ships any hosted backend at all.

## Standing open owner decisions (restated from `HANDOFF_STATE.md`, not new here)

These block scoping some of the above more precisely than "read the seam and start," and are not
this session's to resolve:

1. **Differentiator-first vs. substrate-first reordering** — `06_WORK_PACKAGES.md`'s own alternate
   sequencing question; substrate-first is in effect because nobody has ruled otherwise.
2. **The bounded-backend amendment (`MKT-001`)** — scoped, defensible, needs the supersession
   record, legal/Play layer, and a phasing decision.
3. **`FB-RAT-PHN-011` (undo/redo) ratification** — implemented as code (WP-8b), never ratified.
4. **`FB-RAT-WS-NEW-1`/`FB-RAT-LANG-NEW-1`** — both PROPOSED in
   `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`, neither self-ratified (see item 1/3 above and
   `docs/TRACEABILITY_MATRIX.md` Part C).
