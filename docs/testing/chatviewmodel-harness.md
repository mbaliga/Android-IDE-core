# ChatViewModel JVM harness

The real `ChatViewModel` runs on the JVM under `core-engine/src/test/java/dev/fonebrew/ui/chatharness/`:
real stores over an in-memory `FakeContext`, fake DAOs behind the real `MessageTreeRepository`,
scripted engines behind the `ChatEngines` seam. Nothing in the view-model under test is faked.
Wave 1 covered the plain send / stream / stop / tree / model-switch paths; wave 2 (this doc's
second half) added the paths that used to touch Android directly.

## Pieces

| File | Role |
|---|---|
| `ChatHarness.kt` | builds the view-model; `newViewModel()` simulates a cold start; exposes `service`, `shell`, `photos`, `kvCache`, `councilStore`, `receipts`, `markerStore`, `installSdModel()` |
| `ScriptedEngine.kt` | scripted `InferenceEngine`: records loads, prompts and the `(sessionLoadPath, sessionSavePath)` of every call; optional shared `trace`; also `ServiceSpy`, `FakeShell`, `FakePhotoStore` |
| `FakeAndroid.kt`, `FakeDaos.kt` | the minimal `Context` / prefs / DAO fakes |
| specs | `ECHO_A`, `ECHO_B`, `CLOUD_SPEC` (default); opt in with `ChatHarness(specs = listOf(LOCAL_SPEC, ...))` for `LOCAL_SPEC` (LOCAL_GGUF) and `VISION_SPEC` |

## Production seams (all trailing `ChatViewModel` constructor parameters, defaults = old behaviour)

| Parameter | Default | Why |
|---|---|---|
| `startGenerationService` / `stopGenerationService` | `GenerationService.start/stop(appContext)` | the real ones build an `Intent` |
| `shellExec` | `LocalExec.run(command, dir)` | spawns `/system/bin/sh` |
| `photoStore` | `null` = the BitmapFactory downscale | BitmapFactory is not on the JVM |
| `deviceSpecReader` (wave 1) | `DeviceInfo.read(appContext)` | hardware read |

`addPendingAttachmentFromBytes` is `internal` (was `private`) so a test can feed a photo without a
`Uri`; it is not reachable from other modules.

## Test classes (wave 2)

`ChatViewModelCouncilTest`, `ChatViewModelShellEscapeTest`, `ChatViewModelLocalTurnTest`,
`ChatViewModelVisionAndMediaTest`, `ChatViewModelCompactionTest`.

## Findings from the wave-2 tests

1. **Council: one failing voice aborts every later voice.** `sendCouncil` has a single `try`
   around the whole voice loop, so the first member whose engine throws ends the fan-out; later
   members are never asked and the user sees one error. Pinned by the `@Ignore`d test
   `a failing member does not stop the members after it`. Passing sibling test: earlier members'
   replies survive and the failing member writes no fabricated node. Fix is a per-voice catch in
   `ChatViewModel.sendCouncil` (out of scope for the seam-only lane).

## What the JVM cannot test (owner-verified only)

- The real foreground service: that it starts in time, survives minimise, and is never started for
  Echo/cloud (the harness proves the view-model *asks* correctly, in the right order, on every exit).
- llama.cpp KV session files: the harness proves which paths are handed over and when a load path is
  withheld; whether the native side reads or writes a valid snapshot is device-only.
- `LocalExec` itself: its 30 s timeout kill and 20 000-char cap. It hard-codes `/system/bin/sh`, which
  a Linux JVM does not have. Follow-up (outside this lane, `data/remote/`): give `LocalExec.run` a
  shell-path parameter so a JVM test can run it with `/bin/sh`. The shell tests therefore pin the
  view-model's side only (command stripped, output stored verbatim, no model, no service, no ledger).
  The `!` path touches the interactive pty session by no route: the only collaborator is the one-shot
  runner.
- Pixel work: BitmapFactory downscale, camera/gallery intake, the SD on-device image engine, cloud
  image providers, cloud 3D providers, OCR.
- Image-turn success (needs `SdImageEngine` native or a cloud HTTP call). Only its refusals are tested.
- Everything about how any of it feels on the phone.
