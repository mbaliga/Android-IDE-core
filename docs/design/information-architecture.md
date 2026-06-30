# Aarso — Information Architecture (owner spec, 2026-06-23)

This is the **owner's canonical IA** for the whole app, dictated 2026-06-23. It supersedes
the ad-hoc "Settings as launcher" structure (which caused the nested-scroll crash, PR #39).
It obeys the binding rules in `/CLAUDE.md`. **The owner drives this IA; this doc records it
faithfully and tracks open questions — it is a living spec ("I will add more details as we
go").** Nothing here is built yet unless a "status" note says so.

The spine is the **spatial map** (one home = Chat, rooms park off the edges). Settings is
**configuration only — never a launcher**. Every destination is reached by the map, not by
"Open X" buttons.

```
                 ┌───────────────────┐
                 │   F. Tree (up)    │   pinch-OUT
                 └───────────────────┘
  ┌───────────┐  ┌───────────────────┐  ┌───────────┐
  │ A. Convs  │  │   B. Chat (home)  │  │ C.Settings│
  │  (left)   │  │                   │  │  (right)  │
  └───────────┘  └───────────────────┘  └───────────┘
                 ┌───────────────────┐
                 │  G. Loops (down)  │   pinch-IN
                 └───────────────────┘
                 ┌───────────────────┐
                 │  D. Develop (btm) │   (bottom edge)
                 └───────────────────┘
        E. Project management · "Me/Myself/I" meta — placement TBD
```

> ⚠️ The current code has **both** edge-rooms *and* duplicate "Open Models / Develop / Loops /
> Remote / Cloud free tiers" buttons inside Settings → Global. The IA below removes that
> duplication: Settings holds config; the rooms are the destinations.

---

## A. Conversations (left)
Tabs / views:
1. **All chats**
2. **Text only**
3. **Image**
4. **Started** = **Starred / bookmarked** (owner-chosen 2026-06-23 — reuses the existing
   bookmarked set; the tab may be labelled "Starred").
5. **Project view** — chats organised by project.

*Current:* All / Image / Bookmarked tabs exist. New: Text-only filter, the "Started" view,
Project grouping.

## B. Chat (centre)
1. Input text field.
2. **Models in this chat** (visible roster).
3. **Chat bubbles differentiated by model.**
4. **Interaction model**, chosen at the start, *semi-immutable*: changing the chat type
   **forks a new branch** that opens with a **summary of the prior interactions**; otherwise
   unrestricted. The three modes:
   - **Single model**
   - **Mixture of experts** (council — never call it "MoE" in UI, rule 3)
   - **Mixture of models**
   Mixture modes manage participants like a **WhatsApp group chat**: add/remove members later.
   Each **expert** is individually defined — its own memory, instruction set, files. Each
   **model** likewise; the user structures each participant's engagement model.
5. **Image is not a separate pill** — model it like Gemini (attach/generate inline in the one
   composer). **Pattern (from owner's reference shots):** the composer is a single pill —
   `+` · text field · mic · send/voice. The **`+` opens a bottom sheet** with two parts:
   - **Attach row** (scrollable chips): *Photos · Camera · Files* (· Avatar) — attach reference
     media; **no mode switch**.
   - **Tools list** (vertical, icon + title + subtitle): *Images — create & edit · Videos ·
     (Music) · Canvas · Deep Research · …* — these map onto Aarso's **provider types**
     (Image / Video / 3D / Text) and features. Generation is a tool here, not a composer mode.

   So the current separate-`ComposerMode.IMAGE` pill is **removed**; image/video/3D generation
   and file/photo attachment all live behind the one `+`.

*Current:* per-model differentiation + council chips exist; image is a separate `ComposerMode`.
New: interaction-model picker + branch-with-summary on change; group-style participant manager
with per-participant memory/instructions/files; Gemini-style unified composer.

## C. Settings (right) — 5 icon tabs
Providers tabs each **toggle local ⇄ cloud**. Cloud = watched object (rules 1, 2).
1. **Global** (globe): appearance · cloud free tiers + how much is availed · summon-from-anywhere
   · how new conversations start (default) · remote-connection setup · instruments (entropy
   colouring) · git & coding · about · language.
2. **Image providers** (image icon)
3. **Text providers** (text icon)
4. **Video providers** (video icon) — *new capability*
5. **3D-model providers** (sphere icon) — *new capability*

*Current:* Settings is a category list (Global/Text/Image) **and** a launcher. New: 5 icon
tabs; local/cloud provider toggle; Video + 3D provider types (engines don't exist yet);
remove the launcher buttons.

## D. Develop (bottom) — 3 tabs
1. **Launch** — launch objects by **project type** (Android / Linux / Microsoft / …). A
   multi-platform / multi-store target shows fields for each listed platform.
2. **Builds** — each build links out; Android users can **download the APK** in-app
   (install = full-flavor only; Play forbids `REQUEST_INSTALL_PACKAGES`).
3. **Cost** — token cost · manpower cost (per hour) · opportunity cost · etc. (the Cost epic).

*Current:* DevelopRoom has Builds/Branches/Tests + a Cost facet; `BuildsApi`/`CostEstimator`/
`DecisionCost` bricks exist. New: the 3-tab Launch/Builds/Cost framing; platform-typed Launch
objects.

## E. Project management
1. **Tasks** — switchable view: board / Kanban / waterfall / etc.
2. **Notes**
3. **Incidents** — major issues, problems, direction/scope changes, each with an indicator of
   **increase / decrease / unchanged** vs before.

*Current:* ProjectRoom has Kanban/list/notes + `IssueBoard` brick. New: waterfall view;
Incidents with trend indicators. ⚠️ *open: where does this live spatially?* (Top is Tree; the
"Project view" of chats is in A5 — is E the same room as the Project planning room, or
distinct?)

## F. Tree view (centre-up, pinch-out)
1. Pinch-out reveals the tree.
2. **Git-sync indicator** in the tree.
3. **Manual export** of the tree.
4. **Handoff summary** — a summary for handing off to another agent/AI.

*Current:* tree view + `TreeArchive`/`TreeBackup` (Git sync) bricks exist. New: in-tree sync
indicator, explicit manual export, handoff-summary generation.

## G. Loops (centre-down, pinch-in) — 3 tabs
1. **Running**
2. **Retired** — (was "Expired"; owner-chosen 2026-06-23, matches the codebase lifecycle).
3. **Drafts** — (was "Unused"; owner-chosen 2026-06-23 — the editable draft definitions).
4. Tapping an entry → **node view**: show **data movement** between nodes, the current
   node/stage, what's done, and per-node status.

*Current:* lifecycle Unused→Running→Retired + BPMN graph/canvas bricks exist. New: live node/
data-flow visualization (which stage, done/running/blocked, data on edges).

## "Me / Myself / I" — the user meta  ⚠️ placement TBD
Linked to the **Aarso mirror engine** (within-axis self-reflection). Contents:
- **Drift & self-observation** (the Aarso engine). 🚫 **BLOCKED — binding rule 4 / GitHub
  Issue #2.** The §5b drift metric + §5c self-observation engine require the owner's idiolect
  baseline; *do not invent it.* The bounded inert `MirrorLens` seam may exist; the metric may
  not.
- **Linked accounts.**
- **Usage overview** — usage per model, cost per model, total monthly cost, **savings from
  on-device** models, **savings from free tiers vs Claude/GPT**, behaviour patterns (which
  model is used for which task). *(All on-device; usage/cost stores already exist.)*

## Cross-cutting requirements
- **AI-assisted configuration:** every config surface can be **filled / set up / automated by
  AI** (opt-in; the model used is a watched object when cloud).
- **Everything exportable as a freely-usable open object:** each space exports to an open
  format (BPMN for Loops, JSON otherwise). Also export the **entire app config/preferences**
  and **all user data** to the farthest extent possible. (Aligns with tree-sovereignty +
  BPMN-transport decisions already locked.)

---

## Decisions (2026-06-23)
- **Start with:** the **Settings 5-tab restructure** (below, #1).
- **"Me/Myself/I" meta:** build the **shell now** (linked accounts + usage/cost/savings),
  **drift inert** — a visible "pending Issue #2 baseline" placeholder; no invented metric.
- **Loops state tabs:** **Running / Retired / Drafts**.
- **A4 "Started":** **Starred / bookmarked** (reuse the bookmark set).
- **B5 image input:** Gemini `+`-sheet pattern (attach row + tools list); no image pill.

## Still-open questions
1. **E placement** — its own edge? merged with the top Project room? relationship to A5
   "Project view" of chats.
2. **"Me/Myself/I" placement** — a Settings tab, a profile avatar/dock, or its own space?
3. *(owner will add more as we go)*

## Build sequence — status (2026-06-23)
1. ✅ **Settings 5-tab restructure** + on-device/cloud provider toggle (#41).
2. ✅ **Conversations (A)** — All / Text / Image / Starred / Projects (#43).
3. ✅ **Loops (G)** — Running/Retired/Drafts tabs + node status (#44).
4. ✅ **Develop (D)** — Launch / Builds / Cost; platform-typed Launch (#45).
5. ✅ **Chat (B)** — Gemini `+`-sheet composer (#49) + editable council participants (#50).
6. ✅ **Project (E)** Tasks(Board/List/Waterfall)+Incidents (#46); **Tree (F)** sync/export/
   handoff (#47); **Me/Myself/I** shell, drift inert (#48).
7. ✅ Cross-cutting **export everything** (#51). Tree export (#47), Loops→BPMN already.

### Remaining follow-ups (NOT yet built)
- **Chat §B4**: interaction model *immutable with a branch-and-summary on type change*;
  per-member **memory + files**; a unified **model-per-member** roster.
- **Video / 3D engines** (§C) — tabs exist, honest "planned"; no engine wired.
- **AI-assisted configuration** (cross-cutting) — fill/automate any config via a model.
- **Live per-step Loop streaming** (§G) — needs a `WorkflowRunner` progress callback.
- **Naming conflict to confirm**: IA says "mixture of experts"; binding rule 3 forbids that
  UI label, so the council chips stay "Council · personas / models" until the owner overrides.
- **Open placement**: "Me/Myself/I" is provisionally in Settings → Global; its spatial home
  is still the owner's to choose.
