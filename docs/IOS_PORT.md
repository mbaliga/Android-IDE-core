# iOS / iPadOS port: status, evidence, owner actions (2026-11-17)

Governing text: `docs/PORTING_PLAN.md` section 6.3 (IOS-1..IOS-6) and the owner request of 2026-11-17
(build macOS/iOS/Windows/Ubuntu Touch on GitHub Actions CI; Apple bundle ids `com.asoc.<appname>`).
Binding rules honoured: native only (this is a native Kotlin + SwiftUI app, not a wrapper), no
telemetry, on-device default, keys in the OS keystore, no signing material in the repo.

Evidence classes: BUILT-HERE (run in the Linux authoring container, seen to pass) / CI-LANE-WRITTEN
(workflow exists, never run) / CODE-ONLY (written, never compiled) / NEEDS-DEVICE-VALIDATION.

## What exists

| Piece | Where | Evidence |
| --- | --- | --- |
| Isolated Gradle build (Kotlin 2.2.21 per OQ-38, root pins untouched) | `apple/settings.gradle.kts`, `apple/gradle/libs.versions.toml` | BUILT-HERE (configures + runs JVM target) |
| KMP `:shared`, commonMain compiled **in place** from 15 pure `core-engine` domain files (Role, MessageNode, Conversation, GeneratedToken, SamplingParams, tree/*, diff/*, bpmn/BpmnGraph) via `apple/shared/mapping.txt` | `apple/shared/` | BUILT-HERE: `:shared:jvmTest` |
| 9 existing core-engine JUnit test files reused as jvmTest + 2 new `kotlin.test` classes | mapping.txt, `apple/shared/src/commonTest` | BUILT-HERE: 70 tests, 0 failures |
| Common-Kotlin cleanliness guard | `apple/scripts/check_shared_sources.py` | BUILT-HERE (ok: 24 mapped files) |
| `PlatformTiers` (honest stubs: overlay/assist/USB/APK/local-process/image-gen unavailable; on-device LLM + cloud = "not built yet"), `SecretStore` | `apple/shared/src/commonMain/.../apple/` | BUILT-HERE (JVM tests) |
| `KeychainSecretStore` (`WhenUnlockedThisDeviceOnly`, not synchronizable) + simulator test | `apple/shared/src/iosMain`, `iosTest` | CODE-ONLY (Kotlin/Native iOS cannot compile on Linux) |
| SwiftUI proof shell (iPad-first split view; shows shared tree + diff + tier table) | `apple/ios/App/` | CODE-ONLY |
| Pure-Swift `FonebrewKit` (TierBadge glyph+word, SecretMask) + XCTest | `apple/ios/FonebrewKit/` | CODE-ONLY (no Swift toolchain run here, see below) |
| XcodeGen spec, bundle id `com.asoc.fonebrew` in ONE marked place, unsigned | `apple/ios/project.yml` | CODE-ONLY |
| `PrivacyInfo.xcprivacy` (no tracking, no collected data, no required-reason APIs declared) | `apple/ios/App/` | CODE-ONLY (plist parses; API usage not audited) |
| CI lane: JVM tests, `swift test`, Kotlin simulator test, unsigned simulator build, unsigned `.xcarchive` (draft release on `ios-v*` tags), disabled signing template | `.github/workflows/ios.yml` | CI-LANE-WRITTEN (YAML parses; SHA-pinned; no secrets) |

## What was tried and not possible here

- A Linux Swift 6.0.3 toolchain tarball downloads through the proxy, but unpacking/running it was
  blocked by the session permission layer, so no Swift file was parsed or compiled here. Not worked around.
- Kotlin/Native iOS targets (`iosArm64`, `iosSimulatorArm64`) are auto-skipped on Linux.

## Not done (honest gaps; plan step in brackets)

- Most of the 26k-LOC domain is still JVM-bound (`java.time`, `org.json`, `java.util.UUID`, `javax.xml`,
  `java.text`); only the slices listed above are shared (IOS-1 is first-slice only). Next slices need
  kotlinx-datetime / kotlinx-serialization (Attachments, Sources, Nodes, TreeFork, BpmnArchive, search).
- No Ktor/Darwin cloud client, no SSH (IOS-2). No llama.cpp xcframework / Metal engine (IOS-3).
- No Room-KMP / FTS5 on iOS, no `isExcludedFromBackup` handling (IOS-4).
- No Share Extension / App Intents; the shell is a proof shell, not the app (IOS-5).
- The Android build is NOT converted to consume `:shared`; Studio's `includeBuild` path is unchanged (additive by design).
- Root Android gate was not runnable here (no Android SDK); nothing under `core-engine/` was modified, so it is unaffected.

## Owner actions

1. NAMES.md row for `com.asoc.fonebrew` (rule R11; NAMES.md is outside this repo). Bundle id is only in `apple/ios/project.yml`.
2. Enrol in the Apple Developer Program; create the `apple-signing` GitHub environment with required reviewers and the secrets named in `ios.yml`; enable `ios-signed`.
3. Set repo variable `XCODE_APP` (e.g. `Xcode_26.0.app`) to select Xcode explicitly (OQ-38); first CI run decides whether Kotlin 2.2.21 / Xcode combination builds.
4. Pin XcodeGen version + checksum in `ios.yml` (currently `brew install`).
5. Decide the OQ-38 vs root-pin question before any attempt to make `core-engine` itself KMP.
6. TestFlight/App Store crash reports are collected by Apple, not by the app (approved exception PT:D-BD); README/store listing must name it. The shell's About text already does.
7. Device verification on iPad Pro M4 / iPhone 12 mini (everything is NEEDS-DEVICE-VALIDATION): launch, Keychain behaviour, layout.
8. Tag `ios-v*` pushes build via the workflow (the push trigger has no `paths` filter on purpose); CI-LANE-WRITTEN, never seen to trigger.

9. Known risk (predicted, not observed): `KeychainSecretStoreTest` runs as an unsigned Kotlin/Native test binary on the simulator; Keychain calls there can fail with errSecMissingEntitlement (-34018). If `ios-simulator` goes red on that test alone, it is this, not the shared domain.
