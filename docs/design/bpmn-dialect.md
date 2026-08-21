# The Fonebrew BPMN dialect — what actually transfers

> Written 2026-07-27 in response to an explicit ask: before a web-based Loop editor
> (or any other importer) can exist, it needs a precise account of what Fonebrew's
> `.bpmn` files actually contain, what a general-purpose BPMN tool would see, and
> what gets lost crossing that boundary in either direction. This is that account —
> derived directly from `domain/bpmn/BpmnGraph.kt` and `BpmnArchive.kt` (the only
> two files that define the format), not from the spec in the abstract.

## The one-sentence version

A Fonebrew loop is **standard BPMN 2.0 XML** with exactly one Fonebrew-specific
extension point — a single `<aarso:meta>` element per node, carrying flat
string-valued attributes. Everything else in the file is plain, spec-legal BPMN.
That's a deliberate, narrow surface: it's what makes the file open in any BPMN
tool at all, and it's also exactly the seam any importer (a web editor, a CLI, a
second mobile client) needs to replicate — not the whole BPMN spec, just this one
extension contract.

## What's written, element by element

`BpmnArchive.write()` emits, per `BpmnGraph`:

- `<bpmn:definitions>` with the five standard namespace declarations
  (`bpmn`, `bpmndi`, `dc`, `di`, `xsi`) plus one Fonebrew one:
  `xmlns:aarso="https://aarso.dev/bpmn"`.
  **Known naming debt:** this is the pre-rename `aarso.dev` domain — the product
  is Fonebrew now, the namespace URI isn't. It doesn't need to *resolve* to
  anything (XML namespace URIs are just unique strings), but a human reading the
  raw XML will see the old name. Fixing it is a coordinated one-line change across
  every `.bpmn` file this app has ever written and every place that reads one back
  — not urgent, but worth doing deliberately rather than by accident.
- `<bpmn:process isExecutable="false">` — always `false`, unconditionally. This is
  honest: Fonebrew's `GraphRunner` is a custom runner, not a spec-conformant BPMN
  execution engine, and the file says so to any tool that checks.
- One element per node, using the **real BPMN element name** for its kind
  (`bpmn:startEvent`, `bpmn:serviceTask`, `bpmn:exclusiveGateway`, …) — see
  "Node kinds" below for the full set and which ones Fonebrew itself actually
  generates today.
- Inside a node: `<bpmn:incoming>`/`<bpmn:outgoing>` (element IDs, both directions,
  always present, generated from the edge list — never hand-authored, so they're
  always internally consistent) and, when the node has any `ext` entries, exactly
  one extension block:
  ```xml
  <bpmn:extensionElements><aarso:meta role="Proposer" instruction="..." diversity="encouraged"/></bpmn:extensionElements>
  ```
  All of a node's `ext` map becomes attributes on that single `<aarso:meta>`
  element — there is no nesting, no repeated elements, no typed values (everything
  is a string). One element, flat attributes: that's the entire extension
  mechanism, by design.
- `<bpmn:sequenceFlow>` per edge, with `sourceRef`/`targetRef`, an optional `name`
  (the branch label — "approve"/"refine"/"another round"), and an optional
  `<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">` whose **text
  content is a plain string** — not FEEL, not JUEL, not any registered expression
  language. Fonebrew's own `GraphRunner` reads that string with its own ad-hoc
  matching; a real BPMN engine's condition evaluator would not know what to do
  with it (see "Conditions aren't a real expression language" below).
- `<bpmndi:BPMNDiagram>`/`BPMNPlane`/`BPMNShape`/`BPMNEdge` — the diagram
  interchange (DI) layer, so a loop's layout travels with the file. **Only
  `dc:Bounds` (x/y/width/height) is written per node.** Edges get a bare
  `<bpmndi:BPMNEdge>` with no `<di:waypoint>` children at all — an edge's visual
  route is not preserved, only its endpoints (which are already implied by
  `sourceRef`/`targetRef`). A tool that draws bent/routed connectors and expects
  them preserved will lose that on a Fonebrew round-trip.

