# Handoff — Device independence: building Fonebrew on Fonebrew

> **Status:** research brief, no decision made. Written 2026-07-31 after a concrete trigger.
> Companion to `docs/STATE.md` (what's done) and `CLAUDE.md` (build rules).

## Why this document exists

Chasing green CI turned out to be chasing a proxy. The Actions failure is an account-level
billing block — real, but boring. What the investigation actually exposed is the interesting
thing: **every step of this project's loop that isn't "type words at a model" currently requires
a computer that is not the phone.** The Steam Deck, this container, and GitHub's x86 runners are
interchangeable stand-ins for the same missing capability.

That matters because it contradicts the north star directly. `CLAUDE.md` says the product is a
"post-desktop, touch-native computing environment" that makes the phone a "sovereign primary
device." Today the phone can *author* Fonebrew and *commit* Fonebrew, but it cannot *build*,
*test*, or *release* Fonebrew. The tool cannot yet reproduce itself. Until it can, the sovereignty
claim has a footnote, and the footnote is a second device.

This is not an argument that the second device must vanish tomorrow. It is an argument that
"which device does this step need?" should be a tracked property of the roadmap rather than an
unexamined assumption.

## Honest audit — where the loop leaves the phone today

| Step | Works on phone? | What it needs | Blocker |
|---|---|---|---|
| Author / edit code | ✅ yes | Develop → Agent, CodeLens | — |
| Review a ChangeSet per hunk | ✅ yes | `domain/diff` | — |
| Commit + push | ✅ yes | `GitTreeApi` over REST | — |
| Install a built APK | ✅ yes | `ApkInstaller` | — |
| Drive a remote machine | ✅ yes | SSH spine (`SshjTransport`) | *requires the machine to exist* |
| **Run the JVM test gate** | ❌ no | JDK 17 + Gradle | no JVM or Gradle on device |
| **Compile Kotlin → dex** | ❌ no | AGP, `aapt2`, `d8`/`r8` | SDK ships those as x86-64 host binaries |
| **Build native engines** | ❌ no | NDK r28, CMake, clang | NDK ships a `linux-x86_64` host toolchain only |
| **Assemble the APK** | ❌ no | all of the above, **>16 GB RAM** | the CI runner OOMs at ~16 GB today |
| **Sign / publish an AAB** | ❌ no | keystore, bundletool | — |
| **Rebase / resolve a conflict** | ❌ no | real git | REST API can commit trees, not rebase |
| **Run a shell script / `gh`** | ❌ no | shell + coreutils + binaries | W^X since API 29; nothing bundled |

The pattern: **the phone is already a good authoring and publishing client. It is not yet a build
host.** Everything red in that table is downstream of one missing capability — executing a real
toolchain locally.

## One fact that makes this more tractable than it looks

`abiFilters += "arm64-v8a"` — a single target ABI, and the target phone *is* `arm64-v8a`.

So an on-device build is a **native compile, not a cross-compile.** Host architecture equals
target architecture. That removes an entire category of difficulty: no cross toolchain, no
sysroot mismatch, no "build a compiler that emits a foreign arch." The NDK exists here to provide
(a) a clang that targets Android/bionic and (b) the bionic sysroot — both of which a native arm64
Android userland already has.

This is the single strongest lead in this document and the first thing to verify.

## A decomposition ladder — do not treat this as one problem

Each rung is independently useful and independently shippable. Nobody has to reach the top for
the work to pay off.

- **L0 — author, commit, install.** ✅ Already true today.
- **L1 — run the JVM unit gate on-device.** `:core-engine:testFullDebugUnitTest` is Kotlin +
  JUnit. **No native code, no resource pipeline, no `aapt2`.** It is the whole gating check in
  CI. If only one rung ever lands, it should be this one: it makes "did I break it?" answerable
  without another machine, which is most of the day-to-day value.
- **L2 — build a debug APK reusing prebuilt `.so` files.** Decouple the Kotlin/UI build from the
  native engine build. Most changes to this project never touch C++. If the engines ship as
  prebuilt artifacts, an on-device build only needs AGP + `aapt2` + `d8`.
- **L3 — build the native engines on-device.** llama.cpp and stable-diffusion.cpp. Hardest rung,
  rarest need.
- **L4 — sign and publish from the phone.**
- **L5 — retire the x86 CI.** A self-hosted runner *on the phone* would solve the minutes problem
  and the device problem simultaneously — but only once L1/L2 work.

## Route families and the open questions under each

### R1 — Native toolchain on-device (Termux-class userland)
The precedent that matters most is **AndroidIDE**, which genuinely builds APKs on-device using a
patched AGP plus community arm64 builds of `aapt2` and `d8`. Termux ships `openjdk-17`, `gradle`,
`clang`, and `cmake` for arm64 already.

- Can AGP 8.x run under an arm64 JVM if `aapt2`/`d8` are replaced with arm64 builds? What exactly
  does AGP hardcode about host binaries?
- How does AndroidIDE handle this, and is its approach reusable or a hard fork?
- Does Termux's clang produce libraries the app can load from `jniLibs`? **Note the 16 KB page
  alignment requirement** (`CLAUDE.md`: NDK r28 emits 16 KB-aligned libs) — this likely needs
  `-Wl,-z,max-page-size=16384` explicitly.
- W^X: does the toolchain need to live in an exec-blessed location, and does that force the
  "ship binaries as `lib*.so`" packaging discussed for the shell work?

### R2 — Reduce what has to be built
- Publish `llama.cpp`/`stable-diffusion.cpp` JNI wrappers as prebuilt AARs (own Maven or a
  GitHub release), consumed like `dev.aarso:hyle` already is via the submodule/`includeBuild`
  pattern. Then L2 stops depending on L3 entirely.
- How often do the native sources actually change? If it's quarterly, on-device native builds may
  never be worth solving.

### R3 — RAM and thermals, the underrated blocker
The native assemble **OOMs a ~16 GB x86 runner** and the weekly canary adds ~12 GB of swap. A
phone has large unified RAM but no swap by default and aggressive thermal limits.

- What is the actual peak RSS of `assembleFullDebug`, and how much is parallelism (`-j`) rather
  than an irreducible working set?
- Does trading time for memory (`-j2`, smaller Gradle heap) fit in a phone's budget?
- Does a long compile thermally throttle to the point of being useless?

### R4 — Make the second device invisible rather than absent
A headless always-on box the phone drives over the existing SSH spine. **This does not achieve
device independence** — it relocates the dependency. Include it as an honest baseline to measure
the other routes against, and as the pragmatic answer if R1/R3 prove infeasible.

### R5 — Real git on-device
Independent of the build question. `GitTreeApi` can commit a tree but cannot rebase, merge, or
bisect. JGit is pure Java and would run on-device; is it viable against the existing token/
Keystore model, and does it belong in this repo or the routing engine?

## Constraints any answer has to respect

- **Binding rules are not negotiable.** No telemetry (rule 1). On-device is the default (rule 2).
  Keys stay in the Keystore and go nowhere but their provider (rule 5).
- **Flavor split.** A general-purpose local toolchain is a Play-policy risk, like overlay,
  screen-capture and USB-host already are. Assume `full`-flavor only unless research says
  otherwise.
- **APK size.** Bundling a toolchain is tens to hundreds of MB. State the number; don't hand-wave.
- **Environment honesty.** None of this can be verified in the build container — no device, no
  emulator. Every claim about on-device execution is owner-verified until proven on the phone.

## What could not be verified while writing this

Everything about on-device execution behaviour: W^X enforcement specifics, whether Termux-built
`.so` files load from `jniLibs`, AGP's host-binary assumptions, real peak RSS, thermal behaviour.
The audit table above is verified from this repo's own build files and CI workflow; the route
analysis is informed reasoning that **needs research and then a device to confirm**.

## Suggested first move

Attack **L1** before anything else. It is the smallest rung, it removes the most frequent reason
to reach for another machine, and it is a clean experiment: get a JDK and Gradle running in an
arm64 Android userland and make `:core-engine:testFullDebugUnitTest` pass on the phone. It needs
no `aapt2`, no NDK, and no resolution of the hard questions above. If it works, the ladder has a
foundation. If it doesn't, the reason why will sharpen every question in R1.
