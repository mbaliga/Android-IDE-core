# Design: Incognito chats — transient, delete-on-exit conversations

> Status: **design only, no code.** The message tree's whole architecture is
> **append-only** (`CLAUDE.md`: "One append-only, git-like message tree… Restore
> = make an earlier node the active leaf" — never a delete). A chat that must not
> survive past its own session is a real exception to that, not a variant of it,
> so this doc argues the mechanism against the spine directly rather than trying
> to make it look like just another tree operation.

## The idea

A chat, opted into up front, whose turns never survive the session that produced
them: no row in the backup a user pushes to their Git host, no hit in search, no
line in `docs/STATE.md`-style export, gone the moment the app decides the session
is over. The rest of this doc is: which of two mechanisms gets there, why the
append-only spine rules one of them out by default, what has to actively refuse
to look at incognito data even though it technically could, when "exit" actually
fires on Android, and what this honestly does and doesn't defend against.

## Two designs, and why one wins

### Option A: flagged root + hard delete

Insert incognito turns into the real Room-backed tree exactly like normal turns,
tagged (an `isIncognito` flag on the root, or a metadata key the way `TreeFork`
already tags fork/spawn roots — `LINEAGE_KIND_KEY` in `domain/tree/TreeFork.kt`),
then actually `DELETE` the rows on exit — the one legitimate escape hatch from
append-only, used nowhere else in this codebase today.

The problem isn't that a hard delete is hard to write (`MessageNodeDao` would
need a real delete query it doesn't have — `MessageTreeRepository.insert`'s own
KDoc is blunt: "Append-only: never updates an existing one," and there's no
delete either). The problem is **enumerability**. Every consumer of "the tree"
has to be found and taught to filter the flag out, and there is no single
chokepoint that guarantees a future one won't forget:

- `GitBackup.backUp` reads `repository.tree().allNodes()` unconditionally
  (`data/GitBackup.kt:25`) and pushes every node it finds to the user's Git host.
- `DataExport.toJson` reads the same `container.repository.tree().allNodes()`
  (`data/DataExport.kt:74`) into the export JSON.
- `ThreadObserver.snapshot` and `ThreadGraphProjector.project` both take
  `repository.tree()` (or an equivalent snapshot) as input with no filter
  parameter at all (`data/ThreadObserver.kt`, `domain/thread/
  ThreadGraphProjector.kt`).
