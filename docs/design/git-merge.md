# Git engine, part 2: merge and conflict resolution (`domain/gitcore/merge/`, `textmerge/`)

Status: wave-2 lane G2, built. The first commit of this file was the plan; this version records what
was built, what was not, and what only the owner can verify. Test counts are in the lane report, not
frozen here (they go stale).

## Why
Part 1 (`domain/gitcore`) gives objects, packs, refs, an index that can hold stages 1-3, and `commit`
that reads `MERGE_HEAD`, but nothing created conflicts. The owner's goal ("clone, branch, commit,
push, resolve conflicts, no desktop step") needs merge, cherry-pick, rebase and a conflict model a
touch UI can drive. Clean-room, as in part 1: built from git's public documented behaviour (marker
format, index stage numbers, `MERGE_HEAD`/`MERGE_MSG`/`ORIG_HEAD`, the `rebase-merge/` directory
layout) and our own tests; the real `git` CLI is a black-box oracle only. No JGit/libgit2/xdiff source
was opened, ported or copied, and no dependency was added.

## What is built

### `textmerge/` (pure; no repository)
* `RawText`: bytes travel as ISO-8859-1 "raw" strings (one char per byte), so any encoding, even a stray
  invalid byte, survives a merge byte for byte and two lines compare equal exactly when their bytes do.
  Only `\n` ends a line; `\r` stays in the line (CRLF and LF lines are different lines, as in git).
* `LineDiffEngine`: the diff. Strip common prefix/suffix; shortest edit script (Myers 1986, the public
  paper); then each group of changed lines is normalised the way git places hunks (slide up, merging
  groups it touches, then down; among those placements prefer the lowest one aligned with a change in
  the other file, else the lowest). Without that last step equal-cost diffs of duplicate lines land
  differently from git's and merges disagree on where a conflict starts.
* `TextMerge` (diff3): diff base->ours and base->theirs, sweep both hunk lists in base order and chain
  hunks into groups while the next hunk starts at or before the group's end. Touching hunks conflict
  (line 2 edited on one side and line 3 on the other IS a conflict, as in git). One-sided group: take
  that side. Both sides, identical text: take it once. Otherwise a conflict carrying base, ours and
  theirs. Every base line is therefore unchanged-on-both, taken from the one side that changed it,
  identical on both, or inside a conflict that holds both versions: nothing is dropped silently.
* Marker styles: `MERGE` (split a conflict where ours and theirs agree; re-join conflicts separated by
  three or fewer common lines, or by lines with no letter or digit, unless a one-sided change lies
  between them), `DIFF3` (base shown, conflicts whole), `ZDIFF3` (base shown; the lines both sides share
  at the start/end of a conflict move out). Labels and marker size are configurable. CRLF markers follow
  git's rule (the line before the conflict ends in CRLF on both sides); a missing final newline is
  completed before a marker and left exactly alone in clean text.
* `ConflictMarkers.parse`: reads markers back out of a file. Strict and typed (`MarkerParseError`:
  NESTED_START, UNTERMINATED, STRAY_MARKER, OUT_OF_ORDER); a lone `=======` outside a conflict is text.
* `Resolver`: per region OURS / THEIRS / BASE / BOTH (either order) / CUSTOM; open regions keep their
  markers so a half-resolved file re-parses; BASE on a region with no known base and unknown region
  numbers are typed errors; `leftoverMarkers` is the validator.
* Binary (NUL in the first 8000 bytes) and over-cap (8 MiB per input) content is reported UNMERGEABLE and
  never rendered.

### `merge/`
* `mergeBases` (all best common ancestors) and `baseTreeFor`: one base is used as is; several (criss-
  cross) are merged pairwise, newest first, into a virtual base whose own conflicts stay in it as marker
  text. Virtual merge commits are written as unreferenced commit objects with a fixed identity and the
  newer parent's time (deterministic). Bounds: recursion depth 6, 16 bases; beyond either the rest is
  ignored and `BaseTree.truncated` says so. Conflict-marker label for such a base: `merged common
  ancestors`; for one base, its 7-character id (both as git prints them).
