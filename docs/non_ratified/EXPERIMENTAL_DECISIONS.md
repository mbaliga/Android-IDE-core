# Experimental Decisions Register

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**What this is.** The stable-ID record of decisions the Fonebrew ratification register has
adopted provisionally — the register is confident enough in the shape of the decision to build
against it now, but a stated calibration condition (real device data, real release-cycle
telemetry, a real fixture) has to be satisfied before the decision graduates to ACCEPTED, or gets
walked back to REJECTED if the data doesn't support it. This is distinct from `DEFERRED_DECISIONS
.md`, where the register hasn't committed to a shape at all yet.

**Per FB-RAT-COM-002:** every row below carries a globally unique stable ID, independent of
display topic — do not rename or renumber an ID even if its topic label is later reworded.

## Register

| ID | Topic | Decision | Calibration condition | Status |
|---|---|---|---|---|
| `FB-RAT-STR-009` | Benchmark weights | Use the proposed B01–B10 scenario weights (12/12/8/14/10/12/10/10/7/5) as an initial calibration set, subject to three release cycles of observed data. | Encode as `benchmarks/scenarios.v1.json` machine-readable checklist now (a later work package wires it); never mix planned and shipped scores; re-evaluate weights after 3 release candidates worth of fixed-fixture data. | EXPERIMENTAL |
| `FB-RAT-PORT-008` | Local Linux compatibility | Treat a PRoot/AVF-style Linux provider as an optional compatibility experiment, not a foundational dependency. | Measure real package/toolchain coverage gained vs the W^X-constrained native paths already ratified before treating it as anything beyond opt-in. | EXPERIMENTAL |
| `FB-RAT-WS-008` | Workspace scale thresholds | Use 100 forced kills and a 50,000-file repository as initial acceptance fixtures, subject to device calibration. | The invariant (zero lost edits) is normative now; the numeric thresholds (100, 50000) are recalibrated once real reference-phone timing data exists (device-run, not JVM). | EXPERIMENTAL |
| `FB-RAT-EXE-006` | Thermal routing policy | Implement thermal-aware pause/concurrency/offload behavior behind measured device policy rather than fixed universal thresholds. | Thermal STATE OBSERVABILITY is normative P0 now; PAUSE/OFFLOAD POLICY thresholds are calibrated per-device once real thermal telemetry exists. | EXPERIMENTAL |
| `FB-RAT-DEV-010` | Initial USB family set | Treat CDC, FTDI, CP210x, CH34x, STK500, UF2, DFU, ESP bootloader, and CMSIS-DAP as the initial test matrix subject to hardware availability. | Coverage is bounded by which reference boards the owner actually owns and tests. | EXPERIMENTAL |
| `FB-RAT-INT-014` | CSApp optional evidence bundle | Allow optional attachment references by filename and digest only after privacy and bundle-size tests. | Needs a privacy fixture (does an attachment ref leak anything beyond filename plus digest) and a size fixture (bundle stays bounded) before promotion to ACCEPTED. | EXPERIMENTAL |
| `FB-RAT-PHN-010` | Dense pointer layout | Treat detachable inspectors, lasso selection, and simultaneous graph/test panes as pointer-mode experiments until owner verification. | Pointer mode (`FB-RAT-LBX-006`) remains an input register only, never the canonical IA — promote to ACCEPTED only after owner verification on a real docked/pointer session, per `LOOP_PHONE_AUTHORING_SPEC.md`. | EXPERIMENTAL |
| `FB-RAT-PKG-010` | Package size caps | Treat package and asset limits as measured marketplace policy, not hard semantic constraints, until real packages are observed. | Needs real published-package size distribution data (from the static/Git registry, `FB-RAT-MKT-009`) before any numeric cap is treated as more than a provisional policy default. | EXPERIMENTAL |
| `FB-RAT-CMP-007` | Community compatibility claims | Treat creator-declared device/language compatibility as unverified metadata until backed by fixtures or explicit shared receipts. | A creator's compatibility claim is promoted from unverified metadata only once backed by a fixture (`package-fixture-record.schema.json`) or an explicit shared receipt (`loop-result-share.schema.json`) — see `loop-listing.schema.json`'s `verified: false` default. | EXPERIMENTAL |

## Proposed new decisions (`FB-RAT-*-NEW`)

This is the canonical, open proposals section for decisions a session identifies but is **not
authorized to self-ratify**. Decision IDs in the registers above are never reused or renumbered;
a genuinely new decision instead gets a new domain sequence number here, under a
`FB-RAT-<domain>-NEW-<n>` label, for the owner (or a future ratification pass) to accept,
reject, or fold into an existing ID. **Later work packages will append further entries to this
section as they identify proposals of their own** — this section is intentionally left open below
the first entry, not closed off after WP-1.

### `FB-RAT-WS-NEW-1` — JGit (and libgit2-JNI) for real git operations

**Proposed by:** the workspace-kernel domain agent (WP-1), restated here for the non-ratified-
registers domain per this work package's instructions. Filed against `docs/ratified/
WORKSPACE_KERNEL_SPEC.md` §2.5 (`RepositoryState` / `operationLock`).

**Proposal text (restated verbatim from source):** Per the WP-0 survey (`docs/WP0_SURVEY.md`
§3), no repo in this constellation has JGit or any git library as a dependency today — all
existing git integration (`core-engine/src/main/java/dev/aarso/domain/git/GitContentsApi.kt`,
`GitTreeApi.kt`) is pure REST request-builders against GitHub/Gitea, executed by an injected
transport. For a real `RepositoryState`/operation-lock implementation, the independent
validation pass that reviewed the workspace-kernel work package recommends: **JGit** for the
read/status/commit paths (a working-tree-level library, not a REST client, is genuinely needed
once `dirtyDigest`/`stagedDigest` must be computed against an actual `.git` directory rather than
parsed from a host's REST response); **evaluate libgit2-JNI (`git24j`)** for merge/rebase/
conflict, where JGit's pure-JVM merge machinery is a known weaker spot; and note that
**history-rewriting ops MAY also route through the SSH lane** (already real —
`core-engine/src/main/java/dev/aarso/domain/remote/`, per `docs/WP0_SURVEY.md` §1(h)) as an
alternative to an embedded git library, executing `git rebase`/`git reset` etc. on a remote host
the user already trusts.

**Status:** PROPOSED, not self-ratified. `docs/ratified/WORKSPACE_KERNEL_SPEC.md` itself records
this proposal without claiming an `FB-RAT-WS-*` ID for it; this register assigns the tracking
label `FB-RAT-WS-NEW-1` so the proposal has a citable, stable slot in the new-decisions queue
pending owner ratification. Accepting it would mean adding a JGit dependency to
`core-engine` — a change to the "no third-party git library" fact WP-0 recorded as current
state, not a change this session is authorized to make on its own.

---

### `FB-RAT-LANG-NEW-1` — Toolchain delivery-mechanism-to-distribution-flavor legality table

**Proposed by:** the language-lanes domain agent (WP-9). Filed against `contracts/kotlin/
LanguageLaneContracts.kt`'s `ToolchainDeliveryMechanism` enum and
`core-engine/src/main/java/dev/aarso/domain/language/ToolchainDeliveryLegality.kt`.

**Proposal text:** No `FB-RAT-*` decision ID exists for language-lane toolchain delivery at all —
the whole domain is greenfield this build-out (`docs/WP9_GATE_REPORT.md` §0), grounded in
`01_VALIDATION_REPORT.md` §B1/§B3's platform-feasibility findings (Android's W^X exec
restriction; Play's interpreter/VM carve-out) rather than any prior ratified spec. WP-9 built a
concrete per-mechanism, per-`DistFlavor` (`full`/`play`) legality table:
`INTERPRETER_SCRIPTS`/`BUNDLED_JNILIBS`/`REMOTE` legal on both flavors; `PLAY_DYNAMIC_FEATURE`
legal on `play` only (it IS Play's own delivery channel); `CAPSULE_APK` legal on `full`
(sideload) only (a separately-installed signed APK via `PackageInstaller` is not a standard
Play-distributed app capability). Each mapping is explained inline in
`ToolchainDeliveryLegality.kt`'s own KDoc and is real, tested code (`ToolchainDeliveryLegalityTest.kt`,
5 tests) — not merely asserted.

**Status:** PROPOSED, not self-ratified. This is a reasoned starting position derived from
documented platform constraints, not an owner ruling — `DISTRIBUTION_CAPABILITY_SPLIT.md`
(FB-RAT-DIST-001/002/003, WP-1) is the sibling ratified document this table extends into a new
domain without itself amending; a future ratification pass should either fold this table into
that document under a proper `FB-RAT-DIST-*` or `FB-RAT-LANG-*` ID, or explicitly correct any of
the four per-mechanism rulings above if real Play-policy testing contradicts them.

---

*(Later work packages: append new `FB-RAT-<domain>-NEW-<n>` entries below this line as you
identify proposals of your own. Do not renumber `FB-RAT-WS-NEW-1` or `FB-RAT-LANG-NEW-1`, or
insert ahead of either.)*

### `FB-RAT-PORT-NEW-1` — Desktop surface scope (device independence)

**Proposed by:** the multi-platform porting plan (`docs/PORTING_PLAN.md`, 2026-10-06). Filed against
`docs/handoff/device-independence.md` (a research brief, no decision recorded) and `FB-RAT-PORT-002`
(Docked IDE, sub-area scoped only).

**Proposal text:** A desktop build of Fonebrew (Linux first, then macOS and Windows) exists as the larger-screen,
pointer-capable surface for a docked phone and as a build of the open core for other users. It is not an owner
workflow and does not create a second machine the owner must own or maintain; desktop artifacts are built on rented
hosted CI. Android stays the primary surface: no state or feature is reachable only on desktop, pointer and keyboard
stay accelerators (`FB-RAT-LBX-006`), and the desktop head consumes the same pointer-layout machine the Android docked
work will build (`docs/NEXT_SESSIONS.md` item 4) rather than a second information architecture. There is no in-app
updater; downloads are handed to the OS (`docs/design/app-distribution.md` §3).

**Status:** PROPOSED, not self-ratified. Raises the porting program's OQ-7a. Until ruled, the repo's tier in the
program is B, not A.

---

### `FB-RAT-PORT-NEW-2` — KMP seam: module shape and staging

**Proposed by:** the multi-platform porting plan. Filed against the header of `core-engine/build.gradle.kts`,
`docs/WP2_GATE_REPORT.md` §4 and `FB-RAT-PORT-011`.

**Proposal text:** The portable core moves in three stages, each a set of PRs with the Android gate green. (1) A
separate Gradle build under `desktop/` maps `domain/`, `contracts/kotlin` and their tests by directory and runs them
on a host JDK, editing no existing file. (2) Interface refactors on the Android side remove the two `domain/`-to-`data/`
back-edges, the `inference/`-to-`data/` edges and direct `Context` use in the stores. (3) New Kotlin Multiplatform
modules (`androidTarget` and `jvm()`, iOS later) beneath `:core-engine` take `domain/`, `contracts/`, `inference/`, the
pure parts of `data/` and the portable `ui/`, while `:core-engine` stays a `com.android.library` with the `dist` flavor
dimension so the downstream submodule pin keeps resolving. `:core-engine` is not converted in place.

**Status:** PROPOSED, not self-ratified. Open sub-question: how the Android gate keeps running the moved tests when a
port may not edit `ci.yml`. Interacts with the porting program's OQ-17 (toolchain pins).

---

### `FB-RAT-PORT-NEW-3` — Persistence off-Android: Room-KMP first, no tree migration

**Proposed by:** the multi-platform porting plan. Filed against `CLAUDE.md` ("Two SQL toolchains coexist … do not
unify") and `HANDOFF_STATE.md` open thread 10.

**Proposal text:** Spike Room-KMP with `BundledSQLiteDriver` (context-free builder, at the current pins first) for the
message tree on `jvm()` and later iOS, and keep SQLDelight as the FTS5 index in its own SQLite file, as today. The tree
is **not** migrated to SQLDelight. If the spike fails, moving the tree to SQLDelight would amend the do-not-unify rule and
is an owner decision, not a fallback anyone takes silently. The destructive-migration posture (no `Migration` objects,
schema export off) carries over unless the owner asks for migrations.

**Status:** PROPOSED, not self-ratified. Calibration condition: a pass, fail or unknown verdict recorded in
`HANDOFF_STATE.md` at Kotlin 2.1.0, KSP 2.1.0-1.0.29 and Room 2.7.1 before any Room 2.8 or toolchain bump is considered.

---

### `FB-RAT-PORT-NEW-4` — Secret custody per platform (rule 5 equivalents)

**Proposed by:** the multi-platform porting plan. Filed against `CLAUDE.md` rule 5 and the porting program's OQ-22.

**Proposal text:** Rule 5 names the Android Keystore and asks that a port present an equivalent custody model for owner
approval. Proposed equivalents, each reporting its weaker guarantee in the UI as a key-storage tier: macOS and iOS
Keychain (data-protection, this-device-only, never synchronizable, excluded from backup); Windows DPAPI or Credential
Manager; Linux Secret Service/libsecret with a passphrase-encrypted file when no service is present; on Ubuntu Touch no
BYOK key is stored at all. Keys, git tokens and SSH secrets never transfer between surfaces programmatically, and no
custody path logs or exports them.

**Status:** PROPOSED, not self-ratified. No port holds a key until the owner rules.

---

### `FB-RAT-PORT-NEW-5` — Off-Android capability rows, honest stubs and Windows local execution

**Proposed by:** the multi-platform porting plan. Filed against `ExecutionTargetType` in
`contracts/kotlin/ExecutionContracts.kt`, `schemas/common/capability-manifest.schema.json`,
`docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md` (`FB-RAT-DIST-001`) and `FB-RAT-LANG-NEW-1`.

**Proposal text:** Add a `LOCAL_DESKTOP` execution target and per-platform capability rows (linux, macos, windows, ios,
ubuntu-touch), each mechanism declared as `FB-RAT-DIST-001` requires. The assist gesture, overlay bubble, screen-capture
OCR, USB flashing, in-app APK install and foreground services are not built off-Android; each such surface is a stub whose
UI says so rather than hiding it. A desktop build can run downloaded toolchains, which changes the language-lane legality
table and must be recorded there. On Windows, until the owner rules, local execution targets are a labelled stub and SSH
and CI targets work; the alternatives are requiring WSL or Git-Bash, or per-shell adapters with conformance tests.

**Status:** PROPOSED, not self-ratified. Raises the porting program's OQ-6.

---

### `FB-RAT-PORT-NEW-6` — Shapes of the non-desktop targets: Ubuntu Touch and iOS

**Proposed by:** the multi-platform porting plan. Filed against `docs/ratified/loops/LOOP_WEB_STUDIO_SPEC.md`,
`docs/CORE_PHASES.md` invariant 6 (licence policy) and `docs/design/app-distribution.md` §3.

**Proposal text:** Ubuntu Touch is a reframe, not a port: a Click webapp (webapp-container, HTML/JS only, common policy
groups only) over the static Web Studio, labelled as a Web Studio shell and not as the Android app. No QML/Qt client (the
licence rule bans linking LGPL), no JVM-in-click, and no on-device inference or key storage there; running the Android APK
under Waydroid is owner-device evidence only. iOS is hard rather than reframe and follows the desktop seam: iPad first,
foreground-only inference that stops GPU work on resign-active, SSH and CI targets only (no local process), domain and
contracts commonized onto Kotlin Multiplatform with the golden canonicalization vectors
(`schemas/loops/fixtures/canonicalization/`) held byte-identical, and nothing downloaded that changes app features.

**Status:** PROPOSED, not self-ratified. Raises the porting program's OQ-1, OQ-2 and OQ-21, and depends on
`FB-RAT-WEB-010` (itself PROPOSED) for the Web Studio. Neither target starts before the owner rules.

---
