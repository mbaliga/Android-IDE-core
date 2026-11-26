# Home-screen widgets integration (wave 2, lane W4)

Status: design note, written before code. Scope is **integrating open core PR #26** (six App
Widgets, spec in that PR's `docs/widgets.md`), not rewriting it. Nothing here is verified on a
device or launcher (this container has neither).

## What I found (audit of `pr26-widgets`, one commit on merge-base f23f6e4)
- The audit note "merges cleanly" is stale: the PR branched 18 commits back, and a trial
  `git merge-tree` against the dev head conflicts in `ChatViewModel.kt`, `DevelopRoom.kt` and
  `RegularShell.kt`. `AppContainer.kt` and `docs/STATE.md` auto-merge.
- Manifest: six exported `AppWidgetProvider` receivers, no new permission, no service/provider.
  Exported is the platform's requirement for a launcher to bind a widget; the extra actions
  (time/zone/locale) are system broadcasts. `widgetCategory=home_screen` only (no keyguard).
- No network, no new dependency, no build-file change. Alarm is inexact `AlarmManager.set`.
- Privacy: the PR already withholds chat-derived task titles, agent objective/paths, download
  URLs/errors. **But it prints user-typed task titles (Next up) and loop names / step names
  (Loop runs) on the home screen with no opt-in.** The lane's binding rule is: nothing about a
  conversation, task or key on a home screen unless the user opted in per widget, default OFF.

## What I will do
1. Merge `pr26-widgets` with `--no-ff` into the dev head; resolve the three conflicts keeping both
   sides' intent; smallest fixes for compile/test fallout.
2. Add a per-widget **privacy gate**, default OFF, for the two private fields: task titles
   (Next up) and loop + step names (Loop runs). Pure model (`WidgetPrivacy`) in `widget/`, applied
   inside `NextUp.build` / `LoopRuns.build` so a hidden title never reaches RemoteViews at all;
   hidden rows say "Task (title hidden here)" / "Loop run" with a glyph, never an empty row.
   Persisted in a small `WidgetPrivacyStore` (private prefs, JVM-testable), toggled from a
   settings row, and a change redraws the placed widgets.
3. JVM tests for the gate and for any other widget logic lacking coverage.
4. `docs/STATE.md` dated entry: widgets exist, OWNER-UNVERIFIED on a device.

## What I will not do
- Redesign any widget, change layouts, add widgets, or touch the PR's branch (never pushed).
- Hide model names / download file names (public model ids, not user content) or counts.
- Add telemetry, network, permissions, dependencies.

## Seams
`NextUp.build` / `LoopRuns.build` take the gate as a parameter; `FonebrewWidgets.gather` reads the
store; `WidgetUpdates` observes it. Settings row lives with the other Global settings.

## Failure modes
- Store read fails: treated as OFF (private). A race between a toggle and a render redraws on the
  next push; a stale frame can show titles for at most one redraw after turning OFF - flagged
  owner-verify.
- Merge dropping dev changes: checked by diffing the file list against the pre-merge head.

## Owner-verified only
Everything the PR's Owner-verify list names, plus: the toggle row is reachable and flips the
placed widgets immediately; hidden rows read sensibly at 2x1.
