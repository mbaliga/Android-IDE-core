# Debian/proot userland: feasibility, three architectures, and an owner-run probe (wave 2, lane U0)

Status: DESIGN + FACT-FINDING. Nothing in this note is in force; nothing here has run on a device
(this container has none), and nothing is said about any specific phone the author cannot know.
The owner wants "a real on-device shell (proot/Debian-backed)". Scouting found hard constraints;
this lane surfaces them and ships a GPL-free probe so the owner can replace UNKNOWN with measurements.

Companion files: `tools/userland-probe/` (standalone probe project, not in the root build, not in
the core gate) and `docs/non_ratified/USERLAND_PROPOSALS.md` (two PROPOSED amendments, not in force).

## 1. The constraints, quoted

Ratified rules that bear on this (read-only; this lane edits nothing under `docs/ratified/`):

- Copyleft linking (`CONTRIBUTING.md`): "New runtime dependencies need a license on the allowlist
  (`config/allowed-licenses.json`) - Apache-2.0/MIT/BSD/ISC/CC0/Unlicense/MPL-2.0 (case-by-case).
  Copyleft (including LGPL) isn't accepted for linking."
- `FB-RAT-DIST-003` (`docs/non_ratified/DEFERRED_DECISIONS.md`, DEFERRED): "Defer final policy for
  separately executed copyleft toolchains versus linked runtime dependencies pending explicit legal
  review." Unblock condition: "Real legal review, owner-only, cannot be done in-session."
- `FB-RAT-PORT-008` (`EXPERIMENTAL_DECISIONS.md`, EXPERIMENTAL): "Treat a PRoot/AVF-style Linux
  provider as an optional compatibility experiment, not a foundational dependency." Calibration:
  "Measure real package/toolchain coverage gained vs the W^X-constrained native paths already
  ratified before treating it as anything beyond opt-in."
