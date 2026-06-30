# Aarso — design docs (vision & roadmap)

Forward-looking features, captured as design before code so any session (human or
agent) can pick them up. All obey the binding rules in `/CLAUDE.md` (no telemetry;
on-device default + watched cloud; council never "MoE"; keys in the Keystore;
honest about the no-device build environment).

## The docs

| Doc | What | Status |
|-----|------|--------|
| [`information-architecture.md`](information-architecture.md) | **The canonical IA** (owner spec, 2026-06-23): the whole-app map — Conversations (left) · Chat (home, with single/expert/model interaction modes + group participants) · Settings (right, 5 icon tabs, never a launcher) · Develop (bottom) · Tree (up) · Loops (down) · Project mgmt · the "Me/Myself/I" user meta. Spatial map is the spine; settings hold config only. | **owner spec — anchor doc** |
| [`council-workflows.md`](council-workflows.md) | The loop as a product: today's one-shot council fan-out → a loopable graph of **experts** (per-node model = "model suited to the task"); objective→propose→critique→refine; "propose a look". | **design + engine spike built** |
| [`coding-assistant.md`](coding-assistant.md) | Aarso as a coding assistant **on your repos**: connect a Git host, enter git ID, work on repos/branches, edit→review-diff→commit/push, via the Claude API (watched). | design |
| [`tree-sovereignty.md`](tree-sovereignty.md) | Your **history in a repo you own**: mirror the git-shaped message tree to GitHub/Gitea/self-hosted as open, diff-friendly files — "discard the shell" portability. | **design + format brick built** |
| [`workflow-builder.md`](workflow-builder.md) | **Loops** (the workflow builder): the loop visualized & gated; BPMN-2.0 transport for definitions; the Cost-gate macro; the Loops list + Log + canvas. | **design + BPMN/gating bricks built** |
| [`disclosure.md`](disclosure.md) | **Progressive disclosure**: tame complexity via CORE/STUDIO/POWER tiers (intent wizard + depth dial), without ever hiding routing/cost/watched signals. | **design + tier model built** |
| [`app-distribution.md`](app-distribution.md) | **Build/test/install on your own repos**: see your app's branches + versions, install the APK in-app (no browser hop); the final spatial map (Loops zoom-in, dev room at the bottom). | **design + BuildsApi built** |
| [`project-management.md`](project-management.md) | **Run the project from the app**: a Kanban/Jira board whose cards *are* your repo's issues (column = `status:*` label / closed = Done), plus a test dashboard and pending-items view. Part of the agentic-IDE direction. | **design + IssueBoard brick built** |
| [`cost.md`](cost.md) | **The Cost epic**: multi-dimensional, risk-adjusted decision cost — money/time/tokens, transaction costs that recur per attempt, the expected value of being wrong, and the cost of the advice itself (the model's own price + the risk it's wrong). | **design + DecisionCost brick + Cost facet** |
| [`loop-distillation.md`](loop-distillation.md) | **The literature as loops**: import a paper/framework; a meta-agent distils it into an editable BPMN Loop. A curated reference library (MoA, self-consistency, reflexion, debate) is the floor; the distiller is the ceiling. | **design + curated-library brick built** |
| [`voice-input.md`](voice-input.md) | On-device, push-to-talk STT for NL authoring. Deferred. | design |
| [`material-language.md`](material-language.md) | **The material language** (*Hyle*): honest material physics — dark/light metal, sand (hourglass loaders), water-on-sandstone (transient controls that leave a fading stain) — as a *legible* interface vocabulary, not a skin. Supersedes the fixed `Aeon` palette; absorbs the appearance/theme engine. | vision capture |

## Shared foundation

`coding-assistant` and `tree-sovereignty` both need a **Git-host connection layer**
(host kind + base URL + git identity + PAT in `security/KeystoreSecret.kt`; a
`GitTransport` abstraction). Build it once; it serves both.

