# Platforms — Fonebrew is a family of flavours around one core

> **Status:** design, 2026-11-28, from the owner's direction the same morning. Nothing in the
> "next" column is built. Evidence class for the "today" column is what each linked file says about
> itself; none of it has run on a device or desktop the owner uses unless that file says so.

## 1. What changed in the framing

Fonebrew is **not** "the Android IDE, made larger." It is one core with several *surfaces*, and the
design question for each surface is the same: **what can it do itself, and where does the rest run?**

1. **Mobile flavours** — Android (exists), iOS, and probably Ubuntu Touch and HarmonyOS. Each is made
   as capable as the platform allows. Where the platform cannot do something (run a compiler, spawn a
   process, host a toolchain), the work goes to **GitHub Actions** — a permanent, designed-in component,
   not a stopgap (`docs/handoff/device-independence.md`).
2. **Desktop flavours** — Windows, Linux, macOS. A real IDE, expected to be richer than agentic desktop
   IDEs such as Google's Antigravity, with self-analytics (Aarso, Baseline) that stay on-device.
3. **A headless `fonebrew` CLI** — the desktop core without a window. A phone drives it over SSH, so a
   spare Linux laptop (or the CI runner you already have) becomes the machine that does what a desktop
   IDE does, with the phone as the primary — or only — surface.
4. **Agentic as second nature, and *visible*.** Loops, sub-agents, gateway decisions and context
   compaction are drawn, not described in prose. The git-recorded run/personal logs and context
   re-compression are the differentiators.

GitHub is the sync fabric between all of them. A laptop is **optional**: for a public repo it costs
nothing (Actions), with a laptop it is faster, and a laptop can also be a full surface in its own right.

## 2. Flavour matrix

| Flavour | Today | Next | What it leans on |
|---|---|---|---|
| Android `full` / `play` | Shipping v0.13.x line; JVM gate green; device behaviour owner-verified only | Live run view (this branch), extensions, desktop-class kit | Local engines, SSH, Actions |
| Desktop host lane | `desktop/` compiles + tests the pure `domain/` + `contracts/` on a JDK (LX-1). **No UI, no packaging, never run on a desktop** | Compose Desktop head; secret custody; `fonebrew` CLI (§4) | Same domain code, unchanged |
| Windows / Linux / macOS | Nothing | One JVM codebase first; native packaging per OS later | Local process execution, native engines per OS |
| iOS | Nothing. `desktop/PORTABILITY.md` inventories the JVM-only APIs a `commonMain` move must replace (`org.json`, `java.time`, `ProcessBuilder`…); that move is gated and not started | Domain to common code; SwiftUI/Compose head; **no local process execution** | Actions + remote CLI for anything that spawns |
| Ubuntu Touch | Nothing in core (ASOM has an `ubuntu-touch/` dir; separate product) | A thin Qt/QML front-end speaking the CLI protocol (§4) rather than embedding Kotlin | `fonebrew serve` on-device or remote |
| HarmonyOS | Nothing | Evaluate last. As far as I know HarmonyOS NEXT dropped Android-app compatibility, so it needs an ArkTS front-end on the same protocol — verify before committing | CLI protocol |

The cheap way to many surfaces is **one protocol, thin heads**: if a front-end can speak §4's JSONL
protocol to a core (local socket, SSH stdio, or an in-process call), it does not need the Kotlin
domain code at all. That is how Ubuntu Touch and HarmonyOS become small, honest jobs rather than ports.

## 3. Where work runs

