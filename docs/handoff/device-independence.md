# Handoff — Device independence: no second machine to own and maintain

> **Status:** research brief, no decision made. Written 2026-07-31, revised same day after the
> constraint was stated precisely. Companion to `docs/STATE.md` (what's done) and `CLAUDE.md`
> (build rules).

## Why this document exists

Chasing green CI turned out to be chasing a proxy. The Actions failure is an account-level
billing block — real, but boring. What the investigation exposed is the interesting thing:
**every step of this project's loop that isn't authoring currently requires a computer that is
not the phone.** The Steam Deck, the build container and GitHub's x86 runners had all been
serving as interchangeable stand-ins for the same gap.

That contradicts the north star. `CLAUDE.md` calls the product a "post-desktop, touch-native
computing environment" that makes the phone a "sovereign primary device." Today the phone can
*author* Fonebrew and *commit* Fonebrew, but cannot *build*, *test* or *release* it.

## The constraint, stated precisely

This is the hinge of the whole document, and getting it wrong sends the work in the wrong
direction:

- ❌ **Unacceptable: a second physical device the owner has to buy, house and maintain.** A Steam
  Deck, a Raspberry Pi, a NUC, a home server. The objection is ownership and maintenance burden,
  not "compute that isn't the phone."
- ✅ **Acceptable: rented, ephemeral cloud compute.** GitHub Actions minutes are fine. Nothing to
  maintain, nothing to house, nothing that breaks and becomes a weekend.

**This kills the "quiet headless box at home" route outright** — it is precisely the thing being
rejected. And it promotes cloud CI from stopgap to a **legitimate permanent component of the
architecture**.

It also means the earlier framing of green CI as merely a proxy goal was half-wrong. As a
*diagnosis* of the dependency it was useful. But if Actions is a permanent part of the design,
then unblocking it is not a distraction — **it is on the critical path.**

## The architecture this implies

The phone does not need to be a build host. It needs to be an excellent **client** to a build
farm it doesn't own:

```
phone: author → commit (REST) → trigger CI
                                    ↓
cloud: ephemeral runner builds + tests + signs
                                    ↓
phone: list builds → check verdict → download → install
```

Zero owned devices. And this is a *better* fit for "post-desktop" than a local toolchain would
be — a phone that orchestrates disposable compute is more interesting than a phone pretending to
be a laptop.

## What's already built (the important finding)

Most of the client side of that loop exists in this repo already:

| Piece | Where | State |
|---|---|---|
| Author / edit / review per hunk | Develop → Agent, CodeLens, `domain/diff` | ✅ shipping |
| Commit + push | `GitTreeApi` (squashed commits over REST) | ✅ shipping |
| Trigger CI | `domain/builds` (`CiTrigger`) | ✅ built, JVM-tested |
| List CI-produced APKs | `BuildsApi.parseReleases` + dist-branch contents | ✅ built, JVM-tested |
| Read CI verdict | `BuildsApi.checks` (GitHub check-runs / Gitea status) | ✅ built, JVM-tested |
| Download + install | `BuildsRepo.findApkUrl` → `ApkInstaller` | ✅ built |
| The `apk-dist` convention | `BuildsRepo.DIST_BRANCH` | ✅ in use |

**The loop is largely assembled and was never exercised end-to-end, because CI has been billing-
blocked the whole time.** That reframes the work from "build a large new capability" to "unblock
one account-level thing, then verify and close the gaps in a loop that already exists."

## Honest audit — what still leaves the phone

| Step | Phone | Cloud-CI can cover it? | Notes |
|---|---|---|---|
| Author, commit, review | ✅ | — | done |
| Trigger a build | ✅ | — | done |
| Compile Kotlin → dex | ❌ | ✅ yes | runner's job |
| Build native engines | ❌ | ✅ yes | runner's job — see RAM note below |
| Run the JVM test gate | ❌ | ✅ yes | but latency, see R2 |
| Install the result | ✅ | — | done |
| **Sign / publish a release** | ❌ | ⚠️ needs design | signing keys in CI — see R3 |
| **Rebase / resolve a conflict** | ❌ | ❌ **no** | genuinely unsolved — see R4 |
| Run a shell script / `gh` | ❌ | n/a | do the work natively instead |

Only two rows are genuinely unsolved: **release signing** and **real git operations**. Everything
else is either done or is straightforwardly the runner's job.

## Routes and open questions

### R1 — Close the cloud loop (primary, near-term)
1. **Unblock Actions.** Account-level: top up minutes / raise the spending limit, or make repos
   public (unlimited free Actions). Nothing in this repo can fix it.
2. Verify the existing trigger → build → verdict → download → install loop **on the phone**, end
   to end. It has never been run; expect gaps.
3. Decide the artifact shape: does an on-demand build publish to a release, or to `apk-dist`? The
   code supports both. `apk-dist` is already the convention.
4. **RAM:** `assembleFullDebug` OOMs a ~16 GB runner; the weekly canary already adds ~12 GB swap.
   Make on-demand builds use that same hardened path, or they'll fail the first time they matter.
5. Latency and cost: what does a full native assemble cost in minutes, and how often is it
   actually needed versus a Kotlin-only build?

### R2 — On-device JVM tests (optional, quality-of-life)
`:core-engine:testFullDebugUnitTest` is Kotlin + JUnit — no native, no `aapt2`, no resource
pipeline. Running it on-device needs only a JDK and Gradle in an arm64 Android userland.

With cloud CI accepted this is **no longer on the critical path** — it's a latency optimisation
(seconds locally versus push-and-wait). Worth doing if push-and-wait proves annoying in practice;
not worth doing speculatively. Judge it after living with R1 for a few weeks.

### R3 — Release signing without a laptop
The one genuinely new design problem. Options to research: GitHub encrypted secrets holding the
keystore; Play App Signing (Google holds the key, CI holds only an upload key); a hardware-backed
key on the phone that signs an artifact CI produces. Weigh against binding rule 5's stance on key
custody — that rule is about API keys, but its spirit (keys encrypted at rest, never sent
anywhere they don't belong) should inform this.

### R4 — Real git on-device
`GitTreeApi` commits a tree; it cannot rebase, merge, or resolve a conflict. CI cannot do this
for you — it needs a human deciding. **Cloud compute does not solve this route**, which makes it
the most likely thing to force you back to a laptop at the worst moment. JGit is pure Java and
would run on-device. Research whether it fits the existing token/Keystore model, and whether it
belongs here or in the routing engine.

### R5 — On-device toolchain (explicitly deferred, not rejected)
Building the app on the phone remains the only route that removes the GitHub dependency too.
Deferred, not dead. **Revisit if:** Actions pricing or policy changes, GitHub becomes unreliable,
or R1's push-and-wait latency proves intolerable in daily use.

The one fact worth preserving for that day: `abiFilters += "arm64-v8a"` and the target phone
*is* arm64-v8a, so an on-device build would be a **native compile, not a cross-compile** — the
single biggest simplification available. Precedent: AndroidIDE builds APKs on-device with a
patched AGP plus arm64 `aapt2`/`d8`; Termux ships `openjdk-17`, `gradle`, `clang` and `cmake` for
arm64. Watch the 16 KB page-alignment requirement (`CLAUDE.md`: NDK r28 emits 16 KB-aligned libs).

### ~~R6 — A headless box at home~~ **REJECTED**
A Pi/NUC/server the phone drives over the SSH spine. Explicitly ruled out: it is exactly the
"physical device I own and maintain" being eliminated. Recorded so nobody re-proposes it.

## The honest caveat about cloud

Renting compute is not zero dependency — it's a *different* dependency. GitHub can change
pricing, alter policy, or go down, and none of that is under the owner's control. What makes it
acceptable is that it carries no maintenance burden and is **fungible**: any runner will do.

The mitigation is therefore **portability, not avoidance** — and this codebase is already ahead
here. `GitHostKind` is `GITHUB | GITEA`, so the host is an abstraction rather than a hardcoded
vendor. That is the same provider-generic stance binding rule 2 takes toward cloud model
providers, applied to build hosts. **Keep it that way:** avoid Actions-specific features that
can't be expressed against another CI, and the dependency stays swappable rather than locked in.

## Constraints any answer has to respect

- **Binding rules.** No telemetry (1). On-device default for *inference* (2) — unrelated to build
  hosting, but don't let cloud-build convenience erode it elsewhere. Key custody (5) bears
  directly on R3.
- **Flavor split.** Nothing here should need a `full`/`play` divergence, unlike the shell/toolchain
  work would have.
- **Environment honesty.** None of the on-device half can be verified in CI — no device, no
  emulator. Owner-verified until proven on the phone.

## What could not be verified while writing this

The existing trigger → build → install loop has **never been run end-to-end**, because CI has
been billing-blocked. Its components are individually JVM-tested (`CiTriggerTest`,
`BuildsApiTest`, `BuildsRepoTest`), which verifies the request builders and parsers — not that
the whole chain works against a live GitHub with a real workflow and a real APK. Expect gaps.

## Suggested first move

1. **Unblock Actions billing.** Account-level, nothing in the repo can do it, and everything else
   waits on it.
2. **Run the existing loop end-to-end from the phone** — trigger a build, watch the verdict,
   download, install. Find out what's actually missing rather than guessing.
3. **Then** decide between R3 (signing) and R4 (git) by which one bites first. R4 is the likelier
   ambush: a merge conflict at the wrong moment is exactly the scenario that reaches for a laptop.
