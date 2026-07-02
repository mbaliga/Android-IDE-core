# Aarso / Workbench — Project State, Roadmap & End Goal

> Single source of truth: what's **done**, what's **pending**, and the **end goal**. Written
> 2026-06-28. Companion to `CLAUDE.md` (build rules), `docs/monetization.md` (revenue), and
> `docs/design/*` (per-surface specs). When in doubt, this file is the map; the others are the
> territory.

---

## 0. TL;DR
A **local-first, sovereign AI computing environment** for a high-end Android phone — "an open,
on-device Claude Code with loop engineering," extending into a real **agentic IDE** (read repos,
propose+review+commit changes, drive devices like a Pi/Arduino). Free and open to *build*; a paid
"Studio" layer to *ship & sell* a product. Current shipped build: **v0.13.0** on the `apk-dist`
branch (`aarso-sd.apk`). The app compiles, all JVM tests pass, the full APK assembles; everything
runtime/device-side is **owner-verified only** (no device, board, or SSH host in CI).

---

## 1. North star & binding thesis
- **Sovereign primary device.** The phone does real computing work without desktop fluency,
  because every layer is **legible** — routing/influence is visible, the user stays in the loop.
- **Binding rules** (`CLAUDE.md`, do not relax): no telemetry/analytics/phone-home ever;
  on-device is the default, cloud is opt-in + marked a "watched object"; council is never "MoE";
  §5b/§5c drift metric is owner-blocked on Issue #2; API keys encrypted in Keystore, never leave;
  plan before code, small legible commits, honest uncertainty; **never claim on-device behaviour
  works** — owner verifies on the phone.

---

## 2. The constellation (multi-repo / multi-app)
The product is becoming a **family of apps that work together**, not one monolith. Each is a repo;
the dependency direction sinks toward the routing engine.

| Component | What it is | Source | Status |
|---|---|---|---|
| **Aarso / Workbench** (main app) | The computing environment: chat, models, loops, tree, agentic IDE | **Open core** (this repo, `mbaliga/mobile-llm`) | Shipping (v0.13.0) |
| **Hyle** | The render-side **design system** (tokens + contract; later the Compose atoms) | **Open** | **Done** — its own repo `mbaliga/Hyle-Design-System`, single source of `dev.aarso:hyle:0.2.0`, consumed here via git submodule + includeBuild (vendored copy deleted; `0.1.0` retired) |
| **PM + authoring** | The monetized **"Studio" layer** — project-management UX, app-store push pipeline, branding playbooks | **Closed (paid)** | New repo pending owner; code mostly lives in main today, to be carved out |
| **Sound & haptics authoring** | A companion authoring app | **Open** | Not started here |
| **Routing engine** | On-device + cloud LLM **router/orchestrator** — picks model for max-impact/least-cost, manages keys; offered as a service to *any* app | **Closed today; recommendation = open-core** (see §7) | Not started; strategy proposed, owner to confirm |

**Integration rule:** the routing engine needs a **stable, documented public API** from day one —
it's the spine everything else hangs off. Hyle / sound-haptics / PM / main app may depend on the
router's interface; never the reverse.

---

## 3. What's DONE (shipped, this repo)
Built and merged across this work; on `apk-dist` as v0.13.0. **Runtime/gesture/device behaviour is
owner-verified only.**

### Core chat & sovereignty
- One append-only git-like **message tree**; branch / restore / model-switch / council are
  operations over it. Per-token logprob/entropy surface from token one (why llama.cpp).
- On-device GGUF inference (`LlamaCppEngine`, JNI) + Echo dev engine; **cloud providers**
  (Anthropic / OpenAI-compatible / Gemini, SSE streaming) — every cloud model badged "watched".
- Image generation (cloud + on-device SD). KV-cache snapshots. Keystore-encrypted keys.

### Spatial IA (the room model) — full set landed
- **Chat** = home; **Conversations** left (All / Text / Image / Starred / **Projects** grouping);
  **Settings** right (5 icon tabs: Global / Image / Text / Video / 3D, + on-device⇄cloud toggle,
  Git & coding/GitHub connect, **Export everything**, the **Me · Myself · I** profile);
  **Project** top (Tasks: Board / List / **Waterfall** + **Incidents** w/ trend); **Tree** on the
  z-axis (+ git-sync indicator, manual export, handoff summary); **Loops** (see below);
  **Develop** bottom.
- **Chat composer**: Gemini-style **`+` sheet** (image is no longer a pill; video/3D honest
  "soon"); interaction model = Single / Council·personas / Council·models; **interaction model is
  immutable once a chat starts** — changing it **branches with a summary**.
- **Council = editable participants** (group-chat style): per-member name, instructions, **own
  model** (on-device ⌂ / cloud ☁), and long-term memory. (Per-member *files* = honest "soon".)
