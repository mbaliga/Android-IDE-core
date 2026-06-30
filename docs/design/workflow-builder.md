# Design: Workflow builder — the loop, visualized & gated

> Status: **planning.** Engineering is mine and mostly settled below; the **UX is
> the owner's to drive** — the sections marked _(owner drives)_ are a proposal to
> react to, not a spec. Working model: I propose look/flow as mocks, the owner
> steers, we converge (same loop as the Aeon field work). Library: **custom
> Compose** (no third-party node editor — see the chat rationale).

Builds on what's already shipped: `domain/council/Workflow.kt` (`Expert`/`Stop`/
`WorkflowRunner`), `domain/council/Escalation.kt` (cost-budgeted gates),
`inference/EngineGenerator.kt` (engine→Generator), the append-only tree, and the
Git layer.

> **Terminology (owner):** the product word is **"Loops"** — "loop engineering"
> made apparent. A *loop* = a definition (the graph); a *run* = one execution of it
> (the log/timeline). Lifecycle: **Unused** (editable draft, not yet triggered) →
> **Running** (live) → **Retired** (run its course). Duplicating a Running or
> Retired loop yields a new **Unused** draft (never live by default); only Unused
> loops are editable.

## Engineering (mine — settled)

### 1. Graph model + transport = **standard BPMN 2.0** ✅ built (headless)
Owner decision (sovereignty): a loop definition serialises to **standard BPMN**,
not a bespoke format, so it opens in any BPMN tool and travels to the user's own
Git host ("discard the shell"). Built:
- `domain/bpmn/BpmnGraph.kt` — the in-memory model: `BpmnGraph(id, name, nodes,
  edges)`, `BpmnNode(id, kind, name, bounds, ext)` with `kind` the BPMN vocabulary
  (start/end events, user/service/script/business-rule/manual/send/receive tasks,
  call activity, sub-process, exclusive/parallel/inclusive gateways — the owner's
  Bricks set), `BpmnEdge(id, source, target, name, condition)`.
- `domain/bpmn/BpmnArchive.kt` — write/read **BPMN 2.0 XML** (dependency-light:
  hand-built XML out, `javax.xml` DOM in). Aarso-specific data (assigned **model**,
  council **role**, **watched**-cloud, cost **budget**, **macro** marker) rides in
  a single `<aarso:meta …/>` **extension element** per node, so the file stays
  valid-standard. Node positions ride in **BPMN-DI** (`dc:Bounds`), so the canvas
  layout transports. JVM-tested (`BpmnArchiveTest` — full round-trip + escaping).

