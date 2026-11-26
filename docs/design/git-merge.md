# Git engine, part 2: merge and conflict resolution (`domain/gitcore/merge/`, `textmerge/`) - design note

Status: wave-2 lane G2. This first version is the PLAN, committed before any code. The final
version of this file records what was built, what was not, and what only the owner can verify.

## Why
Part 1 (`domain/gitcore`) gives objects, packs, refs, an index that can hold stages 1-3, and
`commit` that reads `MERGE_HEAD`. Nothing creates conflicts yet. The owner's goal ("clone, branch,
commit, push, resolve conflicts, no desktop step") needs merge, cherry-pick, rebase and a conflict
model a touch UI can drive. Clean-room, as in part 1: built from git's public documented behaviour
(conflict-marker format, index stage numbers, `MERGE_HEAD`/`MERGE_MSG`/`ORIG_HEAD`, the
`rebase-merge/` state directory layout) and our own tests; the real `git` CLI is a black-box
oracle only. No JGit/libgit2/xdiff source is opened, ported or copied.

## What will be built
1. `textmerge/` - pure, byte-exact three-way text merge: diff (minimal edit script, hunks slid to
   git's canonical position), diff3 grouping of hunks, conflict refinement, the three marker styles
   (`merge`, `diff3`, `zdiff3`), a marker PARSER with typed errors, a resolution model
   (OURS/THEIRS/BOTH/BASE/CUSTOM per region) and a validator for leftover markers.
2. `merge/` - merge-base set + bounded recursive virtual base, three-way tree merge with rename
   detection, the on-disk merge outcome (index stages, work tree files with markers, `MERGE_HEAD`,
   `MERGE_MSG`, `ORIG_HEAD`), `merge`, `abort`, `continue`, `cherryPick`, linear `rebase` as a
   resumable state machine in `.git/rebase-merge/`.
3. `ConflictSession` - presenter-ready, pure model over a repository's conflicted files.
4. Interop proof against the git CLI (>= 40 scenarios), guarded by assumption.

## What will NOT be built (named follow-ups; the final version lists them again)
Octopus merges, merge drivers / `.gitattributes` merge settings, rerere, submodule conflicts,
whitespace strategies (`-Xignore-space-change` etc.), interactive rebase todo editing, rebase
`--rebase-merges`, `--onto` with non-linear ranges, `.gitmodules`, `core.autocrlf` filters.

## Seams
* Only public part-1 API plus `internal` helpers already in the module (`workTreeFs`,
  `statEntry`, `writeEntry`). Tiny additive seams, each documented below if used.
* Merge lives in a new package; porcelain entry points are extension functions on `Repository`
  (same idiom as part 1). Time and identity come from the `Repository` (injected clock).
* Text merge knows nothing about repositories. Tree merge calls it through a small function type
  so tests can substitute it.

## Failure modes taken seriously (the verifier hunt list)
* A merge must never drop one side's change: every region of the output is either unchanged-in-both,
  taken from exactly the side that changed it, identical on both sides, or a conflict carrying
  BOTH. Tests construct adversarial cases (identical edits, delete/modify, add/add).
* `abort` must not lose later user edits: it refuses (DIRTY_TREE, listing paths) unless the work
  tree still equals what the merge left, or the caller passes an explicit discard flag.
* `continue` must refuse while any index stage is unresolved AND while a resolved file still holds
  conflict markers it did not intend (it reports them; `allowMarkers` is an explicit opt-in).
* The marker parser returns a typed error for nested, unterminated or out-of-order markers; it
  never guesses a region.
* Rebase state is written before each step is applied and after each step completes; a crash
  between any two writes resumes without redoing or skipping work (tested by injected failure).
* Binary content and files over a size cap are reported UNMERGEABLE and become a whole-file
  conflict (no markers written into binary data).

## Owner-verified only
Everything on a device: behaviour on the phone's storage (atomic rename, exec bit, symlinks),
wall-clock cost on large trees, memory use of the diff on very large files, interop with other
git clients sharing the directory, and real-host round trips (this container has no git host).
