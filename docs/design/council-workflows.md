# Design: Council workflows — the loop, as a feature

> Status: **design** (not built). Captures the owner's vision so we can build it in
> deliberate steps. Naming rule (CLAUDE.md §3): this is a **council** / workflow of
> **experts** — never "MoE".

## Why

The owner watched our own working loop — *state an objective → produce something →
review → give feedback → refine → repeat until it matches the destination* — and
recognised it as a product. Today Aarso already has a **council**: a one-shot
fan-out of voices (`domain/council/Agent.kt`, `ChatViewModel.sendCouncil`). The
vision is to grow that one-shot panel into a **workflow**: a small graph of
**experts**, each an expert *role* powered by the *model best suited to it*, that
can **loop** toward a desired outcome — and, crucially, can **"propose a look"**:
generate candidate outputs (text or image) for a human to react to, exactly as we
did with the field designs.

This is the natural top of the "Sovereignty of Attention" thesis: the user doesn't
just pick a model, they compose a transparent, legible *process*, and stay in the
loop at every fork.

## What exists to build on (don't reinvent)

- **`InferenceEngine.generate(messages, params): Flow<GeneratedToken>`**
  (`inference/InferenceEngine.kt`) — uniform streaming surface over on-device
  llama.cpp and the watched cloud engines, with per-token logprob/entropy on
  device. A workflow node runs by calling this.
- **`ModelRegistry`** (`inference/ModelRegistry.kt`) — resolves a model id to an
  engine; knows on-device (`LOCAL_GGUF`) vs watched `CLOUD`. This is how a node
  gets "the model suited to the task".
- **The council** (`domain/council/Agent.kt`, `ChatViewModel.sendCouncil`) — named
  voices with system prompts; persona-diversity (one model, many hats) and
  model-diversity (many models). A workflow node is a generalisation of a voice.
- **The append-only message tree** (`domain/tree/`) — already git-shaped; a
  workflow *run* is a sub-tree, so branching/restore/inspection come for free and
  every step stays visible (legibility).
- **Image generation** (`inference/image/`, SD engines) — lets a "propose a look"
  node emit images, not just text.

## The model (proposed)

A **Workflow** is a small directed graph (usually a short pipeline + one loop):

```
Node = {
  id,
  role:        // the "expert": a system prompt / instruction
  modelRef:    // explicit model id, or a policy ("fastest on-device", "best cloud")
  inputs:      // which upstream nodes' outputs feed this one
  kind:        // GENERATE_TEXT | GENERATE_IMAGE | CRITIQUE | ROUTE | GATE
}
Edge = (from, to)
Loop = { body: subgraph, stop: StopCondition }   // objective→propose→critique→refine
StopCondition = maxIterations | criticApproves | humanApproves | scoreThreshold
```

The canonical loop is exactly ours:

1. **Objective** (human, once): the destination.
2. **Propose** (an expert): produce a candidate (text or image).
3. **Critique** (another expert, ideally a *different* model — model-diversity is
   the point): score/critique against the objective.
4. **Refine**: feed critique back into Propose. Loop until a stop condition.
5. **Human in the loop at every fork** (thesis): the run is a sub-tree; the user
   can inspect, branch, or take over at any node.

"Propose a look" = a loop whose Propose node is `GENERATE_IMAGE` (SD on-device, or
a watched image provider), surfaced as candidates for human selection.

## Autonomy = an escalation matrix (a rule engine) — built

The owner's insight: "how much autonomy before a human gate" is **not a setting —
it's a workflow itself.** A step has an estimated **cost** (tokens, time, money,
compute/calls); below an autonomy **budget** it runs on its own; beyond it, it
**escalates** up an ordered chain of **gates**, each an approver authorized up to a
ceiling: a council **agent/model**, then named **team members** who can also gate,
with the **primary user as the terminal authority**. Test/CI status folds into the
same matrix — **green tests widen** how far the loop may go before asking.

Built (pure, JVM-tested): `domain/council/Escalation.kt` —
`Cost` / `Budget` (null = unlimited per dimension) / `Approver{AGENT|TEAM_MEMBER|
USER}` / `Gate(approver, ceiling)` / `EscalationPolicy(autoBudget, gates,
autoBudgetWithTestsGreen?)`, and `Escalation.decide(cost, policy, testsGreen?) →
Proceed | Escalate(gate)`. The runner consults this before each costly step; an
`Escalate` pauses for that approver and resumes on approval (the integration step).
This is the rule layer beneath both the GATE node kind and the visual builder.

## Authoring (text now, voice later)

- **Text now**: author/edit the workflow in natural language ("a fast local model
  drafts, Claude critiques, loop 3× or until I approve"), parsed into the graph;
  plus a manual node editor. See `voice-input.md` for the voice path (deferred).
- The **visual builder** (draggable nodes/edges, the owner's "workflow builder
  with workflow-builder tools") is a later milestone; the engine and a text/JSON
  representation come first so there's something real to visualise.

## Binding constraints (must hold)

- **On-device default; cloud watched.** Per-node model choice must surface watched
  cloud nodes visibly (a run that touches cloud says so, per node). Never a hidden
  cloud fallback.
- **No telemetry.** Workflows, runs, and critiques are local artifacts.
- **Never "MoE".** Council / experts / workflow.
- **§5b/§5c untouched** (Issue #2).
- **Legibility over convenience**: every node's prompt, model, and output is
  inspectable; the run is a visible sub-tree, not a black box.

## Build order (proposed)

1. **Headless engine spike** (next): `domain/council/Workflow.kt` types +
   `WorkflowRunner` that executes objective→propose→critique→refine over existing
   engines, JVM-tested against the Echo engine. No UI. Proves the loop.
2. Persist a run as a tree sub-graph (reuse the tree + new metadata keys).
3. A minimal **text-authored** run surfaced in chat (a "Run a loop" composer mode),
   candidates shown for human selection.
4. Per-node model assignment UI (reuse the model bottom sheet).
5. The **visual builder**.
6. Voice authoring (separate track).

## Open questions for the owner

- Default stop conditions (max iterations? critic-approves? always human-gated?).
- Cost/latency surfacing for cloud nodes (a run could fan out many calls).
- Where a workflow library lives. (The code-shaped instance of this — Aarso
  driving a coding loop on the user's Git repos via the Claude API — is
  `coding-assistant.md`.)
- How much the builder should auto-propose a graph from a one-line objective vs.
  require explicit composition.