- `docs/ratified/EXECUTION_CONTRACT.md` 3.1: "a `LOCAL_ANDROID` `ExecutionRequest.operation.command`
  MUST invoke an already-installed, package-signed executable or interpreter (a bundled JNI `.so`,
  a shell built into the OS/Termux-style sandbox this constellation does not currently ship, an
  installed app's exported entrypoint) - never a binary fetched at runtime and exec'd from
  app-writable storage."
- `docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md` section 1 table: (c) "APK-bundled `jniLibs`
  executables" are Yes/Yes; (d) "Termux-style linker tricks (`system_linker_exec` and similar)" are
  "No - PROHIBITED" on both flavors; (e) "Downloaded-native-exec ... No / Also no - impossible, not
  merely policy-forbidden".
- `docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md` line 47: "all git integration is pure REST
  request-builders" (the ruling the second proposal would supersede).

Two things follow immediately. (1) proot is GPL-2.0-or-later (see 2, S5) and its talloc dependency is
LGPL (secondary knowledge, not re-verified here), so it cannot be linked or vendored into core.
(2) A Debian rootfs, however obtained, is data; but a *proot binary fetched at runtime and exec'd from
app storage* is exactly mechanism (e). Only "bundled in the APK as a jniLibs library" or "an
already-installed other app" satisfy the execution contract.

## 2. Research ledger

P = primary source (platform vendor or project's own page); S = secondary (community write-up,
news, forum). A secondary claim is never used below as established fact; it is labelled.

| # | Source | Kind | What it established |
|---|---|---|---|
| S1 | https://developer.android.com/about/versions/10/behavior-changes-10 | P | "Untrusted apps that target Android 10 cannot invoke `execve()` directly on files within the app's home directory." Reason given: "Execution of files from the writable app home directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK file." Also: libraries cannot be mapped PROT_EXEC through a writable file descriptor (relevant to text relocations). It says nothing about mmap-exec of a read-only file or about ptrace loaders: that is the open question. |
| S2 | https://github.com/termux/termux-packages/wiki/Termux-and-Android-10 | P (Termux's own wiki) | Termux stays on targetSdk 28: "Google requires the target SDK level to be set to at least 29 ... But due to new operating system behavior changes we cannot do so and have to use SDK level 28." Quotes the W^X rule above. Establishes that the exec restriction is tied to targetSdk, and that the largest existing Android userland deliberately lowers targetSdk. |
| S3 | https://github.com/termux/termux-packages/wiki/Termux-execution-environment and https://github.com/fornwall/termux-exec (search-result summaries only; pages not fetched in full) | S | Summary: exec limits come from SELinux contexts on untrusted_app files; files under the native library directory carry a different label that permits execution; a wrapper (`termux-exec`) works around the app-data exec limit. Treat as secondary until read in full. |
| S4 | https://developer.android.com/about/versions/14/behavior-changes-14 ("Safer dynamic code loading") | P | Targeting API 34+: "all dynamically-loaded files must be marked as read-only. Otherwise, the system throws an exception." The text names DEX, JAR, APK files; it does not say whether the rule covers native `.so` loaded by `System.load`. Probe check (iv) measures that on the owner's device. |
| S5 | https://proot-me.github.io/ and https://github.com/proot-me/proot | P | "Technically PRoot relies on ptrace, an unprivileged system-call available in every Linux kernel." Needs a guest rootfs directory. Licence: README "SPDX-License-Identifier: GPL-2.0-or-later"; manual colophon "licensed under GPL v2 or later". The page does not discuss Android, SELinux, seccomp or process_vm; those were NOT established from the project's own docs. |
| S6 | Search result for termux-packages "Common porting problems" wiki, a DEV Community post on Debian binaries under Android 15, a Termux issue on ptrace cost | S | Claims (unverified by us): Android 8+ applies a seccomp filter to apps that kills forbidden syscalls with SIGSYS ("Bad system call"); one author reports glibc Debian binaries under proot-distro dying with SIGSYS from `set_robust_list` on Android 15; proot's every-syscall ptrace interception is slow. These motivate probe check (v) but are not facts we rely on. |
| S7 | https://source.android.com/docs/core/virtualization/architecture | P | AVF is built on KVM; pKVM protects guest memory from the host; a protected VM is one "marked protected at creation"; AVF is "limited to ARM64" in the reference implementation and needs IOMMU/firmware support. The page does not mention the `android.software.virtualization_framework` feature flag or the Linux Terminal app. |
| S8 | https://itsfoss.com/news/google-android-linux-terminal-rollout/ , https://www.androidauthority.com/snapdragon-chips-android-linux-terminal-3608648 , https://www.androidauthority.com/galaxy-z-flip-7-linux-terminal-3578675 (search-result summaries only) | S | Reported: the Linux Terminal app runs a Debian VM through AVF; support depends on non-protected VM capability; reported on Pixels (Tensor), some Dimensity/Exynos phones, and one report names a RedMagic 12 Pro as the first Snapdragon phone with it. This is news reporting about a model and software build that may differ from the owner's device; NOTHING is asserted here about the owner's phone. The probe (vii) reports the feature flag and whether a terminal package is installed. |
| S9 | https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md and community guides (search-result summaries only) | S | Reported: Android 12+ "PhantomProcessList" kills background child processes beyond a default cap of 32 (`max_phantom_processes`), counted across apps; users see "signal 9"; community workarounds use `adb`/`device_config`, and Android 14+ is said to expose a developer-options switch. Not Google documentation. Motivates probe check (vi), which spawns 40 children. |
| S10 | https://cdimage.debian.org/images/cloud/bookworm/latest/ | P (Debian's own image host) | Lists `debian-12-generic-arm64.tar.xz` and `debian-12-genericcloud-arm64.tar.xz` among arm64 images, and a `SHA512SUMS` file; no `SHA256SUMS` and no signature file were listed on that page when fetched. So integrity of a rootfs from there is a SHA-512 check against a file served from the same host (an integrity check, not an authenticity proof). The cloud-image tarballs are `.tar.xz`; Java/Android have no xz decoder without a dependency (see 9). Debian's debootstrap/`docker` rootfs distribution was not researched. |
| S11 | https://developer.android.com/about/versions/12/behavior-changes-12 | P | Fetched looking for process-limit text: it contains none. Recorded so nobody cites it for the phantom-process limit. |
| S12 | https://source.android.com/docs/security/features/selinux/app-data | P | Fetch returned HTTP 404. The AOSP SELinux policy (`untrusted_app` domains, `app_data_file`, `neverallow` rules on execute) was therefore NOT read in this lane; claims about exact policy rules are not made. |

Not researched (named so nobody assumes it was): Android 15/16 changes to dynamic code loading
beyond S4; the exact Android 16 Linux Terminal storage/RAM limits; proot's loader internals (the
lane brief forbids reading its source); Debian rootfs signature verification (Release/GPG).