* `detectRenames`: exact (same blob id and file kind; ties by same basename, then directory, then path)
  then inexact at >= 50 percent similarity (shared lines weighted by bytes over the larger file),
  greedily by descending score. **Rename-detection bound:** the inexact pass compares every unpaired
  deleted file with every unpaired added file, so it runs only when `deleted * added <= 160,000` and for
  blobs up to 1 MiB; otherwise it is skipped, `TreeMergeResult.renameDetectionSkipped` is set, and those
  paths are treated as a delete plus an add (conservative: a delete/modify conflict, never a lost file).
* `mergeFlatTrees`: per path after rename pairing, same on both sides -> take; changed on one side ->
  take it (a delete too); changed on both -> text merge plus three-way mode merge. Conflict kinds:
  CONTENT, ADD_ADD (empty base), MODIFY_DELETE, RENAME_DELETE, RENAME_RENAME (1 to 2), FILE_DIRECTORY (the
  file moves to `path~label`, as git does), BINARY, MODE, TYPE_CHANGE. Each `PathConflict` carries the
  exact index stages and the work-tree content.
* Operations (`Repository` extensions): `merge` (fast-forward / no-ff / ff-only, no-commit, unrelated
  histories opt-in) returning a typed `MergeOutcome` (UP_TO_DATE, FAST_FORWARD, CLEAN, CONFLICTED with the
  list); `abortMerge`; `continueMerge`; `cherryPick` (outcome CLEAN / CONFLICTED / EMPTY); `rebase`,
  `rebaseContinue`, `rebaseSkip`, `rebaseAbort`, `rebaseState`.
* One apply routine (`applyMergePlan`) writes every result. Before the first write it requires: index ==
  HEAD (no staged changes, no stages) and every path the result changes untouched in the work tree (no
  local edit, no untracked file or directory in the way); otherwise DIRTY_TREE listing the offenders.
  Unrelated local edits are carried across. Order: journal -> state files -> deletions -> writes -> index
  (once) -> journal marked ready.
* The journal (`FONEBREW_MERGE_JOURNAL`, ignored by git) records per path what the operation intended
  to leave in the work tree and index and what HEAD had. `abortMerge` refuses (DIRTY_TREE, naming paths,
  saying why) when any touched path is now neither: that catches a hand-resolved file, a staged
  resolution, an edit to a cleanly merged file. `discardLocalChanges` is the explicit opt-in. `continue`
  refuses while any stage remains, while the journal says `applying` (interrupted), and while a
  conflicted path's committed content still has marker lines (`allowMarkers` is the opt-in).
