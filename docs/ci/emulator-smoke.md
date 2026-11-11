# Emulator smoke (`.github/workflows/emulator-smoke.yml`)

Status: **GREEN IN CI (2026-11-11).** Run 42964815458 on `cdc5c72` passed all three tests on an
x86_64 emulator. The first run (42963566285) failed one test on a wrong UI marker in the test
itself; cold launch and the Echo send passed there too. The tests were written without an
emulator (this container has no `/dev/kvm`), so CI is the only place they run.

## Why

`ci.yml` runs the JVM gate only; the native assemble is `if: false` and nothing launches the app,
so a launch, first-frame or markdown-render crash passes CI (CLAUDE.md "CI caveat"). This job boots
the app on an emulator and drives it.

## What it proves (once green)

`app/src/androidTest/java/dev/fonebrew/smoke/EmulatorSmokeTest.kt`, no network, no model file, no key:

1. **Cold launch** - `MainActivity` reaches RESUMED and stays (it finishes itself when the
   crash-recovery screen is taken), and a first screen renders: onboarding page 1 on a fresh
   install, or the shell's Chat tab.
2. **Echo send** - Echo dev engine selected (debug builds only), one message with markdown in it,
   and an assistant turn (`echo: ...`) renders. This is the markdown path that once crashed every send.
3. **Regular shell** - each of the five bottom-tab destinations (Chat, Chats, Projects, Develop,
   Settings) composes, checked by a marker only that destination contains.
4. **JNI is lazy** - after launch and after an Echo send, `/proc/self/maps` holds no `libaarso_*`.
   The smoke APK has none to load, so an eager `System.loadLibrary` would also crash (1) and (2).

State is seeded through the public setters the UI itself calls (`setOnboardingDone`,
`InteractionModeBridge.setMode`, `setActiveModelId`). No test-only seam was added to production code.

The job also fails if the result XML shows fewer than 3 tests (a green `connectedAndroidTest` that
ran nothing is not a pass), or if the smoke APK contains any `libaarso_*` / arm64 lib.

## What it does NOT prove

- **Not the owner's phone.** An API 34 `google_apis` x86_64 emulator with software GL
  (`swiftshader_indirect`): no real SoC, GPU, thermals, RAM pressure or vendor skin.
- **No on-device inference.** No GGUF, no llama.cpp, no stable-diffusion.cpp, no PTY. The Echo
  engine streams a canned reply. Native libraries are absent from this APK by design.
- **Not the shipped binary.** The smoke APK is x86_64 with no native code; shipped `full`/`play`
  artifacts are arm64-v8a. It exercises the same Kotlin/Compose code, not the same package.
- Not gestures (asoc edge drags/pinch), voice, overlay, USB, SSH, Wear, cloud providers, or
  anything needing a network. Visual correctness (layout, colour, haptics) is not asserted.
- Not a watch test. Everything above stays owner-verified.

## The ABI route (and why)

Shipped ABI is `arm64-v8a`; GitHub runners are x86_64 + KVM. Options considered, in order:

- (i) Native x86_64 build: rejected. `core-engine/src/main/cpp/CMakeLists.txt` hard-codes
  `-march=armv8.2-a+dotprod+i8mm+fp16` (a compile error on x86), so it needs a CMake change plus
  a second llama.cpp and stable-diffusion.cpp cross-compile (the arm64 canary alone is ~14 min with
  swap). Cost and risk far beyond what a launch smoke needs.
- **(ii) Chosen:** `-Pfonebrew.smokeAbi=x86_64` swaps the ABI filter and drops the native build
  (`externalNativeBuild`) in `:app`, `:core-engine` and `:sdengine`, for that invocation only. The
  engine loads natives lazily (`EngineProvider`'s `by lazy` + `runCatching`, `SdImageEngine` and
  `NativePty` loaded on first use), which the test asserts rather than assumes.
- (iii) arm64 image on x86_64: not used (software-translated, very slow).

**Shipped artifacts are unaffected when the property is absent.** `providers.gradleProperty(...)
.orNull` is `null` unless `-P` is passed; `gradle.properties` does not set it. With `null`:
`abiFilters += "arm64-v8a"` (the previous literal) and the `externalNativeBuild` blocks are
configured exactly as before. Only `ci` passes the flag, only in `emulator-smoke.yml`; `ci.yml` and
`release-play.yml` are untouched. Any value other than `x86_64` fails configuration. The sdengine
module is the one edit outside the two named build files: it is a native module the smoke build
would otherwise cross-compile.

## Job shape

KVM udev rule, JDK 17, `setup-android@v4` with `packages: platform-tools` (legacy `tools` package
is gone), pinned platform/build-tools/NDK (no CMake), swap sized to free disk, build with retry,
AVD cache + snapshot, `reactivecircus/android-emulator-runner@v2.33.0`, API 34 google_apis x86_64,
45 min timeout. Triggers: `workflow_dispatch`, `pull_request`, push to
`claude/fonebrew-development-clzu43`.

The test step has **no** `continue-on-error`. Only the logcat/report upload does (artifact-storage
quota is a known false negative); on failure logcat is also printed into the job log.

## Locally verified vs not

Verified here: androidTest sources compile (`:app:assembleFullDebugAndroidTest` with the smoke
flag), the workflow YAML parses, the six-task gate stays green. **Not verified: that any of the
three tests passes on an emulator**, the UI markers' exact semantics (chosen by reading source),
that the NDK install is needed or sufficient, AVD cache behaviour, and run time inside the 45 min.
Expect the first run to need iteration.

## Run it locally (with an emulator, not available here)

```
./gradlew -Pfonebrew.smokeAbi=x86_64 :app:connectedFullDebugAndroidTest
```
