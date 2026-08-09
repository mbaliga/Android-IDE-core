# Distribution Capability Split — the `distribution` domain

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED for `FB-RAT-DIST-001` (ACCEPTED) and `FB-RAT-DIST-002` (REJECTED — a
prohibition is itself the ratified position, see §2). `FB-RAT-DIST-003` is **DEFERRED**
(cross-reference `docs/non_ratified/DEFERRED_DECISIONS.md`, owned by another agent) and is not
designed here.

**Scope:** the `distribution` domain named in the WP-1 task brief. Per the traceability matrix
given to this agent, **this document has no JSON Schema or Kotlin of its own** — it is policy
prose, plus a reference into `schemas/common/capability-manifest.schema.json`
(`docs/ratified/COMMON_CONVENTIONS.md` §10, `CapabilityManifest` per FB-RAT-COM-011), which the
foundations domain already wrote and this document does not redefine. This document is the
citation target for `FB-RAT-DIST-001`…`FB-RAT-DIST-003`; `FB-RAT-DIST-004` (the 8-conformance-
test-class governance framework) is cited but owned by `COMMON_CONVENTIONS.md` §11, not repeated
here. `docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md` is this document's sibling in the same
device+distribution work package — no dependency either direction, grouped by owning agent only.

**Repo-placement / precedent note.** This repo's own `docs/design/app-distribution.md` (pre-dating
this work package) already documents the real, owner-decided Play/full split for exactly one
concrete mechanism — an in-app package-installer + `REQUEST_INSTALL_PACKAGES` for downloaded
**APK** files — stating plainly: *"Play policy forbids in-app installers +
`REQUEST_INSTALL_PACKAGES`, so this lives in the sideload/`full` flavor... never the Play build."*
This document generalizes that single, already-decided example into the full per-mechanism
taxonomy FB-RAT-DIST-002's validation correction requires (§3) — it does not contradict or
re-litigate the existing APK-installer decision, it names the taxonomy that decision is one
instance of.

---

## 1. Play/full split is per-mechanism, not just per-store (FB-RAT-DIST-001)

**FB-RAT-DIST-001 — ACCEPTED.** Explicit Play and full/sideload capability manifests; never
pretend both support the same local toolchain surface.

**Critical correction baked into this document (BLOCKER-severity, from validation).** The naive
reading of FB-RAT-DIST-001 — "Play flavor loses local-execution features, full flavor keeps
them" — is wrong at the level of detail this constellation actually needs. The real boundary is
per-**mechanism**, and five mechanisms exist with five different legality profiles, only one of
which is actually a free policy choice:

