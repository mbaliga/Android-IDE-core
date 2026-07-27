# Daily-driver plan — parity + call mode

Status: **execution spec** (authored 2026-07-27, grounded in a full recon of this repo and
`mbaliga/android-ide-studio`). This is the handoff document for the implementing session:
every workstream cites the real integration points by file:line as they exist on
`claude/fonebrew-branding-launch-wjweq3` today.

**Goal.** Close the gap that keeps the owner on the Claude app: vision input, web search,
artifacts with Time-Machine version history, projects, outbox/drafts reliability, and
user-choice sync — all in this repo — plus a phone-call-metaphor conversational interface
whose *engine* lands here and whose *surface* lands in `android-ide-studio`. Each workstream
carries a best-in-class bar and an owner device-test step.

---

## 0. Ground rules for the executing session

1. **Gate green after every workstream**: `./gradlew :app:testFullDebugUnitTest
   :app:testPlayDebugUnitTest`. One commit per workstream, small and legible (CLAUDE.md rule 6).
2. **Hotspot — serialize, don't parallelize**: `ui/ChatViewModel.kt` and `ui/ChatScreen.kt` are
   touched by W0, W1, W2, W3 and W5. Exactly one agent edits those two files at a time;
   W4/W6/W7 can run in parallel with anything.
3. **Binding rules apply to every line** (CLAUDE.md): no telemetry; cloud opt-in + watched;
   provider-generic (never vendor-special-cased); keys via `security/KeystoreSecret.kt`; never
   claim on-device behaviour works — this container has no device.
4. **Append-only tree is inviolable.** `MessageNodeDao.insert` is `OnConflictStrategy.ABORT` on
   purpose; there is no `@Update` anywhere and none may be added. Every design below works
   within that (metadata keys, side files, derived state) — no node mutation, no schema bump.
5. **No Room migration in this pass.** `AppContainer.kt:43` uses
   `fallbackToDestructiveMigration()` and zero `Migration` objects exist — a version bump wipes
   the user's whole tree. All new persistence below deliberately uses metadata keys,
   SharedPreferences stores (`SessionStore`/`GitHostStore` pattern) or plain files
   (`KvCacheStore` pattern). Fixing the migration story is a separate, owner-visible task.
6. **Cross-repo rule (W6)**: shared-file changes land in *this* repo; `android-ide-studio` gets
   only `studio/`-namespaced unique files + a seam install (its own `STUDIO_SUITE_BRIEF.md:62-65`
   rules). Never edit a shared file inside Studio.
7. **API shapes in this doc are current** (verified against the Claude API reference,
   2026-06 cache): Anthropic vision content blocks, server-side `web_search_20260209` tool,
   Gemini `google_search` grounding. Do not "correct" them from memory.

---

## W0 — Foundations: capability flags, output caps, SSE hardening

*Everything later leans on this. Small, surgical.*

**Capability flags (provider-generic).**
- `domain/cloud/CloudProvider.kt:27-36` — `ProviderKind` already carries `supportsSampling`;
  follow that exact precedent. Add `ProviderKind.supportsSearch: Boolean`
  (ANTHROPIC=true, GEMINI=true, OPENAI_COMPAT=false — server-side search exists only there).
- `CloudProvider.kt:18-25` — add `supportsVision: Boolean = true` **per configured instance**
  (model ids are user-typed free text at `SettingsRoom.kt:1543-1548`, so vision can't be
  inferred; a checkbox "Model understands images" in `ProviderForm`, default on for cloud).
- `inference/ModelRegistry.kt:93-103` — thread both flags through `CloudProvider.toSpec()`
  onto new `ModelSpec.supportsVision` / `ModelSpec.supportsSearch` (`domain/model/ModelSpec.kt:17`).
  Local GGUF specs: `supportsVision=false` (mmproj is out of scope — see §Not-in-this-pass).

