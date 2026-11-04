# Contributing

Thanks for considering a contribution. A few practical notes before you open a PR.

## License of your contribution

This project is licensed under **Apache-2.0** (see `LICENSE`). By submitting a contribution, you
agree it's licensed under the same terms — Apache-2.0 §5 already covers this for anything you
submit, no separate CLA needed.

## Developer Certificate of Origin (DCO)

Every commit must be signed off, certifying you wrote it (or otherwise have the right to submit it
under this project's license) — the standard [Developer Certificate of Origin](https://developercertificate.org/).

Add `Signed-off-by` to your commits with `git commit -s`, or by hand:

```
Signed-off-by: Your Name <your.email@example.com>
```

A DCO check runs on incoming PRs (or will, once CI is wired for it) and blocks merge until every
commit in the PR carries a sign-off.

## Before opening a PR

- Read `CLAUDE.md` for the binding rules (no telemetry ever, on-device-first, no
  `dev.fonebrew.*`-collision naming, Keystore-only key handling) — these aren't up for debate in a PR.
- Run the JVM gate locally if you can: `./gradlew :core-engine:testFullDebugUnitTest
  :core-engine:testPlayDebugUnitTest :core-engine:checkLicense`. If you can't (no Android SDK
  locally), say so in the PR — CI will run it.
- New runtime dependencies need a license on the allowlist (`config/allowed-licenses.json`) —
  Apache-2.0/MIT/BSD/ISC/CC0/Unlicense/MPL-2.0 (case-by-case). Copyleft (including LGPL) isn't
  accepted for linking.
- New gestures need a Regular-mode control and a TalkBack action in the same PR — see `CLAUDE.md`
  and `docs/STUDIO_UX_SPEC.md`-style parity conventions where applicable.
- Be honest about what's verified. This project has no device/emulator in its usual build
  environment — anything render/gesture/haptic should be marked `owner-verify` rather than claimed
  as working.

## Trademark note

See `TRADEMARKS.md` — the code license doesn't carry trademark permission. Forks are welcome under
Apache-2.0; they need their own name and application ID.