## What's read back

`BpmnArchive.read()` is narrower than `write()` produces, on purpose — it looks
for exactly what Fonebrew itself writes, nothing more:

- Finds the **first** `bpmn:process` element in the whole document (namespace-
  qualified lookup, first match only — a multi-process file only yields its first
  process; pools/lanes/collaborations are not modeled at all).
- Within it, walks direct children: `sequenceFlow` becomes a `BpmnEdge`
  (`conditionExpression`'s raw text, if present, becomes `BpmnEdge.condition`);
  anything else whose element name matches a known `BpmnNodeKind` becomes a
  `BpmnNode`. **Anything whose element name isn't one of the 14 kinds below is
  silently skipped** — not an error, not a warning, just absent from the resulting
  graph. A boundary event, an intermediate catch/throw event, a timer, a message
  event, a data object, a text annotation, a group, an ad-hoc sub-process: all
  invisible to this reader.
- A node's `ext` map is populated **only** from
  `bpmn:extensionElements/aarso:meta`'s attributes (namespace-filtered so a
  `xmlns:...` declaration on that element itself isn't mistaken for a data
  attribute). Any *other* extension element — a different vendor's own
  `extensionElements` payload, `bpmn:documentation`, anything — is invisible here
  too.
- Node bounds come from `BPMNShape`/`dc:Bounds`, matched back to a node by
  `bpmnElement` id; a node with no matching shape defaults to `Bounds(0,0)`
  (origin, default 130×64 size) rather than failing.
- No schema validation of any kind runs. A well-formed-XML-but-not-really-BPMN
  document, or one using a BPMN namespace variant this app doesn't check for,
  will either throw (no `process` element found → `ClassCastException`/NPE, an
  ungraceful failure with no "this isn't a BPMN file" message) or parse into an
  emptier-than-expected graph, never a loud "this file wasn't understood."

## Node kinds: 14 defined, ~5 actually produced

