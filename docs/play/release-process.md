# Play release process (play flavor)

The Play build is the `play` flavor (`dev.aarso`): policy-safe catalog, no
overlay bubble / screen capture, in-app output flagging. The sideload build
stays `full` (`dev.aarso.full`) on the `apk-dist` branch.

## One-time setup (owner)

1. **Upload keystore** (never committed; `keystore.properties` is gitignored):
   ```bash
   keytool -genkeypair -v -keystore fonebrew-upload.keystore -alias fonebrew-upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Store the file OUTSIDE the repo. Create `keystore.properties` at the repo root:
   ```properties
   storeFile=/absolute/path/fonebrew-upload.keystore
   storePassword=…
   keyAlias=fonebrew-upload
   keyPassword=…
   ```
   (CI alternative: env vars `FONEBREW_KEYSTORE_FILE/_PASSWORD/_ALIAS`, `FONEBREW_KEY_PASSWORD`.)
2. Play Console: create the app (`dev.aarso`), **enroll in Play App Signing**
   (Google holds the app key; the keystore above is only the upload key).
3. Fill: Data safety (`data-safety.md`), content rating (`content-rating.md`),
   GenAI declaration (`genai-declaration.md`), privacy policy URL (hosting —
   owner decision; repo is private so raw links won't serve).
4. **Set the output-report email** in
   `app/src/play/java/dev/aarso/flavor/InvocationFeatures.kt` (`FLAG_REPORT_EMAIL`).

## Console declarations needed

- **Foreground service — dataSync** (GenerationService, DownloadService): "keeps
  on-device model inference and user-initiated multi-GB model downloads alive
  while the app is backgrounded; downloads show notification progress." The play
  flavor has **no** specialUse / mediaProjection / SYSTEM_ALERT_WINDOW.
- Note: Android 15+ budgets dataSync (~6h/day). Downloads resume from `.part`
  via Range requests, so interruption is cheap.

## Build & sign in CI (GitHub Actions — chosen path)

The signed AAB is built by `.github/workflows/release-play.yml`. The upload key
lives ONLY in repository Secrets (never committed); the workflow base64-decodes it
to an ephemeral file and points the build's `FONEBREW_KEYSTORE_*` env vars at it.

**One-time — add these repository secrets** (Settings → Secrets and variables →
Actions → New repository secret), on the **core** repo (`Android-IDE-core`), which
is what produces the AAB — Studio is not part of this build:

| Secret | Value |
|---|---|
| `FONEBREW_KEYSTORE_BASE64` | `base64 -w0 fonebrew-upload.keystore` (the upload keystore, encoded) |
| `FONEBREW_KEYSTORE_PASSWORD` | store password |
| `FONEBREW_KEYSTORE_ALIAS` | key alias (e.g. `fonebrew-upload`) |
| `FONEBREW_KEY_PASSWORD` | key password |

Constraints, up front: the native cross-compile is memory-heavy (the job adds 12 GB
swap, same as CI's native-assemble canary); and on this **private** repo the job
won't start at all if Actions minutes / the spending limit are exhausted (runs die
in ~3-6 s with no runner assigned — an account-level billing block, not a code
error; see `docs/STATE.md` §9). Resolve minutes before expecting a green run.

Automated Play upload (service-account JSON + `r0adkll/upload-google-play`) is
deliberately not wired yet — no Play service account exists. The workflow produces
the AAB as a downloadable artifact; upload it by hand for now.

## Each release

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, commit.
2. Tag it: `git tag v0.1.0 && git push origin v0.1.0` (or run the workflow via
   **Actions → Release (Play AAB) → Run workflow**). The workflow runs the gate
   (`testFullDebugUnitTest testPlayDebugUnitTest checkLicense`), builds
   `:app:bundlePlayRelease`, verifies the AAB is signed, and uploads it.
3. Download the `app-play-release-aab` artifact from the run summary →
   `app-play-release.aab`.
4. Upload to **closed testing** first (new personal accounts: Play requires a
   closed test with ≥12 testers for 14 days before production — verify current
   requirement in the Console).
5. Screenshots for the listing come from the owner's phone (no emulator exists
   in the build environment); see `store-listing.md`.

### Local alternative (no CI)

`keystore.properties` at the repo root (gitignored) + `./gradlew :app:bundlePlayRelease`
produces the same signed AAB on your machine. Same output path:
`app/build/outputs/bundle/playRelease/app-play-release.aab`.