The seam already exists: `domain/execution` has `LocalProcessExecutionProvider`,
`SshExecutionProvider` and `CiActionsExecutionProvider`, gated by `RunAuthorityGate` /
`RunGrants`. The rule (binding rule 2's spirit, extended): **the user always sees where a run goes**.

- *Local* — on this device. Default wherever the platform allows it.
- *Remote host* — an SSH host the user added (their laptop, a runner). A "watched object": named,
  visible, never a silent fallback.
- *CI* — GitHub Actions on the user's repo. Also visibly marked; a run says which runner it used.

**Amendment to `device-independence.md`.** That document rejects a *required* second machine the
owner must buy and maintain. This design keeps that: **no flavour may require a second machine.** What
it adds is an *optional* provider — a machine you already own, offered for speed or for a fuller
desktop surface. If it is off or absent, the same run goes to CI or local, with the same events.

## 4. The `fonebrew` CLI and its protocol

A headless build of the desktop core. It does what the app's execution/repo/loop layers do, without a
window.

```
fonebrew serve                  # JSONL protocol on stdin/stdout — meant to be launched over SSH
fonebrew run <loop> [--objective …] [--param k=v …] [--events jsonl]
fonebrew exec -- <cmd…>          # through the same authority gate as the app
fonebrew repo status|sync|diff   # git is the sync fabric
fonebrew attach <run-id>         # live-tail or replay a run's events
fonebrew version --protocol
```

**Transport is SSH stdio.** No listening port, no daemon account, no new network edge: the phone opens
`ssh host fonebrew serve`, authentication is the user's existing SSH key, and the host verification
the app already does (`KnownHosts`) applies unchanged. A local-socket mode may follow for same-machine
front-ends, never a network-facing default.

**Wire format is JSONL, one message per line.** Requests carry `{id, op, args}`; responses and
streamed events carry the same `id`. Run events are exactly
[`RunProgress`](../../core-engine/src/main/java/dev/fonebrew/domain/loop/RunProgress.kt) encoded by
`RunProgressCodec` — **already built and tested** (`v:1`, unknown events and fields ignored, so an
older phone survives a newer laptop). The run view is a pure fold over that stream
(`RunViewReducer`), so the same panel renders a local run, a remote run and a replayed log.

Protocol rules: versioned (`fonebrew version --protocol`); additive-only within a major version; keys
never cross the wire (a remote provider call uses keys that live on the machine that makes it, or the
run is refused); every write op is a normal `RunGrants`-gated request, never an implicit one.

## 5. GitHub as the sync fabric

Syncs, per repo, on the user's own host: the message tree, loop definitions (standard BPMN 2.0),
run logs (JSONL events — replayable), compaction summaries and handoffs.
Never syncs by default: API keys, and Aarso data (see `aarso-drift.md`: opt-in, per private repo).

A laptop surface and a phone surface are two clients of the same repo state; reconciliation is git's.
Conflicts on append-only logs are structurally rare (appends), and on loop definitions they surface as
an ordinary diff the user resolves — not a silent merge.

## 6. Seeing the agents

Built on `claude/live-run-progress` (JVM-tested; **not run on a device**):

- `GraphRunner.run(onEvent)` — run started, every node entered, step started / **streaming text** /
  completed / failed, gateway decided (with the edges *not* taken), edge taken, run finished — which is
  delivered even on cancellation.
- `RunViewReducer` — the fold: active nodes, per-node visit counts, edge traversals, gateway history,
  an **agent tree** (parent pointers, so a nested sub-agent attaches anywhere), tokens that stay `null`
  unless a counter measured them.
- `LoopRoom` — canvas nodes light up while running; `RunLivePanel` shows the tree, the streaming tail
  of the running step and a ticking clock. Status is always glyph **and** word (never colour alone).

Next, in order of value:
1. **Edge highlighting** on the canvas from `edgeTraversals` (loop-backs visibly repeat).
2. **External agents as first-class rows.** A Claude Code (or any agent) session transcript mapped
   onto `StepStarted`/`StepDelta`/`StepCompleted` with `parentAgentId` shows sub-agents spawned *by a
   tool*, which today are invisible. Mapping per tool is the work; the view needs no change.
3. **Context compaction as an event** (`ContextCompacted{before,after,summaryRef}`) so a re-compression
   is a visible, git-recorded moment in the run, not a silent truncation. `AarsoEventKind.COMPACTION_RUN`
   already names the write side.
4. **Parallel branches.** `GraphRunner` walks one node at a time; the events and reducer already allow
   concurrent agents, the runner does not yet produce them.

## 7. Sequencing and gates

| Step | Deliverable | Gate (evidence class) |
|---|---|---|
| P-1 | Live run view | JVM gate green (**done**); on-device feel owner-verified |
| P-2 | `fonebrew serve` + `run` over stdio, JVM only, loop events end to end | JVM tests against an in-process pipe; real SSH to a real host owner-verified |
| P-3 | Phone `RemoteProvider` speaking the protocol; run view on remote runs | Same view for local/remote proven by replaying one recorded log through both |
| P-4 | Compose Desktop head on the host lane | Builds in CI; first run on a desktop is the owner's |
| P-5 | iOS common-code move (IOS-1), then a head | **Treated as ungated** (owner, 2026-11-28); toolchain pins from OQ-38 still apply; GitHub macOS runners are the fallback |
| P-6 | Ubuntu Touch / HarmonyOS thin heads on the protocol | After P-3; evaluate demand first |

## 8. Owner decisions (2026-11-28)

| Question | Decision |
|---|---|
| CLI packaging | **JAR first**, bundled runtime (`jlink`/`jpackage`) later. Native binary not started |
| Windows | **Joins at the same time as Linux** — the first desktop head is tested on both (Windows via dual-boot) |
| Mobile order | **iOS first, then Ubuntu Touch, HarmonyOS last** |
| Remote host and cloud calls | **Allowed, with the host's own keys, visibly marked** — keys are never copied from the phone (see below) |
| Run logs in git | **One dedicated `fonebrew-runs` repo** (private; Aarso data joins it only if opted in) |
| Self-hosted runners | **Private repos only.** Public repos use GitHub-hosted runners |
| Apple builds (iOS, Apple-silicon macOS) | **GitHub-hosted macOS runners only, for now.** No Mac purchase until the iOS head starts (P-5) |
| IOS-1 gate (OQ-2 / OQ-38) | **Treated as ungated for now.** The owner will amend this if necessary; the fallback is GitHub-hosted runners |

**Consequence of "host's own keys" — new work, not started.** Binding rule 5 is written for Android Keystore.
A desktop host needs its own custody (the OS keyring: libsecret on Linux, Keychain on macOS, Credential
Manager on Windows), with the same guarantees: encrypted at rest, never logged, sent only to the provider it
belongs to. The desktop lane has **no secret custody at all today** (`desktop/README.md`). A remote run that
uses a cloud provider must say, in its event stream and on the phone, which host's key was used.

## 9. The owner's machines and the roles decided for them

| Machine | Role |
|---|---|
| Dell | Self-hosted **Linux runner** (already set up), private repos only |
| Windows PC → **dual-boot Arch + Windows** | Arch as daily system; the Windows partition is where the Windows flavour is hand-tested |
| HP, 8th-gen i5 | Wanted as: runner 2 + always-on `fonebrew serve` host + clean-room install target + personal server/cloud. **Isolation layout undecided** — depends on its RAM and disk. A CI job runs arbitrary code, a clean-room target is wiped, and a personal cloud holds data, so they should not share one OS: the recommendation is a minimal hypervisor with a VM per role |
| 2015 MacBook Pro 15" (Intel; macOS 12 is the newest it can run) | macOS desktop-flavour **test box**, a daily-driver Fonebrew surface, and a self-hosted macOS runner for non-Xcode jobs. **Cannot run Xcode 26** (Apple's release notes require macOS Sequoia 15.6+), so it cannot build iOS or satisfy the OQ-38 Xcode pin |

All five are x86_64. Nothing here tests **Apple-silicon macOS** or **arm64 Linux/Windows** by hand; those
remain CI-only (GitHub's macOS runners are Apple-silicon). A JVM CLI is architecture-neutral, but the
native model libraries are not: each OS/CPU pair is its own build.

**Runner policy** (from GitHub's own guidance and OWASP's cheat sheet: never attach self-hosted runners to
public repos, since a fork's pull request can run code on them): the Dell and the HP serve **private
repos only**. Self-hosted minutes are free, which also removes the earlier private-repo Actions-minutes
exhaustion. When a repo (core, Hyle) goes public, its workflows move to hosted runners **in the same
change** that makes it public. Not yet applied to any `ci.yml`.

## 10. Still open

1. HP RAM, disk and CPU, to fix its isolation layout.
2. ~~Whether the OQ-2 / OQ-38 gate on IOS-1 is still live~~ — resolved 2026-11-28: treated as ungated, owner may amend (§8). The register itself is still not in these repos.
3. Desktop secret custody design (§8).
4. Language detection for Aarso (function-word lists are built, with Konkani and Odia gaps; see `aarso-drift.md`).
