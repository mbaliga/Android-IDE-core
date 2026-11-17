# Fonebrew Diff Review (Ubuntu Touch click)

**This is a small companion for Ubuntu Touch. It is not the Fonebrew Android app and not a port of it.**
It is the honest shape that fits what the open core can run on Ubuntu Touch today (porting plan
`docs/PORTING_PLAN.md` section 6.2, rule R12). A native Fonebrew port is a separate, owner-gated decision;
see `docs/UBUNTU_TOUCH_PORT.md`.

## What it does

An offline line-diff reviewer: paste "Before" and "After", get hunks plus the same `--- / +++ / @@` unified
diff the Android Agent's review sheet shows. The diff algorithm is a line-for-line JS port of
`core-engine/.../domain/diff/LineDiff.kt`, and CI requires it to reproduce Kotlin-generated golden vectors
(`golden/linediff.v1.json`) byte for byte.

## What it deliberately is not

No models (on-device or cloud), no keys, no agent, loops, council or devices, **no network access at all**
(no `networking` policy group, a CSP with `connect-src 'none'`, `scripts/check_click.py` enforces both), no
telemetry. Text you type is saved continuously to the app's private WebView storage because Lomiri freezes
unfocused apps within seconds.

## Layout

| Path | Role |
| --- | --- |
| `manifest.json` | click name `fonebrew-diff.asoc` (the ONE place the id lives; NAMES.md row is an owner follow-up, rule R11) |
| `fonebrew-diff.apparmor` | policy group `webview` only |
| `qml/Main.qml` | whole native layer: a WebView on `www/`, refuses navigation outside the bundle. Loaded by the OS's QML runtime, nothing linked (the LGPL-linking rule I-11 is not triggered; owner to confirm) |
| `www/` | the static payload (HTML/CSS/JS, no remote resources) |
| `golden/linediff.v1.json` | Kotlin-generated vectors (regenerate: `UPDATE_GOLDEN=1 ./gradlew :shared:jvmTest --tests '*LineDiffGoldenTest*'` in `apple/`) |
| `tests/`, `scripts/check_click.py` | node parity + draft tests, headless-Chrome smoke, static click rules |
| `clickable.yaml` | pure builder, architecture `all`, framework `ubuntu-sdk-20.04` (assumption: installs on 24.04-x) |

## Build and test

```
node tests/linediff.test.cjs && node tests/drafts.test.cjs && python3 scripts/check_click.py
clickable build        # needs Docker; CI lane: .github/workflows/ubuntu-touch.yml
clickable desktop      # run on a Linux desktop
```

Evidence classes and owner actions: `docs/UBUNTU_TOUCH_PORT.md`. On-device checks: `DEVICE_CHECKLIST_UT.md`.