A **run** is *not* BPMN (it's an execution trace, not a definition): it rides the
existing append-only message tree (§5). So **loops = `.bpmn`, runs = tree JSON**,
both syncing to the user's host via the existing Git layer.

### 2. Graph runner (`WorkflowGraphRunner`)
Topological execution over the graph; each EXPERT/CRITIC node runs through an
`EngineGenerator` for *its* assigned model (model-diversity per node). Emits a
`Flow<RunState>` so the UI follows live.

### 3. Escalation wiring = a suspendable state machine ✅ built (headless)
Landed on the linear loop as an optional `Gating(policy, estimateStep, approve,
testsGreen)` on `WorkflowRunner.run` (`domain/council/Workflow.kt`). Before each
build step the runner sizes the projected **cumulative** cost and calls
`Escalation.decide(cost, policy, testsGreen)`:
- `Proceed` → continue silently.
- `Escalate(gate)` → the runner **suspends** on the single `suspend approve(gate,
  cost): Boolean` callback and waits; `true` resumes, `false` halts the run
  (`stoppedBecause = "halted at <approver> gate before iteration N"`).
- Approver kinds: with no callback, **AGENT** rungs auto-proceed and human rungs
  (**USER**/**TEAM_MEMBER**) halt conservatively. When a callback is supplied it
  is the single resolution point — the app routes it (AGENT → ask an agent model;
  USER → inline card; TEAM_MEMBER → inbox). True async team gates still need the
  Git/identity layer + a shared inbox (later).

JVM-tested against a fake generator (`WorkflowGatingTest`, 6 cases) + the pure
`CostEstimator` (`CostEstimatorTest`). The graph runner (§2) will reuse this same
decide/suspend/resume core per GATE node, surfacing `RunState ∈ { Running |
AwaitingApproval(gate, cost) | Completed | Failed }` as a `Flow` for the UI.

**The "Cost gate" is a macro, not a primitive** ✅ built (`EscalationBpmn.kt`).
The owner's observation: the escalation matrix decomposes into standard BPMN — an
exclusive **gateway** (the budget test) feeding a chain of **approval tasks**
(AGENT rung → **service task**; teammate/you rung → **user task**, BPMN's native
human-in-the-loop). `EscalationBpmn.expand(policy)` produces that sub-graph (so a
Cost gate transports as plain BPMN and "explodes" to inspectable nodes);
`collapse(graph)` reads the `EscalationPolicy` back from the gateway's `<aarso:meta>`
(the metadata is the source of truth, the tasks are the legible expansion).
JVM-tested incl. a full BPMN write→read→collapse round-trip (`EscalationBpmnTest`).
In the palette it stays **one glanceable brick** with an explode/collapse affordance.

### 4. Cost estimator (`CostEstimator`, pure + tested)
From a node's prompt + model spec → `Cost(tokens via countTokens, calls=1,
seconds ≈ tokens × model rate, moneyCents ≈ tokens × price for cloud; 0 for
on-device)`. Per-model coefficients in a small table. Feeds the gate decision.

### 5. Persistence
A **run writes nodes into the message tree** (objective + each proposal/critique/
gate-decision as nodes tagged `workflowRunId`, `nodeRole`, `model`, `iteration`,
`approver`). Reuses the tree ⇒ branching, history, and Git backup for free — this
is the **Log** (the run timeline; see the Loops UX below). A `LoopStore` holds the
loop *definitions* as **`.bpmn`** files (syncable via the Git layer, §1).

### 6. NL authoring (text now)
A parser turns "a fast local model drafts, Claude critiques, loop 3× or until I
approve" into a `WorkflowGraph`. v1: LLM-assisted (ask a model to emit graph JSON,
validate, lay out) — on-thesis (the app composes itself with its own council).
Voice later (`voice-input.md`).

## UX _(owner-driven — decisions locked; mocks rendered)_

Library = **custom Compose**. Lives in a **Loop spatial room** (parks like Chats/
Settings/Models). Approvals shown **both** inline (on the run) and in a dedicated
inbox. Aeon dark/violet, themeable accent. Two mocks rendered & accepted (the
canvas; the Loops list + Log) — see the chat history.

**Loops list** (owner spec): small-caps `LOOPS` header; tabs **Running / Retired /
Unused** (count badges, slanted active chip); a round **+ FAB** (as Chats) creates
a loop; rows show loop **name** + a state dot + sub (models · step) + **date
right-justified**. Tap a row → its **Log**. Duplicate (Running/Retired) → a
non-live **Unused** draft; Unused rows are editable.

**Log** (owner spec): docked header = loop **name** left, **date** right-justified;
the docked date **follows the scroll position** (Today → Yesterday → date), so a
multi-day run reads naturally. Body = the run timeline (selectable entries, the
Log-History style). A pending gate surfaces as an **approval card** at the foot
(what's proposed · cost · Approve/Reject).

**Canvas** (the builder): pan/zoom over a faint **dot-grid that blooms only around
elements** (owner ask); nodes = Aeon **bricks** (BPMN kinds; slanted icon chip +
label + model/watched badge; selected = solid violet); gateways = diamonds; edges
= violet beziers (labelled yes/no, dashed for loop-back); a **BRICKS palette dock**;
candy back/run buttons. Node inspector reuses the model bottom sheet. The **Gate**
brick is the cost/approver-chain macro (§3) with an explode affordance.

## Build order
1. ✅ **Escalation wiring (headless)** — `CostEstimator` + suspend/resume gates on
   `WorkflowRunner.run`. JVM-tested.
2. ✅ **BPMN model + transport (headless)** — `domain/bpmn/` (`BpmnGraph`,
   `BpmnArchive`) + `EscalationBpmn` (Cost-gate macro ⇄ gateway+approval-tasks).
   JVM-tested incl. full round-trip. _(This supersedes the old bespoke-JSON
   `WorkflowArchive` plan — BPMN is the stronger sovereignty call.)_
3. **Persist a run as a tree sub-graph (next)** — the Log. Tag nodes with
   `loopRunId`/`nodeRole`/`model`/`iteration`/`approver`; reuse Git backup.
4. **Graph runner** (`bpmn` graph → topo execution via `EngineGenerator`,
   per-node model) emitting `Flow<RunState>`; `LoopStore` for `.bpmn` definitions
   (+ Git sync).
5. **Custom Compose**: the Loop room — Loops list + Log first, then the canvas
   (nodes/edges/connect-drag/pan-zoom/inspector/gate/approval card + inbox).
6. NL authoring (→ emit BPMN); then voice; then async team-member gates.