| # | Mechanism | Play-legal? | Full/sideload-legal? | Why |
|---|---|---|---|---|
| (a) | **Interpreter/VM lane** — a bundled Node/CPython (or similar) runtime executing **downloaded scripts**, not compiled/native code | **Yes** — an explicit Play policy carve-out | Yes | Google Play's policy on executable code distinguishes interpreted/scripted content run by an already-reviewed interpreter from downloaded compiled/native/DEX code; the interpreter itself ships in the reviewed APK, only data (source text) is downloaded |
| (b) | **Play Dynamic-Feature-delivered native code** | **Yes** — ships *through* Play itself | Yes (also usable outside Play, though the mechanism exists specifically for Play) | Delivered via the Play Feature Delivery / Play Asset Delivery pipeline — Google reviews and signs the delivered module; this is not "downloading executable code" in the sense Play's policy prohibits, because Play itself is the delivery and integrity-verification path |
| (c) | **APK-bundled `jniLibs` executables** | Yes | Yes | Both flavors ship native `.so`/executable content *inside* the APK itself, versioned with app releases (this repo's own `libaarso_llama.so`/`libaarso_sd.so` are exactly this) — nothing is downloaded at runtime, so no exec-of-downloaded-content question even arises |
| (d) | **Termux-style linker tricks** (`system_linker_exec` and similar) | **No — PROHIBITED** | **No — PROHIBITED** | Fragile (depends on OS-version-specific linker behavior that Android has actively closed off release over release) and Play-toxic even as a concept; prohibited on **both** flavors by this document, not merely discouraged on Play — see §3's flavor-independent framing |
| (e) | **Downloaded-native-exec** (fetch a compiled binary at runtime, then `execve()`/mmap-exec it) | No | **Also no — impossible, not merely policy-forbidden** | Android's W^X/SELinux exec restriction (targetSdk 29+) blocks this at the **OS level**, on every flavor, full/sideload included — see §3 for the full platform fact and why the sideload SKU is not an escape valve |

**The load-bearing correction:** (d) and (e) look superficially similar to (a)/(b)/(c) — "code
arriving at runtime, then running" — but only (a) and (b) are legitimate Play-legal *mechanisms*
for that shape, because both route through infrastructure Google Play itself reviews or executes
(a Play-shipped interpreter; the Play delivery pipeline). (c) sidesteps the question by shipping
everything in the APK. (d) and (e) are the two shapes that actually attempt to execute
freshly-downloaded, non-Play-mediated native code — and (e) is not even reachable on **either**
flavor for a platform reason that has nothing to do with Play policy at all (§3).

## 2. No downloaded executable code in Play (FB-RAT-DIST-002)

**FB-RAT-DIST-002 — REJECTED.** Reject downloading executable DEX/JAR/native code in the Play
distribution.

Stated as both a prohibition and a required adversarial fixture description, per the task brief:

### 2.1 Prohibition (normative)

A Play-flavor `CapabilityManifest` (`schemas/common/capability-manifest.schema.json`,
`subjectKind: EXTENSION` or `DEVICE`, `targetRequirements.distributionFlavor: "play"`) **MUST
NOT** declare any `supportedOperations` entry naming mechanism (d) or (e) from §1's table — under
any naming variant. This applies regardless of whether the declared operation would, if executed,
actually succeed (per §3, (e) cannot succeed on either flavor anyway) — the **declaration itself**
is the violation this rule targets, because a `CapabilityManifest` that claims a capability is a
promise to a caller, and a promise of a prohibited/impossible mechanism is a defect independent of
runtime outcome.

### 2.2 Why `CapabilityManifest`'s schema cannot enforce this itself

`schemas/common/capability-manifest.schema.json`'s `supportedOperations` is deliberately an open
array of non-empty strings — "operation vocabulary is subject-kind-specific," per that schema's
own description (`COMMON_CONVENTIONS.md` §10). There is no closed enum that schema could add which
both (a) allows every legitimate operation name across every subject kind (LSP/DAP/EXECUTION/
DEVICE/MODEL_PROVIDER/EXTENSION) and (b) rejects specifically the download-and-exec shape — that
requires a distribution-flavor-aware **denylist** check, which is policy logic layered on top of
an intentionally open vocabulary, not a shape constraint.

### 2.3 Required adversarial fixture

`fixtures/devices/adversarial/play-manifest-declares-download-and-exec.adversarial.json` — a
`CapabilityManifest` tagged `targetRequirements.distributionFlavor: "play"` declaring
`"distribution.downloadAndExecuteNativeCode"` in `supportedOperations`. It **structurally passes**
`capability-manifest.schema.json` (per §2.2), which is exactly the point: this is a fixture
demonstrating a gap only a policy-level CI check can close, with a sibling
`play-manifest-declares-download-and-exec.expected.txt` naming that check.

### 2.4 The CI policy check this fixture names (described, not executable here)

Since no real CI runs in this sandbox, the check this fixture's `.expected.txt` requires is
described rather than run: a static scanner over every `CapabilityManifest` emitted for (or every
Kotlin source declaring a `CapabilityManifest` literal tagged for) the Play build variant, matching
each `supportedOperations` entry against an explicit denylist of prohibited operation-name
substrings —

- `downloadAndExecute`
- `dynamicCodeLoad` (unless the declaring context is specifically the Play Dynamic-Feature lane,
  mechanism (b) — a distinct, Play-legal mechanism this denylist MUST NOT also catch; the check
  needs a narrow allowlist carve-out for (b), not a blanket ban on the word "dynamic")
- `system_linker_exec` / `linkerExec`
- `execFromAppWritableStorage`

A match on a Play-flavor manifest fails the check with a non-zero exit code and rejection code
**`DIST_PLAY_DOWNLOAD_EXEC_PROHIBITED`**, blocking the PR/release. This check is **defense-in-depth
documentation and early rejection**, not the only thing standing between this constellation and
mechanism (e) actually working — §3 states the deeper, platform-level reason it cannot work at
all, on either flavor.

## 3. Platform grounding: Android W^X makes (e) impossible everywhere, not just on Play

Since `targetSdk` 29+, Android's SELinux policy blocks `execve()`/exec-mmap of files located in
app-writable storage (W^X — write XOR execute) — a fact independently confirmed (API 29+; Termux's
own November 2020 write-up of hitting exactly this wall is the commonly-cited public account). This
is **OS-level**, **channel-independent**: it applies identically whether the app came from Google
Play or was sideloaded via the `full` flavor's own distribution path. Consequences for this domain:

- **The full/sideload SKU is explicitly NOT a policy-free escape valve for mechanism (e).**
  Sideloading changes *how the APK arrived on the device*; it does not change what the OS's SELinux
  policy permits that APK's own process to do once installed. A `full`-flavor `CapabilityManifest`
  declaring `distribution.downloadAndExecuteNativeCode` would be just as non-functional as a
  Play-flavor one — the §2 prohibition is written for Play specifically because Play additionally
  reviews and can reject the *declaration itself* pre-install, but the underlying execution is
  impossible on both.
- This directly grounds §1's table: mechanism (c) (APK-bundled `jniLibs`, e.g. this repo's own
  `libaarso_llama.so`/`libaarso_sd.so`) works on both flavors precisely *because* the executable
  content ships inside the APK's own package-signed, non-writable storage at install time — it
  never touches app-writable storage as an executable artifact, so W^X never enters the picture.
  Mechanisms (a)/(b) work for the analogous reason: (a)'s downloaded content is *data* (interpreted
  source text, not something ever `execve()`'d directly), and (b)'s delivered module is installed
  through Play's own package-management path, not written to arbitrary app-writable storage and
  then exec'd.