- `SearchProjector`'s tree walk and `SearchIndexer.reindex`/`sync` (`data/
  search/SearchIndexer.kt`) run over the same corpus.

Four call sites today, caught by reading the code once — but the flag has to be
threaded into *every one of them individually*, forever, including ones written
after this doc is merged. Miss one and an incognito turn is one push-to-Git-host
tap away from permanently existing in a remote repo the "delete" was supposed to
have made moot. A DELETE afterward doesn't help retroactively, either — appending
to a git-DAG-shaped remote and then deleting locally doesn't un-push it.

### Option B (recommended): a separate in-memory tree, never inserted

`MessageTree` (`domain/tree/MessageTree.kt`) is already "pure, in-memory view over
a set of message nodes… deliberately free of Android/Room types" — it takes a
plain `Collection<MessageNode>` and does not care where that collection came
from. An incognito conversation keeps its nodes in a second, ordinary in-memory
list — held by `AppContainer` (`di/AppContainer.kt`, one instance per process,
same lifetime as `MessageTreeRepository` itself) — and builds its own
`MessageTree` from that list, without ever calling
`MessageTreeRepository.insert`. `ChatViewModel` picks which backing store a given
root reads/writes through based on whether that root started as incognito; the
UI (message list, branch/restore, the tap-to-connect-shaped turn actions) is
unchanged, because it already only depends on `MessageTree`'s interface, not on
Room.

This wins on the same axis Option A loses on: **exclusion by construction, not by
audit.** None of the four call sites above need to change at all, and none need
to remember incognito exists, because they only ever see what
`MessageTreeRepository` (the Room-backed one) holds — an incognito node was never
there. "Exit" becomes: drop the reference to the in-memory list. No delete query,
no tombstone, no risk of a forgotten consumer. The tradeoff, honestly stated: this
*is* still holding plaintext turns in process memory for the session's duration —
see "What this does not promise" below for exactly what that does and doesn't
defend against.

**Recommendation: Option B.** Option A's per-consumer audit is exactly the
maintenance burden the append-only spine's simplicity was supposed to spare this
codebase; Option B keeps the invariant literally true ("the tree is append-only")
by simply not putting incognito turns in the tree, rather than making the
invariant a lie with an asterisk.

## Required exclusions (even though Option B makes most of this moot)

Stated explicitly because "isn't in the tree" is a design choice that depends on
every future write path respecting it — this is the checklist a code reviewer
should hold a PR against, not busywork Option B already solved:

- **`SearchIndexer`/`SearchProjector`** (`data/search/SearchIndexer.kt`,
  `domain/search/WorkspaceSearchProjector.kt`): must only ever be handed rows
  derived from `MessageTreeRepository`'s tree, never the incognito one. Correct
  today by construction (Option B); would need an explicit filter under Option A.
- **`DataExport`** (`data/DataExport.kt:74`): same — reads
  `container.repository.tree()`, which an incognito store is never part of.
  Worth a one-line comment at the call site (`// incognito conversations are a
  separate in-memory tree, never inserted here — nothing to exclude`) so a future
  reader doesn't "fix" what looks like an omission.
- **Git tree sync (`GitBackup`, `data/GitBackup.kt`)**: same reasoning. This is
  also where `tree-sovereignty.md`'s own still-open question — "Selectivity:
  sync-all by default, or per-conversation opt-in?" — and incognito chats meet:
  incognito is the strongest possible case *for* per-conversation opt-out
  existing at all, since sync-all-by-default with no selectivity would mean an
  incognito chat is only "excluded" for as long as it stays in Option B's
  in-memory store — the moment any future feature promotes it into the real
  tree (e.g. "save this conversation after all"), it would sync on the very next
  backup unless selectivity already exists as a mechanism.
- **`ThreadGraphProjector`/`ThreadObserver`** (`domain/thread/
  ThreadGraphProjector.kt`, `data/ThreadObserver.kt`): both take a tree snapshot
  as a parameter, never re-derive it themselves — whoever wires an incognito
  root's UI must simply never hand its tree to these. No projector change needed.
- **The usage ledger** (`data/LedgerStore.kt`, appended from `ChatViewModel.kt:
  1027-1043` via `LedgerCapture.singleTurn`): **open**, not resolved here. A
  ledger row carries no message content — `chatId`, `nodeId`, token counts,
  cost, model, provider, latency (`domain/ledger/LedgerEntry.kt`) — and the
  ledger's whole purpose (`LedgerStore`'s KDoc: "on-device… no telemetry, no
  server rollup") is an honest local spend record, which an incognito turn
  genuinely contributed to. Two defensible answers: (a) keep writing ledger rows
  for incognito turns, since they carry no transcript and the `chatId` becomes a
  harmless opaque, unresolvable UUID the moment the in-memory tree is dropped; or
  (b) skip the ledger entirely for incognito turns, for a stricter "leaves
  zero trace" guarantee at the cost of an honest gap in the user's own spend
  history. This doc does not pick — see owner decisions below.

## Lifecycle: what "exit" means

Android gives no single clean "the user is done with this screen" signal, so this
needs to pick from what actually exists, not from what would be convenient:

- **Process death** is free under Option B: the in-memory store lives in
  `AppContainer`, which `FonebrewApp` constructs once per process
  (`FonebrewApp.kt:22`) — when the process dies, the store is gone with it. No
  explicit teardown code needed for this case; it's the same reason
  `SessionStore` exists at all (`SessionStore`'s own KDoc: "every process death
  silently reset the chat… without this"), except here that reset is the *point*
  rather than the bug it was written to fix elsewhere.
- **Task removal** (swiping the app away in Recents) does *not* by itself kill
  the process on modern Android — a foreground service (`GenerationService`,
  used for on-device generation — `service/GenerationService.kt`, started at
  `ChatViewModel.kt:915`) can keep the process alive well past the swipe. If an
  incognito turn is mid-generation when the task is removed, "exit" should mean
  stopping that generation and dropping the store, not letting it finish
  invisibly in the background. No `onTaskRemoved` hook exists anywhere in this
  codebase today (checked: none does) — this is a real gap Option B's
  implementation has to close, not something already handled elsewhere.
- **An explicit timeout** (e.g. N minutes backgrounded) is not proposed here as a
  requirement — it's a plausible hardening layer, but "leave incognito mode /
  close the chat" as an explicit user action, plus process death, plus a
  task-removal hook, already cover the honest cases. Adding a timer is an owner
  call, not a default this doc assumes.
- **An explicit "leave incognito" / close action** in the UI is the primary,
  expected path — the other two are what happens when the user *doesn't* get to
  take that action cleanly (killed, swiped, crashed).

## What this honestly does not promise

Matching this repo's own environment-honesty rule (`CLAUDE.md`: "never claim
on-device behaviour works… owner-verified only") extended to a privacy claim,
which deserves the same discipline:

- **No defense against OS-level memory forensics.** Plaintext turns sit in JVM
  heap for the conversation's duration. A rooted device, a debugger attached to
  the process, or a heap dump taken before exit can recover them. This is not
  solvable by this design (or by Option A, or by any on-device chat app without
  hardware-backed memory encryption) — it is stated here so nobody reads
  "incognito" as "forensically unrecoverable."
- **No defense against Android's own swap/zram or a manufacturer's crash-dump
  pipeline** writing heap pages to storage outside this app's control.
- **No screenshot/Recents-thumbnail protection today.** There is no
  `FLAG_SECURE` anywhere in this codebase (checked). An incognito chat's turns
  are exactly as visible in the Recents-screen thumbnail as any other chat's,
  until a `FLAG_SECURE` window flag is added — a real, concrete, currently-open
  gap, not a hypothetical one.
- **No claim about the on-device model's own runtime state** (llama.cpp's KV
  cache / mmap'd context). §8.3's KV-cache snapshot path
  (`kvCache.pathFor`/`sessionSavePath`, referenced in `ChatViewModel.kt`'s
  `runTurn` KDoc) must not be used for an incognito turn's assistant node, or its
  content survives on disk as a snapshot file regardless of what the tree does —
  this is a concrete implementation requirement, not a nice-to-have, and belongs
  in whatever PR builds Option B.
- **"Transient" means "this session," not "cryptographically erased."** Dropping
  a Kotlin list reference does not zero the underlying memory; nothing in the
  JVM/ART memory model guarantees that. The promise is "not retained by design,"
  not "erased to a forensic standard."

## Owner decisions required

1. **Ledger for incognito turns: write, or skip?** (see "Required exclusions"
   above — both options are defensible; this doc does not pick one.)
2. **Task-removal behavior**: should removing the app from Recents mid-generation
   stop an in-flight incognito turn immediately, or let it finish in the
   foreground service and only drop the store after? (Affects whether an
   `onTaskRemoved` hook needs to also call something like `runJob?.cancel()`.)
3. **Should an explicit idle timeout exist** (auto-exit incognito after N minutes
   backgrounded), on top of process death / task removal / explicit close? Not
   assumed here.
4. **`FLAG_SECURE` for incognito screens**: in scope for this feature, or a
   separate follow-up? Given it's a concrete, currently-real gap the moment
   incognito ships, the owner should decide whether shipping incognito without it
   is acceptable for v1 or blocks it.
5. **KV-cache snapshot suppression for incognito assistant turns** — confirm this
   is a hard requirement of the initial implementation, not a follow-up, since
   skipping it defeats "transient" at the storage layer even if the tree side is
   done correctly.
