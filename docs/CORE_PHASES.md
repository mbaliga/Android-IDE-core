# CORE PHASES — free-tier work items (public-safe excerpt of the Studio Suite brief v1.4)

**Scope:** this file drives Claude Code sessions running on **android-ide-core only**. It contains the free-tier phases (P0-core, P1, P2, P3) verbatim in intent, with the paid layer's internals omitted — core is a public repo. Where this doc says "the Studio layer," that means whatever an above-core layer installs into the existing seams (`ProjectRoomSlot` S6, `DevelopTabs` S2); its contents are not core's concern. If a session needs paid-surface detail, stop — that work belongs in the private repo.

**Gate (every phase):** `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test` green, plus the phase's own new JVM tests. If the Android SDK is missing in the environment, run `scripts/setup-android-sdk.sh` first.

**Status vocabulary (mandatory in commits/docs):** `code-complete` = written + compiles · `verified-JVM` = covered by the gate's tests · `owner-verify` = requires the owner's device (render, gesture, network); **never claim these work**. CI never launches the app.

---

## Invariants (do not change)

1. **Room grammar is locked.** Chat is home/origin; planar swipes pick rooms (Conversations left, Settings right, Develop bottom, Product top); Z from Chat via pinch (in → Tree, out → Loops); compass depth-pip inside zoomable canvases; Back → Chat origin; Up hierarchical; predictive back; process-death restore. This work adds content **inside** the Product room and the Loops run surface — no new rooms, gestures, or Z levels.
2. **Sovereignty.** No backend, no telemetry, no automatic network. The only network this work may touch is user-initiated and already-modeled (e.g., existing GitHost paths).
3. **Colorblind-safe (hard constraint).** Never encode state in red/green anywhere. State = shape/icon + label + luminance; violet `#8E7BFF` / cyan `#08FED5` are the only hue axis. Every glyph has a text sibling (TalkBack + redundancy), matching the existing `ProvenanceBadge` house rule.
4. **Hyle + type.** Vendored `:hyle` tokens as-is (do not switch to the published artifact). AMOLED black ground, ~300ms cubic-bezier motion. Plus Jakarta Sans.
5. **Fenced-off code.** `domain/council/CostEstimator.kt` is Council-escalation-scoped; do not refactor, generalize, or reuse it for Loops budgets. The message tree is append-only; `GraphRunLog.toNodes` semantics don't change.
6. **License policy.** Linked/vendored dependencies: Apache-2.0 / MIT / BSD / ISC only (MPL-2.0 case-by-case, flagged). All copyleft incl. LGPL banned for linking. Every borrow: version-pinned, LICENSE → `NOTICE`, license-report CI gate must pass. Runner-invoked tools may carry any license.
7. **Brand string is "FoneBru"** (owner ruling 2026-07-11). Package rename stays deferred (Sprint R) — do not touch Gradle identifiers.

---

## Data models (locked — do not redesign)

### Task — `AppDatabase` (free)
```kotlin
@Entity data class Task(
  @PrimaryKey val id: String,
  val projectId: String? = null,
  val title: String,
  val notes: String = "",
  val state: TaskState = TaskState.TODO,     // TODO / DOING / BLOCKED / DONE
  val orderKey: Double,                      // manual ordering (fractional insert)
  val dueAt: Long? = null,
  val startAt: Long? = null,                 // reserved for an above-core layer; dormant in free
  val endAt: Long? = null,
  val dependsOn: List<String> = emptyList(), // reserved; dormant in free
  val tags: List<String> = emptyList(),
  val source: TaskSource = TaskSource.MANUAL, // MANUAL / AUDIT / INCIDENT / TEMPLATE
  val sourceRef: String? = null,
  val createdAt: Long, val updatedAt: Long, val doneAt: Long? = null,
)
```
Free UI reads/writes only: `title, notes, state(TODO↔DONE), orderKey, dueAt`. `TaskStore` (DAO + repository) in core; JVM tests: CRUD, ordering, migration.

### WatchedItem — `AppDatabase` (free)
```kotlin
@Entity data class WatchedItem(
  @PrimaryKey val id: String,
  val label: String,
  val kind: WatchKind,            // RENEWAL / EXPIRY / STATUS
  val dueAt: Long? = null,
  val note: String = "",
  val amountText: String? = null, // freeform, user-editable, never asserted as current fact
  val snoozedUntil: Long? = null,
  val createdAt: Long, val updatedAt: Long,
)
```
Empty state offers seed templates (Play one-time fee, Apple yearly, Garmin merchant, upload-key/cert expiry, EEA commercial-seller state) — inserted only on tap, all fields editable, amounts prefilled with a "check current figure" hint. **Never assert fee amounts as facts.**

---

## Engine extension: parameterized loops + per-loop cost boundary + live progress

Additive, non-breaking. Do not touch `CostEstimator.kt`.

