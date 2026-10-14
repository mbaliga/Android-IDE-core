# :watchbrew — WatchBrew, the Wear OS app

The wrist surface of Fonebrew. **A sovereign remote surface, not a second computer:** the phone
stays the only place where inference runs, keys live and the message tree grows; the watch asks,
shows what the phone says, and checks tasks and loop runs at a glance. The design — and every
DECIDED default below — is [`docs/design/watchbrew.md`](../docs/design/watchbrew.md); this module
follows it exactly and does not widen it.

> **Honest scope.** The build container has no watch, no emulator and no paired phone. What is
> machine-verified here: the pure logic (connection state machine, reply-chunk accumulation, task
> reduction, loop/spend/complication/tile derivations, the message router and cache) and the module's
> hard lines (dependency law, no network, applicationId parity, brand-asset parity), both flavors
> compiling and assembling. **Everything that needs a device is owner-verified** and listed in
> [`OWNER_VERIFY.md`](OWNER_VERIFY.md): pairing, Data Layer delivery, RemoteInput/voice, streaming
> feel, how Compose/Tile/complication actually render, battery.

## What it does (v1)

| Surface | What | Where |
|---|---|---|
| **Ask** | Speak or type (RemoteInput) → `AskRequest` to the phone → the reply streams in chunk by chunk. Every reply shows **who answered** — glyph + words, and the **watched-object marker for cloud** ("cloud · watched"), exactly like the phone UI. Errors and a missing phone are rendered states, never a bare spinner. | `ui/AskScreen.kt`, `core/Ask.kt` |
| **Tasks** | The phone's task list. Tapping asks the phone to toggle; **the row shows only the state the phone last confirmed** (optimistic-free — the phone is the single writer). Offline, the last confirmed list stays with its age. | `ui/TasksScreen.kt`, `core/Tasks.kt` |
| **Loops** | Read-only run glances: state shape + word, step, the run's real spend (`~` + "(estimated)" when it is an estimate; "not recorded", never `0`, when the ledger has no figure). | `ui/LoopsScreen.kt`, `core/Loops.kt` |
| **Tile** | One Tile: Ask shortcut + a snippet of the last reply, its provenance and its age. | `tile/WatchTileService.kt`, `core/Surfaces.kt` |
| **Complication** | One SHORT_TEXT complication: the pending-task count. Unknown is `--` (never `0`), an old count carries a trailing `?`, above 99 reads `99+`. Push-driven, no polling. | `complication/…`, `core/Surfaces.kt` |

Navigation is a `SwipeDismissableNavHost` (Home → Ask / Tasks / Loops; swipe right to go back).

### Connection honesty

The phone link is a **first-class rendered state** with its own shape, headline and one sentence of
detail: checking / linked / not reachable (no phone, link error, Play services missing) / versions
don't match (the contract's `VersionMismatch.reason` names which app to update). "Linked" means *the
radio link is up* — Fonebrew's own version shows only after it says hello, and an ask the phone never
answers becomes "No answer yet" after a timeout (a late reply still revives it). A reply stream that
ends without a terminal chunk is "Phone link lost" with whatever arrived shown as **partial**, never
as an answer.

### Colour-blind rule (the owner is red-green colour-blind)

State is carried by **shape + words** only (`core/Glyph.kt`, 16 vector shapes in `res/drawable/glyph_*.xml`).
The palette has no success-green, warning-amber or error-red; Wear's required `error` slot is filled
with paper. `GlyphLawTest`, `WatchPaletteTest` and `WatchbrewSourceLawTest` enforce this.

## Layout

```
watchbrew/
  src/main/kotlin/dev/fonebrew/watch/
    core/       PURE Kotlin, JVM-tested: Connection (state machine), Ask (chunk accumulation + failure
                ladder), Tasks, Loops, Surfaces (complication + tile), MessageRouter, SnapshotCache,
                ReplyPaging, WatchPalette, … — no android.*, enforced by WatchbrewSourceLawTest
    runtime/    PURE: WatchController (state, intents, watchdogs, persistence), ReplyStreamReader,
                PhoneLink (the one outbound interface) — driven in tests by a fake link + virtual time
    link/       Android glue, device-only: DataLayerPhoneLink, WatchListenerService, prefs store
    ui/         Compose for Wear OS (device-only): theme, screens, glyph icons
    tile/ complication/   the Tile and the complication (device-only rendering)
  src/main/res/           16 glyph vectors, launcher icon (brand mark), strings
  src/test/               unit tests (see "Gate" below)
```

## Dependency law (machine-enforced by `WatchbrewModuleGuardTest`)

- Depends **only** on `:watch-bridge` (the wire contract) + `androidx.wear` / Wear Compose +
  `play-services-wearable` + `activity-compose`. **Never `:core-engine`** (~130 MB of native engine libs
  and the whole substrate) and never `dev.aarso:hyle` (phone Compose).
- **No network, no keys.** No `<uses-permission>` at all (so no INTERNET), no HTTP/analytics/crash
  library, no network or credential API in any source. The Wearable Data Layer is **device-local pair
  transport** (Bluetooth/Wi-Fi between the paired phone and watch) — it is not telemetry and nothing
  leaves the pair. The watch never holds, receives or sends an API key (the wire contract has no field
  that could carry one); keys live only in the phone's Keystore.
- **On-device default, cloud watched**: the wrist never hides who answered, and a reply whose chunks
  disagree is shown as watched (the flag only ratchets up).

## Hyle on the wrist

