# Design: Loop distillation — the literature as editable, owned loops

> Status: **design + curated-library brick built.** The *floor* (a hand-authored
> reference-pattern library, `domain/council/PatternLibrary.kt`, JVM-tested) lands
> with this doc; the *ceiling* (a meta-agent that distils a source you bring into a
> new loop) is design-only here. Naming rule (CLAUDE.md §3): everything below is a
> **council / loop / workflow of experts** — never "MoE". Importing a paper that is
> *named* "Mixture of Experts" is fine; what we never do is rebrand Aarso's own
> council as MoE.

## The owner's insight

> "I read about mixture of agents, mixture of experts, mixture of models — they have
> repos too. These are ultimately nothing but **loops**, in our terminology."

That is exactly right, and it is the most thesis-pure framing the project has found.
Almost every orchestration paper in the literature is a **graph over model calls** —
service tasks (an engine call), gateways (routing/voting), and loop markers (rounds,
retries). In Aarso's ontology that is precisely a **Loop**: a `BpmnGraph` of experts
(`domain/bpmn/BpmnGraph.kt`) executed by the graph runner over `EngineGenerator`s.

So "loop engineering" stops being a slogan and becomes literally true: the research
canon is a body of **loops you can run, read, edit, and own**. This doc turns that
observation into a feature.

## The mapping (proof it holds)

Each well-known method is a short graph. The curated library ships the first four:

| Paper / method | Source | As a Loop (BPMN over engines) |
|---|---|---|
| **Mixture of Agents** | Wang et al. 2024, arXiv 2406.04692 | parallel **fan-out** to N proposers → **join** → **aggregator** synthesises → loop the aggregate back as context for the next *layer* |
| **Self-consistency** | Wang et al. 2022, arXiv 2203.11171 | fan-out **N samples of one prompt** (diversity = sampling, *not* models) → **majority vote** (business-rule task) |
| **Reflexion** | Shinn et al. 2023, arXiv 2303.11366 | actor attempts → evaluator scores → gateway *succeeded?* → on fail, a **self-reflection** writes verbal feedback to **memory** and loops back (this is our refine loop + memory) |
| **Multi-agent debate** | Du et al. 2023, arXiv 2305.14325 | fan-out to N debaters → exchange → *more rounds?* gateway loops the transcript back → **judge** converges |

The point is not that these are exotic — it's that they're all the **same handful of
BPMN shapes** (fan-out/join, a loop-back gateway, a memory node), which is why one
substrate expresses the whole family. Tree-of-Thoughts, self-refine, router/MoE-style
dispatch, and chains-of-debate all decompose the same way.

## Two tiers: a floor and a ceiling

Mirrors the decision already made for themes (curated presets are the floor, a custom
picker is the ceiling) and for disclosure (CORE → POWER).

### Floor — the curated reference library (built)
`domain/council/PatternLibrary.kt` hand-authors the four loops above as `BpmnGraph`s,
each wrapped in a `ReferencePattern { id, title, source, summary, graph }`. They:

- **round-trip through `BpmnArchive`** (verified) — i.e. they are valid, transportable
  BPMN 2.0 that opens in any BPMN tool and syncs to the user's Git host as `.bpmn`;
- carry **provenance** in the start event's `<aarso:meta>` (`source`, `summary`,
  `pattern`, and the pattern's knob — `layers` / `samples` / `rounds`);
- are **model-agnostic**: nodes carry a `role` and, where model-diversity is the
  research point, `diversity="encouraged"` — but **no node defaults to a watched
  cloud model** (binding rule #2: on-device is the default; cloud is opt-in when the
  user assigns models on moving the loop Unused → Running).

This is valuable *on its own*, independent of any AI magic: a small canon of
orchestration patterns the user can drop in, inspect, and edit.

### Ceiling — the meta-distiller (design only)
A **meta-agent that reads a source you bring and emits a new loop.** Crucially, the
distiller is *itself a Loop* — Aarso eating its own tail:

```
read source → identify the pattern → extract topology (roles, fan-out N,
aggregation, stop condition) → emit a BpmnGraph → validate (BpmnArchive round-trip)
→ critique/repair until valid → land as an Unused draft with provenance
```

That graph is a `WorkflowRunner` (`domain/council/Workflow.kt`) with one extra
service-task that calls `BpmnArchive` to validate its own output — the same
propose→critique→refine engine already built and Echo-tested.

## The honest part (where the cost and risk are)

1. **Scaffold, not reproduction.** Extracting the *topology* (how many agents, the
   aggregation, the stop condition) is achievable. *Faithfully reproducing* a paper's
   exact prompts, hyper-parameters, and results is **not** — papers under-specify,
   repos drift from papers, and a model summarising a method will invent specifics.
   The feature must be framed as **"a legible first draft you refine,"** never "a
   verified implementation." Trust/legibility is the whole thesis; over-promising
   here would spend it.
2. **The distiller usually wants a capable model → watched.** Distilling a 12-page
   paper is a long-context structured-extraction task where a 3B on-device model is
   weak. So the distiller will often run on a **watched cloud** model — which must be
   surfaced as a watched object, per node, like every other cloud touch. On-device
   stays the default; **pasting the method text yourself** is the most sovereign
   intake and is first-class (no fetch at all).