## 3. Facts established vs UNKNOWN

Established (by the P sources above):
1. On targetSdk >= 29, `execve()` of a file in the app's writable home directory is blocked (S1).
2. Termux's own response is to stay at targetSdk 28 (S2). Fonebrew targets 36.
3. proot is GPL-2.0-or-later and works by ptrace (S5).
4. AVF/pKVM exists in AOSP as an ARM64 virtualization stack (S7).
5. Debian publishes arm64 rootfs-like tarballs with a SHA512SUMS file (S10).

UNKNOWN until the probe runs on the owner's device (each maps to a probe check):
- Whether a jniLibs `lib*.so` *executable* in nativeLibraryDir can be exec'd by this app at
  targetSdk 36 on that device (check ii) and on which Android build.
- Whether the same file copied into filesDir is blocked, and with which exact errno (iii).
- Whether mmap(PROT_EXEC) and `System.load` of a file in filesDir are allowed (iv). This is the
  crux for any ptrace loader that maps guest binaries.
- Whether an app process may `fork`, `PTRACE_TRACEME`, `PTRACE_SYSCALL` and `process_vm_readv`
  under that device's seccomp/SELinux (v).
- How many child processes survive 10 s under a foreground service, and whether any is SIGKILLed (vi).
- User namespaces, /dev/kvm, the AVF feature flag, a Linux Terminal app (vii).
- Free storage for a ~1 GB rootfs (viii); whether a shell-to-app socket bridge works (ix).
- Everything about the owner's RedMagic specifically, including whether its Linux Terminal exists.

## 4. Architecture A - proot inside the full-flavor APK (jniLibs) executing a downloaded rootfs

Shape: the full APK carries `libproot.so` (an executable renamed lib*.so, packaged by AGP) plus its
loader helper; a downloader fetches a Debian rootfs archive (data), verifies it, extracts it to app
storage; the terminal runs `nativeLibraryDir/libproot.so -r rootfs /bin/bash`.

- Licence status: **blocked today.** proot GPL-2.0-or-later; talloc LGPL. Shipping a GPL executable
  in an Apache-2.0 APK is "mere aggregation" arguments at best and is exactly what `FB-RAT-DIST-003`
  defers to a legal review. The compliance obligations (source offer, notices) also attach to
  the Debian rootfs contents (a mixed-licence data set with thousands of packages).
- Ratified rules in conflict: CONTRIBUTING (copyleft not accepted for linking - a separately
  executed binary is not linking, which is precisely the open `FB-RAT-DIST-003` question);
  EXECUTION_CONTRACT 3.1 is satisfied only if the proot binary is in the APK (jniLibs), not fetched.
  DISTRIBUTION_CAPABILITY_SPLIT (c) allows APK-bundled jniLibs executables. (d) forbids linker tricks
  such as `system_linker_exec`; proot's own loader/mmap behaviour is NOT a linker trick but must be
  checked against that wording by the owner, because proot launches guest ELF binaries from app
  storage via its own loader - at the very least a reviewer will read it as "downloaded-native-exec
  under another name" for the *guest* binaries (mechanism (e)) even though the exec'd image is proot.
