#!/usr/bin/env bash
# The plan's "root-unchanged" check for the desktop/ port lane (docs/PORTING_PLAN.md section 6.1, LX-1).
#
# Rule being enforced: steps LX-1 and LX-2 "edit no existing file" (section 6 placement rules). The
# port lane may ADD files under desktop/ and .github/workflows/desktop-*.yml; it may not modify,
# delete or rename anything that already existed outside those paths. Two independent checks:
#
#   history <base>   Every non-merge commit in <base>..HEAD that touches a lane path must not
#                    modify / delete / rename / retype any file OUTSIDE the lane paths. (Additions
#                    elsewhere are other lanes' business and are not "editing an existing file".)
#                    <base> may be the word ALL to scan the whole history.
#   tree             After the lane's Gradle run: `git diff --exit-code` (no tracked file changed) and
#                    an empty `git status --porcelain` (no stray untracked file outside .gitignore).
#                    Proves running the desktop build writes nothing into the repo it maps.
#
# Android-side steps (LX-3 onward) edit existing files BY DESIGN and are owner-gated; they must land in
# commits that do not touch lane paths (put the matching desktop/core-host/mapping.txt update in a
# follow-up commit), or the owner approves relaxing this check in that PR. This script does not
# paper over that: a mixed commit fails here, on purpose.
set -euo pipefail

# Lane paths: everything under desktop/ plus the lane's own workflow files.
LANE_RE='^(desktop/|\.github/workflows/desktop-[A-Za-z0-9._-]+\.yml$)'

usage() { echo "usage: $0 history <base-sha|ref|ALL> | tree" >&2; exit 2; }
cmd="${1:-}"; [ -n "$cmd" ] || usage

case "$cmd" in
  history)
    base="${2:-}"; [ -n "$base" ] || usage
    if [ "$base" = "ALL" ]; then
      range="HEAD"
    else
      if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
        echo "::warning::base '$base' is not a known commit (force-push?); scanning the whole history instead" >&2
        range="HEAD"
      else
        range="${base}..HEAD"
      fi
    fi
    bad=0; checked=0; lane_commits=0
    for c in $(git rev-list --no-merges --reverse "$range"); do
      checked=$((checked + 1))
      # --no-renames: a rename shows as delete(old) + add(new), so moving an existing file into
      # desktop/ is caught as a deletion outside the lane.
      changes="$(git diff-tree --no-commit-id --name-status -r --no-renames --root "$c")"
      paths="$(printf '%s\n' "$changes" | cut -f2-)"
      if ! printf '%s\n' "$paths" | grep -Eq "$LANE_RE"; then continue; fi
      lane_commits=$((lane_commits + 1))
      # Offenders: a non-Add status (M, D, T, ...) on a path outside the lane.
      offenders="$(printf '%s\n' "$changes" | awk -F'\t' -v re="$LANE_RE" '$1 !~ /^A/ && $2 !~ re {print $1 "\t" $2}')"
      if [ -n "$offenders" ]; then
        bad=1
        echo "ROOT-EDIT in $(git log -1 --format='%h %s' "$c"):" >&2
        printf '%s\n' "$offenders" | sed 's/^/    /' >&2
      fi
    done
    echo "history check: scanned $checked commit(s) in '$range'; $lane_commits touched the desktop lane."
    if [ "$bad" -ne 0 ]; then
      echo "FAIL: a desktop-lane commit edits or deletes an existing file outside desktop/ and .github/workflows/desktop-*.yml." >&2
      exit 1
    fi
    echo "OK: no desktop-lane commit edited an existing file outside the lane."
    ;;
  tree)
    git diff --exit-code
    stray="$(git status --porcelain --untracked-files=all)"
    if [ -n "$stray" ]; then
      echo "FAIL: the desktop build left changes in the repo:" >&2
      printf '%s\n' "$stray" >&2
      exit 1
    fi
    echo "OK: git diff --exit-code clean and no stray files after the desktop build."
    ;;
  *) usage ;;
esac
