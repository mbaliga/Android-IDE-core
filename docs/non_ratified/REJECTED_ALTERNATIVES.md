# Rejected Alternatives Register

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**What this is.** The stable-ID record of design alternatives the Fonebrew ratification register
considered and rejected. A rejection here is not a stigma on the idea — it is a decision, with a
reason, that a later session MUST NOT silently re-litigate by re-proposing the same shape under a
new name. If circumstances genuinely change, the correct move is a new proposal in a
`FB-RAT-*-NEW` section (see `EXPERIMENTAL_DECISIONS.md` for the open one) that explicitly
supersedes the old ID, not a quiet drift back toward the rejected shape.

**Per FB-RAT-COM-002:** every row below carries a globally unique stable ID (`FB-RAT-<domain>-
<number>`) independent of the decision's display topic — do not rename or renumber an ID even if
its topic label is later reworded.

## Register

| ID | Topic | Decision | Status |
|---|---|---|---|
| `FB-RAT-PORT-010` | Uncontrolled package universe | Reject an unrestricted package universe embedded inside Fonebrew. Toolchains must be versioned, verified capsules or external targets. | REJECTED |
| `FB-RAT-DIST-002` | Downloaded executable code in Play | Reject downloading executable DEX/JAR/native code in the Play distribution. | REJECTED |
| `FB-RAT-AUTH-007` | Automatic authority inheritance | Reject automatic or transitive delegation that can expand scope without user approval. | REJECTED |
| `FB-RAT-INT-003` | Shared backend or database | Reject shared databases, account backends, background services, broadcasts, and automatic synchronization for v1. | REJECTED |
| `FB-RAT-INT-004` | V1 app-to-app IPC | Reject direct IPC/AIDL as the v1 integration mechanism; use files and repository artifacts. | REJECTED |

## Notes per entry

### `FB-RAT-DIST-002` — Downloaded executable code in Play

Adversarial-fixture cross-reference: the device-and-distribution domain agent is writing a
concrete fixture for this. Reference `fixtures/devices/adversarial/` once it exists — at the time
this register was written that directory already contains
`play-manifest-declares-download-and-exec.adversarial.json` (plus its
`play-manifest-declares-download-and-exec.expected.txt` sibling), which is exactly the shape of
adversarial coverage this rejection implies. This document does not own that fixture; it only
cites the location.

### `FB-RAT-AUTH-007` — Automatic authority inheritance

Adversarial-fixture cross-reference, same pattern as above: reference `fixtures/authority/
adversarial/` once it exists — at the time this register was written that directory already
contains `decision-transitive-delegation-attempt.adversarial.json` and
`decision-child-exceeds-parent.adversarial.json` (with `.expected.txt` siblings), which are the
concrete adversarial specimens of exactly this rejected shape. This document does not own those
fixtures; it only cites the location.

### `FB-RAT-INT-003` — Shared backend or database

**Forward pointer, not resolved here.** A later loop/dual-surface contract corpus introduces
`FB-RAT-MKT-001`, a scoped amendment to this rule for deliberately-published marketplace
artifacts only. See `AMENDMENTS.md` (a later work package) for the `MKT-001` supersession line,
once that file exists. This register does not write `AMENDMENTS.md`, does not define
`FB-RAT-MKT-001`'s scope beyond the one-line pointer above, and does not weaken `FB-RAT-INT-003`
itself — the rejection stands as written until a ratified amendment says otherwise.

### `FB-RAT-INT-004` — V1 app-to-app IPC

**Binding scope note.** This rule applies to companion-import lanes (CSApp/Assay/Studio) ONLY.
ASOM AIDL pairing plus localhost HTTP (the `127.0.0.1:11435` daemon in
`asystemofcells/asystemofmodels`) predates this rule and is a ratified **daemon** relationship,
not an **import-lane** relationship — it is explicitly exempt, not a violation read narrowly. Do
not let `FB-RAT-INT-004` read as a blanket ban on all IPC anywhere in the constellation; it bans
IPC/AIDL specifically as the mechanism by which companion apps import content into Fonebrew for
v1. A future session extending an import lane MUST route it through files/repository artifacts
per this rule; a future session touching the ASOM daemon relationship is operating outside this
rule's scope entirely and does not need an exception filed against it.