- **Me · Myself · I**: linked accounts + usage overview; **drift/self-observation ships INERT**
  (blocked on Issue #2). Reached via a header **profile avatar**.

### Agentic IDE
- **Agentic repo loop** (Develop → **Agent**): read repo files → model proposes a `ChangeSet`
  (`<<<FILE>>>` blocks, parser JVM-tested) → **per-hunk review sheet** → commit. Commits as **one
  squashed commit** via the Git tree API (per-file Contents-API fallback). Review-first; read-set
  shown.
- **Editable CodeLens** (✎ Edit → review → commit through `GitEdit`).
- **Devices** (Develop → Devices): **Raspberry Pi** shell over SSH; **Arduino-via-Pi** (FQBN +
  port + sketch → list/compile/upload, `arduino-cli` output parsed); **ESP-OTA**; **This phone
  (USB)** direct flash (CDC transport + `Stk500`/`IntelHex` core, **JVM-tested**; USB runtime is
  device-gated, CDC boards only).
- **Loop editor** (now a **full free-form graph editor**): long-press to add nodes (Task/Gateway/
  End), drag to move, tap to edit (name/instructions/own model), long-press → connect/delete,
  **tap-to-connect** with gateway branch labels (approve/refine/else); **runs arbitrary graphs via
  `GraphRunner`**; saves/loads as **standard BPMN 2.0**; Drafts/Running/Retired tabs + Git push/pull.

### Reliability & design system
- **Crash-recovery harness** (ships in the final app, both flavors): a global handler captures any
  crash's trace; the next launch shows a minimal **Recovery screen** (Continue / Share report /
  Reset data) instead of bricking — stock Material only, can't re-trigger a theming crash.
- **Hyle** design-system module: pure tokens (`Finish`/`Pulse`/`RadiantHues`), JVM-tested,
  publishable as `dev.aarso:hyle:0.1.0`; `:hyle-probe` render harness.
- **Build**: two flavors (full sideload / play policy-safe), `arm64-v8a`, NDK r28; CI runs the JVM
  unit-test gate (the native APK assemble is disabled to avoid runner OOM — **CI never launches
  the app**, which is why device-only crashes were invisible).

### Notable fixes this cycle
- **Launch/send crash** root-caused & fixed: markdown renderer 0.35.0 needed Compose **Foundation
  1.8**; the BoM pinned 1.7.6 → `NoSuchMethodError(BasicText…TextAutoSize)` on every markdown turn.
  Bumped `composeBom` → 2025.05.01 (Foundation 1.8.2).
- Dev-Echo send: foreground service no longer started for non-on-device turns + hardened.

### Version history (this cycle)
v0.9.0 full IA · v0.9.1 B4 depth + loop streaming + profile icon · v0.10.0 agentic IDE + devices ·
v0.11.0 per-hunk review + squashed commits + USB runtime · v0.12.0 Echo-send fix · v0.12.1 crash
harness · v0.12.2 markdown/Compose fix · **v0.13.0 full Loop graph editor (current)**.

---

