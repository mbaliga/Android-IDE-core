# Design: Coding assistant — Aarso on your repos

> Status: **design** (a Settings → Global → "Git & coding" entry is stubbed). The
> owner's intent, clarified: *"integrate with GitHub / Gitea etc. so I can enter my
> git ID, work on my repos, branches, etc. — whatever I do in Claude Code, but on
> Aarso, using the Claude API."* This is **not** about syncing Aarso's conversation
> tree to a repo; it's Aarso acting as a **coding assistant** on the user's
> repositories.

## Why

The owner already drives a coding loop with a Claude-Code-style assistant against
their Git repos. They want that on Aarso: connect a Git host, pick a repo and
branch, and run the same objective→propose→review→refine loop on real code — with
the **Claude API** (a watched cloud provider) as the engine instead of a desktop
tool. It is the concrete, code-shaped instance of the council/workflow vision in
`council-workflows.md`.

## Shape

1. **Connect a host.** Settings → Global → Git: kind (GitHub / Gitea / GitLab /
   Generic), base URL, git identity (name/email), and a token. Marked **watched**
   (opt-in, visible). Token in `security/KeystoreSecret.kt`, sent only to that host.
2. **Browse.** List the user's repos, branches, and a file tree (host REST API or a
   shallow clone).
3. **Work.** Open files, ask for changes; the assistant proposes a diff, the user
   reviews (human-in-the-loop at every step — the thesis), then commit + push to a
   branch / open a PR via the host API.
4. **Loop.** The edit cycle reuses the workflow engine (`council-workflows.md`):
   an expert proposes a patch, a critic (ideally a different model) reviews against
   the task and any tests, refine until the user approves — "propose a look" for
   code.
5. **Engine — provider-generic.** The loop is **engine-agnostic**: every node runs
   through `InferenceEngine`, so the assistant is driven by whatever **watched cloud**
   provider the user wires up (Claude API, any OpenAI-compatible endpoint — incl. a
   **self-hosted open model**), with on-device models assisting the cheaper steps
   (drafting, summarising) where they're capable. No vendor is special-cased (binding
   rule #2). The owner's MiMo (Xiaomi) question is analysed under *Model engine* below.

## The Lens — code made legible for non-coders (owner's brief)

For a non-technical owner, "review the diff" is necessary but not sufficient — you
also have to be able to *read what is already there*. The **Lens** is a draggable
"filter screen": a translucent frame you pass over a file; the lines beneath it stay
crisp while the rest dims, and a stable card reads back, in 2–3 plain sentences with
no jargon, what those lines do. It is the legibility thesis made literal — turning
code into something a non-coder can understand without learning to code.

- **Built (headless):** `domain/codelens/CodeLens.kt` (`CodeLensTest`) — `explain(lines,
  ext, generator)`: skips blank windows, caps the snippet, maps the file extension to a
  language hint, and prompts a plain-English, no-code, no-jargon reading. Model-agnostic
  (the caller passes the `Generator`); on-device by default, a watched provider when the
  user opts in. **The same engine surface, two directions:** the Lens *reads* code, the
  diff *reviews a change* — together, comprehension before and after.
- **Built (UI, device-unverified):** `ui/codelens/CodeLensScreen.kt` — the draggable
  frame + dimming + a debounced call (settles before "interpreting", so a drag never
  fires a model call per frame — cost/latency-aware) + a watched badge when the
  interpreter is cloud. Gesture/scroll feel is owner-verified (no device in CI). A
  feel-able **`LensProbe`** also ships in the standalone Hyle Probe app (sample file +
  canned reading, no model needed) so the interaction can be tried on device now.
- **Open (owner decides):** where the Lens lives (its own surface, or a mode inside the
  file view) and whether it auto-reads on settle or only on tap.

## Auth

**PAT first** (host-generic, simplest; stored in `KeystoreSecret`). Device-flow
**OAuth later** for GitHub/GitLab as a nicety. Git identity (name/email) is config,
not a secret.