**Output cap fix (a live bug parity depends on).** `SamplingParams.maxTokens` defaults to 1024
and is never overridden (`domain/SamplingParams.kt:17`; call sites `ChatViewModel.kt` ~410, 641,
821, 956, 1006) — every reply truncates at 1024 tokens today. Raise the default to 8192, and fix
the two engines that silently ignore it: `OpenAiCompatEngine` must send `max_tokens`,
`GeminiEngine` must send `generationConfig.maxOutputTokens` (only `AnthropicEngine.kt:31` wires
it today).

**SSE hardening.** `CloudEngine.kt:70-105` swallows every parse failure via
`runCatching{}.getOrNull()` with no logging — add a `Log.w` on parse exceptions (debug builds)
so provider drift stops degrading silently. Keep unknown event/block tolerance exactly as is.

**Tests.** Golden-JSON request-builder tests per engine (new
`app/src/test/java/dev/aarso/inference/cloud/`), following `domain/cost/ProviderUsageTest.kt`
style: assert `max_tokens`/`maxOutputTokens` present, assert flags flow `CloudProvider→ModelSpec`.

---

## W1 — Vision input (photo → model)

**Bar (Claude app):** snap or pick a photo, ask about it, thumbnails visible in the turn,
works on every vision-capable cloud provider; a blind model says so instead of silently
dropping the image.

**Data model — metadata, not a table.** Follow the existing generated-image precedent
(`Conversations.IMAGE_KEY`, `ChatViewModel.kt:898-905`): user-node attachments are stored as
`metadata["attachments"] = JSON array of {path, mime}`. New `data/AttachmentStore.kt` copies
`ImageStore.kt`'s shape exactly (`filesDir/attachments/<uuid>.<ext>`). Nodes are never deleted
(append-only), so orphan-file cleanup is a non-problem, same as `ImageStore`. Add
`Conversations.ATTACHMENTS_KEY` next to `IMAGE_KEY` (`domain/tree/Conversations.kt:57`).

**Capture pipeline.** Before save: downscale longest edge to **2048 px**, re-encode JPEG q85
(constant `ATTACHMENT_MAX_EDGE` — bounds token cost across providers; Anthropic's high-res tier
takes 2576 but at ~3× image tokens, owner can raise the constant later).

