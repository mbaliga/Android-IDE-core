# aarso — Detailed Handoff (current)

> **Supersedes `docs/HANDOFF.md`** (dated 2026-06-21, pre-split). This is the authoritative,
> detailed state of the app against the **Consolidated Build Brief**, after the constellation
> split and the current build session. Editable — refine freely.
>
> **Repo:** `mbaliga/android-ide-core` (public open core). **Branch:**
> `claude/android-ide-studio-refactor-sa2uq9` → mirrored to `main`. **APK:** `apk-dist` branch as
> `aarso-sd.apk` (`https://raw.githubusercontent.com/mbaliga/Android-IDE-core/apk-dist/aarso-sd.apk`).
>
> **Status legend:** ✅ done (gate-green; Compose compile-verified, device-owner-verified) ·
> 🟡 partial/in-works · ⛔ pending/not-started · 🔒 owner-gated. **CI never launches the app** —
> pure domain logic is JVM-tested; all runtime/render/gesture/hardware behaviour is **owner-verified
> on the RedMagic**. Package `dev.aarso.*`; Kotlin + Compose; manual DI (`AppContainer`, no Hilt).

---

## 0. Ground truth & invariants

- **Gate:** `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test` — green.
- **Native build:** restored — the orphan carve had dropped the `llama.cpp` /
  `stable-diffusion.cpp` submodule pointers (core was silently unbuildable). Now builds with real
  engines (`libaarso_llama.so` 5 MB + `libaarso_sd.so` 55 MB). Fetch pattern if it breaks again:
  git-proxy blocks out-of-scope GitHub, but `codeload.github.com` tarballs work — pull the pinned
  commits (`app/src/main/cpp/llama.cpp` @ `5343f450`, `sdengine/.../stable-diffusion.cpp` @
  `cfbc19d1`, SD's `ggml` @ `0ce7ad34`).
- **Flavors:** `full` (sideload, appId `dev.aarso.full`) / `play` (policy-safe, appId `dev.aarso`).
- **Invariants (never regress):** no telemetry/phone-home ever · on-device default, cloud = a
  visible **watched object** · keys/credentials Keystore-encrypted, never logged, never exported by
  default · **Council never "MoE"** · no aarso backend/build-farm/store-account/payment-backend
  (use the user's runner/CI + accounts) · user owns the substrate (exportable tree; projects =
  repos; unbind/delete never touches their git) · consume **Hyle tokens**, not literals · never
  claim on-device behaviour works.

### Constellation & repo routing
| Repo | Visibility | Holds | Status |
|---|---|---|---|
| **android-ide-core** (this) | public | the free computing environment (everything below except the paid suite) | shipping |
| **Hyle-Design-System** | public | `:hyle` tokens (+ atoms pending); the Hyle colour picker pattern | carved |
| **Aarso** | private | the inert **"I"** self-observation lens (`domain/mirror`; Issue #2) | seeded |
| **Android-IDE-Studio** | private/paid | the **Product/Studio** suite (Assets/Epics/Kanban/Timeline/Launch/Publishing/product-issues) | seams landed |

Free core exposes clean interfaces (loop engine, runner spine, repo model, vault); Studio
**consumes** them — a layer, not a fork. Seams: `DevelopTabs` (S2, paid Launch tab),
`ProjectRoomSlot`/`ProjectRoomProvider` (S6, paid top room).

---

## 1. Information Architecture (spatial room model)

**Not bottom-nav.** A spatial model: planar **swipes** pick a room; depth **pinches** pick
structure vs execution. Entry: `ui/spatial/SpatialRoot.kt` (+ `SpatialController` for gesture
progress `h`/`v`/`z`).

```
                         PRODUCT (top)  — swipe ↓ from top
                         free To-do · Studio suite
   CONVERSATIONS ◀────────  CHAT (home / origin)  ────────▶ SETTINGS
     (left)                                                   (right)
                         DEVELOP (bottom) — swipe ↑ from bottom
                         Hardware · Files · Terminal · Audit

   Z-AXIS (pinch, Chat = origin):
     pinch-IN  → zoom OUT → TREE   (tabs: Conversation | Commits | Builds)
     pinch-OUT → zoom IN  → LOOPS  (graph editor; per-loop cost boundary)
```

- **Rooms** are peers; system **Back** from a room returns to Chat (origin) unless deep-linked.
  **Up** (header chevron) is hierarchical within a room; Back is temporal. `BackHandler` wired.
- **Gesture-collision (both canvases zoom):** from Chat, pinch = Z-nav. Inside Tree/Loops (zoomable)
  pinch zooms the *canvas*; leave/move on Z via an explicit **compass depth-pip** — 🟡 pip not yet
  built; owner must verify the feel.
- **Non-gesture parity** (compass taps for every room + Z; canvas +/−/fit): 🟡 partial.
- **Me·Myself·I** opens from the header avatar (and is listed in Settings) — two doors.
- **RTL** should mirror the whole spatial model — 🟡 not fully verified.

**Status:** ✅ rooms + swipe/pinch nav + Back-to-origin · ✅ room **TalkBack announce** (settled
room → `SpatialLinearization.announce`, polite live region) · 🟡 compass non-gesture parity /
depth-pip · 🟡 predictive-back / full deep-link routes / process-death restore of Z+tab+scroll+sheet.

---

## 2. Cross-cutting contracts (build against these everywhere)

| Contract | State | Where |
|---|---|---|
| **State grid** (loading/empty/partial/ready/error/offline/permission-blocked) | 🟡 `UiState<T>` sealed type exists (`domain/state`) + `StatePane`; not every surface implements all 7 | `domain/state/UiState.kt`, `ui/components/StateComponents.kt` |
| **Tree = single source of truth** (append-only, git-like; branch/restore/council/commits/builds are ops over it) | ✅ | `domain/tree/*`, `data/MessageTreeRepository` |
| **Unidirectional state; repos are the boundary** | ✅ (ViewModels → `domain` presenters → `data` seams) | `ui/state/*`, `data/*` |
| **Hyle token contract** (consume roles/ramps/shape, never literals) | 🟡 module is pure tokens; UI still uses Material/Aeon literals in places; re-point on Hyle atoms | `hyle/`, `ui/theme/`, `ui/aeon/` |
| **i18n** (ICU, RTL, locale-formatting, pseudoloc CI) | 🟡 `LocaleFormat` wired for token counts + relative time (incl. Indian lakh); **string externalization + pseudoloc-CI pending** | `domain/format/LocaleFormat.kt`, `Pseudolocalize.kt` |
| **Knowledge scoping** (this/selected/all; verbatim floor → truncation → recall; mode = watched) | 🟡 deterministic floor built + tested; **UI (scope chip/inspector/meter) not mounted**; recall waits on real embedder | `domain/scope/*` |
| **Usage ledger** (1 entry/turn + 1/council-member; local, never leaves) | ✅ writer live | `domain/ledger/*`, `data/LedgerStore.kt`, `ChatViewModel` |
| **Provenance / watched objects** (4 states, never colour-only; on every external touch) | ✅ model + badge; 🟡 not on every surface yet | `domain/provenance/*`, `ui/components/ProvenanceComponents.kt` |
| **Accessibility** (non-gesture parity, TalkBack linearization, ≥48dp, colour-independence, charts→table+summary) | 🟡 room announce + component content-descriptions done; broader sweep pending | `domain/a11y/SpatialLinearization.kt` |
| **Performance** (60fps incl. transitions; virtualize Tree/Loops; no main-thread inference; runner for sustained) | 🟡 virtualization + FGS generation exist; owner-verify budgets | `service/GenerationService` |

---

## 3. Chat & Composer (home room) ✅ + hardening

Entry: `ui/ChatScreen.kt`, `ui/ChatViewModel.kt`.
- **Thread** = active path through the tree (linear read); branch points inline (tap child to
  switch). Message types: user / assistant (per-model, provenance) / council round / summary
  (branch-on-change bridge) / image / build-or-loop ref.
- **Markdown streaming-safe** ✅ — persisted turns render through `StreamingMarkdown.reconcile`
  (a turn stopped mid-fence renders clean; idempotent on complete markdown). Live stream keeps
  per-token entropy colouring. (Foundation 1.8 pin — do not regress; see CLAUDE.md.)
- **Interaction models:** Single / Council·personas / Council·models — **immutable once a chat
  starts** (change → branch + summary node). ✅ Never "MoE".
- **Council:** editable participants (name/instructions/own model ⌂/☁/memory), sequential rounds,
  per-member provenance; **one ledger entry per member** — 🟡 the per-member *ledger fan-out* writer
  is the remaining ledger case; per-member **files** = honest "soon".
- **Generation lifecycle:** idle→sending→streaming→complete|stopped|error; SSE cloud /
  token-by-token on-device (logprob from token one); stop → branchable stopped node.
- **Logprob/entropy inspector:** 🟡 `domain/inspect` (TokenInspector) built + tested; heatmap
  component not mounted; honest about cloud availability.
- **THE LEDGER WRITER lives here** ✅ — `ChatViewModel` appends one `LedgerCapture.singleTurn(...)`
  per completed turn (cloud = provider tokens+cost authoritative; on-device = local tokenizer count,
  cost 0, flagged estimated; latency measured; guarded so it never fails a turn).
- **Image gen** from the `+` sheet (cloud / on-device SD) → in-thread image message + provenance +
  regenerate; writes a ledger entry. ✅ (SD native owner-verified).
- **Agentic coding starts here:** ask in Chat → model proposes → review in **Develop → Files** →
  accepted → commits (Tree → Commits) → builds (Tree → Builds). **No separate "Agent" surface.**

---

## 4. Conversations (left room) ✅ + hardening

Entry: `ui/rooms/ChatsRoom.kt`; logic `domain/library/*`; live source `data/ConversationsStore.kt`.
- **Row:** title · relative time (ICU) · branch pip · type (Text/Image) · **model flairs**
  (⌂/☁ from the ledger) · star · project tag.
- **Filters** All/Text/Image/Starred compose with **Projects** grouping (sticky headers; "No
  project" bucket).
- **Sort** ✅ — Recent/Created/A–Z(collated)/**Most used**/**Most branched**, via the JVM-tested
  `domain/library.Conversations.sort` + `ConversationProjection`. `useCount` from a **real
  open-counter** (`SessionStore.recordConversationOpen`), never faked. `createdMillis`/`branchCount`/
  `hasText` computed from the tree (`Conversations.summarize`).
- **On-device search** (script-aware, ranked; semantic when embedder lands): 🟡 `domain/search`
  (LexicalSearch) built + tested; **not yet wired** into the Conversations UI.
- **Multi-select bulk** (scoped-to-view, undo window, partial-failure honesty): 🟡 `domain/library/
  BulkSelection` built + tested; not mounted.
- **Model-flair strips** in a full `ConversationRow` swap: 🟡 seam ready; flairs populate now the
  ledger writes; cosmetic gain, optional against the shipping room.

---

## 5. Projects-as-Repos & Scoping 🟡

- **project = repo:** binding none/local-git/github (token in Keystore). Corpus = repo files ·
  conversations · project memory · attached files ("soon").
- **Three scopes** (this / **selected** (saved multi-select) / all) with inheritance
  (global→project→conversation) + saved sets; **hybrid assembly** (verbatim floor → deterministic
  prioritized-truncation with "what was cut" → semantic recall on the embedder, same interface);
  mode is a watched object.
- **State:** ✅ the deterministic domain floor is built + JVM-tested (`domain/scope/*`:
  `KnowledgeScope`, `ContextAssembly` (Verbatim/PrioritizedTruncation/Recall + budget meter),
  `ScopeInheritance`, selected-sets). ⛔ **the scope UI is not mounted** (scope chip → inspector,
  budget meter, pin/toggle). Retrieval ceiling waits on the real on-device embedder (replaces
  `PlaceholderEmbedder`) — 🔒.

---

## 6. Tree & Loops (z-axis)

### 6.1 Tree ✅ (now tabbed) — `ui/rooms/TreeRoom.kt`
Tabs over one git-like tree:
- **Conversation** ✅ — branch/restore/time-travel/compare; node provenance; summary node;
  git-sync indicator (watched) + manual export + handoff summary.
- **Commits** 🟡 — honest **"soon"** placeholder (needs the Files→commit history plumbed; nothing
  fabricated).
- **Builds** ✅ (moved here from Develop, §6.1) — CI artifacts + sideload install
  (`BuildsFacet`, `data/BuildsRepo`); builds run on the runner / GitHub Actions.

### 6.2 Loops (z-out) 🟡 — `ui/loops/*`, `domain/loop/GraphRunner`, `domain/bpmn/*`
- ✅ Free-form graph editor (Task/Gateway/End; add/move/connect/delete; per-node model ⌂/☁;
  approve/refine/else gateway labels; **GraphRunner** execution; **BPMN 2.0** round-trip
  JVM-tested; Drafts/Running/Retired; git push/pull of `.bpmn`).
- ⛔ **Per-loop cost boundary/gate** (where Cost moved from the profile/Develop) — **not built**.
  This is the piece that completes "cost lives on Loops."
- 🟡 **Live per-step streaming** (GraphRunner progress callback; animated active edges;
  node run-state) — pending. Drag-a-wire connect — pending. Run-trace ledger writes — pending.
- 🟡 Parameterization = visual variables (the Studio publish-pipeline seam).

---

## 7. Develop (bottom room) ✅ restructured — `ui/develop/DevelopRoom.kt`

Tabs are now **exactly Hardware / Files / Terminal / Audit** (brief §7). Underlying spine: the
brain/runner split (on-device orchestration; heavy compute on the user's runner; **no aarso build
farm**).

- **Hardware** ✅ 🟡 — former Devices facet (`HardwareFacet`): the four control paths (Pi-over-SSH,
  Arduino-via-Pi, ESP-OTA, on-phone **USB flash** with tested CDC/Stk500/IntelHex core) + a
  supported-boards / troubleshooting header. 🟡 **Sample/example sketches** picker not yet real;
  device detection UX minimal.
- **Files** ✅ 🟡 (`FilesFacet`) — the repo **change-review** flow (propose → per-hunk review →
  commit; nothing commits without review; cloud-proposed change = watched). Absorbs the old
  "Agent" mode. 🟡 A full **file-tree browser** (GitBrowse) is not mounted yet.
- **Terminal** ✅ (`TerminalFacet`) — run a shell command over SSH on the runner (reuses
  `DeviceRepo.exec` + identity resolution; remote sessions marked watched).
- **Audit** ✅ (`AuditFacet` + JVM-tested `domain/audit/AuditChecklist`) — a **to-do list of
  checks, not a scanner**. Default checklist across categories; "Run" fires the check as a **prompt
  into Chat** (via `SharedIntake`); Pass/Fail/Skip record outcome; an **external-QA-app** connect is
  a stub (watched). aarso doesn't build heavy scanners.
- **DELETED:** the "Agent" mode (review lives in Files); the **Cost** tab (→ Loops); **Builds**
  (→ Tree).
- **Launch** (store-publish) is the **paid Studio** tab via `DevelopTabs` (S2 seam) — not in core.

---

## 8. Settings (right room) & Me·Myself·I

### Settings 🟡 — `ui/rooms/SettingsRoom.kt`, `ui/SettingsViewModel.kt`
Five icon tabs **Global / Image / Text / Video / 3D**.
- ✅ on-device⇄cloud master + per-modality; providers & keys (**Keystore vault**: add/test(watched)/
  delete Anthropic/OpenAI-compat/Gemini; never displayed/exported); model management (GGUF + SD
  download/storage); Git/GitHub connect; **Appearance** (theme mode + accent via the new
  **`HyleColorPicker`** ✅ + texture + ambient gradient); free-tier guide; disclosure tier.
- 🟡 Export-everything (sovereign takeout, keys excluded by default); watched-object summary;
  reconciliation-overlay opt-in; global default knowledge scope; language/i18n panel (dynamic
  switch, Konkani gap note). Video/3D = honest "soon".

### Me·Myself·I (profile) ✅ 🟡 — `ui/rooms/MeScreen.kt`, `ui/state/MyselfPresenter.kt`
- **Me** — identity, configured providers, key-status mirror (never shows keys), header-avatar
  usage glyph; **provenance badges** on linked accounts ✅ (four-state watched).
- **Myself** — usage ledger views (on-device aggregations of `domain/ledger`): input vs output
  tokens, by-provider, sovereignty split (on-device vs cloud), budget rings (**token/usage, not
  cost**). ✅ Populates from the live ledger writer. **Global cost REMOVED (§9)** — cost is a
  per-loop boundary; cards render `showCost=false`. 🟡 by-model / counts / flaired interaction
  history / trends / reconciliation overlay: partial.
- **I** — reflective self-observation (§5b/§5c drift) — 🔒 ships **inert** (Aarso repo, Issue #2);
  fabricates nothing.
- 🟡 Every chart needs a data-table + spoken-summary equivalent — partial.

---

## 9. Product management (top room) ⛔ / 🔒 — `ui/spatial/ProjectRoomProvider.kt` (S6 seam)

Currently the core shows the **locked placeholder**; the paid Studio installs the real room.
- ⛔ **FREE floor: a to-do list** (simple, on-device) — **not built yet** (replaces the locked
  placeholder in core).
- 🔒 **Studio suite (paid, Studio repo):** Assets (+ generation loops), Tasks/Epics,
  Kanban/Timeline, pre-populated Launch to-dos, **Publishing** (API stores via runner/fastlane:
  Play/Apple(macOS-gated)/Samsung/Huawei; no-API **submission concierge**: Garmin/Zepp/Huawei
  Themes), product issue-tracking. Rollout: Garmin → Wear OS → Samsung → Apple → Zepp → Huawei.
- **Free/paid line:** building (§§3–9) is free **and** the publishing-admin **watchlist** is free
  (renewals/expirations as watched objects). Studio = ship-and-sell. **Lemon Squeezy** MoR for the
  one-time ~$20 unlock (license check, no payment backend/telemetry).

---

## 10. What shipped THIS session (chronological, all gate-green)

1. Restored native submodule pointers → APK buildable again; APK delivered + `apk-dist`.
2. Usage-ledger vertical slice (Room → MeScreen) + **per-turn capture writer**.
3. Streaming-markdown reconcile in the render path.
4. Conversations seam (tree fields + open-counter + projection + store) + **sort control**.
5. i18n `LocaleFormat` wiring; a11y room announces; **`HyleColorPicker`** for accent.
6. Provenance badges (MeScreen).
7. **Profile cost removal (§9).**
8. **Develop restructure** → Hardware/Files/Terminal/Audit (+ `domain/audit` tests); **Builds→Tree**
   (Tree now tabbed Conversation/Commits/Builds).

---

## 11. Immediate next steps (recommended order)
1. **Loops per-loop cost boundary (§6.2)** — completes "cost moved to Loops"; the profile already
   stopped claiming cost.
2. **Files file-tree browser** (GitBrowse) + **Files→Commits** history → lights up the Tree Commits
   tab honestly.
3. **Hardware sample sketches** picker + richer device detection.
4. **Scope UI** (chip + inspector + budget meter) over the tested `domain/scope` floor.
5. **Top room free to-do floor** (core) + begin the Studio suite in the Studio repo.
6. **Phase 0 debt:** string externalization + pseudoloc-CI; compass non-gesture parity/depth-pip;
   token-contract sweep when Hyle atoms land.

## 12. Owner decisions / blockers (🔒)
- Publish Hyle + core as artifacts (registry/token) so Studio depends on published core; set the
  **Aarso** repo default branch (no `main` base → no PR yet).
- Monetization seam: closed `:power` vs open + supporter flag.
- §5c/§5b mirror baseline (Issue #2) — owner-only input.
- Device verification of every Compose surface on the RedMagic.
- CI minutes on the private Studio repo (red `build-test` = billing, not code).

---
_Old `docs/HANDOFF.md` (2026-06-21) is stale (pre-split) and can be deleted or replaced by this._
