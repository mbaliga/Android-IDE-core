# Design: Progressive disclosure — managing complexity without betraying the thesis

> Status: **decided (owner) — headless foundation built.** The tier model
> (`domain/disclosure/Disclosure.kt`) and the persisted pref (`SessionStore`) are in;
> the onboarding intent step and the room-gating are next (UX owner-driven; mock first).

## The problem

Aarso has grown: chat, images, the tree, models depth, the theme engine, Git/coding,
and now Loops (the workflow builder + escalation). A new user dropped into all of it
is lost; but the whole point of Aarso is **legibility** — making routing, cost, and
cloud-influence *visible*. So we cannot solve complexity by *hiding* the machinery.

## The guardrail (non-negotiable)

> **Disclosure governs what is shown by default — never what is knowable.**

"Simple" = fewer entry points and gentler defaults. It must **never** make the app
opaque. Whenever something is actually happening that involves routing, cost, or a
watched-cloud call, it is surfaced (collapsed, expandable) **at every tier**. A simple
mode that hid the watched-object marker would turn Aarso into the generic chat client
it is defined against — so signals like `Surface.WATCHED_BADGE` are marked
**mandatory** and cannot be switched off.

## The decision (owner)

Of the three options floated — (1) a simple/advanced switch, (2) a separate "lite"
app, (3) an init wizard — the answer is **3 + 1, unified by tiers; not 2.**

- **Not a separate lite app.** A minimal Aarso *is* a generic chat client (the thing
  the project rejects), and it doubles maintenance for a solo developer. It also must
  not be conflated with the `full`/`play` build flavors, which exist for **Play
  policy**, an orthogonal concern.
- **A wizard sets the starting point; a depth dial changes it.** These are not two
  mechanisms — the wizard simply *sets* the dial. The dial lives in Settings so the
  wizard is never a one-way door.

## The tier model (built)

Three tiers (`DisclosureTier`), each a superset of the last:

| Tier | Reveals |
|------|---------|
| **CORE** | one chat, on-device default, the **watched-cloud badge**, basic settings |
| **STUDIO** | + Images, Models depth (coverflow/BYO), the Tree map, the theme engine |
| **POWER** | + Loops, Git & coding, council defaults, voice, expanded instruments |

- `Surface` enumerates the gateable rooms/capabilities, each with its `minTier` and a
  `mandatory` flag (mandatory ⇒ always on, never hidden).
- `Disclosure.isRevealed(surface, tier, overrides)` is the single decision point.
- `DisclosureOverrides(enabled, disabled)` lets a tinkerer switch one surface on early
  or off — except mandatory ones.
- `Intent` (`PRIVATE_CHAT` / `CHAT_AND_IMAGES` / `POWER_USER`) is the first-run
  question; `Disclosure.tierFor(intent)` maps it to a starting tier.
- Persisted as a string in `SessionStore.disclosureTier`, **defaulting to `POWER`** so
  existing installs (the owner + testers, all power users) see **no change**; only new
  users go through the wizard. JVM-tested (`DisclosureTest`).

## Next (UX owner-driven — mock before code)

1. **Onboarding intent step** — extend `OnboardingScreen` with the "what do you want
   Aarso for?" card that sets the tier (+ keeps the existing RAM-fit starter-model
   step). Teaches the sovereignty framing while it configures. _(Mock first.)_
2. **A depth dial in Settings → Global** — move tiers any time; advanced toggles for
   individual surfaces.
3. **Gate the rooms/affordances** by `isRevealed(...)` — nav entry points, palette
   bricks, instruments. Collapse, never amputate; mandatory signals always present.

Binding rules still hold: no telemetry; on-device default; the watched marker is a
thesis invariant; both flavors stay green.
