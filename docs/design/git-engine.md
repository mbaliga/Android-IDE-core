# Git engine, part 1 (`domain/gitcore/`) — design note

Status: wave-2 lane G1, built. The first commit of this file was the plan; this version records
what was actually built, what was not, and what only the owner can verify. Test counts are
in the lane report, not frozen here (they go stale).

## Why
Fonebrew's git layer (`domain/git/`) is REST-only: browse and agent commits go through the host's
contents/tree API. There was no object model, no clone, no on-device checkout. The owner's goal
("clone, branch, commit, push, resolve conflicts, against any git host, no desktop step") needs a
real engine. JGit and libgit2 are licence-banned, so this is a **clean-room** engine written from
the public git format documentation (object, pack, pack-index, index, ref-name rules) and our own
tests; the real `git` CLI is used only as a black-box oracle in tests. The REST layer is untouched
and stays the fast path for browse and agent commits.

## Scope (built)
Pure Kotlin on the JVM. No `android.*`, no `org.json`. Only `java.io`, `java.nio.file`,
`java.util.zip`, `java.security`. All storage access goes through `FileSystemPort`
(`JvmFileSystem` is the one implementation), so a Kotlin-multiplatform move means re-implementing
one interface.

1. **Objects** — `ObjectId` + `HashAlgorithm` (width lives in the algorithm; SHA-1 only),
   `Blob`/`Tree`/`Commit`/`Tag`, byte-exact. Parsed trees/commits/tags retain their original bytes,
   so a hostile-but-hashed object (zero-padded modes, non-UTF-8 text) re-hashes to its true id.
2. **Stores** — loose (zlib, atomic temp+rename, size-capped reads, header peek, streaming);
   pack v2/v3 + idx v2 reading (`PackIndex` binary search over the fanout without loading the
   index; `PackFile`/`PackReader` with OFS and REF delta resolution, bounded LRU delta cache,
   iterative chain walk, chain cap); `PackIndexer` (spool + trailer check, scan, delta resolution
   to a fixpoint, thin-pack completion by appending the borrowed bases, idx v2 incl. large
   offsets); `PackWriter` (whole objects, streaming); `ObjectDatabase` facade (`has`, `readRaw`,
   `read` with hash verification, `peek`, `streamTo`, `write`, `indexPack`, `resolvePrefix`,
   `writeBlobFromStream`). `objects/info/alternates` is deliberately **ignored**.
3. **Refs/layout** — `RefName` (check-ref-format rules), `RefStore` (loose, packed-refs, symbolic,
   `.lock` + compare-and-swap `Expect`, directory/file conflict refusal, minimal reflog),
   `GitConfig` (read the subset, edit textually so comments survive), `Repository.init/open`
   (bare and non-bare; refuses SHA-256, gitfile/worktree, unknown extensions).
4. **Index v2** — `GitIndex` read/write byte-exact, sorted-path invariants. **Extensions: optional
   ones (`TREE`, `REUC`, `UNTR`, `FSMN`...) are DROPPED on read** (git rebuilds them); a required
   (lower-case) extension such as split-index `link` makes the file UNSUPPORTED rather than risk a
   wrong rewrite. v3 is read when no entry uses extended flags; v4 is UNSUPPORTED.
