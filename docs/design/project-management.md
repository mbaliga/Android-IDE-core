# Project management — the board is your repo's issues

Part of the expanded direction: Aarso as an **agentic Android IDE** where you
conceive, build, test, and launch an app from the phone. This doc covers the
**project-management surface** — a Kanban/Jira-style board, test dashboards, and a
pending-items view — built the sovereign way.

## Thesis: no Aarso-side project store

The board is **a view over the issues in your own Git host repo**, exactly as the
chat history is one append-only message tree, not a separate subsystem. There is no
hidden Aarso database of tasks. A card *is* an issue; moving it edits the issue on
your host. Open the same repo on github.com / your Gitea and the board is legible
there too — anti-black-box by construction (mirrors the tree-sovereignty decision:
plaintext, openable, yours).

## Column convention (host-portable)

| Column | Encoded as |
|---|---|
| Backlog | open issue, no `status:*` label |
| To do | label `status:todo` |
| Doing | label `status:doing` |
| Review | label `status:review` |
| Done | issue **closed** |

A move rewrites the `status:*` label and flips open/closed; non-status labels
(`priority:*`, `area:*`, …) are preserved. Closing wins — a closed issue is Done
regardless of labels.

## Built (headless, JVM-tested)

- `domain/pm/IssueBoard.kt` — `BoardCard`, `BoardColumn` (+ `of(labels, isOpen)`
  column inference), and `Boards` (group into ordered columns; `labelsForMove`;
  `isOpenAfter`).
- `domain/pm/IssueBoardApi.kt` — pure request builders (`listIssues`, `createIssue`,
  `moveCard`) + `parseIssues` (skips PRs that GitHub lists among issues; tolerates
  Gitea string-labels). Same shape as `BuildsApi` / `GitContentsApi`; GitHub + Gitea.
- `IssueBoardTest` — column inference, move semantics, request shapes, parsing.

## Not yet (next)

- `data/IssueBoardRepo` — wire the API through `GitTransport` (token from Keystore).
- Compose board room — drag a card between columns → `moveCard`; tap to open in the
  CodeLens/issue view. Owner drives the UX (the spatial-room placement is open).
- **Test dashboard** — reuse `BuildsApi.parseChecks` (already summarises CI verdicts)
  as the board's "Tests" facet; pending-items = the Backlog + Doing counts.
- Link cards ⇄ Loops: run a loop against a card's objective; attach the run log.

## Non-negotiables it must honour (CLAUDE.md)

No telemetry. The host is a watched object; the token goes only to that host. The
board never invents methodology — it's plumbing over issues the user already owns.