## Binding constraints (must hold)

- **No telemetry.** The app talks only to (a) the user's Git host and (b) the
  user's chosen model provider (Claude API = watched). Nothing else.
- **Watched cloud.** Using the Claude API for coding is opt-in and visibly watched,
  never a hidden default. On-device stays the default everywhere else.
- **Human-in-the-loop.** No silent commits/pushes; the user reviews every diff and
  approves the push. Legibility over convenience.
- **Keys in the Keystore.** Git token and model API key both; never logged, sent
  only to their own host.
- **Never "MoE"** for the underlying multi-model loop — council / experts.

## Build order (proposed)

1. ✅ Host connection + identity + token (`data/GitHostStore` + Settings → Global →
   Git & coding; token in `KeystoreSecret`), PAT auth.
2. ✅ Repo/branch/file browse (read-only) via the host REST API (`data/GitBrowse`
   over `domain/git/GitContentsApi`; per-host "Browse"). Network owner-verified.
3. Single-file edit → **review diff** → commit/push to a branch.
   - ✅ **Review-diff primitive built (headless):** `domain/diff/LineDiff.kt`
     (`LineDiffTest`) — an LCS line diff that emits **standard unified diff** (the
     `--- / +++ / @@` format git and every patch tool read), with hunks/stat for a
     glanceable "+n −m". Prefix/suffix-trimmed so a one-line edit in a big file
     stays cheap; a safety valve caps the table on-device. This is the human-in-the-
     loop review surface — you see exactly what changes before anything is committed.
   - ✅ **Commit path built:** `data/GitEdit.kt` (`GitEditTest`) — `open` reads a file
     *with its blob sha*, `unified`/`preview` describe the change locally (via
     `LineDiff`, no network), `commit` PUTs the approved text with that sha on the
     host's branch and returns the new commit sha. Refuses a no-op (never an empty
     commit); no silent pushes — the diff is shown and approved first. Request shape +
     sha threading are JVM-tested against a fake `GitTransport`; the live round-trip is
     owner-verified.
   - Next here: surface it in the UI (open a browsed file → edit → see the diff →
     approve → commit), and let the model propose `newText` (step 4's loop).
4. The agentic loop (reuse `WorkflowRunner`): propose-patch → critique → refine,
   gated on tests + human approval (the escalation matrix, already built).
5. PR creation; multi-file changes; OAuth.

## Decisions (owner)

- **Auth: PAT now**, device-flow OAuth later.
- **Transport: the host REST contents API** (GitHub / Gitea / Forgejo / GitLab),
  not JGit. Rationale: the sovereignty property is in the *destination* (real
  commits, open-format files, in the user's own repo — clone-able and readable by
  any tool), which is identical either way; the API is far lighter. The only thing
  the API can't reach is a *bare* self-hosted git daemon with no REST layer (a
  niche — all mainstream self-hosting uses Gitea/Forgejo/GitLab, which have APIs).
  JGit stays documented as a drop-in fallback if that need arises; because both
  produce identical commits, swapping later is invisible to the user.

## Decided / resolved

- **Autonomy gates** are the **escalation matrix** from `council-workflows.md`
  (`domain/council/Escalation.kt`): cost-budgeted gates from agent → team members →
  the user as terminal authority; test/CI status widens autonomy. Not a per-feature
  setting — one rule engine, shared with the workflow side.

- **Where tests run (decided):** *size-routed*. Grade the quantum of work; a tiny
  pure script runs **on-device**, anything larger reads the repo's **own CI**
  (GitHub/Gitea Actions) via the host API. A **human gate is the universal
  fallback** — and since the owner's repos may not have CI yet, the human gate
  leads in v1; the CI signal is best-effort and widens autonomy when green.

## Model engine — provider-generic, with MiMo (Xiaomi) analysed (June 2026)

The owner asked specifically about **MiMo (Xiaomi)** — recently open-sourced and
reported at/above Claude Code on long-horizon tasks — as the engine. The honest
finding: "MiMo" is **two different things**, and only one path fits a phone app.