Colour tokens only — ink, paper and the brew accent ramp — copied into `core/WatchPalette.kt` and mapped
onto Wear Compose Material in `ui/WatchTheme.kt`. **No Hyle dependency, no ported components** (design
doc section 3). The duplication is named, not hidden: `WatchPaletteTest` reads Hyle's own `Color.kt`
from the submodule and fails the gate if any mirrored value drifts. **Follow-up (owner-gated, in the
Hyle repo, not done here):** a *wear target* in `mbaliga/Hyle-Design-System` that publishes these tokens
(and later Wear-shaped components), after which `WatchPalette.kt` is deleted for a dependency.

## ApplicationId and signing — the Data Layer constraint

The Wearable Data Layer only delivers between a phone app and a watch app that share **applicationId**
*and* **signing key**. Therefore:

| flavor | phone (`:app`) | watch (`:watchbrew`) |
|---|---|---|
| `full` (sideload) | `dev.aarso.full` | `dev.aarso.full` |
| `play` | `dev.aarso` | `dev.aarso` |

(`WatchbrewModuleGuardTest` compares this module's `applicationId`/flavors against `app/build.gradle.kts`
and fails on any mismatch — a mismatch silently breaks every message in both directions.)

**Sign the phone build and the watch build you pair with the same key.** Release builds read the same
gitignored `keystore.properties` / `FONEBREW_KEYSTORE_*` environment as `:app`. Debug builds on one
machine share that machine's default debug keystore, which satisfies the rule for dev sideloads — but
**sideload builds of BOTH must come from the same keystore**: a phone APK from CI and a watch APK from
your laptop will install fine and then never see each other. Version spine: the watch starts fresh at
`0.1.0 (1)`, independent of the phone's line.

## APK budget (measured, not estimated)

**No native code of ours** (no NDK/CMake, no `jniLibs`, no `assets/`; `WatchbrewModuleGuardTest` pins it) and
a handful of small resources (16 vector glyphs + the two copied brand PNGs, about 100 KB on disk). The
only native code in the APK is AndroidX Compose UI's tiny `libandroidx.graphics.path.so` (7 to 11 KB per
ABI). ABIs are deliberately *not* filtered: many Wear OS watches run 32-bit userspace, and an arm64-only
APK would not install on them.

What actually weighs anything is the **unshrunk dex of Compose + Wear Compose + ProtoLayout**. Measured
in the build container (2026-10-14):

| build | size | note |
|---|---|---|
| `assemble{Full,Play}Debug` | **31.4 MB** (31,386,125 B) | unshrunk dex, as built |
| `assemblePlayRelease` (v1 setting: `isMinifyEnabled = false`) | **25.0 MB** (25,018,733 B, unsigned) | what v1 ships if you do nothing |
| *trial only, not committed:* release with R8 + resource shrinking, default `proguard-android-optimize.txt`, no custom rules | **3.4 MB** (3,376,057 B, unsigned) | built without R8 errors; **never run on a device** |

v1 keeps `isMinifyEnabled = false` on purpose: R8 on a Wear Compose + Data Layer + Tile app is exactly the
kind of change that can only be proven on a real watch, and none is available here, so shipping it would
trade a known size for an unverified risk. The follow-up is one flip (`isMinifyEnabled = true`,
`isShrinkResources = true`, `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))` in the
`release` block) plus an on-device smoke pass over Ask / Tasks / Loops / Tile / complication. Nothing in our
own code is reflection-based (the wire types are hard-coded strings, by the bridge's design), and the
manifest-referenced services are kept automatically.

Merged-manifest check (`aapt2 dump badging` on the built APK): **no `INTERNET`, no network permission of any
kind.** The only `uses-permission` in the merged manifest is
`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, an app-private signature permission that AndroidX
Core injects into every app (it is not a capability grant and is not ours).

## Icon

The launcher icon and the complication glyph are **byte-identical copies** of the phone app's canonical
monochrome brand assets (`core-engine/.../drawable-nodpi/ic_launcher_monochrome.png`, `ic_stat_fonebrew.png`
— derived from the owner's drink-on-a-lanyard mark, owner ruling 2026-09-06). They are copied, never
redrawn (`WatchbrewModuleGuardTest` pins byte equality). When the owner's real monochrome exports replace
the phone's placeholders, replace these two files too.

## Wire contract

Every message is a `:watch-bridge` envelope; every path is a `WatchPaths` constant. The watch sends only
`ASK`, `TASK_TOGGLE` and an empty-body `SYNC`; it listens on `HELLO`, `TASKS`, `TASK_TOGGLE_RESULT`,
`LOOPS` (messages) and `ASK_REPLY` (a channel of length-prefixed `AskChunk`/`AskError` frames read with
the bridge's `FrameDecoder`). A frame that does not decode **damages the reply** rather than being
skipped — a reply with a hole in it is never shown as complete.

## Deliberately deferred (named follow-ups, not silent gaps)

- Hyle **wear target** / Wear-shaped Hyle components (Hyle repo; see above).
- **R8/minification** of the watch APK: 25.0 MB to 3.4 MB in the container trial above; held back until it
  can be smoke-tested on a real watch.
- **Ambient / always-on** rendering of the Compose screens (v1 uses the system default).
- **Tile preview** artwork for the tile carousel (the launcher glyph is the placeholder preview).
- A **GMS-free transport** (raw BLE GATT) — a real project; parked per design doc section 2.
- **Standalone** mode, **council editing**, tree surgery, the IDE, image/3D — out by design (section 4).
- **Apple Watch** — gated on the owner verifying this on a real Wear OS device (design doc section 5).

## Gate and measurements

```
export LANG=C.UTF-8 LC_ALL=C.UTF-8 ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk
./gradlew :watchbrew:testFullDebugUnitTest :watchbrew:testPlayDebugUnitTest :watchbrew:checkLicense
./gradlew :watchbrew:assembleFullDebug :watchbrew:assemblePlayDebug
```

(Run Gradle unpiped and read its exit code: a piped run reports the pipe's status, not Gradle's.)