- This document does not merely assert this as house policy — it is recorded as a platform fact a
  future implementation cannot design around, on either flavor, ever, short of Android itself
  changing its exec-restriction model.

### 3.1 Risk register: Android Developer Verification (sideload identity enforcement)

A related, separate platform change, recorded here as a risk-register item rather than folded into
the mechanism table above (it is about **identity/accountability of the publisher**, not about
what code can execute): Google's **Android Developer Verification** requires sideloaded apps to
carry a verified developer identity, with enforcement beginning **30 September 2026 in initial
launch markets**, expanding **globally in 2027**. This changes *who is accountable for* a
sideloaded APK's contents; it does **not** relax W^X, does **not** re-open mechanism (e), and does
**not** make Play's mechanism (a)/(b) carve-outs available to a sideload-only distribution — it is
an orthogonal accountability/anti-fraud requirement layered on top of the sideload channel, which
this document flags as a **near-term compliance planning item** (the `full` flavor's own
sideload-identity posture will need a decision before September 2026) rather than something this
document itself resolves. Restated explicitly per instruction: **the full/sideload SKU is not a
policy-free escape valve** — first for the W^X reason above (execution-level), and separately, on a
different axis and a different timeline, for this publisher-identity reason (distribution-level).

## 4. Toolchain license policy (FB-RAT-DIST-003 — deferred, not designed here)

**FB-RAT-DIST-003 — DEFERRED.** Cross-reference `docs/non_ratified/DEFERRED_DECISIONS.md`, owned
by a separate agent/session. This document does not design a toolchain license-compatibility
policy (e.g. what license terms a bundled interpreter, a Dynamic-Feature-delivered native module,
or an `jniLibs`-bundled executable must carry to ship legally under either flavor) — it only names
mechanisms (a)–(e) above as the taxonomy any future license-policy document would need to apply
its rules *against*, per mechanism, not per flavor alone.

## 5. Conformance test-class coverage (FB-RAT-DIST-004)

Following `COMMON_CONVENTIONS.md` §11's eight-class table. This domain has no schema of its own
(§ scope note above), so its conformance surface is entirely borrowed from
`schemas/common/capability-manifest.schema.json` plus the one adversarial fixture this document
adds.

| # | Test class | Coverage in this domain | JVM-testable |
|---|---|---|---|
| 1 | Golden serialization | Not applicable — no dedicated schema; a compliant Play-flavor `CapabilityManifest` is exactly `fixtures/common/valid/capability-manifest-valid.json` with `targetRequirements.distributionFlavor: "play"` added and no denylisted operation name present. | N/A (inherited from common) |
| 2 | State transition | Not applicable — `distribution` has no state machine. | N/A |
| 3 | Adversarial | `fixtures/devices/adversarial/play-manifest-declares-download-and-exec.adversarial.json` + `.expected.txt` (§2.3–2.4) — the REQUIRED fixture this document's task brief named. | **YES** (structural pass is the point; the policy check itself is described, not executable here) |
| 4 | Provider conformance | Not applicable — no provider interface in this domain. | N/A |
| 5 | Recovery | Not applicable. | N/A |
| 6 | Performance | Not applicable. | N/A |
| 7 | Accessibility | Not applicable — no user-facing state surface owned by this domain directly (a future distribution-settings UI surface, not built here, would inherit `CapabilityManifest`'s general obligations). | N/A |
| 8 | Compatibility | Inherited from `CapabilityManifest`'s own `schemaVersion`/`unknownFields` handling (`COMMON_CONVENTIONS.md` §3). | **YES** (inherited) |

---

## Forward pointers (owned elsewhere, not restated here)

- **`FB-RAT-DIST-003`'s toolchain license policy** — `docs/non_ratified/DEFERRED_DECISIONS.md`,
  owned by a separate agent/session (§4).
- **Android Developer Verification compliance planning** (§3.1) — a near-term (pre-30 Sep 2026)
  decision this document flags but does not resolve; likely an `OWNER_GATES.md` or
  `docs/non_ratified/DEFERRED_DECISIONS.md` item for whichever session owns release governance.
- **`docs/design/app-distribution.md`** — this repo's existing, owner-decided APK-installer
  Play/full split (mechanism (c)'s concrete instance for the specific case of installing a
  downloaded release APK, not a toolchain executable) — not superseded by this document, cited as
  precedent (see "Repo-placement / precedent note" above).
- **`docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md`** — this document's sibling in the
  device+distribution work package; no dependency either direction.