**1. MiMo Code — the harness (the actual "Claude Code rival").** A terminal coding
*agent* (a fork of **OpenCode**; Node/npm; **MIT** + `USE_RESTRICTIONS.md` + Xiaomi
ToS). It genuinely beats Claude Code on ultra-long (200+ step) tasks per its own
benchmarks. But it is a **terminal/Node application — not embeddable in an Android
app.** Aarso's equivalent is its *own* native loop (`WorkflowRunner` + escalation
gates, `council-workflows.md`). It's a useful **design reference** (persistent memory,
subagents, plan/build/compose modes) and it speaks **OpenAI-compatible** APIs — which
matters in (3) — but it is not something Aarso embeds.

**2. The MiMo models, on-device — infeasible.** `MiMo-V2-Flash` is a **309B-total /
15B-active MoE**; a 4-bit quant is **~174 GB**, and MoE needs *all* experts resident
(the active-param count cuts compute, not memory). That's ~10× a high-end phone's
unified RAM; `MiMo-V2.5`/`-Pro` are flagship-scale too. So the "frontier coding
on-device" dream is **not** reachable with MiMo. On-device coding needs a *small*
coder (Qwen-Coder-7B/32B class, or a future small MiMo) at materially lower quality —
a real but separate option, fine for the cheap steps only.

**3. The MiMo models as a watched cloud provider — ✅ the fit.** The weights are
**open (MIT, per the HF model card)**, **256k** context, with strong coding numbers
(**SWE-Bench Verified 73.4**, post-trained), served over an **OpenAI-compatible API**
(Xiaomi's MiMo Platform / the free "MiMo Auto" channel / **self-hosted**). Aarso
already has a **provider-generic OpenAI-compatible engine** (`inference/cloud/`), so
MiMo slots in as **just another watched cloud provider — likely config only** (base
URL + key + a catalog entry), no new engine and no vendor branch.

**The sovereignty angle (why open weights matter here).** Because the weights are
open, a sovereignty-minded user can **self-host the endpoint** (own box / rented GPU)
and point Aarso's OpenAI-compatible provider at *their* server — Aarso then talks only
to a model the user controls. It's still off-device, so it stays a **watched object**,
but it's *your* watched object: closer to the on-device ideal than any closed API,
without the phone-RAM wall.

**Recommendation.**
- Keep the loop **engine-agnostic** (already the design).
- Add **MiMo as a watched cloud provider through the existing OpenAI-compatible
  engine** — config + a catalog entry; verify the endpoint shape on device.
- Document **self-hosting MiMo** as the sovereignty path.
- Keep **on-device** for cheap steps with a small coder; don't promise frontier
  on-device coding.
- Honest caveats: the harness/ToS carry **use restrictions** (read
  `USE_RESTRICTIONS.md` + Xiaomi ToS before any commercial use); the free "MiMo Auto"
  channel and the Xiaomi-hosted endpoint are **watched cloud** (data leaves the
  device) and must be surfaced as such; "beats Claude Code" is the vendor's claim,
  owner-verified in practice.

Sources: [VentureBeat — MiMo Code](https://venturebeat.com/technology/xiaomis-new-open-source-agentic-ai-coding-harness-mimo-code-beats-claude-code-at-ultra-long-200-step-tasks),
[MiMo-Code (GitHub)](https://github.com/XiaomiMiMo/MiMo-Code),
[MiMo-V2-Flash (GitHub)](https://github.com/xiaomimimo/MiMo-V2-Flash),
[MiMo-V2-Flash (HuggingFace)](https://huggingface.co/XiaomiMiMo/MiMo-V2-Flash).

## Open questions for the owner

- Scope of v1: single-file edits on one repo, or full multi-file branch workflows.
- MiMo: use Xiaomi's hosted endpoint (watched, easiest) or stand up a **self-hosted**
  endpoint (max sovereignty, needs a GPU box)? Either way it's the same OpenAI-compatible
  provider in Aarso.
