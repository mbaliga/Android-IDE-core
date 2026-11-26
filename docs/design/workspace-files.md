# Workspace and file model (`domain/workspace/{roots,tree,ignore,fuzzy,files,grep}`) — design note

Status: wave-2 lane F1. Plan first, code second; this note is committed before any code and is
updated at the end with what was actually built. No UI in this lane: everything is pure
Kotlin/JVM (`java.io`, `java.nio.file`, `org.json`, kotlinx-coroutines `Flow`), so it is all
JVM-tested. Every runtime and on-phone behaviour is owner-verified only.

## Why
Today there is no on-device file tree, no multi-root concept (`LocalWorkspaceProvider` serves one
root and is PARKED), no fuzzy matcher (`Stemmer.kt` lists it out of scope) and no search over
local files. The later Develop-tab UI needs a headless model to bind to.

## Alignment with the ratified kernel (WORKSPACE_KERNEL_SPEC.md)
- Roots are `WorkspaceProvider`s (the §6.1 seam). v1 ships `LocalFileProvider` (java.io). An
  Android Storage Access Framework provider is a named follow-up behind the same interface; SSH
  and SNAPSHOT roots are other lanes.
- Revisions are content digests (SHA-256 via `Digest`), never mtimes (FB-RAT-WS-004). Saves from
  `OpenDocuments` present the base revision and get a typed conflict instead of overwriting.
- `OpenDocuments` mirrors the buffer vocabulary (`LineEndings`, clean/dirty, REMOTE_REVISION_CHANGED
  -> conflicted) and reuses `DocumentBufferMachine` for legality; it holds no text (no editor yet).
- `LocalWorkspaceProvider` (parked) is NOT modified. The new provider is a separate class with the
  same URI shape (`local://<relative>`) plus the escape guard the parked one lacks.
- The unified identity string is `local://<rootId>/<relative>` (a `WorkspacePath`); it is a
  Workspace-level key, a root's own provider sees only `local://<relative>`.

## What is built
1. `roots/` — `WorkspacePath(rootId, rel)` with a normaliser that rejects `..`, absolute paths,
   NUL and drive prefixes; `PathGuard` which additionally resolves symlinks (real path of the
   deepest existing ancestor must stay under the real root); `Workspace` (ordered named roots,
   add/remove/reorder/rename, per-root `RootSettings` excludes and default-ignore toggle,
   per-root `RootUiState` expanded dirs / last open files / frecency, all bounded);
   `WorkspaceCodec` (versioned JSON) and `data/workspace/WorkspaceFileStore` (atomic write,
   corrupt file is moved aside and an empty workspace returned with a typed reason).
2. `tree/` — `NaturalOrder`, `DirectoryLister` (bounded: scans at most `MAX_SCAN` = 20,000 entries
   of one directory, reports `truncated`, pages of 200), `FileTreeModel` (expand/collapse, lazy
   load, flattened visible rows), change detection (`DirectoryWatcher` over `WatchService`,
   `PollingDiffer` fallback, `ChangeCoalescer` debounce).
3. `files/` — `FileOperations` (create/rename/move/copy/delete) with typed `FileOpResult`; delete
   goes to a per-workspace `Trash` and returns an `UndoToken`; bounded retention (50 items, 7 days,
   512 MiB total); an item larger than the byte cap is refused for trash and only an explicit
   `permanent = true` deletes it. `OpenDocuments` registry and the atomic `AtomicFileWriter`.
4. `ignore/` — `Wildmatch` and `IgnoreMatcher` implementing the documented gitignore semantics
   (nested files, negation, dir-only, anchoring, `**`, escapes, trailing spaces, parent-excluded
   rule), `DefaultIgnores` (toggle per root), and `explain()` returning the deciding pattern,
   file and line. Interop test against the real `git check-ignore` on randomised trees.
5. `fuzzy/` — `FuzzyMatcher` (DP, fzf-style bonuses, smart case, multi-term AND, positions),
   `TopK` bounded heap, `QuickOpenIndex` (incremental), `Frecency` (decaying, bounded).
6. `grep/` — `SearchInFiles` (literal / regex, globs, ignore rules, binary and size skipping,
   context lines, `Flow` with cancellation, replace preview), regex bounded by a counting
   `BudgetCharSequence` with a typed `Timeout` outcome.

## What is not built
Editor, any Compose UI, SAF/SSH roots, multi-line regex, apply-replace (preview only), symbol
search, `.git/info/exclude` and `core.excludesFile` (only `.gitignore` files plus user excludes),
case-insensitive-filesystem ignore matching (git's `core.ignorecase` is treated as false),
persisted search index (the quick-open index is in memory and rebuilt from a walk).

## Seams and ownership
Gradle: none changed. DI: not wired (no UI consumer yet; the stores are constructed by the
future UI lane) — this avoids touching `AppContainer`. The grep lane's results are plain data so
a later search lane can merge them with `LexicalSearch` hits.

## Failure modes and the decision for each
- Path escape (`..`, absolute, symlink to outside, symlinked parent): refused with a typed reason,
  never resolved. A TOCTOU race between check and use cannot be fully closed with `java.io`; the
  guard resolves right before every operation and never follows a final symlink for delete/rename.
  Stated as a limit.
- Huge directory / huge tree: per-directory scan cap, file-count cap for walks (500,000), `truncated`
  flags surface to the UI; nothing silently drops.
- Directory changes during listing: vanished entries are skipped, never thrown.
- Delete: always trash unless explicit `permanent`; undo refuses to clobber an occupied origin.
- Corrupt persisted JSON: file renamed to `*.corrupt-<n>`, empty workspace, typed reason returned.
- Catastrophic regex: per-line execution budget + cancellation check inside `charAt`; on exceed the
  search ends with `Outcome.Timeout` naming file and line. Lines longer than 20,000 chars are
  skipped (counted) for regex.
- Unknown stays unknown: a binary or oversize file is reported skipped, not silently "no matches".

## Performance target (MEASURED on the JVM in a test, not on a phone)
Quick-open top-50 over 200,000 paths, 5-character query: target under 500 ms on a desktop-class
JVM; the test asserts a generous 5 s and prints the measured time. The phone number is owner-measured.

## Owner-verified only
Real file-system behaviour on Android storage (scoped storage reach, `WatchService` delivery
latency, symlink behaviour on app-private vs shared storage), battery cost of polling, trash on
removable storage (cross-filesystem move falls back to copy+delete), all scroll and typing feel.