**Owner decisions (locked):**
- **Auth: PAT now**, device-flow OAuth later.
- **Transport: the host REST contents API** (GitHub / Gitea / Forgejo / GitLab) —
  not JGit. The sovereignty property is in the destination (real commits, open
  files, in the user's own repo), identical either way; the API is far lighter.
  JGit stays a documented fallback for bare self-hosted git daemons (a niche).
- **Privacy (tree-sovereignty): plaintext by default with a plain-language
  warning; E2E strongly recommended for advanced users.** Anti-obfuscation: the
  default is openable/readable, never a black box.

## What's already built

- **Git-host connection layer** (the shared foundation — wired to the UI):
  · `domain/git/GitHost.kt` (config + kinds) and `domain/git/GitContentsApi.kt`
    (pure REST request builder for GitHub + Gitea/Forgejo) + `GitContentsApiTest`.
  · `data/GitHostStore.kt` (config in prefs, **token encrypted via KeystoreSecret**)
    and `data/GitTransport.kt` (thin OkHttp adapter + `testConnection`).
  · Settings → Global → **Git & coding**: add/list/remove a host + a "Test"
    button. The host is a *watched object*; the app talks only to it.
  · The network round-trip is **owner-verified** (no host in CI).
- **`domain/sync/TreeArchive.kt`** + `TreeArchiveTest` — the open tree format
  (manifest + one JSON file per node), conflict-free union. (headless)
- **`domain/council/Workflow.kt`** (`Generator`/`Expert`/`Stop`/`WorkflowRunner`)
  + `WorkflowRunnerTest` — the refine-loop engine vs a fake generator. (headless)
- **`domain/bpmn/`** (`BpmnGraph`, `BpmnArchive`) + `EscalationBpmn` — loops ("Loops")
  serialize to **standard BPMN 2.0** with `<aarso:meta>` extension elements; the
  Cost-gate is a macro that ⇄ a gateway + approval-tasks. `BpmnArchiveTest`,
  `EscalationBpmnTest` (full round-trip). The transport format for loop definitions.
  (headless — see `workflow-builder.md` §1/§3.)
- **`domain/council/PatternLibrary.kt`** + `PatternLibraryTest` — the curated
  reference library (Mixture-of-Agents, self-consistency, reflexion, multi-agent
  debate) as standard BPMN loops that round-trip through `BpmnArchive`, carry
  provenance, and default to no watched cloud. The *floor* of loop distillation
  (`loop-distillation.md`). (headless)
- **`domain/loop/LoopCatalog.kt`** + `LoopCatalogTest` — surfaces the curated
  `PatternLibrary` as **Unused** `Loop`s (each `bpmnXml` = the pattern's BPMN, with
  provenance in its start event). Bridges the floor into the existing loop lifecycle
  (`domain/loop/Loop.kt` + `LoopLifecycle`, built earlier). (headless)
- **`domain/loop/Distiller.kt`** + `DistillerTest` — the *ceiling*: reads a method,
  the model extracts a `DistilledSpec`, code builds a guaranteed-valid `BpmnGraph`
  (validated by `BpmnArchive` round-trip), returns an Unused `Loop` whose `bpmnXml`
  carries the provenance. Repair rung + pipeline fallback; model-agnostic. (headless)
- **`domain/diff/LineDiff.kt`** + `LineDiffTest` — the coding-assistant's **review
  surface** (`coding-assistant.md` step 3): an LCS line diff emitting **standard
  unified diff** (`--- / +++ / @@`, git-readable) + hunks/stat, prefix/suffix-trimmed
  with an on-device size guard. You see exactly what a proposed edit changes before
  any commit — legibility, human-in-the-loop. (headless)
- **`data/GitEdit.kt`** + `GitEditTest` — the coding-assistant's **commit path**
  (step 3): `open` a file with its blob sha, `unified`/`preview` the change locally,
  `commit` the approved text on the host's branch (returns the new commit sha; refuses
  a no-op). Request shape + sha threading JVM-tested against a fake `GitTransport`;
  the live round-trip is owner-verified.

## Recommended next steps (in order)

1. ✅ **Git-host connection layer** (shared) — built (see above).
2. ✅ **Tree-sovereignty v1 (backup)** — built: `data/GitBackup.kt` +
   `domain/sync/TreeBackup.kt` (append-only ⇒ create-only-missing) wire
   `TreeArchive` → `GitContentsApi.putFile` via the transport; a **"Back up"**
   action per host in Settings → Global → Git & coding. Network owner-verified.
   Next here: pull → union-import; images (device-local v1); per-conversation opt-in.
3. ✅ **Council-workflows v1 (loop)** — built: `inference/EngineGenerator.kt`
   adapts `InferenceEngine`→`Generator` (end-to-end JVM-tested on Echo);
   `WorkflowRunner` now resolves a generator *per expert* (model-diversity); a
   "Run a loop (preview)" dialog (Settings → Global → Council) runs
   objective→propose→critique→refine on chosen models. ✅ **Escalation gates
   (pause/resume) now wired headless** — optional `Gating` on `WorkflowRunner.run`
   + `CostEstimator` (`domain/council/`, see `workflow-builder.md` §3). Next:
   persist a run as a tree sub-graph; the graph model + canvas; move into the
   composer.
4. **Coding-assistant**: ✅ read-only browse (`data/GitBrowse.kt` + a per-host
   "Browse"). Next: single-file edit → review diff → commit/push, reusing the loop
   engine + escalation gates for the agentic path; the size-routed test signal.
5. **Loops (the workflow builder)** — terminology + transport locked (owner). A
   loop = a BPMN definition (`.bpmn`, ✅ format built); a run = the Log (the message
   tree). Both sync to the user's Git host. Lifecycle Unused→Running→Retired
   (duplicate → non-live Unused; only Unused is editable). Next: persist a run as a
   tree sub-graph → graph runner + `LoopStore` → the custom-Compose **Loop room**
   (Loops list + Log, then the dot-grid canvas). Mocks accepted; see
   `workflow-builder.md`.
6. **Voice** authoring (→ emit BPMN): later track.

Each step: pure domain logic gets JVM tests; both flavors stay green; runtime
behaviour is owner-verified on the target device (no device/emulator in CI).