5. **Porcelain** — `status`, `add`, `remove`, `move`, `restoreWorktree/Staged/Paths`, `checkout`,
   `checkoutNewBranch`, `commit` (+amend, MERGE_HEAD aware), branch/tag create/delete/rename/list,
   `resolve`, `log` (limit, first-parent, path filter with git's simplification, hide),
   `mergeBase`, `isAncestor`, `objectsToSend` (for the push lane), `diffTrees`/`diffStaged`/
   `diffWorktree` + `unifiedDiff` (feeds the app's `LineDiff`), `reset` soft/mixed/hard.
6. **Safety** — see below.
7. **Interop proof** — `GitInteropTest` against git 2.43.

## Safety model
Every object, pack, tree and index is hostile input. Malformed data yields a typed `GitException`
(`GitErrorKind`), never an uncaught runtime crash and never an unbounded allocation.
- `PathSafety` is the single gate for tree entries, index entries and every porcelain path:
  rejects empty/`.`/`..` components, absolute and drive-letter paths, backslash, NUL, over-long
  paths, and any component that names the git dir under case folding, trailing dots/spaces,
  `git~1`, or HFS-ignorable Unicode. Tree entry names containing `/` are refused.
- `WorkTreeFs` lstat's every ancestor before any write and refuses a symlinked ancestor
  (UNSAFE_PATH) - also under `force`. A symlink at the leaf is replaced, never followed. Reads of
  explicit paths (add/remove/restore) refuse symlinked ancestors too; status never descends into a
  symlinked directory.
- Ceilings (`GitLimits`): in-memory object 64 MiB (larger whole blobs stream), delta cache 16 MiB,
  delta chain 4096, tree entries 1,000,000, tree depth 256, index entries 4,000,000, pack objects
  50,000,000 and a lying pack object count is rejected against the pack size, pack stream 4 GiB,
  commit/tag 8 MiB, path 4096 bytes. The inflater never produces more than the declared/capped size.
- Checkout and reset never clobber local work silently: the guard runs over every affected path
  before the first write and throws DIRTY_TREE listing the files (modified, deleted, staged, or
  untracked-in-the-way). Hard reset refuses unless `discardLocalChanges = true`. `restore` over a
  modified file likewise. `force` on checkout is an explicit parameter, never a default.
- Reflog/ref writes are lock-file + compare-and-swap; a concurrent writer yields LOCKED or
  REF_CONFLICT, never a silent overwrite.

## Memory ceilings (summary)
Peak per operation = one object (<= `maxInMemoryObject`) + delta cache (<= `deltaCacheBytes`) +
bookkeeping: the indexer keeps ~50 B/object of scan records and assembles the idx in memory
(28 B/object); `flattenTree`/`status` hold one map entry per tracked file; `log`/`ancestors` hold
one id per visited commit. None of these is bounded by repository size on a phone yet - see the
owner-verified list.

## Deliberately NOT built (named follow-ups)
Transport (smart-HTTP clone/fetch/push, pkt-line, credentials) is the next lane. Not built:
**stash, submodules** (gitlinks are carried and checked out as empty directories, never
recursed), **sparse checkout, LFS, hooks, gc/repack, SHA-256, partial-clone filters, linked
worktrees**, merge and conflict resolution (the index supports stages 1-3 and `commit` reads
`MERGE_HEAD`, but nothing creates conflicts yet), delta *writing*, rename detection in diffs and
`--follow` in log, commit signing, alternates, the real `.gitignore` matcher (`IgnoreRules` is a
port with a no-ignore default), non-UTF-8 path names (UNSUPPORTED), `core.autocrlf`/filters,
`core.filemode=false`-style per-platform stat differences beyond honouring the config flag,
untracked-directory collapsing in status (files are listed individually), and
`objectsToSend` completeness optimisations (it may send a few objects the remote already has).

## Deviations worth knowing
- Duplicate object ids inside one received pack are rejected (git never emits them).
- `mergeBase` returns one common ancestor chosen by recency, not the full best-bases set.
- `log` orders by committer time, ties by discovery; `git log` can differ on equal timestamps.
- Large files are hashed/streamed on add; a deltified object above `maxInMemoryObject` cannot be
  materialised (LIMIT_EXCEEDED) because delta application needs the base in memory.

## OWNER-VERIFIED-ONLY (nothing here ran on a device)
- Atomic rename, symlink creation, executable bit and `lstat` ino/dev behaviour on the phone's
  app-private storage and on shared storage (FUSE/sdcardfs may refuse symlinks or chmod).
- Memory and wall-clock behaviour on a large repository on a phone (the container has no phone
  memory limit): `status` and `flattenTree` are O(files), `ancestors` is O(commits).
- Performance of pack indexing for hundreds of MB, and of the double-pass hashing on add.
- Behaviour against real git hosts and real packs they produce (the interop proof here is the
  container's git 2.43 only, with fixtures we built).
- Concurrency with a second process (a desktop git sharing the directory) beyond lock files.
