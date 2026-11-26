# Debian/proot userland: feasibility and an owner-run probe (wave 2, lane U0)

Status: PLAN, written before code. Replaced in a later commit by the full three-architecture
analysis. Nothing here is in force; nothing has run on a device (this container has none).

## What this lane builds
1. `tools/userland-probe/` - a STANDALONE Gradle Android project (not in the root
   `settings.gradle.kts`, so the gate is unaffected). No third-party dependencies. One screen,
   a Run button, a plain-text report with a Copy button. No network. Checks: device identity,
   exec from nativeLibraryDir, exec from filesDir, mmap-exec/dlopen from filesDir, ptrace +
   process_vm_readv, fork limits under a foreground service, userns / kvm / AVF feature, free
   storage, local-socket command bridge. Each check reports PASS / FAIL (raw errno) / UNKNOWN.
2. Pure report/verdict logic in plain Kotlin with JVM tests (a tiny standalone test source set).
3. A disabled "step two" screen (real proot + Debian rootfs) that is compiled in but unreachable
   and never fetches anything unless the owner flips a build flag in a later build.
4. The full design doc (three architectures, a "no userland" section, ranked decisions).
5. A proposals file under `docs/non_ratified/` (PROPOSED, not in force).

## What this lane does not build
- No proot, talloc, busybox, Termux code or binary; no GPL/LGPL source is opened or copied.
- No rootfs downloader in core; no edits to `docs/ratified/`, root `settings.gradle.kts`, the
  allowlist, or the core gate.
- No claim about any specific phone. Every probe result is owner-verified only.

## Seams / failure modes / owner-verified-only
Seams: `ProbeCheck` (pure report model) vs Android-side runners; a C test executable that only
prints identity and limits. Failure modes: a check hangs (every child gets a timeout), a check
crashes the app (ptrace/fork run in a child), step two accidentally enabled (build-flag gated,
tested). Owner-verified only: every probe result.
