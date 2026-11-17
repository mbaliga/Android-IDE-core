# Ubuntu Touch: status, evidence, owner actions (2026-11-17)

Governing text: `docs/PORTING_PLAN.md` section 6.2 (UT-1..UT-6, "reframe, not a port") and the owner request of
2026-11-17 (build Ubuntu Touch on GitHub Actions). Rulings still binding: native only, no substitute presented
as a port (OQ-21: no Waydroid), no telemetry, no keys in the package, key custody on UT would be an app-private
file with the weaker guarantee shown (OQ-22).

## What this is, plainly

A **companion click, not a port**. `ubuntu-touch/` builds "Fonebrew Diff Review": an offline line-diff reviewer
whose algorithm is a JS port of the Android core's `LineDiff.kt`, verified against Kotlin-generated golden
vectors. A native Fonebrew on Ubuntu Touch does not exist; the plan lists every native shape as not planned
(see "Decision for the owner").

Evidence classes: BUILT-HERE (run in the Linux authoring container, seen to pass) / CI-LANE-WRITTEN (workflow
exists, never run) / CODE-ONLY (written, never run) / NEEDS-DEVICE-VALIDATION.

## What exists

| Piece | Where | Evidence |
| --- | --- | --- |
| Kotlin golden vectors: 17 cases rendered from `LineDiff` and compared byte for byte with the checked-in file | `apple/shared/src/jvmTest/.../LineDiffGoldenTest.kt`, `ubuntu-touch/golden/linediff.v1.json` | BUILT-HERE (`:shared:jvmTest`, BUILD SUCCESSFUL; file generated then re-verified without UPDATE_GOLDEN) |
| JS `LineDiff` port reproduces every golden case (stat, unified text, hunks) plus the large-input safety valve | `ubuntu-touch/www/linediff.js`, `tests/linediff.test.cjs` | BUILT-HERE (node 22: "17 golden cases + safety valve OK") |
| Draft persistence (injected storage, throwing-storage safe) | `www/drafts.js`, `tests/drafts.test.cjs` | BUILT-HERE (9 assertions) |
| Static click rules: no network policy group, CSP `connect-src 'none'`, no network APIs, no remote resources, hook files exist, honest label present, no signing material | `scripts/check_click.py` | BUILT-HERE (ok) |
| `clickable.yaml` accepted by Clickable 8.10.0's schema (a bad `builder` is rejected, ours loads; `clickable build` ran the pure copy step) | `ubuntu-touch/clickable.yaml` | BUILT-HERE (config load only) |
| `qml/Main.qml` syntax | `qmllint` (rc 0; bad QML rc 255). Lomiri/Morph imports are not resolvable here, so this is syntax only | BUILT-HERE (syntax) |
| Web UI (`index.html`, `app.js`, `style.css`) rendering, event wiring | `www/` | CODE-ONLY (`node --check` passes; headless Chrome hung in the container, smoke not seen) |
| Click package build, `click-review` | `.github/workflows/ubuntu-touch.yml` job `click-build` | CI-LANE-WRITTEN. The local `clickable build` reached the container step; Docker daemon started but the SDK image pull exhausted the container's disk, so no click was produced here |
| Headless-Chrome smoke of the real page | `tests/smoke.sh`, workflow step (non-blocking) | CI-LANE-WRITTEN |
| Install, launch, Lomiri confinement, WebView behaviour, freezing, OpenStore review | `ubuntu-touch/DEVICE_CHECKLIST_UT.md` | NEEDS-DEVICE-VALIDATION |

## Assumptions (unverified)

- Framework `ubuntu-sdk-20.04` click installs on 24.04-1.x and 2.x; `Morph.Web 0.1` and `Lomiri.Components 1.3`
  import on those images; policy group `webview` with `policy_version` 20.04 is accepted.
- `Exec=qmlscene %U qml/Main.qml` is how the confined runtime launches a QML click (taken from Clickable's
  templates, not observed).
- `QML loaded by the OS runtime` is not "linking Qt" for the LGPL rule (I-11). The owner has not ruled on this.

## Not done (gaps)

- Everything beyond the diff view: Content Hub import/export (UT-4), models, keys, agent, loops, council.
- A real Web Studio (FB-RAT-WEB-010 undecided); this payload is a tiny stand-in, not that.
- Store prep (UT-6): no OpenStore listing; click name not minted in NAMES.md.
- Native port: not started (below).

## Decision for the owner

The owner wants "native apps on all mobile devices"; the plan's native shapes are all unbuilt:
(a) QML/Qt client: needs an LGPL-linking exception (I-11, OQ-36); (b) Compose Desktop under XMir with the
`unconfined` template (the owner's chosen first spike, S-UT2, needs a 24.04 device and manual store review, high failure
risk); (c) Kotlin/Native core + QML (24+ weeks). The domain slice already shared for iOS (`apple/shared`) is the
natural seed for (c). Until one is chosen, this companion is all that exists, and it must never be described as
Fonebrew on Ubuntu Touch. Android-IDE-Studio can reuse this scaffold by changing the click name, title and payload.

## Owner actions

1. Ruling on whether the companion is acceptable at all, and on the native shape (a/b/c above).
2. NAMES.md row for the click name (`fonebrew-diff.asoc` placeholder, one place: `ubuntu-touch/manifest.json`; rule R11).
3. Run the `ubuntu-touch.yml` lane (workflow_dispatch) and report the first result; check `clickable build` against the real image.
4. Pick the device (OQ-1/OQ-37) and walk `ubuntu-touch/DEVICE_CHECKLIST_UT.md`.
5. Tag `ut-v*` for a draft release with the unsigned click; OpenStore submission and signing are yours (no key in the repo).
