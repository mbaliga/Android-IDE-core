# userland-probe

A standalone Android app that measures, on the owner's own phone, what a Linux userland would be
allowed to do. Design and how to read the results: `docs/design/userland-proot.md`.

- Not part of the root Gradle build and not part of the core gate. Build it from this directory:
  `./gradlew :app:assembleDebug` (needs the same Android SDK/NDK as core; reads `../../gradle/libs.versions.toml`).
- Default build: no INTERNET permission, no network code reachable, no GPL code or binary anywhere.
- One screen: Run, then Copy. Nothing is uploaded; paste the copied report where you want it.
- `:app:assembleStepTwo` builds the disabled-by-default step-two experiment (a real proot against a
  Debian rootfs). Gated on the owner's legal ruling; empty config, so it fetches nothing as shipped.
- Every result is a measurement of one device at one moment. None has been run by the author.
