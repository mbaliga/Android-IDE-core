# Design: App distribution — build, test & install on your own repos

> Status: **decided (owner) — headless data layer started.** `domain/builds/`
> (`Build`, `BuildsApi`) is built + JVM-tested. The UI (the bottom **Branches /
> Tests / Builds** room) and the installer are next, on the locked Aeon atoms.

When you develop an app on Aarso (coding-assistant + Loops on your Git repo), you
should be able to **see its branches and versions and install the build in-app** —
no leaving for the browser to grab an APK. This closes the loop from *"develop on
your repos"* to *"ship and run from the app,"* talking only to your watched host.

## The confirmed spatial map (Option 2 + riders)

The dev features layer onto the existing chat-first model as a **POWER-tier**
overlay; they don't reshuffle the core. Five honest relationships on five
directions; the top stays empty (no thumb-stretch on a tall phone):

| Direction | Room | Meaning |
|-----------|------|---------|
| **Left** | Chats | the chat's siblings |
| **Right** | Settings (now holds **Models** management) | its configuration |
| **Bottom** | **Branches / Tests / Builds** (POWER) | the codebase it stands on |
| **Zoom in** | this level's **Loops** | the engine powering it |
| **Zoom out** | the **Tree** | the history holding it |
| Top | — | (unused) |

Riders folded in: **Models management moves into Settings** (quick model-switch
stays inline in the composer; the catalog/downloads/BYO move to Settings →
Models), which frees the bottom edge for the dev room. The **pinch is fixed** to
`in = Loops, out = Tree` ("in = deeper/under, out = broader/map"). Discreetness
now comes from the **disclosure tier** (the dev room simply doesn't exist for
Core/Studio), not from burying it at an awkward edge.

Two consequences handled: the old top **download dock** becomes a slim transient
strip above the composer + the existing FGS notification (full list in Settings →
Models); and the one-time **spatial-map overlay** must teach that "underneath"
means two distinct things — **zoom-in = the loop** (process/engine) vs
**swipe-down = the codebase** (branches/builds/tests).

## Where builds & tests live

- **Builds** = the **"Builds" facet** of the bottom room (your app → branches →
  versions, each installable), *plus* the **tail of a Loop's Log** when a run
  produces one ("Build #42 · ready · install").
- **Tests** = a **signal, not a room**: the **badge on each build** (`✓ unit · UI ·
  on-device smoke` / `✗ failing` / `building…`), the **same** signal that streams
  **inline in the Loop Log** (and feeds the escalation gate), with a **Checks
  facet** for the full CI history.

## Engineering

### 1. `domain/builds/` ✅ built (headless)
- `Build(id, version, name, branch, createdAt, downloadUrl, sizeBytes, source)` +
  `BuildSource {RELEASE_ASSET, CI_ARTIFACT, DIST_BRANCH}`; `ChecksSummary` +
  `CheckConclusion {SUCCESS, FAILURE, PENDING, NONE}`.
- `BuildsApi` (pure, like `GitContentsApi`): request builders `listReleases` /
  `checks` (GitHub check-runs · Gitea combined-status) + parsers `parseReleases`,
  `parseDistBranchApks` (the `apk-dist` pattern, via the contents API),
  `parseChecks`. JVM-tested (`BuildsApiTest`). Execution rides the existing
  `GitTransport`; the token stays in `KeystoreSecret`.

### 2. Install path (next, full flavor only)
Download the APK to app storage → fire Android's **package-installer** intent
(`FileProvider` + the one-time *"install unknown apps"* grant — how F-Droid/Aurora
work). **Play policy** forbids in-app installers + `REQUEST_INSTALL_PACKAGES`, so
this lives in the **sideload/`full` flavor** (and F-Droid), never the Play build —
consistent with the existing flavor split.

### 3. Honest limits
- **Aarso doesn't *build* the APK.** A phone can't realistically run Gradle/AGP/NDK;
  the build stays in your CI/cloud (where `aarso-sd.apk` comes from today). Aarso is
  the **viewer + installer** of artifacts your CI produces.
- **Multi-platform** generalises on the *download* side (any release asset — deb/
  AppImage, exe/msi, dmg), but only **Android APKs install in-app**; desktop ones
  are downloads handed to the OS. **iOS is last** — Apple has no sideload path
  (`.ipa` needs TestFlight/notarization), so there Aarso shows/downloads but never
  installs.

### 4. The differentiated bit
Once Aarso can **install** the build it just produced, it can run an **on-device
smoke/perf pass on the real APK on your own device** — a feedback loop CI physically
can't give you (no runner has your phone). That on-device verdict becomes another
test badge + gate input.

## Build order
1. ✅ `domain/builds/` (`Build`, `BuildsApi`) — the data layer.
2. `data/BuildsRepo` — wire `BuildsApi` through `GitTransport`; aggregate the three
   sources per branch; attach `ChecksSummary`.
3. The installer (`data/ApkInstaller`: download + `FileProvider` + install intent;
   full flavor; manifest `REQUEST_INSTALL_PACKAGES`).
4. Compose: the bottom **Branches / Tests / Builds** room on the locked atoms
   (the Builds view is already mocked).
5. Surface a build at the **tail of a Loop Log**; the on-device smoke pass (§4).
