# Home-screen widgets

Fonebrew ships six standard Android App Widgets (`AppWidgetProvider` + `RemoteViews`), so any launcher
can host them: Pixel Launcher, One UI, Nova, the owner's own launcher. They live in `:core-engine`
(`dev.fonebrew.widget`, manifest entries in `core-engine/src/main/AndroidManifest.xml`), so both
flavors get them, and so does Studio when its `core` submodule is next bumped. Every word comes from
`res/values/strings.xml`; the brand comes from `@string/app_name`, never a literal.

**Status: compiled and JVM-tested only. Nothing here has been seen on a phone or launcher** (the build
environment has no device or emulator). See [Owner-verify](#owner-verify).

Strategy background: `personal-tracker/snapshots/widget-strategy-2026-10-14.md` (templates §5.3,
states §5.4, privacy §6). Owner decision 2026-11-04: per-app native widgets now, no shared kit.

## The widgets

Every widget is resizable from 2x1 up. All its size layouts are sent at once
(`RemoteViews(Map<SizeF, RemoteViews>)`, API 31+, which is this app's `minSdk`), and the launcher picks
the one that fits without waking the app. Breakpoints use Android's `70n - 30` dp cell formula
(`WidgetSizes` in `WidgetModels.kt`), except the pads, whose breakpoints come from the 48 dp touch target
(below).

| Widget (picker label) | Template | Small (2x1) | Medium (2x2 / 4x2) | Large (2x3 / 4x3 and up) | Tap opens |
|---|---|---|---|---|---|
| **Next up** | `list` | the first task, its due word, "as of" | header + 3 rows | 5 rows + "N open · K overdue · M due within a day" | Tasks (Project room's To-do). Small: opens on that task, highlighted |
| **Model** | `stat` | phase ("▶ Generating", "◌ Loading model", "● Ready", "○ No model yet") + as-of, after "☁ Cloud · watched" whenever a cloud model is involved | + the active model (or, for an image / 3D / Council turn, where that turn runs) with ⌂/☁ and On-device / Cloud · watched, since/last ended | + on-device model count, downloads running | the chat (Models if there is no model) |
| **Model downloads** | `progress` | overall %, count, as-of | first download: name, state, %, bytes, bar | up to 3 downloads, "+N more" | Models (Settings room) |
| **Agent review** | `card` | state in a few words + as-of, after "☁ Cloud · watched" when a cloud model is behind it | title + ⌂/☁ provenance, time, body | + `+12 −3 lines · read 3 files · ⌂/☁ proposed by…` (or the running model's provenance) and a "Review changes" button | Develop → Agent tab |
| **Loop runs** | `list` | latest run: state, loop, step, as-of | 3 runs | 5 runs with recorded spend, run count | Loops |
| **Quick start** | `pads` | one pad: New chat (the whole widget is its target) | 4x1: three pads in a row (New chat, Tasks, Agent); 2x2: four as a grid (+ Loops) | 4x2: six pads (+ Models, Chats) | each pad its own place |

Below a template's minimum the widget collapses to one tappable summary (small layouts), never
squeezed controls. List rows are not separate targets (they would not fit 48 dp at 2x2), the whole
widget is. Pads get their 48 dp by construction: the pads breakpoints are derived from it
(`WidgetSizes.PAD_*`: a row needs 64 dp of height, two rows 120 dp; three pads across need 250 dp, a
two-wide grid 160 dp, so each pad has room for its label under its glyph). A pads widget smaller than a
breakpoint gets the next smaller layout, down to the single New chat pad, whose tap target is the whole
widget. `padBreakpoints_giveEveryPadA48dpTarget_andRoomForItsLabel` pins the arithmetic.

In the large card, progress and list layouts the content sits in a weighted block above the explicit
action or the footer, so text that outgrows the widget is what gets cut, never the "Review changes"
button, "+N more" or the counts (`WidgetResourcesTest` checks the structure). The small layouts use
4 dp of vertical padding so their two lines fit Android's 40 dp one-row minimum at 1.0x font scale; at
larger font scales on a launcher whose rows really are that short, the second line ("as of") is cut.

### Next up (`list`)
- **Shows:** open tasks (not done). Anything overdue or due within 24 h first (most overdue first),
  then the rest in the user's own To-do order: the top of the list the app shows. Due words are the
  To-do row's own (`TaskDue`: overdue / due soon / today / in Nd), and `NextUpTest` pins that they match.
  Overdue is `▲` + the word, in the accent (as in the app); DOING/BLOCKED (Studio's Board) show `◐`/`■`
  + the word. The large footer counts overdue tasks apart from those due within a day.
- **Data:** `TaskStore.tasks` (Room `TaskDao.observeAll`, manual order). Pure logic: `NextUp`.
- **Refresh:** any write to the task table (Room invalidation, so Studio's lenses writing the same
  table count too); plus one inexact, non-waking `AlarmManager.set(RTC, …)` at the next instant a due
  word changes (`NextUp.boundaryAfter`, tested to be exactly that instant); time / zone / locale change.
- **Privacy:** a task made with "Convert → Task" (`TaskSource.CHAT`) has a title cut from a chat turn,
  so it shows as "Task from a chat (title hidden here)" with `◌`. Notes are never read.
- **Empty:** "No open tasks. Your To-do list is clear."

### Model (`stat`)
- **Shows:** whether the chat is loading a model or generating, in which composer mode (Council,
  image, 3D object; the Council is always "Council", never "MoE"), and which model chats use, with
  provenance as a shape and words: `⌂` On-device, or `☁` Cloud · watched. An image, a 3D object or a
  Council turn need not run on the chat's text model, so while one is in flight the widget says where
  that turn runs instead ("☁ This turn uses a cloud model" / "⌂ This turn runs on this phone"): a cloud
  image provider, a cloud 3D object, or a Council with any cloud voice is always marked cloud. The small
  layout prints "☁ Cloud · watched" before its as-of time whenever a cloud model is involved.
- **Data:** `GenerationStatus` (new, `data/GenerationStatus.kt`): a process-scoped mirror of the
  chat's `GenPhase`. `ChatViewModel` reports to it from one collector in its `init` and resets it in
  `onCleared`; no turn logic changed. The image, 3D and Council paths also record where their turn runs
  (`turnRunOnDevice`, set when the turn starts and cleared when it ends); every other turn runs on the
  active model. Before any chat has reported in a process, the active model is
  what `DefaultModelPolicy` resolves (the same thing the chat starts from; it never resumes a cloud
  model). Counts come from `ModelRegistry.allSpecs()` and `DownloadCenter.active`.
- **Refresh:** phase / mode / active model change, model list change (downloads, providers, Nano), a
  download starting or stopping.
- **Honest limits:** a `!` shell escape sets the chat's phase too, so it reads "Generating" for its
  second or two. The wrist relay's turns (WatchBrew) are not mirrored. If the process dies mid-turn
  the widget keeps its last frame until the process next starts (then it redraws from the truth:
  nothing generating), which is why it prints "since"/"as of" with a weekday.

### Model downloads (`progress`)
- **Shows:** each download in flight: file name, Downloading / Paused / Failed · open to retry (`↓`,
  `‖`, `✗`), whole percent, bytes, a static bar. No length from the server: no bar, "N so far".
- **Data:** `DownloadCenter.active` (process-scoped). Pure logic: `Downloads`. The URL and the error
  message are never read.
- **Refresh:** `DownloadCenter.active`, reduced to whole percents (or, with no length, 8 MiB steps of
  bytes, so "N so far" moves) and rate-limited to one redraw every 2 s
  (`WidgetUpdates.DOWNLOAD_INTERVAL_MS`). Finished downloads drop off, as in the app.
- **Empty:** "No model downloads in flight."

### Agent review (`card`)
- **Shows:** whether the coding agent (Develop → Agent) is proposing, has changes waiting for review
  (count, lines added/removed, files read), finished with nothing, failed, or whether no Git host is
  connected. Provenance of the model behind the state (the running model while it works, the proposing
  model once it has proposed) is shown at every size as `⌂` On-device / `☁` Cloud · watched; the small
  layout shows the cloud mark (the watched case) before its as-of time.
- **Data:** `AgentReviewHolder` (new, `data/AgentReviewHolder.kt`), a process-scoped *mirror* of the
  Agent tab. The proposal itself still lives in `remember` inside `FilesFacet`, exactly as before: it is
  reviewed and committed there, against the host it was read from, and it is gone when the tab leaves
  composition. The tab tells the holder when a run starts (with its model's provenance), what it
  proposed, when a run failed or was cancelled, and clears it on commit and when the tab is disposed, so
  the widget never claims a proposal the app no longer has. Agent runs come from `BackgroundJobs` (kind
  `agent`). "No Git host" uses the tab's own test (`AgentRepoRunner.hasHost`: the first host has a
  token), checked with `GitHostStore.hasToken`, a presence check that never decrypts the token.
- **Privacy:** never the objective (it is in the job label; the holder doesn't keep it, and the widget
  doesn't read the label), never file paths, never the repo name. Counts and provenance only.
- **Also fixed:** an agent run cancelled by leaving the Develop room mid-read now finishes its
  `BackgroundJobs` entry as failed, instead of reading "running" forever (Chat's strip and this widget).

### Loop runs (`list`)
- **Shows:** up to five runs: running `▶`, complete `✓`, stopped `■`, failed `✗`, or the run log's own
  word with `◇`; the loop's name ("Unsaved loop" when none); the step name (a node name from the loop
  editor, never a step's output); recorded spend in large (`~… (estimated)` when the ledger says so;
  no spend line when the ledger has none, never a made-up zero).
- **Data:** the same assembly the watch uses, `WristSnapshots.loopRuns` (run log in the message tree,
  the usage ledger, live `BackgroundJobs`). It reads the whole tree, so it runs only when a widget is
  placed and a loop job starts or finishes, or a loop is renamed or deleted (`LoopStore.loops`), since
  finished rows take their loop's current name.
- **Empty:** "No loop runs yet."

### Quick start (`pads`)
- **Pads:** New chat (primary), Tasks, Agent, Loops, Models, Chats. No data. "New chat" does exactly
  what the chat's own new-chat control does (`ChatViewModel.newChat()`; refused while generating) and
  lands on the Chat lens with its composer. In a row or grid each pad shows its glyph over its label,
  and the label shrinks to 12 sp before it would be cut.

## How taps work
`PendingIntent.getActivity` with an explicit component (`MainActivity`), action
`dev.fonebrew.widget.action.OPEN`, an extra naming the destination, `FLAG_IMMUTABLE |
FLAG_UPDATE_CURRENT`, and a request code unique per widget id and tap slot (`WidgetRoutes.requestCode`).
No URL scheme. `MainActivity` is exported (it is the launcher activity), so `WidgetRoutes.parse`
accepts only known destinations and a well-formed task id; the route is acted on once (not again on
rotation or when reopened from Recents). `WidgetRouteInbox` hands it to whichever shell is up:

| Destination | asoc (`SpatialRoot`) | Regular (`RegularShell`) |
|---|---|---|
| New chat / Chat | home, Chat lens | Chat tab, Chat lens |
| Chats | Chats room | Chats tab |
| Tasks | Project room (free To-do; a Studio-installed room ignores the highlight, as with search) | Projects tab |
| Agent review | Develop room, Agent tab | Develop tab, Agent tab |
| Loops | Loops | Loops overlay |
| Models | Settings room (where Models live) | Settings tab |

No widget changes app state from the background: there is no broadcast action beyond redraws.

## Updates
Pushed, not polled (`updatePeriodMillis="0"`). `WidgetUpdates` watches the stores above and redraws
the widgets whose data changed (deduplicated, at most every 0.5 s; downloads every 2 s). It starts only
once a widget is placed (`FonebrewApp.onCreate` checks, and so does every provider update), and its
first pass redraws every placed widget from the fresh process's state. Providers also redraw on the
launcher's update, on time / time-zone / locale changes (delivered to manifest receivers), and on Next
up's own boundary tick. Reads happen off the main thread under `goAsync()` with an 8 s cap; a read
that fails or overruns draws the error state ("Fonebrew couldn't read this just now. Open the app to
see it."), never stale numbers. Renders can overlap (a provider update and a push); each takes a ticket
before it reads, and a widget only ever takes views from a newer read than the one it shows, so a slow
older read can never land last. Next up's boundary alarm is likewise set only by the newest read. Nothing is cached, so nothing can reach a backup (the app also sets
`allowBackup="false"`).

## Rules this follows
- No network, no new permission (no exact alarms), no telemetry, no new dependency.
- No conversation text, typed input, file paths, keys or secrets on the home screen
  (`WidgetResourcesTest` scans the widget package for the obvious ways to break this).
- Colour never carries meaning: every state has a shape and a word; no red, no green. Colours are the
  Hyle light/dark registers as `values/` + `values-night/` resources; violet is emphasis only.
- No engagement mechanics: no streaks, no badges, no unread counts. The task count is a plain total.
- The Aarso mirror is not a widget (it ships inert; no drift numbers anywhere).
- Text in sp, never under 12 sp; one-line ellipsized fields; `start`/`end` layout; glyphs are hidden
  from TalkBack and every tappable surface has a spoken description ("…. Opens Tasks.").

## Code map
- `widget/WidgetModels.kt`: pure models (sizes, Next up, Model, Downloads, Agent review, Loop runs,
  Quick start). JVM-tested.
- `widget/WidgetRoute.kt`: destinations, extras, validation, request codes, the route inbox. JVM-tested.
- `widget/WidgetRender.kt`: RemoteViews per size. `widget/FonebrewWidgets.kt`: reads, render, providers,
  the boundary alarm. `widget/WidgetUpdates.kt`: the push hub.
- `data/GenerationStatus.kt`, `data/AgentReviewHolder.kt`: the two small process-scoped hoists.
- Resources: `res/layout/widget_*.xml` (templates and picker previews), `res/xml/widget_info_*.xml`,
  `res/values/widget_{colors,dimens,styles}.xml`, `res/values-night/widget_colors.xml`,
  `res/drawable/widget_*.xml`.
- Tests: `src/test/java/dev/fonebrew/widget/` and `data/GenerationStatusTest.kt`.

## Owner-verify
Only a device can confirm these; none has been checked.
- [ ] All six appear in the picker of Pixel Launcher, One UI, Nova and Orrery Home (once it hosts App
      Widgets), each with its preview.
- [ ] Each one resizes through its layouts (2x1, 2x2, 4x2, 4x3; Quick start 4x1) and the right layout
      shows at each size; nothing clips at 1.3x and 2x font scale.
- [ ] Glyphs render in the system font (`⌂ ☁ ▲ ◐ ◌ ◆ ✓ ✗ ‖ ↻ ▤ ☰ ‹›`); none is a tofu box.
- [ ] Day and night colours follow the system theme; the overdue accent switches with it.
- [ ] Every tap lands where the table above says, in both asoc and Regular modes, from a cold start and
      with the app already open; "New chat" does not fire again on rotation or from Recents.
- [ ] Next up updates when a task is added, completed or edited in the app (and in Studio), and its due
      words change at the boundary (late is expected: the alarm is inexact).
- [ ] Model reads Loading → Generating → Ready during an on-device turn and shows Council / image modes;
      after force-stopping during a turn it does not keep claiming "Generating" once the app starts again.
- [ ] With an on-device chat model, an image from a cloud provider (no SD model downloaded), a cloud 3D
      object and a model-diversity Council with a cloud voice each read "☁ This turn uses a cloud model"
      / "Cloud · watched" (and "☁ Cloud · watched" on the small layout) while in flight.
- [ ] Quick start: a 4x1 shows three labelled pads, a 2x2 four and a 4x2 six, each at least a finger
      tall with its whole label; a strip too short or narrow for them shows the single New chat pad.
- [ ] Large Agent review, Model downloads and the large lists keep their button / footer visible when
      resized to their shortest large size, at 1.0x and 1.3x font scale.
- [ ] Model downloads tracks a real multi-GB download, pause, resume and failure without redrawing
      more than about every 2 s.
- [ ] Agent review shows Working (with the run's ⌂/☁), then "N changes to review" with the right
      provenance; it clears after commit and when you leave the Develop room's Agent tab (the proposal is
      gone in the app too); a cloud proposal says "watched".
- [ ] Loop runs shows a running run, then its finished state and spend.
- [ ] TalkBack reads each widget's description and each pad's label, not the glyphs.
- [ ] OEM battery managers (One UI and others) don't block the redraws or the due-boundary alarm.