```kotlin
data class LoopBudget(
  val maxTokensTotal: Long? = null,
  val maxSteps: Int? = null,      // distinct from hardCap: budget = user intent, hardCap = engine safety
  val maxWallMs: Long? = null,
)
// GraphStep gains:  tokensIn: Long?, tokensOut: Long?, durationMs: Long, estimated: Boolean
// GraphRunResult gains: totalTokensIn/Out: Long?, elapsedMs: Long
//   stoppedBecause adds "budget:tokens" | "budget:steps" | "budget:wall" | "cancelled"
class GraphRunner(...) {
  suspend fun run(
    graph: BpmnGraph, objective: String, hardCap: Int = 24,
    params: Map<String, String> = emptyMap(),
    budget: LoopBudget? = null,
    onStep: (suspend (GraphStep) -> Unit)? = null,
  ): GraphRunResult
}
```

Semantics: `${key}` placeholders substituted in objective + node system prompts; unresolved placeholders → run **refuses to start** with a legible list (never silent empty substitution); param specs derived by scanning BPMN for `${...}` at open; string-typed v1. Budget checked after every step; the step that would exceed is not started; token counting mirrors ledger semantics (cloud provider-authoritative; on-device counted locally, `estimated=true`, surfaced as such). `onStep` fires per completed step; cancellation = structured coroutine cancellation (verify generators cooperate); partial/cancelled runs still tree-log via `GraphRunLog` with stop reason. Each step writes a `LedgerCapture` row (surface="loop", loopId, runId).

Loops-room UI (free): Run sheet = auto-generated params form + optional budget fields (tokens/steps/wall) + Run; refuse-to-start lists missing params inline. Live run view = streaming step list (role, model, tokens with "est." marker, duration), running totals vs. budget as a **luminance-filling violet bar with a cyan cap tick** (never a red ramp), Stop control. After: summary row (stop reason as icon+label) + link to the tree-logged sub-tree.

---

## Free Product-room surfaces

### To-do (free floor)
Single scrolling list replacing the current `ProjectRoomLocked` screen. Composer row pinned top (text + Add; enter adds). Row: circle checkbox (outline → filled violet + strike, ~300ms), title, overflow (notes, due, delete). Long-press drag reorder (fractional `orderKey`); done items sink to a collapsed "Done" section. Due renders relative ("in 3d"/"overdue"; overdue = high-luminance violet + filled-alert glyph + label — never red). Swipe → done with undo. No projects, tags, or filters in free — flatness is the feature. `ProductRoomFree(extraTabs: List<Pair<String, @Composable () -> Unit>> = emptyList())` so an above-core layer can append tabs.

### Watch
WatchedItem rows: kind glyph (RENEWAL ↻ / EXPIRY ⌛ / STATUS ◉), label, days-remaining chip (luminance scales toward due; overdue = filled glyph + "overdue" label), amountText as quiet secondary, snooze/edit in overflow. Sorted by dueAt, nulls last. Empty state = tappable seed ghost rows. Header microcopy: "Legibility, not automation — nothing here acts on your accounts."

### Observable seams (2-line change)
Back `ProjectRoomSlot.content` and `DevelopTabs.provider` with `mutableStateOf` (setters stay install-only) so a mid-session install recomposes; JVM test asserts install triggers snapshot invalidation.

---

## Phases (core repo)

**P0-core (docs + config only).** Append a pointer to the Studio Suite brief in `docs/HANDOFF-CURRENT.md`; note beside it that `docs/STATE.md` is stale. Commit this file as `docs/CORE_PHASES.md`. Install a dependency-license-report Gradle plugin + allowlist CI gate; scaffold `NOTICE`.
DoD: gate green on current tree; license report runs; no source semantics changed.

**P1 — Task substrate + free floor shell + observable slots.** Task entity/DAO/store + migration; `ProductRoomFree` with [To-do] tab per above (Watch arrives P2); observable seams change.
DoD: store CRUD/order/migration tests; bare core renders To-do, not the lock text [code-complete + verified-JVM]; reorder/undo/done-section gestures [owner-verify]; slot-invalidation test passes.

**P2 — Watchlist.** WatchedItem + store + migration; Watch tab per above incl. seeds.
DoD: store tests; seeds insert-on-tap only; due/overdue math tested with fixed clock; render [owner-verify].

**P3 — Engine extension + Loops run UI.** Exactly the spec above.
DoD (each as a named JVM test): param substitution incl. refuse-to-start; budget stop on each axis with correct `stoppedBecause` and no over-run step; `onStep` ordering + cancellation; totals math incl. `estimated` propagation; ledger rows per step; `GraphRunLog` for partial/cancelled runs; existing `GraphRunner` callers compile unchanged. UI stream/cancel feel [owner-verify].

**Every phase ends:** gate green → push branch → PR titled `P<N>: <summary>` with an honest landed/verified-JVM/owner-verify paragraph → **stop**. Do not begin the next phase.
