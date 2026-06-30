# Build Plan — Workbench (host) + Aarso (mirror lens)

> Annotated companion to `HANDOFF.md` / `CLAUDE.md`. Sequences the work so **autonomous
> Claude Code sessions** (cheap/fast model, thinking off) extract maximum headless progress
> before the **owner-gated ceiling** (device-verified + design-system + Issue #2), which is
> marked explicitly in §E. Nothing here relaxes a binding rule.
>
> **Naming in this doc:** `Aarso` = the **mirror lens** (within-axis self-reflection;
> reserved). **Workbench** = *pure placeholder* for the host app — **not** a name proposal
> (see §A naming brief). Package rename is decoupled (Sprint R, run late).

---

## A. North star + naming

**Locked north star.** *The 2026 flagship is already a Mac-class computer. What was missing
was never power but a way to do real computing work without desktop fluency — and that
arrived as the agent. Workbench is the post-desktop, touch-native environment that makes the
phone a **sovereign primary device**: sovereign because every layer is legible, not because
the work is effortless.*

**Naming brief (owner decides).** The host-app name must read as a *computing environment /
seat of capability*, not a single tool. Evoke **sovereignty over systems** — a place you
build and command from. Avoid: the **trap** register (Chakravyuha owns it), the **mirror**
register (Aarso owns it), and any **vehicle/charioteer** image ("AI drives, you ride" is the
deskilling claim we argue against). Direction: workshop / forge / seat-of-self-rule.

**Module boundaries (set early, Sprint 0):**
- `mirror/` (Aarso) — the within-axis lens. **Namespace only.** §5b drift + §5c
  self-observation logic stays **⛔ blocked on Issue #2**; this module is an empty, bounded
  seam so the lens can later be dropped into any host. Building the seam is allowed; building
  the metric logic is not.
- Everything else = Workbench (the host).

---

## B. How to run this (carry-forward rules for every sprint)

1. **THE LAW** — state shown by **material**, never status words. Every new surface obeys it
   (a vetted SSH host is *materially* distinct from an unknown one; a flash-in-progress is a
   material fill, not "Uploading… 47%").
2. **No telemetry / analytics / phoning home.** Zero such deps, ever.
3. **Council, never "MoE."** Keys in Android Keystore; never logged; sent only to their own host.
4. **Cloud = watched object.** On-device default; cloud opt-in per use, never a hidden fallback.
5. **Never claim on-device behaviour works** — build env has no device/emulator. Mark
   device-gated tasks 📱 and stop at the headless boundary.
6. **Both flavors green** (`testFullDebugUnitTest` + `testPlayDebugUnitTest` + `:hyle`);
   small legible commits; one capability per PR.
7. **Issue #2 is a wall.** Never wire anything into §5b/§5c. The real embedder (Sprint P4) is
   a *generic* capability and must **not** be connected to drift/idiolect.

**Each sprint below specifies:** target files · headless-vs-gated split · Definition of Done
(DoD) · must-not-touch · tests to add. Legend (from HANDOFF): ✅ done · 🔌 headless engine ·
📱 device-verified-only · 🎨 design-system-gated · ⛔ blocked.

---

## C. Sequence

### Phase 0 — Re-baseline (do first; fast, low-risk)

**Sprint 0 — Framing + mirror seam.** 🔌
- Targets: `CLAUDE.md`, `HANDOFF.md`, `docs/status.md` (north star + naming split in); create
  bounded `domain/mirror/` namespace (interfaces + a no-op `MirrorLens` seam, no metric logic).
- DoD: docs reflect Workbench/Aarso split; mirror seam compiles; both flavors green.
- Must-not-touch: no §5b/§5c logic; no package rename yet.
- Tests: seam contract test (lens is pluggable, currently inert).

### Phase 1 — Remote-exec / device-IO spine *(the new asks; the most "computer-like" missing capability; almost entirely headless)*

> Reframe: not "remote control" but "a real computer talks to other machines and to hardware."
> Legibility hook: the remote stream is a **watched object** — the remote machine's raw,
> unmediated voice, shown verbatim and marked as not-Workbench's-voice.

**Sprint 1 — SSH session + key/trust model.** 🔌 (📱 only for real round-trips)
- Targets: `domain/remote/{RemoteSession,RemoteHost,Identity,KnownHosts,ExecRequest,ExecStream}`;
  `data/remote/{SshTransport,SftpTransport}` over a maintained JVM SSH lib
  (**verify at build time** — `sshj` or `mwiede/jsch`, check maintenance + modern algos);
  extend `security/KeystoreSecret` to hold SSH private keys (parity with API keys).
- Headless: session state machine, auth model, exec/stream framing, known-hosts trust logic,
  SFTP put/get/list model. 📱 actual socket/crypto round-trip is owner-verified.
- DoD: full session lifecycle + key storage + trust decisions are JVM-tested with a faked
  transport; trust state representable materially (data, not words).
- Tests: connect→auth→exec→stream→close; key round-trips Keystore; unknown vs vetted host.

**Sprint 2 — Terminal session model (terminal as watched object).** 🔌 (🎨 rendering only)
- Targets: `domain/remote/term/{PtyChannel,ScreenBuffer,Cursor,Sgr,VtParser}` — parse the PTY
  byte stream into a screen-buffer model (rows/cols/cursor/colors/scrollback).
- Headless: the whole VT/screen-buffer model is JVM-testable. 🎨 the Compose terminal view
  (monospace surface, input) is design-gated — leave a thin renderer interface.
- DoD: a scripted byte stream produces a correct screen buffer; resize works.
- Tests: VT sequence → buffer assertions (cursor moves, line wrap, SGR, clear).

**Sprint 3 — Hardware targets (RPi + Arduino) on the remote spine.** 🔌 (📱 hardware)
- Targets: `domain/device/{DeployTarget,RemoteTarget,ArduinoTarget,Fqbn,SerialPort,FlashRecipe}`;
  `domain/device/recipe/` (compile/upload/run as remote-exec recipes); arduino-cli output parser.
- **RPi** = SSH + SFTP + remote-exec (push code → run → stream output); falls out of Sprint 1.
- **Arduino v1 (delegate-to-Pi)** = an `ArduinoTarget` reachable *through* a `RemoteHost` (the
  Pi running `arduino-cli`); `compile`/`upload` are recipes; CLI output parsed into legible
  state (real compiler/flasher output shown, never a synthesized "success" word). 🔌 fully here.
- **ESP-OTA** = network firmware push recipe (ArduinoOTA/espota). 🔌 recipe; 📱 verify.
- **Arduino v2 (compile-remote-on-Dell, flash-local over USB-OTG)** = `usb-serial-for-android`
  + STK500/AVR109 (avrdude-equivalent). 📱 **device-gated and hard — do NOT attempt in
  autonomous sessions.** Model only what's pure (protocol framing); defer flashing to owner.
- DoD (autonomous part): v1 + RPi + OTA recipe models + arduino-cli parser JVM-tested.
- Tests: recipe → command construction; CLI output → state; FQBN/port model.

### Phase 2 — Close the agentic loop on **existing** repos *(the keystone; integrates pieces that already exist; mostly headless)*

> The pieces exist (Board, Loop, Git browse/edit, `domain/diff/`, Builds, CodeLens). The
> missing keystone is the *chain*. Legibility hook: the agent's output is a **material diff you
> approve**, persisted as tree nodes, with cost legible throughout.

**Sprint 4 — Graph runner + runs-into-tree.** 🔌
- Targets: `domain/loop/GraphRunner` (execute arbitrary `BpmnGraph`, not just proposer↔critic);
  persist loop **runs** as message-tree nodes; `domain/loop/` Bricks palette + free-edge model.
- DoD: an arbitrary graph runs on Echo models; the run is a tree subgraph; round-trips BPMN.
- Tests: 3-node non-linear graph executes; run nodes appear in tree; BPMN round-trip.

**Sprint 5 — Repo work loop (Board → diff → CI → commit).** 🔌 (📱 real host)
- Targets: `domain/ide/RepoWorkLoop` chaining `IssueBoard` → `LoopConfig` objective →
  `GitContents`/CodeLens read → **proposed change set** (`domain/diff/ChangeSet`) → CI
  (`BuildsApi`) → commit (`GitEdit`); `card↔run` link.
- Headless: the orchestration + ChangeSet model + approve/reject gate. 📱 real Git/CI calls.
- DoD: end-to-end loop drives on fakes; a ChangeSet is reviewable/approvable before apply.
- Tests: issue→objective→changeset→(approve)→commit path; reject path is a no-op.

**Sprint 6 — Diff-review model (review-first editing).** 🔌 (🎨 review UI)
- Targets: `domain/diff/{ReviewSession,Hunk,Decision}` — per-hunk approve/reject, apply
  selected. The *primary* edit path on a phone (agent proposes, you read material, you apply);
  terminal/editor stay the power escape hatch.
- DoD: hunk-level review/apply is JVM-tested; partial apply works. 🎨 the review surface itself.
- Tests: multi-hunk changeset; partial apply; reject leaves tree untouched.

### Phase 3 — Remaining headless follow-ons *(prime autonomous grind; order flexible)*

**Sprint P1 — Live SSE usage → Cost.** 🔌 (📱 real providers)
- Wire `CloudEngine` SSE usage into `Cost.adviceCost` (parsers exist); fixture-test the
  capture/aggregation. 📱 real-provider numbers owner-verified.
**Sprint P2 — Provider pricing surface.** 🔌
- A config surface so `UsagePricing` reflects **user-set** per-model prices (per binding rule:
  prices are user input — build the surface, not a baked table).
**Sprint P3 — PM test-dashboard facet.** 🔌 (🎨 styling)
- Reuse `BuildsApi.parseChecks`; a checks summary in the Board facet (content; restyle later).
**Sprint P4 — Real local embedder.** 🔌
- Replace `PlaceholderEmbedder` with a real on-device embedder (pipeline is live). **Generic
  capability only — must NOT be wired to §5b/§5c drift (Issue #2).**
**Sprint P5 — Connectivity/queue.** 🔌 (🎨 surfacing)
- A durable operation queue (enqueue/retry/backoff/optimistic-state) so every network journey
  survives cellular/subway. Headless core; 🎨 the surfacing of queued/failed state (material).
**Sprint P6 — Git sync of loops + tree archive.** 🔌 (📱 host)
- Sync `.bpmn` loop defs + tree archive to the user's Git host (backup brick exists).

### Phase 4 — Device-gated (owner-verified; **not** autonomous) → see §E

---

## D. Open decisions (surfaced, not decided)

- **Navigation/IA (now genuinely open).** The cardinal-room model (Chat home · Chats left ·
  Settings right · Models below · Tree on z) was sized for a chat app. Under the
  *computing-environment* north star — five pillars + the remote/terminal/device-IO layer —
  the compass is over-subscribed. B1 already folds Models into Settings to free the bottom
  axis for Develop. Question: does the device-IO/terminal layer get a place in the compass, or
  does the spatial model need a rethink for an OS-like surface? **Owner + Opus-thinking.** 🎨
- **Arduino path.** v1 (delegate-to-Pi) ships now and is fully legible. v2 (on-device USB-OTG)
  is the device-gated stretch. Confirm v1-first.
- **Rename timing (Sprint R).** Decoupled mechanical sweep `dev.aarso` → `dev.<name>` (package,
  appIds `…full`/`…`, `apk-dist` branch, install URL, docs). Run **after** the name is locked;
  low-risk, CI-checked. Recommend: not now (don't block the build); soon (before launch/portfolio).
- **Cost placement (HANDOFF §9 G).** Still open: G1 standalone+inline / G2 standalone / G3
  decisions-as-tree-nodes. Doesn't block headless work.

---

## E. The ceiling — where the Max budget stops buying progress

**Device-verified (📱 — your phone).** Native llama.cpp load/stream/logprobs; SD image output;
overlay/assist/OCR/STT; **acceleration-path benchmark (NPU/QNN vs Adreno/Vulkan vs CPU —
measure, never assume)**; every network journey against a real host (SSH/SFTP, board move/create,
scaffold publish, build install, Git backup, cloud chat/usage); Arduino USB-OTG flashing + ESP-OTA
on real hardware; loop coherence with real models; tokens/sec on real GGUFs.

**Design-system-gated (🎨 — your read-only design system).** Restyle all wireframes; terminal
renderer; diff-review surface; loop canvas; B1 spatial reorg + the navigation question (D);
connectivity/queue surfacing.

**Owner-only / blocked (⛔).** §5b/§5c (Issue #2) — mirror seam stays inert. Play submission:
flag-report email, privacy-policy hosting, Console setup, content rating, data-safety,
signing/AAB, screenshots.

> **Honest budget read:** Phases 0–3 (and the headless parts of Phase 1's hardware layer) are
> grindable autonomously and cheaply — that buys a finished **spine**. It becomes "my single
> source" only after the §E device + design work, which is *yours*. Spend the budget on the
> spine; don't expect a finished app at the other end of it.

---

## F. Suggested run order for autonomous sessions

`Sprint 0` → `Sprint 1` → `Sprint 2` → `Sprint 4` → `Sprint 5` → `Sprint 6` → `Sprint 3 (v1/RPi/OTA)`
→ `P1…P6` (any order). Rename (`Sprint R`) whenever the name lands. Each = one PR, both flavors
green, headless boundary respected.

---

## G. Progress log (autonomous sessions append here)

- **Sprint 0** — ✅ this doc committed; north-star + Workbench/Aarso naming split added to
  `CLAUDE.md` / `HANDOFF.md` / `docs/status.md`; bounded `domain/mirror/` seam landed
  (`MirrorLens`, `InertMirrorLens`, `Observation`, `Reflection`, `MirrorSeam`) — inert, no
  metric logic, JVM-tested (`MirrorSeamTest`). §5b/§5c untouched (Issue #2). [PR #5]
- **Sprint 1** — ✅ headless remote-exec/SSH spine landed in `domain/remote/`:
  `RemoteHost`/`Identity` (key/password/agent, secrets by reference only),
  `KnownHosts`/`Trust` (Vetted/Unknown/Changed — the trust-on-first-use legibility model),
  `SessionState`+`SessionMachine` (explicit lifecycle with legal-transition table),
  `ExecRequest`/`ExecChunk`/`ExecResult` (raw-voice streaming), `Sftp`+`RemotePath`,
  `RemoteTransport` (the I/O seam), and `RemoteSessionDriver` (connect→trust→auth→exec→close).
  JVM-tested over a fake transport (`KnownHostsTest`, `SessionMachineTest`, `RemotePathTest`,
  `RemoteSessionDriverTest`). 📱 left for the data layer: pick/wire sshj-or-jsch + real
  Keystore key storage (owner-verified). Both flavors green. [PR #6]
- **Sprint 2** — ✅ terminal-as-watched-object model in `domain/remote/term/`: `ScreenBuffer`
  (grid + cursor + capped scrollback + resize), `Sgr`/`Cell`/`Cursor`, `VtParser` (GROUND→ESC→
  CSI state machine: printables, CR/LF/BS/TAB, auto-wrap, cursor moves + absolute position,
  erase line/display, SGR colours/attrs, charset-designation + unknown-sequence swallow),
  and `PtyChannel` + `TerminalRenderer` (UI-free renderer seam). JVM-tested (`VtParserTest`,
  `ScreenBufferTest`, `PtyChannelTest`). 🎨 the Compose monospace terminal view is design-gated.
  Both flavors green. [PR #7]
- **Sprint 3** — ✅ hardware targets on the remote spine in `domain/device/`: `DeployTarget`
  (`Fqbn`/`SerialPort` value types; `Remote` = a Pi/Dell over SSH, `Arduino` = a board flashed
  by `arduino-cli` running **on** a remote host — the v1 delegate-to-Pi path), `recipe/
  DeviceRecipes` (pure `ExecRequest` builders: run/shell, `arduino-cli` compile/upload/
  compileAndUpload/listBoards, `espOta`; conservative shell quoting), and `ArduinoCli` (parses
  arduino-cli/avrdude/esptool output into errors+sizes+upload-result — only the truth the tool
  stated). JVM-tested (`DeployTargetTest`, `DeviceRecipesTest`, `ArduinoCliTest`). 📱 real
  hardware + Arduino v2 USB-OTG owner-verified. Both flavors green. [PR #11]
- **Sprint 4** — ✅ graph runner + runs-into-tree + free-form editing in `domain/loop/`:
  `GraphRunner` executes an **arbitrary** `BpmnGraph` (start→tasks→gateways→end) via a per-node
  `Generator`, threading a transcript, with a pluggable `GatewayPolicy` (`ConditionGatewayPolicy`
  reads approved/!approved + approve/refine) and a hard step cap; `GraphRunLog` maps a run to a
  detached message-tree sub-tree (like `RunLog`); `GraphEdit` + `Bricks` give immutable
  add/remove/connect/disconnect + runnability validation + the palette vocabulary. JVM-tested
  (`GraphRunnerTest`, `GraphRunLogTest`, `GraphEditTest`). 🎨/📱 wiring into `LoopRoom` +
  persisting runs through `MessageTreeRepository` is the thin follow-on. Both flavors green. [PR #8]
- **Sprint 5** — ✅ repo work loop in `domain/diff/ChangeSet` + `domain/ide/RepoWorkLoop`:
  `ChangeSet`/`FileChange` (CREATE/MODIFY/DELETE on `LineDiff`, stat, unified diff, no-op filter,
  `of(old,new)`); `RepoWorkLoop` orchestrates card→objective→read→propose→**approve**→commit over
  three seams (`RepoReader`/`ChangeProposer`/`ChangeCommitter`) — empty→no-op, reject→no-op,
  failure→reported. JVM-tested (`ChangeSetTest`, `RepoWorkLoopTest`). 📱 real Git/CI behind the
  seams. Both flavors green. [PR #9]
- **Sprint 6** — ✅ diff-review (review-first editing) in `domain/diff/`: `ReviewSession`
  (per-hunk PENDING/APPROVED/REJECTED; applies only approved hunks via dual old/new position
  tracking so partial inserts/deletes/modifies land correctly; `approvedChange()`) +
  `ChangeSetReview` (per-file aggregate → approved-only `ChangeSet`, the bridge to Sprint 5's
  commit). JVM-tested (`ReviewSessionTest`, `ChangeSetReviewTest`). 🎨 the review surface is
  design-gated. Both flavors green. [PR #10]

> **Milestone (2026-06-21):** Phases 0–2 complete and merged — re-baseline (Sprint 0), the
> remote-exec/device-IO spine (1, 2, 3 headless), and the closed agentic loop on existing repos
> (4, 5, 6).

- **P1** — ✅ live SSE usage capture → Cost: `domain/cost/UsageAccumulator` (folds Anthropic/
  OpenAI/Gemini stream usage fragments, max-per-field) + a `usageOf` hook on `CloudEngine`
  feeding a per-turn `lastUsage` (overridden in all three engines). JVM-tested
  (`UsageAccumulatorTest`). 🎨/📱 per-turn Cost display + real numbers. Both flavors green. [PR #13]
- **P2** — ✅ provider pricing surface: `domain/cost/PricingBook` (user-set per-model
  `UsagePricing`; explicit→on-device→fallback; immutable transforms) + `PricingCodec` (tolerant
  JSON). JVM-tested (`PricingBookTest`). 🎨 the config form. Both flavors green. [PR #14]
- **P5** — ✅ connectivity/durable queue: `domain/net/OperationQueue` (enqueue→due→inflight→
  succeed|fail-with-backoff→park-as-FAILED, never silently dropped; retryNow/discard; optimistic
  `pending()`) + overflow-safe `Backoff`. JVM-tested (`OperationQueueTest`). 🎨 queued/failed
  surfacing; 📱 the worker. Both flavors green. [PR #15]
- **P6** — ✅ Git sync of loops: `domain/sync/LoopSync` (pure `GitContentsApi` request builders
  for `loops/<id>.bpmn` push/fetch/list + listing parse + toPush/toPull plan; complements
  `GitBackup`/`TreeArchive` for the tree). JVM-tested (`LoopSyncTest`). 📱 transport. Both flavors
  green. [PR #16]

> **Phase 3 status:** P1, P2, P5, P6 done. **P3** (PM test-dashboard) is mostly 🎨 (reuses
> `BuildsApi.parseChecks`) and **P4** (real local embedder) is a 📱/model-bundling task — both
> are better done with the device/design-system in hand. Remaining: the §E owner-gated ceiling
> (device verification, data-layer wiring — sshj/jsch transport, run persistence, recipe/queue
> workers — design-system restyle, Issue #2).