* Rebase is linear and resumable. Layout: git's own `rebase-merge/{head-name,onto,orig-head,
  git-rebase-todo,done,end,msgnum,stopped-sha,message,author-script,interactive}` + `REBASE_HEAD` (git's
  CLI recognises, aborts and continues it - tested both directions), plus private `fb-todo` (the full
  list, immutable), `fb-state` (`tip`, `next`: the single atomic commit point of a step) and
  `fb-applying` (in-flight marker). All git-visible files are derived and regenerated on resume. A step:
  marker -> replay (journal) -> commit object -> marker with new id -> HEAD moves -> `fb-state` written
  (commit point) -> derived files. A crash at any of those points resumes without losing or repeating a
  step (tested by injected failure at every point, three steps deep). A rebase begun by the git CLI is
  adopted (plain `pick` lines only). The branch moves once, at the end, by compare-and-swap against the
  original tip; if someone moved it meanwhile the rebase refuses (REF_CONFLICT) instead of overwriting.
* `ConflictSession`: presenter-ready and pure. Lists conflicted files (git's two-letter code, glyph +
  label status, region counts), per-region views, next/previous open region, `choose`/`chooseAll`,
  `preview`, `markResolved` (write + stage, refusing open regions or leftover markers), `resolveWith`
  OURS/THEIRS/BASE for non-text conflicts and for unreadable markers, `reload` after an external edit.
  Status is never carried by colour: every state has a glyph and a word.

## Algorithm choice, and why
Myers' minimal diff + git's slider normalisation, not histogram or patience as the primary: for small
and medium inputs only a minimal diff with git's hunk placement reproduces git's conflict boundaries,
and the interop table requires byte equality with `git merge-file`. For inputs whose edit distance
exceeds 1500 lines the diff is decomposed at lines that occur exactly once on both sides (a patience
anchor set, by longest increasing subsequence) and each gap solved again; a gap that is both anchor-less
and too costly becomes one replace hunk. That fallback is coarser (larger conflicts) but never wrong.
Output is deterministic: no randomness, no hash-order dependence.

## Honest scope: what is NOT built (named follow-ups)
Octopus merges; merge drivers and `.gitattributes` merge settings (so `merge=union`, `-merge`,
`conflict-marker-size`); rerere; submodule conflicts (a gitlink conflict is reported as TYPE_CHANGE with
stages, never merged); whitespace strategies (`-Xignore-space-change` and friends); interactive rebase
todo editing (squash/edit/reword/fixup, `--rebase-merges`, `--onto` over a non-linear range, patch-id
dropping of already-applied commits); rebase of a detached HEAD; directory-rename inference (git can move
a new file into a renamed directory, or report it; the engine does neither); rename/add collisions at one
destination (typed UNSUPPORTED, nothing written); `merge` of more than one head; merge-commit
cherry-pick (needs a mainline choice; refused UNSUPPORTED); `core.autocrlf` filters; sparse checkout.

Known deviation: when two different minimal diffs tie and the inputs are duplicate-heavy, the engine can
place a hunk differently from git, so the conflict boundaries differ (the merged content still keeps
both sides). A seeded fuzz against `git merge-file` bounds this below 1 percent of cases; the exact-
equality proof is the scenario table. Virtual-base conflicts (a criss-cross whose bases themselves
conflict) are built but only the clean-base and conflict-on-top criss-crosses are compared to git.

## Interop proof (`MergeInteropTest`, `TextMergeInteropTest`, `PickRebaseInteropTest`)
* Text: 47 hand-built scenarios x 3 marker styles compared byte for byte, plus conflict count equal to
  `git merge-file`'s exit status, plus a seeded fuzz.
* Tree: 52 scenarios, one JUnit test each. git and the engine merge copies of one git-built repository.
  Clean: identical tree id, parents, work tree, and `git status` clean on the engine's result. Conflicted:
  identical `git ls-files -u`, `git status --porcelain`, `git diff`, work-tree bytes, MERGE_HEAD,
  ORIG_HEAD, MERGE_MSG; and `git merge --abort` run by git on the engine-written state restores the
  pre-merge tree. `git fsck --strict` on every engine result.
* Tree fuzz (`MergeFuzzInteropTest`): seeded random repositories where each side adds, edits, deletes,
  renames and chmods files; outcome, merged tree id (clean) or `ls-files -u` and `status` (conflicted)
  must equal git's. 80 seeds run in the gate; 1,500 seeds were run while building (1,127 clean, 373
  conflicted) with no remaining disagreement (the one class found - rename/rename where one side also
  changed the file - is fixed: the merged version travels to both names, as git does).
* Cherry-pick and rebase: same shape; git aborts and continues engine-stopped state and the engine
  continues a rebase git stopped.
All of it is skipped by assumption when `git` is not installed; skipped versus run counts come from the
JUnit report.

## OWNER-VERIFIED-ONLY (nothing here ran on a device)
* Everything on the phone: atomic rename and exec bit on app-private and shared storage, symlink
  handling, behaviour when storage is full or the process is killed mid-write (the crash tests simulate
  death between steps; they cannot simulate a torn write by the OS or a power cut).
* Wall-clock and memory cost on a large tree: merge holds one map entry per file for three trees; the
  inexact rename pass is bounded at 160,000 pairs; the diff of two very large files with a huge edit
  distance relies on the anchor fallback; none of this was timed on a phone.
* Interop with a second process (a desktop git) sharing the directory beyond lock files, and against any
  real host or large real-world repository: the oracle here is the container's git 2.43 on fixtures built
  for the scenarios.
* The feel of the conflict flow on a touch screen (no UI was built in this lane).

## Part-1 changes
None. The lane uses only public part-1 API and the module-internal helpers part 1 already exposes
(`workTreeFs`, `statEntry`, `writeEntry`, `worktreeChange`, `canonMode`, `indexMtimeSeconds`).
