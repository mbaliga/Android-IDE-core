# Play release process (play flavor)

The Play build is the `play` flavor (`dev.aarso`): policy-safe catalog, no
overlay bubble / screen capture, in-app output flagging. The sideload build
stays `full` (`dev.aarso.full`) on the `apk-dist` branch.

## One-time setup (owner)

1. **Upload keystore** (never committed; `keystore.properties` is gitignored):
   ```bash
   keytool -genkeypair -v -keystore aarso-upload.keystore -alias aarso-upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Store the file OUTSIDE the repo. Create `keystore.properties` at the repo root:
   ```properties
   storeFile=/absolute/path/aarso-upload.keystore
   storePassword=…
   keyAlias=aarso-upload
   keyPassword=…
   ```
   (CI alternative: env vars `AARSO_KEYSTORE_FILE/_PASSWORD/_ALIAS`, `AARSO_KEY_PASSWORD`.)
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

## Each release

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest` — green.
3. `./gradlew :app:bundlePlayRelease` → `app/build/outputs/bundle/playRelease/app-play-release.aab`.
4. Upload to **closed testing** first (new personal accounts: Play requires a
   closed test with ≥12 testers for 14 days before production — verify current
   requirement in the Console).
5. Screenshots for the listing come from the owner's phone (no emulator exists
   in the build environment); see `store-listing.md`.
