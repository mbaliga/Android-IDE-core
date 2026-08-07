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

*(Later work packages: append new `FB-RAT-<domain>-NEW-<n>` entries below this line as you
identify proposals of your own. Do not renumber `FB-RAT-WS-NEW-1` or insert ahead of it.)*