3. **Text before repos.** Prose (a method section / abstract / pasted description)
   yields topology far more reliably than arbitrary agent-framework **code**. v1
   scopes to text; "read the repo" is a later, harder ambition — don't lead with it.
4. **No preset zoo.** If this becomes "generate 40 loops nobody inspects," it betrays
   legibility. Guardrail, enforced by the lifecycle: a distilled loop **always lands
   Unused** (must be opened/edited before it can run) and **always carries provenance**
   in `<aarso:meta>` — *"distilled from `<source>` by `<model>` on `<date>`; these
   assumptions were made."* Influence stays visible; that's the binding rule, applied
   to method instead of chrome.

## What exists to build on (don't reinvent)

- **`domain/bpmn/BpmnGraph.kt` + `BpmnArchive.kt`** — the loop model and its BPMN 2.0
  transport; Aarso semantics ride in `<aarso:meta>` extension elements. The library's
  output target and its validity oracle.
- **`domain/council/Workflow.kt`** (`WorkflowRunner`, `Expert`, `Stop`, `Generator`,
  `Gating`) — the refine-loop engine the distiller *is*.
- **`domain/council/EscalationBpmn.kt`** — precedent for building `BpmnGraph`s in code
  and encoding richer data into `ext` (the cost-gate macro). The library follows its
  style.
- **`inference/EngineGenerator.kt` + `ModelRegistry`** — per-node model resolution
  (on-device vs watched cloud).
- **Lifecycle Unused → Running → Retired** (`workflow-builder.md`) — the review gate
  that keeps distilled drafts from auto-running.
- **Git layer** (`data/GitTransport.kt`, `GitHostStore.kt`, `domain/git/`) — both the
  sync target for the library *and* the future "import from a repo URL" intake (a
  user-chosen, watched source — same category as the existing host connection, not
  telemetry).
- **`domain/disclosure/Disclosure.kt`** — the importer is a **POWER**-tier surface;
  the curated library can appear at STUDIO.
- **`data/SharedIntake`** — the intake precedent for "paste text / share a URL in."

## Binding constraints (must hold)

- **Never "MoE"** for Aarso's own feature. Council / experts / loop.
- **On-device default; cloud watched.** Templates default to no cloud; the distiller's
  own cloud use is a visible watched object.
- **No telemetry.** Sources are fetched only when the user asks, only from the
  user-named location; the library is local; distilled loops are local artifacts.
- **Legibility:** every distilled loop is editable BPMN that lands Unused with
  provenance — never a black box that just runs.
- **§5b/§5c untouched** (Issue #2).

## Build order

1. ✅ **Curated library** — `domain/council/PatternLibrary.kt` (MoA, self-consistency,
   reflexion, debate) + `PatternLibraryTest` (round-trip + integrity + provenance +
   the never-MoE / no-default-cloud guards). *This doc's brick.*
2. ✅ **Loops lifecycle + catalog** — the lifecycle already exists in `domain/loop/`
   (singular): `Loop` (`bpmnXml` definition + `state`/timestamps), `LoopLifecycle`
   (UNUSED→RUNNING→RETIRED, only-Unused-editable, `duplicate → Unused`) and the
   prefs-backed `data/LoopStore`. This doc adds `domain/loop/LoopCatalog.kt`
   (`LoopCatalogTest`) surfacing the curated `PatternLibrary` as **Unused** seed loops
   (`bpmnXml` = the pattern's BPMN). Next here: the Loops-list UI (duplicate-to-edit)
   and Git-sync of the `.bpmn` (push `loop.bpmnXml` via the existing Git layer).
3. **Intake** (Settings → POWER): paste method text / a description; later a URL or
   PDF (watched fetch via the Git/HTTP layer).
4. ✅ **The distiller** — `domain/loop/Distiller.kt`: reads a method → the *model*
   extracts a compact, inspectable `DistilledSpec` (pattern + agents/rounds/roles/
   aggregation) → *code* deterministically builds a guaranteed-valid `BpmnGraph` from a
   known topology family (fan-out-aggregate / sample-vote / refine-loop / debate /
   pipeline) → validated via `BpmnArchive` round-trip. A repair rung re-asks on a bad
   parse, with a pipeline fallback; clamps absurd counts. Returns an **Unused** `Loop`
   whose `bpmnXml` carries the provenance (source + model + date) in its start event.
   Model-agnostic — the caller picks on-device or a watched model; nothing defaults to
   cloud. JVM-tested against a scripted generator (`DistillerTest`). (Implemented as a
   direct extract→build→validate→repair loop rather than literally over
   `WorkflowRunner` — same shape, clearer code.) Next: the intake (paste text) +
   surfacing distilled drafts in the Loop room.
5. **Provenance surfacing** in the Loop room (who/what/when distilled, assumptions).
6. **Repo ingestion** (harder) and **voice intake** (separate track) — later.

## Open questions for the owner

- **Library scope.** Ship the four above first; which next — Tree-of-Thoughts,
  self-refine, router/dispatch, plan-and-execute?
- **Where the library lives in the UI.** A "Reference" tab in the Loops list? Seeded
  into a user's loop set on first run, or kept read-only until duplicated?
- **Distiller default model.** Always ask the user to pick the (likely watched) model,
  or offer "best available on-device" with an honest "this will be rough" note?
- **How much to auto-propose.** From a one-line objective ("debate, 3 rounds, then a
  judge"), should the importer assemble a graph directly, or only ever distil from a
  named source?