- Play flavor: excluded entirely (policy-safe flavor; nothing here is Play-legal for executable
  content downloaded and run, S1 aside). Full flavor only.
- Feasibility: UNKNOWN on the three load-bearing facts: exec of nativeLibraryDir executables (ii),
  mmap-exec from app storage (iv), ptrace+seccomp (v). Probe checks decide it. If (iv) or (v) FAIL,
  A is dead regardless of the licence ruling.
- Effort (work packages, WP = a small reviewable lane): WP1 licence ruling + amendment (owner);
  WP2 jniLibs packaging of proot + loader behind a full-flavor-only source set; WP3 rootfs
  downloader with sha256/sha512 verification + resumable download (extends ModelDownloader, which
  has resume and an FGS but no checksum or archive extraction); WP4 tar/xz extraction with
  path-traversal and symlink handling (the probe's `TarReader` is a starting shape, tar.gz only);
  WP5 session wiring into the terminal engine (`TerminalSessionManager` backend); WP6 persistent
  FGS and phantom-process mitigation guidance; WP7 storage UX and uninstall; WP8 on-device verification.
- Failure modes: SIGSYS from seccomp (S6, unverified); PhantomProcessList kills (S9); ptrace
  slowness; exec denied; the rootfs's `apt` needs DNS/network and a resolv.conf; setuid binaries
  and device nodes do not extract; a half-extracted rootfs after a kill; storage exhaustion.

## 5. Architecture B - a separate companion APK ("Fonebrew Shell")

Shape: a second app, built in its own repository from separately licensed (copyleft) code at a lower
targetSdk where app-home exec still works (the S2 route), exposing a shell over a local socket that
Fonebrew connects to as a client. Core ships no copyleft code and imports nothing from it.

- Licence status: **cleanest available.** The GPL work lives in a different program, executed
  separately, in a different repository with its own licence; the contract between the two is a
  socket protocol (to be specified, public). Whether that separation satisfies the owner's legal
  view is still `FB-RAT-DIST-003`, but this is the strongest position short of building nothing.
  The core repo only needs the client side (a socket + protocol) - Apache-2.0, no copyleft.
- Ratified rules: EXECUTION_CONTRACT 3.1 explicitly lists "an installed app's exported entrypoint"
  as a legal local command target, and a socket to an installed, separately signed app is that shape.
  No runtime-fetched binary is exec'd by Fonebrew itself (the *companion* is a user-installed app).
  The companion itself would exec files in its own home at an older targetSdk: that is the Termux
  route (S2) and it is not "Termux-style linker tricks" (d) unless it uses `system_linker_exec`.
  `FB-RAT-PORT-008`'s "optional compatibility experiment" fits.
- Play flavor: none (Play would not carry the companion; the Play build has no such feature).
  Distribution of the companion is outside this repo's APK-delivery rules and is an owner decision
  (its own sideload channel). A targetSdk below Play's current floor means it cannot be on Play at all.
- Feasibility: the exec side is the one part with a *known* working precedent (Termux at targetSdk
  28, S2), but that precedent's continued viability on the owner's Android build is UNKNOWN (probe
  check iii run in the companion's targetSdk is the measurement; run the probe with a lowered
  targetSdk build to learn it). Socket pairing/auth between two apps is design work (peer uid check
  via `LocalSocket.getPeerCredentials`; capability token).
- Effort: WP1 protocol spec in core (public); WP2 core-side client + permission UX + visible
  "this is a separate app" marker (legibility); WP3 companion repo bootstrap and licence; WP4
  companion shell service; WP5 pairing/auth; WP6 terminal engine backend; WP7 owner verification.
- Failure modes: companion not installed or killed (phantom processes, S9); OEM battery policy;
  two APKs signed by different keys cannot use signature permissions; the user confuses which app is
  executing; companion targets an SDK that a future Android release stops running; the same
  PhantomProcessList cap applies inside the companion.

## 6. Architecture C - Android's Linux Terminal / AVF virtual machine

Shape: where the device ships the Linux Terminal app (a Debian VM on AVF, S8), Fonebrew does not run
Linux itself; it hands the user to that app, or (if a supported API exists) talks to the VM over a
network port. No Linux code, no GPL, no exec in Fonebrew.

- Licence status: **no copyleft in our artifact** (the VM is the platform's, the user's device).
- Ratified rules in conflict: none identified; this is the least-conflicting route. It is a
  platform feature, not our mechanism, so it fits "optional compatibility" (`FB-RAT-PORT-008`).
- Play flavor: unaffected (we ship nothing); at most a deep link / intent to a system app.
- Feasibility: depends entirely on the device. Reported (S8, secondary) to exist on a subset of
  phones, including one report naming a RedMagic 12 Pro; whether the owner's phone has it is
  UNKNOWN and is probe check (vii) (feature flag, a terminal package, /dev/kvm). Whether Fonebrew
  can integrate (open a session, share files, call back) beyond launching the app is UNKNOWN: no
  public integration API was found in this lane's sources (S7 describes the framework, not the app).
  Likely integration = launching the app + `ssh` from Fonebrew's existing remote-exec spine to the
  VM if the VM exposes sshd and a reachable address (UNKNOWN; the VM's networking was not researched).
- Effort: WP1 detect + launch + honest "not available on this device" state; WP2 SSH-host preset for
  a local VM (reuses the existing remote host store); WP3 documentation of file sharing limits;
  WP4 owner verification. Small, because most of it is already built (SSH client, remote terminal).
- Failure modes: feature absent; non-protected VM unsupported; VM memory/storage limits; the VM
  stops when the app leaves foreground (UNKNOWN); no network to the VM from the host app.

## 7. What works with NO userland at all

Deliverable even if the owner rules out every option above. All pure Fonebrew code, Apache-2.0.

1. **A better Android shell.** The system `/system/bin/sh` (mksh) and toybox already give ls, cp,
   grep, sed, awk-less text tools, tar, `nc`, etc. (exact applet list varies by device build; the
   probe lists whether `nc` exists). Improvements are ours: a generated `ENV` rc file (already
   designed in `docs/design/terminal-engine.md`), sane `PATH`, `HOME`, prompt, aliases, history,
   completion in the UI, working-directory handling, a visible "this is the Android system shell,
   not Debian" banner. No exec of anything not already installed.
2. **A command bridge ("shim by shell function").** Scripts can be interpreted even when files cannot be
   exec'd directly (the mksh interpreter is the installed executable; the script is data fed to it).
   Fonebrew defines shell functions in the session's rc file: `git() { fb_call git "$@"; }`,
   `fb_call() { printf '%s\n' "$*" | nc -U "$FB_SOCK"; }` (exact shape to be specified). The app listens on
   a private unix socket (filesystem socket inside app files, peer-uid checked), receives the argv,
   runs Fonebrew's *own engines* (the clean-room git engine being built elsewhere; file commands
   over the workspace; the build/agent loops) and streams stdout/exit code back. The result is that
   `git status` typed in the shell works with no git binary and no Debian. Limits, stated honestly:
   only functions and builtins are intercepted (not binaries invoked by absolute path or by other
   programs); pipes and subshells work because functions are shell-level, but `xargs git` or
   `env git` bypass them; the socket needs `nc -U` or an equivalent in the system toybox (probe
   check ix measures it; if absent, a tiny JNI helper exe in jniLibs - Apache-2.0 code of ours,
   allowed by mechanism (c) - can replace `nc`).
3. **Interpreter lane.** Mechanism (a) in DISTRIBUTION_CAPABILITY_SPLIT: a bundled interpreter in the
   APK running downloaded scripts. Already a ratified, Play-legal shape for scripting (a language
   choice is outside this note).
4. **Remote Linux.** The existing SSH remote terminal gives a real Debian to anyone with a server or a
   Pi; Architecture C's VM, where present, is just another SSH host.

Consequence: even in the worst case (A and B both ruled out, C absent), the owner gets the "git in the
shell" and "editor/build commands in the shell" experience, but not `apt`, not arbitrary ELF programs.

## 8. The probe (what ships in this lane)

`tools/userland-probe/` - a standalone Gradle project (own `settings.gradle.kts` and wrapper;
NOT included from the root `settings.gradle.kts`; the core gate does not build or test it).
Version pins come from the root catalog (`gradle/libs.versions.toml`: AGP 8.9.1, Kotlin 2.1.0, junit
4.13.2 test-only); compileSdk/targetSdk 36, minSdk 31, arm64-v8a, NDK 28.2.13676358, CMake 3.31.6.
No library dependency (only the Kotlin toolchain stdlib the Kotlin plugin adds; junit is
`testImplementation` and never packaged). No INTERNET permission in the default build.

Checks (each prints `[+ PASS]`, `[x FAIL]` or `[? UNKNOWN]` with the raw text; a Copy button copies the report):

| id | Check | PASS means |
|---|---|---|
| header | device, Android, kernel, SELinux context (`/proc/self/attr/current`), targetSdk, ABI, CPU features | informational |
| ii | exec of the probe's own `libprobe_exec.so` from nativeLibraryDir | it ran and printed its marker |
| iii | same file copied to filesDir, chmod +x, exec | allowed (FAIL carries the exact error) |
| iv | mmap(PROT_EXEC) of a filesDir copy; `System.load` of a filesDir copy of `libprobe_dl.so` | both allowed |
| v | forked child: PTRACE_TRACEME, PTRACE_SYSCALL stepping, `process_vm_readv` parent->child | all worked |
| vi | spawn 40 `sleep` children under a foreground service, count survivors at 10 s, count exit 137 | all 40 spawned and alive |
| vii | /dev/kvm, `android.software.virtualization_framework`, Linux Terminal package, userns files + `unshare(CLONE_NEWUSER)` | UNKNOWN unless feature flag and /dev/kvm both present; individual lines are the output |
| viii | free bytes on the app data volume | >= 2 GiB (the threshold is this probe's heuristic) |
| ix | shell function -> `nc -U` -> app socket -> `PONG` | answered; also reports `nc` presence and whether the shell has `/dev/tcp` (informational: no TCP server is opened) |

The C test executable only prints identity and `RLIMIT_NPROC`/`RLIMIT_NOFILE`. The ptrace, unshare and
mmap helpers are a separate JNI library and run in forked children with deadlines.

How to read results (decision table):
- (ii) PASS + (iii) FAIL: Android W^X as documented; jniLibs executables are the only route. A is
  *possible in principle*; continue to (iv) and (v).
- (iii) PASS: the device is more permissive than S1 predicts; do not build on that (it is not a stable contract).
- (iv) FAIL or (v) FAIL: A is dead on this device regardless of licensing. Choose B or C or section 7.
- (vi) deaths with exit 137: the phantom-process / low-memory killer applies to any userland (A, B).
- (vii) PASS-ish and the Linux Terminal app opens for the owner: C is the lowest-conflict path.
- (ix) PASS: the section-7 command bridge is buildable with what the system already has.

## 9. Step two (documented, NOT run, disabled in this build)

`tools/userland-probe/.../steptwo/` contains Kotlin for a later experiment: download a *pinned* proot
build and a Debian rootfs, verify sha256, extract (tar.gz only; no xz decoder without a dependency,
so the owner converts a Debian tarball to .gz or supplies one), then run `proot -r rootfs /bin/true`,
`... /bin/uname -a`, `... /usr/bin/apt --version`, printing the raw exit codes and output.

How it is gated (and tested): (1) `StepTwoActivity` is declared with `android:enabled="false"` in the
default manifest; (2) the default build has no INTERNET permission, so even if reached it could not
connect; (3) only the separate `stepTwo` build type (`./gradlew :app:assembleStepTwo`) flips
`BuildConfig.STEP_TWO_ENABLED`, adds INTERNET and enables the activity; (4) `StepTwoRunner.run()` throws
before any fetch or file write when the gate is closed (JVM-tested), and refuses an unset config;
(5) `StepTwoConfig.UNSET` holds no URL and no checksum - nothing is pinned in this repository; the
owner fills in values from sources they trust; (6) a confirmation dialog precedes any fetch.
No proot binary or source is in the repository. Optionally the owner packages a `libproot.so` into a
private build's jniLibs; the downloaded-proot path exists only to *measure* what the OS does with
a downloaded executable (expected: mechanism (e), blocked) and is not a product path.

**Building and distributing step two is gated on the owner's legal ruling** (`FB-RAT-DIST-003`) and
on the amendment text in `docs/non_ratified/USERLAND_PROPOSALS.md`. Until then the stepTwo build type
exists only as source and was compiled, never run.

## 10. Comparison

| | A: proot in APK | B: companion APK | C: AVF Linux Terminal | D: no userland (section 7) |
|---|---|---|---|---|
| Copyleft in our artifact | yes (proot, talloc; rootfs mixed) | none in core | none | none |
| Needs owner legal ruling | yes (DIST-003) | likely yes (separation) | no | no |
| Ratified-rule conflicts | CONTRIBUTING, DIST-003, possibly (e) for guest binaries | none direct (3.1 lists an installed app's entrypoint) | none | none |
| Play flavor | excluded | excluded | unaffected | unaffected |
| Feasibility facts | UNKNOWN: ii, iv, v, vi | exec precedent exists (S2); viability UNKNOWN | UNKNOWN: device support, integration | high: all pure Fonebrew |
| Effort | 8 WP | 7 WP | 4 WP | 3-4 WP (rc file, bridge, engines glue) |
| Real Debian + apt | yes if feasible | yes | yes (a VM) | no |

## 11. Recommendation and ranked decision list

Recommendation: do the owner-run probe first (it costs one install and one tap); build section 7
(D) regardless because it is conflict-free and delivers the visible win ("git in the shell"); pursue
C if check (vii) shows the Terminal app exists on the owner's phone; keep B as the real-Debian path
that does not touch core's licensing; treat A as last, gated on both the legal ruling and probe
checks (iv)/(v), because it is the only option that conflicts with the most ratified rules and is the
most likely to fail technically.

Ranked owner decisions:
1. Install and run the probe on the RedMagic; paste the report back (replaces every UNKNOWN above).
2. Approve or reject building section 7 (shell rc + command bridge) - no legal question attached.
3. Rule on `FB-RAT-DIST-003` (separately executed copyleft toolchain): needed for A and probably B.
4. If (vii) is positive: approve option C integration (launch + SSH preset).
5. Choose B vs A for a real Debian userland, given probe results and the ruling.
6. Ratify or reject proposal 2 (supersede "REST-only git") - independent of everything above.
7. Decide whether the step-two experiment may ever be built.

## 12. Owner-verified only

Every probe result (it has never run on a phone); everything about the RedMagic; whether the
`libprobe_exec.so` executable is actually packaged and extracted by AGP exactly as expected
(the APK was only inspected here, never installed); the foreground-service behaviour in check vi;
the section-7 bridge behaviour under toybox; and every claim attributed to a secondary source.
