# Agent publish: branch, pull request, CI status (wave-2 lane A1b)

Status: design note, written before code. Live behaviour against a real host is
UNVERIFIED (this container has no git host, device or emulator).

## What this lane builds

Pure request builders and response parsers in `domain/git/`, in the same idiom as
`GitTreeApi` / `GitContentsApi` (data-only `GitRequest`, token passed in, no I/O):

- `GitBranchApi` - create a branch ref from a base sha; `validate(name)` per git
  ref rules; `suggestName(slug, shortId)` deterministic `fonebrew/agent/<slug>-<id>`.
- `GitPullRequestApi` - create a PR (draft defaults to true), fetch by number,
  parse number / html url / state; `body(...)` states honestly which engine
  proposed the change and whether it is a cloud engine (watched object).
- `GitActionsApi` - list workflow runs for a branch or sha, fetch check runs and
  combined status for a sha, POST workflow_dispatch; parse everything into one
  pure `CiState` (NONE / PENDING / PASSING / FAILING + counts + failing names)
  with a stable glyph and label per state defined here.
- `GitPublishModels.kt` - shared small types: `GitBuild` (Ready / Unsupported /
  Invalid), `GitApiResult` (Ok / Err), `GitApiError` + `GitApiErrorKind`.

## What it does not build

No UI, no `AgentRepoRunner` edits, no transport changes, no polling loop, no
merge. Lane A1 consumes these. No invented endpoints for hosts without them.

## Host matrix (GitHub primary)

GitHub: all three APIs. Gitea/Forgejo, checked against the Forgejo/Gitea
swagger served at codeberg.org/swagger.v1.json (Gitea 1.22-compatible):
- branch: `POST /repos/{o}/{r}/branches` {new_branch_name, old_ref_name}
  (there is NO `POST git/refs` in that spec - not used for Gitea).
- pull: `POST /repos/{o}/{r}/pulls` has no `draft` field -> draft=true on Gitea
  is typed Unsupported("pull request draft flag"); caller passes draft=false.
- `GET /pulls/{index}` supported.
- combined status: `GET /commits/{ref}/status` supported.
- runs: `GET /actions/runs?head_sha=&ref=` and dispatch
  `POST /actions/workflows/{file}/dispatches` exist; run `status` strings are not
  enumerated in the spec, so Gitea runs are parsed raw and NOT folded into
  CiState (unknown stays unknown).
- check runs: no endpoint -> Unsupported("check runs").
GitHub docs used: docs.github.com REST (pulls, git refs, check runs, commit
statuses, workflow runs, workflow dispatch). Dispatch success is accepted as
200 or 204 (docs.github.com currently describes 200 with run details).

## Seams and failure modes

- Builders are total: bad input returns `GitBuild.Invalid`, unsupported returns
  `GitBuild.Unsupported`; neither carries the token.
- Parsers take `(status, body)` only - they never see the token, so error values
  cannot contain it. 401/403/404/409/422 map to typed kinds; malformed JSON maps
  to MALFORMED; empty body to MALFORMED (except 204 where documented).
- `GitRequest.toString()` now redacts header values (a one-line change in
  GitContentsApi.kt, outside this lane's file list: the data class otherwise
  printed `Authorization: Bearer <token>`). Flagged for the owner/merge.
- CiState never infers success from absence: no runs, no checks, no statuses =
  NONE, not PASSING. Glyph + label per state; never hue.

## Owner-verified only (UNVERIFIED here)

Fixtures are HAND-AUTHORED from public docs, not recorded from a live host. The
owner must try, on a real repo: create branch; open a draft PR and see the body
text; fetch the PR; read CI state for a sha with passing and failing checks;
dispatch a workflow (needs `workflow_dispatch` trigger and token scope); on a
Gitea/Forgejo instance, branch + PR (non-draft) + status.