**Composer.** `ChatScreen.kt:994-1013` `PlusSheet` — the Photo/File rows at :1004-1005 are
already stubbed disabled ("Soon"). Wire:
- *Photo* → `ActivityResultContracts.PickVisualMedia` (no permission needed, Play-safe).
- *Camera* → `ActivityResultContracts.TakePicture` + a `FileProvider` (new manifest `<provider>`
  + `res/xml/file_paths.xml` — ~10 lines; full-resolution capture, don't use `TakePicturePreview`).
- A pending-attachment strip above the input field (thumbnails + ✕ remove) held in
  ChatViewModel state; cleared on send. Rows enabled only when the active spec's
  `supportsVision` is true; otherwise visible-but-disabled with the reason (legibility).

**Send path.** `ChatViewModel.send()` (`:519-546`): if pending attachments exist, copy them via
`AttachmentStore`, put `ATTACHMENTS_KEY` on the user node (`Nodes.child(..., metadata=...)`),
then normal `runTurn`.

**Engine serialization** — inside each `buildRequest`, when any path message carries
`ATTACHMENTS_KEY` **and** the spec supports vision, that message's `content` becomes a block
array (else keep today's plain string — byte-identical requests, cache-friendly):
- `AnthropicEngine.kt:21-43`:
  `[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":<b64>}}, {"type":"text","text":<content>}]`
  — image blocks **before** the text block.
- `OpenAiCompatEngine.kt:20-39`:
  `[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,<b64>"}}, {"type":"text","text":...}]`.
- `GeminiEngine.kt:22-59`: parts `[{"inline_data":{"mime_type":"image/jpeg","data":<b64>}}, {"text":...}]`.
If the active model is vision-blind but attachments exist, do **not** silently strip: refuse the
send with a visible error ("<model> can't see images — switch model or remove the photo").

**Render.** `ChatScreen.kt:1310-1348` `MessageTurn` reads metadata; add an attachments branch in
`MessageBubble` (`:1428-1431` is the existing image branch to sit beside) rendering a thumbnail
row via the existing `FileImage`. Council cards (`CouncilCardView`, separate render path —
recon gotcha) are excluded in v1.

**Tests.** Pure: attachment JSON round-trip; per-engine golden request JSON with one image +
text (assert block order, media_type, data-URI shape); blind-model refusal logic.

---

## W2 — Web search (server-side, provider-generic)

**Bar (Claude app / Gemini grounding):** per-chat opt-in, model searches when it needs to,
sources visible with tap-out links, never a hidden fallback.

**Mechanism.** Server-side search tools only — **no client tool-loop needed**:
- Anthropic (`AnthropicEngine.buildRequest`): when search is on, add
  `"tools":[{"type":"web_search_20260209","name":"web_search","max_uses":3}]`.
  Keep the tool type as a single constant `ANTHROPIC_WEB_SEARCH_TOOL`; older user-typed model
  ids may 400 on it (`web_search_20250305` is the legacy fallback constant, documented beside
  it). A 400 surfaces through the existing error banner — visible, not swallowed.
- Gemini (`GeminiEngine.buildRequest`): `"tools":[{"google_search":{}}]`.
- OpenAI-compat: no standard server search → the toggle is disabled for those providers with
  the reason shown (`ProviderKind.supportsSearch=false` from W0). No app-side scraping in this
  pass (see §Not-in-this-pass: `SearchProvider` seam for local models).

**Toggle.** A globe chip beside/inside the composer `+` area; state in `ChatUiState.transient`,
default off (cloud extras are opt-in per use — binding rule 2). Enabled only when the active
spec's `supportsSearch` is true.

**Capturing sources.** `CloudEngine` currently ignores `content_block_start` entirely
(recon-confirmed). Mirror the existing `UsageAccumulator`/`lastUsage` precedent: engines
accumulate a `sources: List<Source(title,url)>` — `AnthropicEngine` parses
`content_block_start` events whose `content_block.type == "web_search_tool_result"`
(items carry `url`/`title`); Gemini parses `groundingMetadata` from the final chunk. After the
collect loop, `runTurn` reads `engine.lastSources` and writes
`metadata["sources"] = JSON` + `metadata["webSearch"]="true"` on the assistant node.
If a `message_delta` carries `stop_reason:"pause_turn"` (server search loop hit its cap), set
`metadata["searchPaused"]="true"` and render a "search paused — send 'continue'" note;
full auto-resume is out of scope v1.

**Render.** Sources footer in `MessageBubble` at the existing footer slot
(`ChatScreen.kt:1441-1453` — divider + labelSmall rows pattern); each row opens the URL via
`Intent.ACTION_VIEW`. Add a search glyph to `headerLabel` (`:1406-1411`) beside the existing
☁ — text glyph, never color-alone (`ProvenanceComponents` rule). This is a **watched object**
event: the model reached the web; the footer *is* the legibility surface.

**Tests.** Pure parsers: Anthropic `web_search_tool_result` block → sources list; Gemini
groundingMetadata → sources list; request-builder golden JSON with tools present/absent by flag.

---

## W3 — Reliability: drafts, outbox, crash-safe partial replies

**Bar (Gmail outbox / WhatsApp ticks):** nothing typed or sent is ever lost — across process
death, crash, or app switch; pending/failed states visible; one-tap retry.

**Drafts.** Composer text is a bare `remember{}` in `ChatScreen.kt:130` — wiped on process
death *and* it silently follows you across conversations (recon gotcha). Fix both: move `input`
into `ChatViewModel` as a `StateFlow`, keyed by **root id** (`Conversations.rootOf`; leaf ids
churn on branch — recon warning), persisted debounced (~400 ms) into a new `SessionStore` map
`drafts: Map<rootId,String>` (JSON in prefs, same shape as `conversationProjects` at
`SessionStore.kt:97-100`); key `"new"` for the unstarted conversation. Restore on
`openConversation`; clear on successful send.

**Crash-safe partials.** The assistant node is inserted only at end-of-stream
(`ChatViewModel.kt:675-684`); a hard crash mid-stream loses the whole reply (graceful Stop
already persists — `:651-655`). No node mutation is allowed, so checkpoint to a **file**
(`KvCacheStore` path-only precedent): during the token collect at `:639-647`, every ~2 s write
`filesDir/outbox/<userNodeId>.partial.json` `{userNodeId, modelId, text, updatedAt}`. On
ViewModel init, scan that dir: for each orphan whose userNode exists and has no assistant
child, insert the assistant node with `metadata["partial"]="true"` (+ existing "stopped"
styling) and delete the file. Delete on normal completion too.

**Outbox — derived, not stored.** The tree already encodes it: a USER node with no assistant
child is an unanswered send. Surface it — in the thread, an unanswered user turn older than the
in-flight generation gets a "not answered — Retry" chip wired to the existing `regenerate()`
path (`canRegenerate` precedent). No new storage, no node mutation, works after crash by
construction.

**Tests.** Draft store round-trip + keying; partial-checkpoint recovery (pure function: given
tree + orphan file set → recovery actions); unanswered-turn derivation over `MessageTree`
fixtures (`ChatThreadPresenterTest.kt` style).

---

## W4 — Artifacts + Time-Machine version history

**Bar (Claude Artifacts + macOS Time Machine):** substantial code/document blocks become
openable artifacts; full-screen viewer; scrub versions along the conversation; visual diff
between any two; save/share. (The owner's explicit ask: version history of the artifact while
previewing, "like Time Machine".)

**Detection — new pure domain, `domain/artifact/`.** `StreamingMarkdown`'s fence scanner is
private and only *closes* fences (recon) — write a fresh extractor:
- `ArtifactBlocks.parse(markdown): List<Block(lang, title?, body)>` — fenced blocks ≥ 6 lines.
  Identity = explicit filename when present (info-string `title=`, or first-line
  `// file: X` / `# file: X` comment), else `"<lang> #<ordinal>"`.
- `ArtifactHistory.versions(tree, leafId, identity): List<Version(nodeId, body, createdAt)>` —
  walk `repository.path(leafId)` collecting assistant-node blocks with matching identity,
  oldest→newest. Active path only in v1 (cross-branch scrubbing deferred).

**Viewer.** Full-screen `Dialog(usePlatformDefaultWidth=false)` + `Surface(fillMaxSize)` —
the exact `SettingsRoom.kt:1034` GitBrowser precedent. Opened from a per-block chip rendered
under assistant turns (new arm beside `ChatScreen.kt:1439`'s `Markdown(...)` call). Inside:
- Content pane: code → `CodeLensScreen(code, fileName, onCommit = null)` (read-only mode is
  already supported — `CodeLensScreen.kt:70,151`); markdown → `Markdown`; else plain text.
- Bottom **version strip**: chips `v1…vN` from `ArtifactHistory`; tap to jump, swipe to scrub.
- **Diff toggle**: `ReviewSheet(ChangeSet(listOf(FileChange(title, oldBody, newBody))))`
  read-only (no-op `onCommit`) — Dialog-in-Dialog is a working precedent
  (`CodeLensScreen.kt:180-213`).
- **Save** via SAF `ACTION_CREATE_DOCUMENT`; **Share** via `ACTION_SEND`.

HTML *live* preview is deferred (zero WebView precedent in the app — recon; it would be new
architecture). HTML renders as code in v1. Noted in §Not-in-this-pass.

**Tests.** `ArtifactBlocks` (fences, info-strings, nested/unclosed, streaming tails) and
`ArtifactHistory` (identity chaining across a fixture tree, branch isolation) — pure JVM,
`domain/diff` test style.

---

## W5 — Projects (instructions + files as context)

**Bar (Claude Projects):** a project holds instructions + files; every chat in the project gets
them as context; the injected context is visible, not hidden.

**Which "project"?** Two unrelated concepts exist (recon): the Chats-tab **label**
(`SessionStore.conversationProjects`, rootId→String) and the top-axis Project **room**
(deliberately store-less Git-issues view — do not touch it; its anti-store thesis is
documented). This workstream upgrades the *chat-side* concept only.

**Store — no Room.** New `data/ProjectStore.kt` (SharedPreferences JSON, `GitHostStore`
pattern): `Project{id, name, instructions, fileUris: List<String>}`. Migrate existing label
strings to auto-created projects on first read; `conversationProjects` becomes rootId→projectId
(`SessionStore.kt:97-100,232-247`). Update `DataExport.kt:43` to export the new shape.

**Files → context (`domain/context/FileContext.kt`)** — the plumbing `STATE.md` §B4 already
wants; build it here, council per-member files reuse it later. Files attach via SAF
`ACTION_OPEN_DOCUMENT` (+ persistable grant), read through `contentResolver`, text-decoded with
per-file and total budgets (~24k chars total v1, largest-trimmed-first), producing a context
block + a human summary ("3 files, ~6k tokens").

**Injection.** Single-agent path: `runTurn`'s path build (`ChatViewModel.kt:634`) — prepend one
SYSTEM `MessageNode` carrying `project.instructions + FileContext` when the conversation's
project has content. Council path: same prepend inside `sendCouncil`'s `msgs` buildList
(`:812-816`, exactly where per-voice systemPrompt already goes). Tag the user node
`metadata["projectId"]` for legibility; the chat header shows a small "project: <name>" chip.

**UI.** Project editor sheet from the Chats room Projects tab (name, instructions, file list
with add/remove). Settings untouched.

**Tests.** `FileContext` budgeting/trimming (pure); ProjectStore round-trip + label migration;
injection composition (fixture path → expected message list), `ConversationProjectionTest` style.

---

## W6 — Call mode (core engine here; Studio surface there)

**Bar (phone dialer × ChatGPT voice):** start a call, talk hands-free-ish, hang up; the phone
*rings* when the reply is ready; answer and hear it; lift to ear; a "video call" where the AI
shows an image while speaking. Owner decision: this is a **Studio** feature — but Studio's own
binding rules (`STUDIO_SUITE_BRIEF.md:62-65`: free/engine code in core only; never modify
shared files in Studio; Studio ships unique `studio/` files installed via seams) mean the split
below. **Record in the decision register: call *surface* = Studio paid delta; call *engine* =
core shared substrate.** Studio also carries a freeze on non-defork app-tree commits
(`STUDIO_DELTA.md:13`) — the seam-install + `studio/call/` files are exactly the sanctioned
shape, and each Studio-unique file must be appended to STUDIO_DELTA §1's keep-table in the same
commit.

**Core substrate (this repo, shared files — Studio picks them up via the owner's sync):**
1. `domain/call/CallSession.kt` — pure FSM, JVM-tested. States
   `Idle → Listening → Thinking → Ringing → Speaking` (+ `Speaking→Listening` barge-in,
   `*→Idle` hang-up); events (`TalkPressed/Released`, `ReplyReady`, `Answered`, `TtsDone`,
   `HangUp`). No Android imports.
2. `service/SpeechOut.kt` — thin `android.speech.tts.TextToSpeech` wrapper (main-thread,
   utterance-progress → FSM events). On-device default voice; no cloud TTS (rule 1).
3. STT: reuse `service/OnDeviceDictation.kt` **unchanged** — it's a plain push-to-talk class,
   already proven reusable in two call sites (recon). **Hold-to-talk inside the call metaphor**
   — this satisfies `docs/design/voice-input.md`'s hard "no ambient listening, ever" rule while
   still feeling like a call. Record as decision-register entry (the doc's open mic question is
   hereby answered: push-to-talk survives into call mode).
4. Ring: new channel `fonebrew.calls` (IMPORTANCE_HIGH + ringtone + vibrate), copied from the
   `GenerationService.kt:56-63` channel shape (raw `Notification.Builder` — the app has no
   NotificationCompat, keep it that way). Manifest adds `POST_NOTIFICATIONS` +
   `USE_FULL_SCREEN_INTENT` (`AndroidManifest.xml:10-19`) + a runtime permission ask on first
   call. Note: POST_NOTIFICATIONS is absent *today* — a pre-existing gap this fixes for
   generation/download notifications too (recon). If the app is foreground, ring in-app
   (sound + full-screen call UI) without a notification.
5. Seam: new `ui/call/CallSlot.kt` in **core** — a snapshot-state install point (copy the shape
   of Studio's `DevelopTabProvider`/`ProjectRoomSlot` seams) + a call icon in Chat's header that
   renders only when a surface is installed. Core ships the slot empty; nothing visible changes
   for Fonebrew users.
6. Keep-alive honesty: cloud turns deliberately have **no FGS** (the "FGS did not start in
   time" crash class, `ChatViewModel.kt:613` gate — do not regress this). v1: an active call
   holds a partial wakelock + screen-on flag; if the process dies while Thinking, the reply is
   recovered by W3's partial checkpoint and the ring fires on next launch. State this
   limitation in the plan's decision register rather than engineering around it now.

**Studio surface (`/workspace/android-ide-studio`, new unique files only):**
- `app/src/full/java/dev/aarso/studio/call/CallSurface.kt` (+ controller): full-screen call UI
  (avatar, state label, hold-to-talk button, hang-up, speaker toggle), installed into core's
  `CallSlot` from `StudioEntryPoint.kt:36/47` (`installStudioEntryPoint`/`reinstallStudioSeams`
  — the existing pattern at those exact lines).
- Proximity: `SensorManager` PROXIMITY + `PROXIMITY_SCREEN_OFF_WAKE_LOCK`; near-ear during
  `Ringing` = answer; near-ear during `Listening` = keep mic hot (hold-to-talk by posture);
  `AudioManager MODE_IN_COMMUNICATION` for earpiece routing.
- "Video call" v1.5 (same workstream, after audio loop works): while `Speaking`, if the reply
  carries an image node (`IMAGE_KEY`) or an artifact block, show it full-bleed in the call
  surface. Flavor-safe (no mediaProjection — that's user-screen-sharing, not this).
- Tests: FSM tests live in **core** (`domain/call/`); Studio adds a seam test mirroring
  `DevelopTabsTest.kt:10` under `app/src/testFull/java/dev/aarso/studio/`.
- Gate in Studio: `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test
  :app:checkLicense` (its CI shape).

---

## W7 — Sync via a service of the user's choice

**Bar:** the user points the app at any folder (Drive, Syncthing, Dropbox — whatever their
provider mounts via SAF); backups land there; restore works; keys never leave the Keystore.

- Zero SAF exists today (recon) — new `data/BackupFolderStore.kt` (persisted tree URI,
  `GitHostStore` prefs pattern; `AppContainer.kt:123` wiring precedent).
- Settings → General → "Your data" (`SettingsRoom.kt:417-444`): add **Backup folder**
  (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`) and **Back up now** — writes
  `DataExport.toJson(container)` (`DataExport.kt:21`) to
  `<folder>/fonebrew-backup-<yyyy-MM-dd>.json` via `DocumentFile`, alongside the existing
  share-intent export.
- **Import (new)** — `data/DataImport.kt`: parse the export JSON, insert only nodes absent from
  the tree (append-only union — the exact semantics `GitBackup.pull()` already uses; imitate
  its status wiring at `SettingsRoom.kt:853-868`). API keys/tokens remain excluded from both
  directions (`DataExport.kt:14,26`, rule 5).
- Git tree-sync stays the primary sync (already whole-tree, append-only push + union pull);
  this adds the no-Git-account path. Scheduled auto-backup deferred (needs WorkManager — new
  dependency; see §Not-in-this-pass).

**Tests.** DataImport union semantics over fixtures (`GitContentsApiTest` request-builder
style); export→import round-trip (pure, in-memory).

---

## Sequencing

```
serial (ChatViewModel/ChatScreen + engines hotspot):
  W0 → W1 → W2 → W3 → W5
parallel with the serial track (after W0 lands):
  W4 (domain/artifact + viewer; only light MessageBubble touch — coordinate that one edit)
  W6-core (domain/call, services, CallSlot)  →  W6-studio (other repo)
  W7 (settings + data only)
finish: full gate both flavors → refresh fonebrew-sd.apk on apk-dist (LFS) → Studio gate.
```

Rough sizes: W0 S · W1 L · W2 M · W3 M · W4 L · W5 M · W6-core M · W6-studio M · W7 S.

## Decision register (owner: confirm or veto; none block the start)

1. **Call mode split** — surface in Studio (paid delta, seam-installed), engine in core.
   Overrides a literal reading of "free features in core" by making the *surface* the paid part.
2. **Hold-to-talk inside the call metaphor** — preserves the no-ambient-listening rule;
   posture-based (lift-to-ear) mic hold via proximity is still push-to-talk, by posture.
3. **No FGS for cloud call turns** — process-death during Thinking degrades to
   ring-on-next-launch (W3 makes the reply itself crash-safe).
4. **Attachments as metadata + files, not a Room table** — avoids the destructive-migration
   trap entirely; revisit when the migration story is fixed.
5. **Web-search tool version pinned** to `web_search_20260209` with documented legacy fallback
   constant; errors surface visibly instead of silent downgrade.
6. **maxTokens default 1024 → 8192** (and actually sent on all three providers).

## Explicitly NOT in this pass

On-device vision (mmproj/LLaVA); app-side `SearchProvider` for local models (seam named, not
built); HTML live preview for artifacts (no WebView precedent); cross-branch artifact
scrubbing; council-mode vision/sources/call; scheduled auto-backup (WorkManager); Room
migration overhaul; §5b/§5c (Issue #2 — inert, untouched); routing engine (separate repo);
PR #9 conflict (task #61); Play publication.

## Owner device-test checklist (nothing here is machine-verifiable — CLAUDE.md rule 6)

1. **W1**: photo from gallery + camera → ask Claude about it (Anthropic key) → same via Gemini;
   local model shows the blind-model refusal.
2. **W2**: globe on → "what happened in F1 this weekend?" → sources footer, links open;
   globe off → no tools sent (check no source footer).
3. **W3**: type a draft, kill the app, reopen → draft intact per conversation. Send with
   airplane mode → visible unanswered chip → retry works. Kill mid-stream → partial reply
   recovered on relaunch.
4. **W4**: generate code, iterate 3×, open artifact → scrub v1→v3, diff v1↔v3, save to Drive.
5. **W5**: project with instructions + 2 files → new chat in project → model demonstrably uses
   the file content; context chip visible.
6. **W6** (Studio APK): full call loop — talk, hang up, lock phone, ring fires, answer,
   TTS reads reply, lift-to-ear, barge-in, image shown during a "video" reply.
7. **W7**: pick a Drive folder, back up, uninstall/reinstall, import → conversations intact,
   keys absent (re-enter keys manually — expected).
