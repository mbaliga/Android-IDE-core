# Plan: Conversation topology, ThreadRail, mega-thread, instruments, and the living graph

For execution by a **Sonnet ultracode session**, one WP (or WP-cluster) per session, each landing green independently on `claude/fonebrew-development-clzu43` in **Android-IDE-core**.

## Context

Fonebrew/Aarso's spine is one append-only, git-like message tree. The owner wants conversation topology to become a first-class, legible surface: branch/fork/spawn with comparison, message-drag gestures, a right-edge dash minimap, a "mega-thread" that stitches sessions+compactions into one human-legible project thread, decision-point tracking, Claude-style context instruments, delegation ("choose for me") tracking, and all of it captured as a latent evolving graph that a local model can observe and remark on — rendered hybrid (native Compose + G6-in-offline-WebView for the deep view).

**Exploration verdict: ~60% of this exists as built-but-unwired, JVM-tested domain machinery.** The plan is mostly wiring, contracts, and surfaces — not new invention.

## Owner decisions (locked in this session)

1. **Pull right + release** = quote into composer; **pull right + hold** = fan Branch/Fork/Spawn (`HyleRadialMenu`).
2. **"Choose for me"** = all four: model-picks-at-branch-point (new), council auto-merge acceptance, loop gateway auto-choice, accepted auto-defaults. One unified delegation-event concept, kept-vs-reverted tracked **descriptively only** (§5b/§5c interpretation stays blocked on Issue #2).
3. **Graph rendering = hybrid**: native Compose for spine/tree/instruments; **G6 v5.1.1** (MIT, verified fully offline-bundleable, Fisheye/EdgeBundling/BubbleSets/Minimap/Timebar built-in) in a locked-down WebView for the deep graph room only. **D3 dropped** — G6 covers graph interaction; Compose covers bespoke micro-viz.
4. **Importance = three levels** (pinned/normal/demoted) — **derived** from existing Verdict + CompactionDirective machinery via `CompactionContract.resolve`, never a new stored field.
5. **Observer ships built but INERT** behind an off-by-default settings toggle (`AarsoCaptureSettings` pattern).

## Placement decisions (recommended, with reasons)

- **Graph substrate lives in core** (`dev.aarso.domain.thread`, pure JVM, extraction-ready per the `search-core` precedent) — **NOT** the public Shared-Libraries-asoc repo: compaction semantics are under the owner's **patent hold** (Personal-Tracker D-V; core went private as the stopgap; do-not-relocate rule). **Not Hyle**: shared-libs exists precisely because non-design-system code kept landing in Hyle. Record the decision in Personal-Tracker `DECISIONS.md` (also flag: Shared-Libraries-asoc missing from `CONSTELLATION.md`).
- The G6 kit HTML lands in **core** (`core-engine/src/main/assets/graph/`), not Hyle — no Hyle repo change needed. (Separate owner backlog note: Hyle repo root has **no LICENSE file** despite `package.json` MIT — close independently.)

## What already exists (verified inventory — reuse, don't rebuild)

| Need | Existing (all `core-engine/src/main/java/dev/aarso/` unless noted) |
|---|---|
| Branch ops | `ChatViewModel.branchFrom/switchAlternative/rewindFrom`; `PathView.Step(alternativeCount, activeAlternative)`; inline `‹ n/m ›` pager |
| Spawn payload | **`domain/bridge/SummaryBridge.kt` — fully built, zero callers** (+ unmounted `SummaryNodeCard` in `ui/components/InspectComponents.kt:204`) |
| Capture importance | **Full compaction stack, tested, no caller**: `CompactionDirective` (F0–F3), `CompactionContract.resolve` (precedence: user directive > −2 tombstone > max floors), `CompactionEngine.run` + byte-comparing `CompactionVerifier`, `Verdict` (±1/±2), `MessageBookmark` (kinds incl. DECISION), `Version`/`VersionSpines`, `GhostBranch` |
| Export/share | `domain/sync/TreeArchive` (open format), `GitBackup` union-sync, `DataExport` |
| Instruments | `ContextCheck`, `TokenStats`, live `InstrumentsStrip`; **unmounted**: `ContextAssembly`/`BudgetMeter` (included/cut attribution — the Claude-panel analogue), `TokenInspector` heatmap, `InputOutputCard`, `ScopeInspector`, `BudgetRingView` |
| Event capture | `domain/mirror/AarsoEventLog` (JSONL, inert `AarsoCaptureSettings.OFF`, **write-only contract**); `LedgerStore` (per-turn), `ReceiptStore` (unconsumed) |
| Graph editor precedent | `ui/loops/LoopRoom.kt` `LoopCanvas` (dot grid, node Boxes + drag/tap/long-press) — lacks pan/zoom/layout |
| Gesture-fan menu | Hyle `cells/HyleRadialMenu` (anchored at gesture point, 2–5 items); `HyleHaptics` (needs arm-tick/detent additions) |
| Contracts discipline | root `schemas/` + `fixtures/` corpus, `ContractEnvelope`, fixtures-first Definition of Ready, fork-lineage vocab precedent (`schemas/loops/loop-fork-lineage.schema.json`) |
| Design vocabulary | `Finish.Reflective/Radiant`, `Pulse.WATCHED(2400ms,42–78%)/STILL`, `Provenance.OnDevice(RADIUM,FILLED_DISC)/Cloud(COLD_CYAN,HOLLOW_RING)` — hue+glyph dual-channel machine-enforced (`ProvenanceTest`) |

## Binding constraints (do not relax)

1. **Patent design-around (US 9,729,695)**: a drag selects a judgment *value*, never a destination/collection; **the message never moves**. Verdict-drag spec already exists: `Android-IDE-Studio/docs/STUDIO_UX_SPEC.md` §4.2 (150 ms hold-to-arm, ±1/±2 detents, judgment ribbon).
2. **Patent hold on compaction contract** (D-V): nothing compaction-semantic goes to public repos.
3. **Issue #2**: `AarsoEventLog` stays write-only; graph projection reads **Room stores**, never the log; observer inert behind toggle; zero drift/idiolect interpretation.
4. **Gesture non-negotiables** (spec §4): tappable parity in the same PR, TalkBack custom actions, Settings→Gestures disable toggles. Horizontal-on-messages is a recorded spec divergence (owner override) — document in new `docs/design/gestures.md`.
5. **Append-only tree**: metadata minted at insert, never edited; retroactive facts → Room tables. Two SQL toolchains stay separate; don't touch the two documented SQLDelight analyzer workarounds.
6. **WCAG 1.4.1 / house gate**: colour never the sole carrier — dash thickness/glyph is the redundant channel. Motion only via `Pulse`, only for real state (watched cloud generation).
7. **WebView**: prior rollback was **WebGL-in-WebView** garbling (Hyle commit `1198515`) → G6 **Canvas2D renderer only**, `blockNetworkLoads=true`, asset-only, CSP meta, narrow bridge. First WebView config in the codebase — owner-verify on device.
8. No telemetry ever; cloud = watched object; council never "MoE"; container has no device (all render/gesture/haptic/WebView behaviour ships **owner-verified**; JVM-test the pure cores).
9. Naming: "spine" is taken (`VersionSpines`) → the minimap component is **ThreadRail**.

## Data model (one Room bump, v8→v9)

- **`thread_markers`** (id, rootId, anchorMsgId?, kind CHAPTER|SESSION_START|COMPACTION_RUN|LINEAGE_SRC, label?, note?, at, source USER|SYSTEM, payloadJson?) + `ThreadMarkerDao`/`ThreadMarkerStore` (CurationStore shape).
- **`delegation_events`** (id, at, kind MODEL_PICK_BRANCH|COUNCIL_AUTOMERGE|GATEWAY_AUTO|AUTO_DEFAULT, rootId?, anchorMsgId?, chosenRef?, alternatives, outcome PENDING|KEPT|REVERTED, outcomeAt?) + DAO/Store.
- **Node-metadata keys (insert-time only)**: `lineage.kind|srcRoot|srcNode|at` (first node of fork/spawn roots), `bridge`/`bridge.payload` (spawn bridge node), `compaction.boundary`/`compaction.receipt`, `session.start`.
- **`conv_facets` columns**: `lineage_parent`, `lineage_kind`, `chapter_count`, `compaction_count` + projector/indexer updates + `PROJECTION_VERSION` bump + idempotent `ensureFacetColumns` ALTERs in `SearchDriverFactory.open` (raw-SQL precedent).
- **`AarsoEventKind` additions** (write-only log): FORK_CREATED, SPAWN_CREATED, CHAPTER_MARK, SESSION_START, DELEGATION.
- **Contracts (fixtures FIRST)**: new `schemas/thread/` — `thread-event.schema.json`, `thread-marker.schema.json`, `thread-graph.schema.json` in `ContractEnvelope`, with valid/invalid/adversarial fixtures + `.expected.txt` siblings.

## Gesture arbitration (verified against `SpatialRoot.kt`)

- Shell facts: `spatialEdgeDrag` claims horizontal-from-L/R and vertical-from-T/B **only** — vertical drags starting in the right band stand down (line-verified); `spatialPinch` claims only ≥2 pointers. LazyColumn scroll is beaten by the 150 ms hold-arm.
- Bubble `combinedClickable` is **replaced** by one custom `awaitEachGesture` detector. Pure state machine `domain/gesture/MessageDragLogic.kt` (events→intents: Yield/Arm/VerdictDetent/CommitVerdict/Reply/Quote/OpenRadial/LongPress/DoubleTap), exhaustively JVM-tested; thin `pointerInput` wrapper owner-verified.
- Timings: 150 ms arm (haptic tick) → vertical = verdict ribbon with ±24/±56 dp detents committing via existing `setVerdict` (+snackbar undo); horizontal left = reply-quote, right release = quote, right hold ≥400 ms = `HyleRadialMenu` fan (Branch/Fork/Spawn); 500 ms stationary = TurnActionsSheet; double-tap = bookmark.
- Capture-importance chip shows `Fates.forResolution(CompactionContract.resolve(...))` — pinned=F3/mustInclude, normal=default, demoted=tombstone.

## ThreadRail (the dash minimap)

Overlay in the thread Box, `align(CenterEnd)`, ~10 dp visual / 24 dp hit strip; one dash per `PathView.Step`; tap-to-jump (reuse `findScrollPrefix` offset) + vertical scrub (ratchet haptic per turn); hidden when shell not at home (avoids `EdgePeek`/parked-card collision). Pure `ThreadRailPresenter` binds: **thickness** ← `ResolvedFidelity` (3/2.25/1.5/1 dp; tombstone = 1 dp + strike), **hue+glyph** ← `Provenance` (dual-channel enforced), **accents** ← bookmark pin / version flag / chapter bracket / session hairline rule / compaction diamond / lineage branch glyph, **motion** ← `Pulse.WATCHED` only on the live dash during watched-cloud generation. Parity: "Jump to…" sheet. Disclosure: new `Surface.THREAD_RAIL(STUDIO)`.

## Work packages (strict order; each gates green independently)

**Gate for every WP (unpiped, exit code checked):**
`export LANG=C.UTF-8 LC_ALL=C.UTF-8; ./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :core-engine:testFullDebugUnitTest > /tmp/gate.log 2>&1; echo $?`

- **WP0 — Contracts first**: `schemas/thread/*` + `fixtures/thread/*` + `domain/thread/ThreadCodec.kt` (+tests, `EnvelopeCodec` patterns). 100% JVM.
- **WP1 — Data model**: AppDatabase v9 (two entities/DAOs/stores + fake-DAO tests per `CurationStoreTest`), `AppContainer` wiring, `ChatViewModel` marker/delegation flows + `markChapter/renameChapter/removeChapter/markSessionStart`, `AarsoEventKind` additions, TurnActionsSheet rows ("Mark chapter here…", "Start fresh session here").
- **WP2 — Fork/Spawn/lineage + compare**: pure `domain/tree/TreeFork.copySubtree` (fresh ids, remapped parents, lineage meta on new root), `domain/bridge/SpawnBridge` (reuses `SummaryBridges.selectCarryForward`; prose via `summarizeActivePath` pattern), `domain/tree/SiblingCompare` presenter, `ui/CompareSheet` (stacked cards, "Continue with this" → `branchFrom`); `forkFrom`/`spawnFrom` on ChatViewModel (+events/markers); mount `SummaryNodeCard` for bridge nodes; "Compare alternatives" on the pager row.
- **WP3 — Compaction runs**: `inference/EngineCompactionAgent` (the model-call half), `CompactionBoundary.effectivePath` (prompt truncation above newest boundary node), `CompactionPreviewPresenter`, receipt persistence via existing `ReceiptStore`, `CompactionSheet` with **loud** `Failure(F3Violations)` dialog; entry from InstrumentsStrip + TurnActionsSheet; `runTurn`/`recomputeStatus` route through the boundary.
- **WP4 — Message drag gestures**: `MessageDragLogic` (+exhaustive tests), `Modifier.messageGestures` + ribbon visuals, radial host, quote/reply composer helpers, TalkBack actions, TurnActionsSheet parity rows, Settings→Gestures toggles (3 SessionStore keys), `docs/design/gestures.md` (records spec divergence + arbitration table). SpatialRoot untouched.
- **WP5 — ThreadRail**: `ThreadRailPresenter` (+tests incl. structural "provenance differs ⇒ glyph differs"), `ui/components/ThreadRail.kt`, ChatScreen mount + `showThreadRail` from `controller.atHome`, Disclosure surface.
- **WP6 — Mega-thread + search facets**: pure `ThreadChains` (roots + lineage meta + markers → chains), TreeRoom "Chain" chip view, `conv_facets` columns + projector/indexer/driver updates (real-SQLite JVM tests), ChatsRoom "spawned from …" chip.
- **WP7 — Instruments panel**: mount `ContextAssembly`+`BudgetMeter`+`ScopeInspector`+`InputOutputCard`+`TokenHeatmap` in an `InstrumentsPanel` dialog; ViewModel keeps `lastTurnTokens` + `instrumentsAssembly` flow; entry from expanded InstrumentsStrip.
- **WP8 — Delegation events**: `DelegationOutcomes.correlate` (KEPT/REVERTED truth-table-tested; reverted = rewind/switch off the choice within 5 turns), `RecordingGatewayPolicy` wrapper, `DelegationPrompts` + `chooseForMe(branchNodeId)` beside the pager, `autoMergeCouncil` records, LoopRoom wires the recording policy; descriptive counts card in WP7's panel ("delegated 12 · kept 9 · reverted 3" — no interpretation).
- **WP9 — Graph substrate + observer (inert)**: pure `ThreadGraph`/`ThreadGraphProjector` (plain-snapshot inputs; extraction-ready), `ThreadDeltas.diff` (degree+direction), `ObserverScript` (descriptive tone — Orrery's anti-"overseer" ruling), `ThreadGraphJson` validated against WP0 fixtures; `data/ThreadObserver` reads Room stores (never the event log), gated by `SessionStore.observerEnabled=false` + Settings toggle; remarks presented on request, never pushed.
- **WP10 — Native graph surfaces**: `ThreadMapCanvas` reusing `LoopCanvas` patterns + pan/zoom (`detectTransformGestures`), `ThreadMapLayout` (pure layered layout, chains as columns, JVM-tested), `HyleRadialMenu` node menus, `GraphRoom` + TreeRoom "Graph" chip, observer remark card.
- **WP11 — G6 deep graph room**: `scripts/build-graph-room.js` (mirror `build-color-picker.js` + `build-texture-surface.js` guard idiom; vendored `third_party/g6/g6.min.js` 5.1.1 with MIT header; output committed single-file `core-engine/src/main/assets/graph/graph-room.html` with CSP meta, canvas-renderer pin, `__graphRoom.load(json)` entry); `GraphWebRoom` WebView locked down (`blockNetworkLoads`, no file/content access, always-block navigation, narrow bridge); POWER tier; G6 MIT text hand-appended to core `NOTICE` (checkLicense can't see assets). JVM `GraphRoomAssetTest` asserts CSP/no-external-URL/MIT/renderer-pin; all touch behaviour owner-verified.

Dependencies: WP0→WP1→WP2→{WP3→WP5, WP4, WP6, WP7→WP8→WP9→WP10→WP11}.

## Verification

- Per WP: the standard JVM gate (above) + the WP's named new tests; grep-parity checks for gestures (gesture ↔ tappable control ↔ TalkBack action triple).
- SQLDelight tests run real in-memory FTS5 (`SearchIndexerTest` precedent); WP0/WP9 JSON validates against fixtures.
- Owner-verify list travels in each PR body (gesture feel, ribbon/rail rendering, haptics, radial fan, WebView touch behaviour, compaction sheet).
- APK refresh on `apk-dist` (`fonebrew-sd.apk`, git-lfs) after user-visible WPs; PR #17 branch flow continues.

## Handing to Sonnet ultracode — yes, with this shape

Run **one ultracode session per WP** (WP0+WP1 can share one). Each session gets: this plan, the WP's section, and the binding-constraints block. Native assemble stays out of CI (OOM) — sessions verify via the JVM gate and build the APK locally when a user-visible WP lands. Two owner actions outside the code: record the placement decision in Personal-Tracker, and close the Hyle root-LICENSE gap.