# Phonebrew core — roadmap foundations (Phase 0)

> What was built into the open core against the 9-document design set (Docs 00–08), this
> stage. Everything here is **pure-Kotlin `domain/` logic with JVM unit tests** — the
> machine-verifiable layer. **UI wiring (ViewModels + Compose) is the next phase** and is
> *not* done here: these are the primitives the surfaces will consume, not the surfaces.
> Honest by the binding rule — runtime/Compose behaviour is owner-verified on device.

## Status legend
- **logic ✅ / tests ✅** — built, JVM-tested, gate-green.
- **wiring ⏳** — not yet consumed by a ViewModel/Compose surface.

## New packages

| Package | Doc | What it provides | State |
|---|---|---|---|
| `domain/format` (`LocaleFormat`) | 00 §3.3 | Locale-aware formatting — tokens/count/percent/decimal/currency/duration/bytes/date + relative time. **Supplies Indian (lakh) grouping deterministically** because this JDK's CLDR won't (the doc's robustness canary). | logic ✅ tests ✅ · wiring ⏳ |
| `domain/format` (`Pseudolocalize`) | 00 §3.3 | Pseudolocalization for CI: accent + 1.4× expand + forced-RTL, **format placeholders preserved**. Catches truncation before translations exist. | logic ✅ tests ✅ · wiring ⏳ (wire into a CI string sweep) |
| `domain/scope` | 03 | Knowledge-scoping deterministic floor: `this/selected/all`, verbatim↔prioritized-truncation assembly, the budget meter, "what's included / what was cut" (pins survive). + the inheritance chain (global→project→conversation, with the deciding `ScopeSource` legible) and saved selected-sets. | logic ✅ tests ✅ · wiring ⏳ |
| `domain/search` | 02 §5 | Sovereign on-device lexical search: script-aware (Devanagari/Japanese/RTL) normalization, legible recency-weighted ranking. | logic ✅ tests ✅ · wiring ⏳ |
| `domain/provenance` | 00 §3.6 / 01 §6.5 | Four-state watched-object model (icon+label, never colour-alone) + the legible routing decision `{model, tier, why, cost, provenance}` with override + a heuristic baseline router (not the closed engine). | logic ✅ tests ✅ · wiring ⏳ |
| `domain/ledger` | 01 §10 / 07 | `LedgerEntry` + on-device aggregations: by provider/model/provenance, time buckets, **sovereignty ratio**, budget rings, reconciliation Δ, estimated-flag honesty. | logic ✅ tests ✅ · **wired ✅** (Room `LedgerStore` → `MyselfPresenter` → `MeScreen` cards; see "Wired vertical slices" below) |
| `domain/library` | 02 | Conversations-room logic: filter (All/Text/Image/Starred, contains-semantics), sort (recency/created/title-collated/most-used/most-branched), group-by-project, model-flair derivation from the ledger. | logic ✅ tests ✅ · wiring ⏳ |
| `domain/markdown` | 01 §2.3 | Streaming-safe markdown reconciliation: closes an open code fence, trims a half-written table row, so a strict renderer doesn't thrash mid-stream. Idempotent on complete markdown. | logic ✅ tests ✅ · **wired ✅** (`ChatScreen.MessageBubble` renders persisted turns through `reconcile`; a turn stopped mid-fence renders clean) |
| `domain/state` | 00 §3.8 | The enterprise state-matrix contract: `sealed UiState<T>` (Loading/Empty/Partial/Ready/Error/Offline/PermissionBlocked) + map/fold/fromResult. The shape every surface's ViewModel should expose. | logic ✅ tests ✅ · wiring ⏳ |
| `domain/bridge` | 01 §4.3 | Branch-on-change summary bridge: the "Switched {A}→{B}" node + honest carry-forward selection (anchors the original objective, recency-fills, token-capped) for a downstream summarizer; threads author provenance. | logic ✅ tests ✅ · wiring ⏳ |

**Gate:** `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test` — green;
~390 new JVM tests added on top of the 858 carried in (≈1250 total). Public CI (free Actions)
runs this gate on every push.

## Additional domain packages (later batches)

| Package | Doc | What | State |
|---|---|---|---|
| `domain/library/BulkSelection` | 02 §8 | Multi-select + bulk actions; select-all scoped to view; partial-failure honesty; destructive undo window. | logic ✅ tests ✅ · wiring ⏳ |
| `domain/inspect` | 01 §7 | Logprob/entropy inspector: entropy→heatmap (discrete bucket, non-colour-alone), highest-uncertainty, availability-honest summary. | logic ✅ tests ✅ · wiring (TokenHeatmap) ⏳ |
| `domain/a11y` | 00 §3.5 | Spatial→linear TalkBack reading order (room announce → header → nav → content), compass adjacency + Back-to-origin, RTL mirror. | logic ✅ tests ✅ · wiring ⏳ |

## UI component layer (`ui/components/`) — Compose, **compile-verified only**

These consume the domain primitives. CI never launches the app, so they are compiled but
**not runtime-verified** — owner verifies render/behaviour on device.

| Component file | Consumes | Renders |
|---|---|---|
| `ProvenanceComponents` | `domain/provenance` | `ProvenanceBadge` (glyph+label, never colour-alone, TalkBack) · `RoutingDecisionStrip` (the legible `{model,tier,why,cost,provenance}` line + override) |
| `StateComponents` | `domain/state`, `domain/scope` | `StatePane<T>` (all 7 `UiState` cases) · `BudgetMeter` |
| `ConversationRow` | `domain/library`, `domain/format` | Dense conversation row: title, relative time, branch pip, type, model flairs (+k more), star toggle |
| `ScopeComponents` | `domain/scope` | `ScopeChip` (scope · mode pill) · `ScopeInspector` (the attributed included/cut ledger — "what does the model know now?") |
| `LedgerComponents` | `domain/ledger`, `domain/format` | The Doc 07 "Myself" cards: input/output, sovereignty split, by-provider, budget ring (text-forward + TalkBack) |

## Wired vertical slices (done — but Compose is compile-verified only)
Each is a complete domain → data → presenter/VM → Compose slice, gate-green. CI never launches
the app, so render/behaviour is **owner-verified on device**; the domain + mapper layers are
JVM-tested.

1. **Usage ledger → Myself.** `LedgerEntryEntity`/`LedgerDao` (Room, append-only) →
   `LedgerStore` (implements the `LedgerSource` seam) → `AppContainer.ledgerStore` →
   `MeScreen` collects the flow and renders `MyselfPresenter` → `StatePane` → input/output,
   sovereignty, by-provider and budget-ring cards. Shows the honest **Empty** state until the
   per-turn capture writer lands (that writer touches the generation path — owner-scope).
2. **Streaming markdown → render path.** `ChatScreen.MessageBubble` runs persisted model turns
   through `StreamingMarkdown.reconcile` before the markdown renderer, so a turn stopped
   mid-fence (a dangling ```` ``` ````) renders clean instead of swallowing the bubble.
   Idempotent on well-formed markdown — a complete turn passes through unchanged. The live
   stream is untouched (per-token entropy colouring, not markdown).

### Conversations seam — data-complete, screen swap is the owner-verified step
The data-source seam (`ConversationsSource`), the JVM-tested `ConversationsPresenter` /
`ConversationsViewModel`, and the `ConversationRow` component all exist — and the seam is now
**wired to a live on-device source** with no fabricated metrics:
- `Conversations.summarize` computes `createdMillis` (earliest turn), `branchCount` (leaf-tip
  count), and `hasText` (any non-image turn) straight from the tree (additive — the shipping
  `ChatsRoom` is unaffected).
- `SessionStore.recordConversationOpen` + `conversationOpens` give `useCount` an honest source
  (the metric that *was* missing); `ChatViewModel.openConversation` records each open.
- `ConversationProjection` (pure, JVM-tested) maps a tree summary + session facts into the row
  model; `ConversationsStore` implements the seam (tree + session + ledger → rows, ledger
  grouped by `chatId`) and is wired into `AppContainer.conversationsSource`.

What's left is the **screen swap** — mounting a `ConversationsViewModel` over `conversationsSource`
in place of `ChatsRoom`'s ad-hoc filtering, which adds the sort options (RECENT / CREATED / TITLE
/ MOST_USED / MOST_BRANCHED) and ledger-derived flairs. That changes a shipping surface, so it's
the **owner-verified-on-device** step. The model-flair strip stays empty until the per-turn ledger
capture writer lands (same honest Empty as the Myself ledger).

## Next phase (not done here)
1. **Wire the remaining primitives into their surfaces** — ViewModels exposing `UiState<…>`,
   Compose components consuming `domain/provenance` (the four-state indicator), `domain/scope`
   (the budget meter + scope inspector), `LocaleFormat` (every number/date/token in the UI),
   `domain/library` (the Conversations list — see the honest blocker above). Ledger and the
   streaming markdown path are **done** (see "Wired vertical slices"). Compose is
   **compile-verified only** here (CI never launches the app) — owner-verified on device.
2. **Pseudolocalization in CI** — add a string-sweep step asserting `isPlaceholderSafe` and
   no truncation across the priority locales.
3. **Retrieval ceiling** for scoping — lights up when the real on-device embedder replaces
   `PlaceholderEmbedder` (owner-blocked); the scope surfaces don't change (same interface).