## 4. What's PENDING
### Tracked as owner-blocked (need an owner action)
- ~~**Hyle repo split** (task #2)~~ **DONE (2026-07-02):** Hyle lives in its own repo
  `mbaliga/Hyle-Design-System` (`dev.aarso:hyle:0.2.0`); core consumes it via git submodule
  (`hyle-design-system/`) + `includeBuild`, and the vendored `:hyle` module is deleted.
- **PM/authoring repo**: owner creates the closed repo → carve the Studio layer (Project room,
  Develop→Launch pipeline, playbooks) out of main.
- **Monetization implementation** (task #1, `docs/monetization.md`): wire the one-time Play
  entitlement + supporter flag (sideload hard-true), a "Friends of the Dev" patron page,
  regional/PWYC/EMI paths. **Open decision:** closed `:power` module vs fully-open + supporter flag.
- **Routing engine** (§7): owner confirms open-core vs other; then it's a new (closed-core) repo.
- **USB Arduino on-device verification**: the CDC flasher is unexercised in CI — needs a real CDC
  board (Uno R3 / Leonardo / Micro / ESP-CDC). Clone chips (CH340/CP210x/FTDI) need a vendor driver.

### Engineering follow-ups (no owner action needed)
- **Chat §B4 remainders**: per-member **files** (needs multimodal/file→context plumbing);
  optionally a unified model-per-member surface.
- **Live per-step streaming in the graph Loop run** (`GraphRunner` needs a progress callback, like
  the simple `WorkflowRunner` already has).
- **Video / 3D engines** (Settings tabs exist as honest "planned"; no engine wired).
- **AI-assisted configuration** (fill/automate any config via a model) — parked at owner request.
- **Drag-a-wire connect** for the Loop editor (current = tap-to-connect; layer this on after the
  owner judges the feel on-device).
- **PM free/paid line** decision (recommend: PM paid; a plain task list could be a free taste).

### Owner-blocked product backlog (from `CLAUDE.md`)
- §5c self-observation + §5b drift metric — **blocked on Issue #2** (binding rule 4).
- Real on-device embedder (replace `PlaceholderEmbedder`).
- Acceleration (Vulkan/NPU) — an on-device empirical decision, benchmark on the target device.
- Google Play publication mechanics (AAB, signing, data-safety, content rating, screenshots).

---

## 5. The END GOAL
1. **A sovereign, post-desktop computing environment on the phone** — you do real work (chat with
   any model, engineer loops, run agents over your repos, drive hardware) without a laptop, and
   **every layer is legible**: you see which model is chosen and why, every cloud touch is a
   watched object, your history is a git-like tree you own and can export.
2. **A family of cooperating, mostly-open apps** — the main app, Hyle (design), sound/haptics, all
   open; a paid PM/authoring "Studio" layer; and a routing engine that any app can use to pick the
   right model for the least cost.
3. **A FOSS-honest business** — *free to build, pay to ship & sell*; patronage not paywall; the
   commercially-successful and the willing fund a solo dev's modest, sustainable lifestyle, while
   nobody is ever locked out (`docs/monetization.md`).

---

## 6. Monetization (summary → `docs/monetization.md`)
Patronage + warm unlock; **no coercive subscriptions ever**; nobody locked out. **Free to build**
(chat, on-device + open models, Loops, git tree, coding Agent, all Settings incl. GitHub) /
**pay to ship & sell** (PM UX, app-store push pipeline, branding playbooks). Tiers: free/open base;
one-time **Lifetime ~$20** (spine); optional **Friends of the Dev** patronage that gates nothing;
**pay-what-you-can + EMI**. Unlock is honor-system by design (an open build can't be enforced).

---

## 7. Routing engine — recommendation (owner to confirm)
Highest-value, most B2B asset; also the strongest commercial candidate. **Don't fully open, don't
fully close — open-core:**
- **Open** the engine/SDK (router, key handling, on-device + cloud calling, fallback, basic
  heuristics). It's sovereign infra; open earns the trust moat and avoids losing to LiteLLM/RouteLLM.
- **Monetize the *intelligence*** (the cost/quality routing brain) via **dual-license** (free for
  individuals/FOSS, **commercial license for companies** shipping it) and/or a **premium routing
  policy** over a free heuristic baseline.
- **Never hold other people's keys/data.** No hosted inference gateway (anti-thesis + a honeypot).
  If hosted at all → a **decision-only** API (returns `{model, why, est_cost}`, inference stays on
  the app side), free tier + self-hostable.

---

## 8. Open decisions awaiting the owner
1. Create the **Hyle** repo, the **PM/authoring** repo, the **sound/haptics** repo, the **routing
   engine** repo (give the agent access).
2. **Monetization seam:** closed `:power` module vs fully-open + supporter flag.
3. **Routing engine:** confirm open-core (§7) or choose otherwise.
4. **PM free/paid line.**
5. Device verification: Echo send + relaunch (markdown fix), the Loop editor feel, the Devices/SSH
   flows, and the USB flash with a real board.

---

## 9. Constraints & honesty
- **No device, emulator, board, or SSH host in this environment.** CI runs JVM unit tests only and
  **never launches the app** — so all runtime, gesture, rendering, network, and hardware behaviour
  is **owner-verified on the phone**. The tested cores (parsers, Intel HEX, STK500, diff, Git
  request builders, BPMN round-trip) are the parts that *are* machine-verified.
- **CI "build-test" is red for a billing reason, not a code one** (corrected 2026-06-30). Every
  run — `main`, `v0-13-0`, even docs-only PRs — fails in **3–6 seconds with no runner assigned**,
  i.e. the job dies *at startup before any step executes*. That is the signature of **GitHub
  Actions minutes / spending-limit exhaustion on a private repo**, not the "Maven Central 403
  during dependency resolution" previously assumed here (a 403 would surface *minutes* into a run).
  "Re-run to clear it" never worked because nothing transient was there to clear. The code is
  sound: the exact gate (`:app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test`) was
  reproduced locally — **BUILD SUCCESSFUL, 868 tests, 0 failures**. Fix is account-level: top up
  Actions minutes / raise the spending limit, **or** lean on the public repos (public repos get
  unlimited free Actions — e.g. the open `android-ide-core` / Phonebrew). No `ci.yml` edit can fix
  a job that never starts.

---

## 10. Where things live (map)
- `app/src/main/java/dev/aarso/` — `domain/` (pure, JVM-tested: tree, council, bpmn, loop, diff,
  device, git, ide, remote), `data/` (Room tree, stores, repos, transports, AgentRepoRunner,
  DeviceRepo, CrashLog), `inference/` (engines, cloud), `ui/` (rooms, loops, develop, codelens,
  ide, remote, theme, aeon, spatial), `service/`, `security/`.
- `hyle/` design-system module · `hyle-probe/` render harness · `sdengine/` Stable-Diffusion module.
- `docs/` — `design/` (per-surface specs incl. `agentic-ide.md`, `information-architecture.md`),
  `handoff/hyle-extraction.md`, `monetization.md`, **this file**.
- `apk-dist` branch — the installable `aarso-sd.apk`.
