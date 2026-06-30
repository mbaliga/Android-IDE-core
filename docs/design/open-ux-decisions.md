# Open UX decisions — pick options, no driving required

> **How to use this.** Each item is a fork I hit while building. I've built the
> *headless, design-system-independent* logic already (so a future design system
> reworks pixels, not architecture) and parked the UX choice here. Reply with the
> letter (and any tweak); I'll implement. My pick is marked ★. Nothing here blocks
> the engine work — it only shapes the rooms that sit on top.
>
> **ANSWERED 2026-06-19** — owner picks: **A1** (`status:*` labels) · **B1** (bottom
> dev-room facets) · **C1** ("New project" card) · **D1** (home-screen connect card) ·
> **E1** ("Prepare for launch" panel) · **F1** (single Install button). Implementing.
>
> **Built so far (wireframe, CI-compiled):**
> - Develop surface — **Board / New project / Launch / Builds** facets (Builds shows
>   tests as a badge + sideload Install, per the locked "tests = a badge" design).
> - **D1 home card** — a dismissible "Connect your repos" card on the chat home, shown
>   only when no Git host is wired; opens Settings (the token-first wizard).
> - **Interim placement:** Develop is still opened from Settings → Global → Develop.
>   **B1** wants it as the bottom spatial room — that move requires the documented map
>   reorg (Models folds into Settings; bottom axis opens Develop) and is gesture code
>   best validated on device. Tracked below; relocating is a re-parent, not a rewrite.

---

## A. Kanban column labels (PM board)

The board encodes columns as host labels. Which scheme should map to columns?

- **A1 ★** `status:todo` / `status:doing` / `status:review` (Backlog = no label, Done = closed).
  Clean namespace, unlikely to collide with existing labels.
- **A2** GitHub Projects-style: `Todo` / `In Progress` / `In Review`.
- **A3** Match a scheme you already use in your repos — tell me the exact label strings.

*Built so far against A1; swapping is a one-line change in `BoardColumn`.*

## B. Where do the new rooms live in the spatial map?

The locked map is: Chats=left, Settings=right, Models=below, Tree=z-out, Loops=z-in,
bottom=dev room (Branches/Tests/Builds). The new pillars need homes.

- **B1 ★** Fold **PM board** + **Builds/Tests** into the existing **bottom dev room**
  as facets (tabs): `Board · Builds · Checks`. One "develop" surface; no new gestures.
- **B2** Give the **Board** its own edge/room (e.g. a second bottom level via deeper
  overscroll), keeping Builds separate.
- **B3** Make "develop on a repo" a distinct **mode** (a top-level switch from chat),
  with its own internal nav — heavier, but cleanest separation of "chat" vs "build".

## C. Agentic-IDE entry point (idea → project)

`ProjectScaffold` turns a spec into a repo. How does a user start one?

- **C1 ★** A **"New project" card** in the dev room: name + package + one-line idea →
  scaffold → push to a new repo on the connected host → CI builds → install.
- **C2** Author it as a **Loop** ("scaffold → review → build → test") so it's visible
  and editable like other loops, reusing the Loop room.
- **C3** Both: C1 is the quick path; C2 is the power path. (More work.)

## D. First-run "Connect your repos" placement

The token-first GitHub wizard exists but is buried in Settings. For users with no host:

- **D1 ★** A dismissible **home-screen card** ("Connect your repos") that opens the
  wizard; disappears once a host is connected. Non-blocking.
- **D2** An **onboarding step** (adds a screen to first-run for everyone).
- **D3** Leave it in Settings only; surface nothing on the home screen.

## E. Launch-generation surface

`StoreListing` generates Play copy; `docs/brand/` has the logo.

- **E1 ★** A **"Prepare for launch" panel** in the dev room: generated listing (editable,
  with live character-limit meters) + the brand assets + a checklist from `docs/play/`.
- **E2** Make it a **Loop** ("draft listing → critique tone → fit limits").
- **E3** Defer entirely until the IDE/PM rooms ship.

## G. Where does Cost live?

The Cost epic (`docs/design/cost.md`) is built headless + has a wireframe **Cost facet**
in the Develop room. Beyond that:

- **G1 ★** Keep the **Cost facet** as a standalone calculator (current), AND let any
  chat turn optionally show "this advice cost X; acting on it risks Y" when you tap it.
- **G2** Cost facet only; no inline per-turn cost.
- **G3** Make decisions first-class **tree nodes** (a "decision" turn type) so a forecast
  attaches to the conversation and travels with your history. Heaviest, most thesis-pure.

## F. APK install affordance wording (sideload vs Play)

Sideload can download+trigger the OS installer; Play can only link out.

- **F1 ★** Same **"Install"** button everywhere; on the Play flavor it opens the APK in
  the browser/Downloads with a one-line note ("installs are handled by Android").
- **F2** Different labels per flavor ("Install" vs "Download APK").

---

## Not asked here (these are mine to engineer, not UX forks)
Data-layer wiring (repos through `GitTransport`), the BPMN/loop engine, parsers, limit
validation, the scaffold templates. Those proceed without you.

## Still owner-only (blocked, not a UX pick)
§5b/§5c methodology + idiolect baseline (Issue #2); the flag-report email for Play.
