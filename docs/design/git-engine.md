# Git engine, part 1 (`domain/gitcore/`) — design note

Status: wave-2 lane G1. Plan first, code second; this note is committed before any code and
updated at the end with what was actually built.

## Why
Fonebrew's git layer (`domain/git/`) is REST-only: browse and agent commits go through the host's
contents/tree API. There is no object model, no clone, no on-device checkout. The owner's goal
("clone, branch, commit, push, resolve conflicts, against any git host, no desktop step") needs a
real engine. JGit and libgit2 are licence-banned (EPL / GPL-with-linking-exception), so this is a
**clean-room** engine written from the public git format documentation (object, pack, pack-index,
index, ref-name rules) and our own tests. The REST layer is untouched and stays the fast path.

## Scope (this lane)
Pure Kotlin on the JVM. No `android.*`, no `org.json`. Only `java.io`, `java.nio.file`,
`java.util.zip`, `java.security`. All file access goes through a small `FileSystemPort` so a later
Kotlin-multiplatform move is mechanical.

1. Object model: `ObjectId` (hash-width abstraction; SHA-1 only implemented), blob/tree/commit/tag
   parse + serialise byte-exact.
2. Stores: loose objects, pack v2 + idx v2 reading (OFS/REF delta, bounded cache, ceilings), a pack
   indexer (thin-pack completion, trailer verification), a non-delta pack writer, `ObjectDatabase` facade.
3. Refs + layout: loose refs, packed-refs, symbolic HEAD, lock-file atomic update with old-value
   compare, minimal reflog, init (bare / non-bare), config read + minimal write, ref-name validation.
4. Index v2 read/write byte-exact.
5. Working-tree operations over a `RepositoryHandle`: status, add, remove, move, restore, checkout
   (dirty-tree guard), commit (+amend), branch/tag ops, rev-walk, tree/index/worktree diffs feeding
   `LineDiff`, reset (soft/mixed/hard with explicit guard).
6. Safety (see below), tested adversarially.
7. Interop tests against the real `git` CLI (skipped by assumption when absent).

## Not built (named follow-ups)
Transport (smart-HTTP clone/fetch/push, pkt-line, credentials) is lane G2, not here. Also not
built: stash, submodules, sparse checkout, LFS, hooks, gc/repack, SHA-256 (the abstraction leaves
room; reads of a SHA-256 repo are refused with a typed error), partial-clone filters, linked
worktrees, real ignore matching (an `IgnoreRules` port with a no-ignore default; another lane
supplies the matcher), merge/conflict resolution (later lane), delta *writing*, commit signing,
`alternates` (ignored, documented), index extensions beyond preserve-or-drop (see below).

## Module map
`FileSystemPort` · `ObjectId`/`HashAlgorithm` · `GitObjects` (Blob/Tree/Commit/Tag + `Signature`) ·
`GitError` · `Zlib` (capped inflate) · `LooseObjectStore` · `PackIndex` · `PackFile` ·
`PackIndexer` · `PackWriter` · `ObjectDatabase` · `RefName` · `RefStore` · `GitConfig` ·
`Repository` · `GitIndex` · `PathSafety` · `IgnoreRules` · `WorkTree` (status/add/rm/mv/restore/
checkout/commit/reset/branch/tag) · `RevWalk` · `TreeDiff`.

## Safety model
Every object, pack, tree and index is hostile input. Malformed data yields a typed `GitError`
(thrown as `GitException`), never an uncaught runtime crash and never an unbounded allocation.
- Paths in trees/index/checkout: reject empty segments, `.`/`..`, absolute paths, backslash,
  NUL, any component equal to `.git` case-insensitively (also trailing-dot/space and NTFS-style
  `git~1` variants), and over-long paths.
- Never write through a symlink: before writing path P, every parent component is verified to be
  a real directory (not a symlink); a symlink leaf is replaced, never followed. Symlink targets
  are stored as data only; checkout creates a symlink whose target is the blob content but
  `PathSafety` ensures later entries cannot be written beneath it.
- Caps: tree depth, entries per tree, delta-chain depth, inflated object size, pack object count,
  delta cache bytes, header/commit sizes.
- Checkout/reset never clobber local changes silently: a dirty-tree guard lists the files.

## Memory ceilings (configurable via `GitLimits`, defaults)
Max in-memory object 64 MiB (larger blobs are streamed by `readStream` to a sink); delta cache
16 MiB; delta chain depth 4096 hard cap; tree entries 1,000,000; tree depth 256; pack objects
50,000,000 (header sanity); commit/tag bodies 8 MiB.

## Failure modes
Truncated/corrupt zlib, bad checksum, bad idx fanout, delta base missing (thin pack with no
base), delta-size mismatch, cyclic OFS/REF delta, index entry past end, lock held by another
process, old-value mismatch on ref update, dirty tree on checkout.

## Owner-verified only
Nothing here has run on a device. Owner-only: behaviour on phone storage (rename atomicity on
the app-private filesystem, symlink support), large-repo performance and memory (this container
has no phone-class constraints), real git hosts, and long-running timing. Interop against the
desktop git CLI is machine-verified here only for the container's git 2.43.
