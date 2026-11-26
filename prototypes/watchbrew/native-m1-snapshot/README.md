# WatchBrew native demo — milestone M1

A standalone Wear OS application in ide-core. Application ID: `dev.aarso.watchbrew.demo`.
Target device: Galaxy Watch8 LTE. Minimum Android API: 30. No phone/core-engine/native-model
module dependency. Wear Compose 1.5.0 matches the existing Compose 1.8 BOM; new AndroidX
runtime libraries are Apache-2.0 and covered by this module's license gate.

## Build

With JDK 17 or 21 and Android SDK 36 available:

```sh
./gradlew -PwatchbrewOnly=true :watchbrew:testDebugUnitTest :watchbrew:lintDebug :watchbrew:checkLicense :watchbrew:assembleDebug
```

`watchbrewOnly` configures just the watch module and skips the phone's Hyle/native submodules.
The regular settings still include all existing modules plus WatchBrew. The isolated build is
intentional: it does not assert that the execution-fabric phone branch builds successfully.

APK: `watchbrew/build/outputs/apk/debug/watchbrew-debug.apk` (debug signed; not a store release).
An independent GitHub Actions workflow runs this gate when watch/build inputs change.

## What is implemented

- Wear Compose scaling list, rotary scrolling, labeled 48 dp chips and Back-to-home controls.
- Home, Capture, Task, Message, next-request Mode, regeneration review, Host/options and demo
  controls. Single / Council models / Council experts are next-request metadata in this demo.
- Keyboard entry and opt-in system dictation, followed by review/send. Uses the installed speech
  activity; no direct microphone permission. Offline speech is requested but not guaranteed.
  The system speech provider may use network access under its own settings. Cancel/unavailable
  speech leaves the draft intact and offers keyboard input.
- Local atomic snapshot of draft, reply context, stable request IDs, bounded FIFO outbox,
  selected host/mode, task state, ratings, branch and last 100 demo events. Writes run off the UI
  thread and are serialized. Accepted local UI state follows a successful save. Failed load/save
  locks changes and preserves prior saved data; reopening retries loading it.
- Explicit reconnect/sync; no automatic replay. Queued requests retain their selected host and
  mode. Newly captured requests never overtake the queue. Capacity: 10; a full queue retains the
  unsent draft. An active request cannot be replaced by a second send.
- Stop request and host confirmation are different states. Completion can win the race.
- E-ink is an independent, persisted preference. It does not affect watch execution. Native
  display pairing/rendering remains M3; the browser prototype demonstrates that interaction.

This is a **fake-host application**, with explicit simulation controls. There are no model calls,
builds, credentials, Git operations, real cloud/computer/phone connections, background polling,
foreground services or wake locks. The app itself requests no internet/microphone permissions.
Speech delegation is the one user-triggered external service interaction.

The demo snapshot is not the portable Fonebrew conversation archive. Real conversation IDs,
append-only history and compaction provenance require the M4 integration. Host commands will
need persist-before-send, durable acknowledgment and replay deduplication in M2. The in-process
fake adapter only returns sample responses; it must not be replaced with network side effects
inside the reducer.

## Local verification — 2026-10-15

The isolated WatchBrew build completed with a debug APK, 13 passing JVM tests, Android lint
with no errors, and a passing dependency license gate. This does not validate the broader phone
build or physical watch behavior. Lint may report newer dependency versions; Wear is deliberately
pinned to the existing Compose compatibility line.

## Device acceptance (pending)

1. Install on a test Watch8 using Android Studio or paired wireless ADB. Launch **WatchBrew Demo**.
2. Capture text, leave/reopen the app, and verify the draft. Dictate; cancel once, then accept a
   transcription. Confirm nothing starts before Send.
3. In Simulation controls disconnect; queue two requests. Force-stop/relaunch. Reconnect, then
   explicitly Sync next. Confirm FIFO and stable mode/host in the task detail.
4. Request stop. Confirm it remains pending until Confirm requested stop. Repeat with Deliver
   sample result arriving first.
5. Rate the sample message, reply, switch next-request mode and review regeneration. Confirm a
   new branch and retained recent events.
6. Toggle optional e-ink off and complete a sample task. The display is not needed.
7. Check round-screen clipping, keyboard visibility, large fonts, TalkBack labels, rotary scroll,
   system back/swipe, app backgrounding and process death. Verify persisted state after writes;
   an OS kill before a save completes may lose the most recent in-flight keystroke.
8. Measure battery and thermal behavior on physical hardware. Fake-host tests cannot establish
   LTE/network energy cost; measure those again after the real transport milestone.

No physical watch/emulator runtime validation is claimed. JVM tests cover state behavior and
snapshot round trips, not Android AtomicFile crash recovery or actual dictation/rotary behavior.

References: [Wear Compose](https://developer.android.com/training/wearables/compose),
[power guidance](https://developer.android.com/training/wearables/apps/power),
[product concept](../docs/design/watchbrew.md).
