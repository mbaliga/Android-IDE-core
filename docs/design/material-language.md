# Design: The material language — honest physics as the interface

> Status: **vision capture (no code).** Kept deliberately light per the owner —
> this doc now; the full system, the rename of `Aeon`, and any module extraction
> land when the design system becomes its **own repo**. Name: **Hyle** (ὕλη —
> Aristotle's *prime matter*; see *Naming*). This is GPU-shader territory; every
> rendered effect is
> **owner-verified on the target device** (no device or emulator in CI).

## The thesis

Aarso's surfaces should be made of **materials that behave honestly** — and the
plural is the whole point. Not one signature material applied as a uniform skin,
but a *vocabulary* of substances, each chosen because its real-world physics
**says something** the user needs to know. A material is not a look; it is a
sentence. Sand falling tells you time is passing. A drop of water darkening
sandstone and then drying to a fading stain tells you an action is live, then
expiring, then *recently-was*. Dark metal flexing under a press and settling tells
you the surface is alive and felt your touch.

This is the "sovereignty of attention" thesis made tactile. The thesis's claim
is **legibility + cognitive sovereignty**: the user should always be able to *read*
what the tool is doing. Honest materials are legibility you don't have to be taught
— you already know, from a lifetime in the physical world, what wet stone and
falling sand and yielding metal mean. The interface stops *explaining* state in
chrome and tooltips and instead *embodies* it in substance. That is the argument
the artifact makes by existing (*tool-as-argument*, not a chat client with a theme).

## What it is not — the two foils

The pitch sharpens against the two dominant lineages, both of which treat material
as decoration:

- **The metaphorical desktop.** Xerox→Mac→Windows gave us material as *icon*: a
  trash can, a folder, a sheet of paper — a picture *of* a thing, dumbed down to a
  reference. "Flat" then stripped even the picture; Google's Material Design kept a
  drop-shadow as a polite lie (paper that was never paper, that casts a shadow but
  has no edge, no weight, no tear). Material as **signage**.
- **Apple's Liquid Glass.** One material — glass — applied system-wide as a uniform
  surface treatment. Beautiful, and a real step toward physicality, but it is *a
  finish*, not a language: everything is the same substance, so the substance
  carries no differential meaning. Material as **veneer**.

Hyle is neither signage nor veneer. It is **material literacy**: the system knows
*many* materials and deploys each one where its honest behaviour is the most
legible possible expression of that element's state. Glass is welcome — *where
something is actually glass-like* (something you see through, something fragile),
never as the answer to every surface.

## The layer model — refinement as hierarchy

The materials don't sit side by side — they **stack**, and the stack *is* the
information architecture. Depth encodes role: the deeper a layer, the more it is
"the system working"; the closer to your finger, the more it is "yours to act on."
Three layers, fixed in meaning:

1. **Substrate — grainy silica (sand / sandstone).** The background layer, where the
   system does its work. Matte, granular, textured.
2. **Surface — glass.** The chat surface and its content: smooth, polished,
   **translucent**. The keystone of the whole language: **glass is refined silica** —
   the surface is literally the substrate *refined by heat*. Sand and glass aren't two
   materials, they're one material at two grades of refinement, which is why the stack
   reads as coherent rather than collaged.
3. **Controls — ink on glass.** Buttons (the back button, the top actions) are
   **ferrofluid beads** resting on the glass: reflective, dark, inky, beaded up by
   surface tension. They keep the rough silhouette of the locked atoms but **round
   toward droplet form** — that's what an inky liquid on glass *does*. Topmost, most
   actionable.

**Translucency is the load-bearing trick.** Because the glass is translucent, the
substrate's motion **shows through** — which gives two learn-once feedback registers,
keyed to the one thing the user cares about: *is my input affected?*

- **App-wide load / refresh** → a **wave crosses the glass**, briefly un-forming it
  toward sand and re-forming it (refinement run backward, then forward). It *reminds*
  you of an hourglass without being one — the whole surface is reconstituting.
- **Background activity** (nothing that touches your input) → the glass holds steady,
  but the **sand shifts beneath it**, seen softly through the translucency. Ambient,
  subordinate, never stealing the foreground: "something's working back there, carry
  on."

That is the hierarchy goal answered by optics alone — foreground (glass + content +
controls) stays stable and actionable while the background stays *visible but
subordinate*. And it is where the **delight** lives: a surface that refines, ripples,
and beads like real matter is quietly pleasurable in a way a spinner never is —
*provided* the motion stays disciplined (see Open questions).

## The generative principle: honest physics carries state

The discipline that keeps this from becoming costume: **a material earns its place
only by mapping a physical behaviour onto a piece of UI state.** If a substance is
just there to look rich, it's veneer and it's out. Each material is a little
state-machine you already know how to read:

| Material | Honest behaviour | What it *tells you* | Where |
|---|---|---|---|
| **Dark metal / ferrofluid** | magnetic specular that tracks light/tilt; spikes and dimples under a "field," then settles | the surface is **live and reactive**; a press was *felt* and the metal returned | the **control ink** — buttons, beaded on the glass |
| **Light metal** (brushed / anodized) | cooler, matte-to-bright specular; the light-mode body | light theme is a **different metal**, not a white background — the same world, lit differently | light mode |
| **Sand / sandstone** (silica) | granular; falls, sifts, piles, slumps; **shifts beneath the glass** | the **substrate** (background layer); its shift seen *through* the translucent glass is the ambient "background work" cue; progress read by *volume*; overscroll mounds | the working layer beneath everything |
| **Sandstone + water** | porous; a drop wets it dark and glistening, then evaporates, leaving a **damp stain that fades over seconds** | a **transient / time-boxed** action: *live now → expiring → recently-was*. The stain is the surface's short-term memory of what just happened | ephemeral confirmations, time-sensitive offers, "act before this dries" |
| **Glass** (refined silica) | smooth, polished, **translucent**; a wave can pass through and re-form it | the **surface / content** layer; a wave crossing it = app-wide load; whatever shows *through* it = background work | the chat surface |

The water-on-sandstone case is the thesis in one gesture. A countdown bar is an
abstraction you must decode. A drop drying on stone is a countdown you simply *see*
— and crucially it leaves a **trace**, so even after it's gone you can tell it *was
there*. The material does the work of three separate widgets (a live indicator, a
timer, and a recent-history marker) without a single label.

## Persistence of trace — the surface remembers

The damp stain points at a deeper property worth designing *toward*: **materials
have memory.** Stone stains. Metal takes a patina and wears a groove where a thumb
returns again and again. Sand holds a footprint until something disturbs it. A
surface that ages under use is a surface that records its own history — and Aarso
already has a thesis about recorded history: the **append-only message tree** never
forgets a turn.

So there is a natural marriage. The tree remembers in data; the **surface can
remember in material.** A conversation node you return to often could wear a
visible path; a branch you abandoned could gather the patina of disuse; a loop that
ran a hundred times could show it in the metal. This is speculative and last in
line — but it is the principle that makes the whole language cohere: *honest
materials accrete a legible history*, which is the Sovereignty thesis again, now
written on the walls instead of only in the log.

## Material sovereignty — this absorbs the theme engine

The appearance work already planned (the Arc-style mode + accent + texture picker,
Phases A/B of the current plan) is **the first half of this**. Reframe it: the user
isn't choosing a hex accent, they're choosing **their material world**. Dark-metal
or light-metal as the body; how sand-forward the motion is; how strongly the
surface stains and wears. "Make the app *yours*" — the sovereignty-of-appearance
argument — becomes literal when "yours" means the materials you live among, not a
swatch. The `AeonColors`/accent-ramp refactor (runtime-swappable palette, AA-clamped
accent) is exactly the substrate this needs; it stands unchanged. The picker just
graduates from *colour* to *material*.

This also gives binding rule #2 (cloud is a **watched object**) a new, stronger
expression, and it surfaced a **primary axis** of the whole language: **reflective vs
radiant.** Everything local and of-the-earth is **reflective** — ferrofluid, glass,
metal, sand only show the light that falls on them; they are inert until you touch
them. A **watched cloud** provider is the one thing that is **radiant** — it emits its
*own* light, faintly **radioluminescent** (the glow of an old radium watch dial, not
fissile danger): powered from elsewhere, quietly radiating, the thing you keep an eye
on. That self-emission — not a second kind of bead — is what makes "watched" legible in
the substance, and it carries the meaning natively: you *watch* what radiates, and
radium literally lit the dials of *watches*. The badge stays — disclosure governs
defaults, never what's knowable — but now the foreignness is optical: **reflective is
yours and here; radiant is from-elsewhere and watched.** (Open: the exact hue — see
Open questions.)

## Honest constraints (the part with no device under it)

- **Rendering is real shader work.** Gyro-reactive specular, ferrofluid SDF
  metaballs, water absorption + evaporation, granular sand — these are procedural
  **AGSL** shaders (Android 13+; the target device qualifies ✓) driven by sensor and
  time uniforms, not drawables. None of it can be validated here. The plan's
  "2–3 rendering probes on the target device" is now the **gate** before any of this
  becomes the design system: prove gyro-specular, a ferrofluid press, sand-fall, and
  the damp-stain decay as isolated AGSL probes on the actual phone *first*.
- **Performance and battery.** Continuous gyro→GPU loops cost power. No-telemetry
  means we can't measure remotely, so the owner's felt experience is the only
  signal. Budget: effects must idle to zero work when nothing's moving; reduce-motion
  is a first-class path, not an afterthought.
- **Accessibility — non-negotiable, but sequenced (owner).** The WCAG pass is
  deliberately parked for now to let the concept breathe; it returns **soon** (see
  Open questions) — deferred, not dropped. Material state fails accessibility by
  default, so every
  signal a material carries needs a **non-material echo**: the damp-stain countdown
  also exists as an accessible timer/text; specular sheen can *never* be the thing
  carrying contrast (the WCAG AA pass and the accent-ramp clamp still own legibility);
  TalkBack announces "expiring in 4s," not "the stone is drying." Honest physics is
  an *enhancement layer* over a fully legible, high-contrast, reduce-motion-safe base
  — never a replacement for it.
- **Assets stay local.** Grain, normal maps, any material texture are bundled and
  procedural; nothing is ever fetched (binding rule #1).

## Relationship to Aeon and to the rest of Aarso

`Aeon` (the current dark-metal/ferrofluid palette + atoms) isn't discarded — it
becomes **one material world inside the larger language**: the dark-metal body.
The broadening has one immediate consequence worth noting: the earlier ferro-/
magnet-centric codename ideas (Lodestone, Ferro, …) are now **too narrow** — the
system is no longer *about* magnetism; ferrofluid is one material among sand, stone,
water, and light metal. The name has to cover earth-and-substance broadly, which is
why the candidates below moved.

"Keep it light" holds: **no rename, no module extraction, no Compose this round.**
This doc is the capture. The full material system — the rename of `Aeon`, the
extraction to its own `:design`/repo, the shader library — is the work we do *when
it becomes its own repo*, where (the owner's words) we'll make it complete.

## Naming — **Hyle** (decided)

> **Decision (owner): Hyle** (ὕλη; "HOO-lay" / "HY-lee") — Aristotle's *prime
> matter*: the formless substance a thing is made *of*, before it takes form.

It is the most thesis-pure name available, and a *tool-as-argument* name in its own
right. The desktop metaphor and Liquid Glass both begin from **form** — an icon, a
finish — and hang material on it as decoration. Hyle begins from the **matter**:
form is what the material *does*, not a costume laid over it. The obscurity and the
pronunciation speed-bump are real costs, accepted on purpose — this is a system
whose entire claim is that it rewards understanding the substance underneath.

Considered and set aside: **Strata** (geological layers — clear and earthy, but it
names the *arrangement* of matter rather than the matter itself), **Materia** (a
pointed reclaiming of "material" from Google — but the brand proximity cuts both
ways), and **Tactus** (touch — elegant, but names the *sense*, not the *substance*).
The earlier ferro-/magnet-centric ideas (Lodestone, Ferro) are out — the language is
no longer about magnetism.

## Open questions (and one deferral) for the owner

- **Reflective vs radiant — the watched-cloud hue.** Resolved direction: local =
  *reflective* (inert until touched), watched-cloud = *radiant* (self-luminous,
  radioluminescent). **Hue decided: radium** — a *pale*, uncanny yellow-green (the aged
  radium-watch-dial tone; `RadiantHues.RADIUM` in `:hyle`), deliberately not a friendly
  success-green; cold cyan was the runner-up. Keep emission **reserved**: if local
  elements also glow the axis muddies — local liveness stays reflective (specular), only
  from-elsewhere radiates. **UV as an interaction (optional, POWER):** a sweepable
  "inspection light" that makes every cloud-touched / influenced surface fluoresce at
  once — reveal-all-influence on demand — distinct from the always-on radium marker.
- **Bead affordance (mostly resolved).** The bead **keeps the atom's silhouette** (the
  back-button shape, etc.), only rounding toward droplet — and ferrofluid is the right
  reference precisely because it *holds defined form* under a field (spikes, ridges),
  not a passive puddle. Identity rides the retained shape + sculpted form, not a guessed
  glyph. Residual detail: a glyph on a mirror-bright bead still fights the reflection —
  float it *above* the surface or sculpt it into the form, with one fixed control-position
  grammar.
- **Motion: heartbeat, not weather.** Ambient motion isn't necessarily anxious — done
  right it *reassures* (cf. the BlackBerry green LED: a slow blink that just means
  "connected, alive"). The line between reassurance and tinnitus is **rhythm + meaning**:
  a regular, low-amplitude, peripheral *pulse* that means one clear thing calms;
  aperiodic, attention-grabbing, or meaningless churn fatigues. So the rule is
  **heartbeat, not weather** — motion is rhythmic and tied to real state; stillness is
  still the default when there's nothing to say. (Bonus: a slow pulse is far cheaper on
  the battery than constant churn.)
- **Per-element-fixed or user-chosen world?** Likely fixed *semantics* (transient =
  water-on-stone; controls = ferrofluid) inside a chosen *palette/temperature* of
  substances — confirm.
- **Persistence-of-trace** — pursue surface-memory (patina/wear driven by the tree)
  now, or park it as a someday and ship the layer stack first?
- **First probes (updated).** The signature is now the **stack**: probe (1) translucent
  glass over grainy silica with the **wave-reform** load + the **sand-shift-through**
  background cue, and (2) a **ferrofluid bead** pressing/beading on glass. Highest
  meaning-per-pixel, and they test the layer optics directly. (Supersedes the earlier
  hourglass / water-drop pair.)
- **WCAG — deferred by you, on purpose, to revisit soon.** Parked to let the concept
  breathe, *not* dropped. Text on translucent glass over moving grain is the hardest
  legibility case in the system and the first thing to solve on return — likely a
  calm/high-contrast mode (still glass, but the substrate quiets and text rides an
  opaque sub-layer). The non-material-echo rule still stands.