`BpmnNodeKind` enumerates 14 real BPMN element names — `startEvent`, `endEvent`,
`task`, `userTask`, `serviceTask`, `scriptTask`, `businessRuleTask`, `manualTask`,
`sendTask`, `receiveTask`, `callActivity`, `subProcess`, `exclusiveGateway`,
`parallelGateway`, `inclusiveGateway` — so the *reader* understands all 14 today.
The *writers* (the Loop editor's own node-add menu, and `Distiller`'s
`TopologyBuilder`) only ever emit five: `startEvent`, `endEvent`, `serviceTask`
(the AI-step task, everywhere the editor calls it a "Task"), `exclusiveGateway`,
and `parallelGateway`. `businessRuleTask` is defined and used once (the
sample-vote pattern's "Vote" node) but carries no real decision-table semantics —
just an `ext["aggregation"]` string. The other nine kinds (`userTask`,
`scriptTask`, `manualTask`, `sendTask`, `receiveTask`, `callActivity`,
`subProcess`, `inclusiveGateway`) are **spec-legal and round-trip if present in an
imported file**, but nothing in this app creates them, and `GraphRunner`'s own
execution semantics for them are unverified — importing a foreign BPMN file that
uses them would read into valid `BpmnNode`s, but running that loop is untested
territory.

## Conditions aren't a real expression language

Every condition this app writes is a hand-built string like `"trials < 2"` or
`"layer == 3"` — readable, but evaluated by `GraphRunner`'s own bespoke matching,
not a general expression evaluator. There is no grammar, no operator set beyond
what each `TopologyBuilder` pattern happens to emit, and no parser shared between
write and read — the string is opaque cargo that only this app's runner knows how
to interpret. A gateway condition authored in a real BPMN tool (FEEL, JUEL,
Groovy, whatever that tool speaks) will parse into `BpmnEdge.condition` as inert
text; `GraphRunner` won't evaluate it correctly unless it happens to match the
handful of literal patterns Fonebrew's own generators produce. **This is the
concrete argument for the DMN question separately raised**: the moment a
condition needs to combine more than one input, a real decision-table format
(with its own defined semantics) is worth more than another bespoke string
grammar — see the loop-distillation doc's open questions for where that fits.

## What a web (or any external) editor needs to replicate exactly

To interoperate — not just "open the XML," but genuinely round-trip a loop a
phone can load back and run — an external editor needs to:

1. Write the same five namespace declarations plus `xmlns:aarso`, and keep
   `isExecutable="false"`.
2. Only ever emit the five node kinds Fonebrew's own editor produces
   (`startEvent`/`endEvent`/`serviceTask`/`exclusiveGateway`/`parallelGateway`) —
   anything else round-trips through the *reader* fine, but Fonebrew's own
   `LoopCanvas`/`GraphRunner` won't know what to draw or do with it.
3. Write one `<aarso:meta>` per node with **only** the attribute keys this app's
   own code currently reads: `systemPrompt`, `model`, `role` (consumed by
   `LoopNode`/`fromBpmnNodes`), plus whatever provenance keys a distilled loop
   carries on its start event (`source`, `pattern`, `distilledBy`, `distilledOn`,
   `summary` — see `Distiller.provenance()`). Any other attribute name is not an
   error, but it's also not lost silently anymore in one specific way: since
   [`LoopNode.provenanceExt`](../../app/src/main/java/dev/aarso/ui/loops/LoopRoom.kt)
   now preserves *unrecognised* `ext` keys through an edit-and-resave cycle, an
   external tool's own custom attributes on `<aarso:meta>` *would* survive a
   round-trip through Fonebrew's editor even though nothing in the UI reads them
   — a soft compatibility seam, not a guarantee.
4. Keep conditions to the literal patterns `GraphRunner` already understands, or
   accept that a condition it writes is decorative until GraphRunner is taught a
   real grammar.
5. Write `dc:Bounds` per node (plain x/y/width/height in the same unit space —
   these are raw doubles, not dp/px-aware; Fonebrew's own canvas treats them as
   pixels at whatever density the phone's screen happens to be, so a web editor
   authoring at a very different canvas scale will produce loops that open
   either tiny or enormous on first load unless it matches Fonebrew's rough
   coordinate range, roughly 0–800 in each axis for the shipped patterns).
6. Not rely on edge waypoints, pools/lanes, message flows, data objects,
   documentation elements, or non-`aarso` extension payloads surviving a
   round-trip through Fonebrew at all — none of it is read, so none of it comes
   back.

## Known limitations, stated plainly

- **One process per file, no collaborations.** Pools/lanes/participants aren't
  modeled; a multi-lane file collapses to whatever's in the first `process`.
- **No DI waypoints.** Edge routing is always a straight line between two
  endpoints on read; a foreign tool's bent connectors are not preserved.
- **No schema validation on read.** A malformed or unexpectedly-shaped BPMN file
  fails hard (exception) or silently under-populates rather than reporting *why*
  it couldn't be understood.
- **Conditions are opaque strings**, not a real expression language — see above.
- **`isExecutable` is always false** — by design, not a bug, but worth knowing if
  a downstream tool branches on that flag.
- **The nine unused node kinds are read-safe, run-unsafe** — they parse, but
  `GraphRunner`'s behaviour on them is untested.
- **The `aarso.dev` namespace URI is a naming artifact** from before the
  Fonebrew rename — cosmetic, but real, and any second implementation of this
  reader/writer needs to match the *exact* string, not a "corrected" one, or the
  two won't recognise each other's files.

## Where this fits

This is the format contract; it doesn't itself change any code. It's the
foundation for: a web-based Loop editor (`asystemofcells.com`), any future "import
a loop" flow in the Loops list beyond what the app already writes itself, and any
second client that wants to read or write a Fonebrew loop without guessing.
