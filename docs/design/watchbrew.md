# WatchBrew — the wrist surface (design, v1)

> Owner directive (2026-10-13): "extend/expand/develop this further into WatchBrew… the first
> target will be Wear OS. If WatchBrew works well on Android, we should expand it to Apple
> Watch as well." This document is the ratified-by-default plan — every DECIDED line below is
> an engineering call the owner can override with one sentence; the OWNER lines need their word
> before anything ships beyond the dev branch.
>
> Naming: **WatchBrew** is the user-facing name of the watch app (launcher label "WatchBrew",
> same register as Fonebrew). It is NOT Studio's "Watch" feature (store-review watching,
> `studio-pm` `domain/watch/`) — the two share a word and nothing else.

## 1. What WatchBrew is (v1 thesis)

A **sovereign remote surface for Fonebrew on the wrist** — not a second computer. The phone
remains the single place where inference runs, keys live, and the message tree grows. The watch
is a legible window onto it: ask from the wrist, see the answer stream, check tasks and loop
runs at a glance. Where a fork exists, same law as the host: make routing/influence visible,
keep the user in the loop.

**DECIDED — companion-first.** No on-watch inference in v1 (watch RAM/thermals make local
models dishonest toy demos), and **no cloud calls from the watch, ever in v1** — the watch
never holds an API key (binding rule 5 extends to the wrist: keys live in the phone's Keystore
only). Everything the watch shows arrives over the local link from the phone.

**DECIDED — the watched-object rule reaches the wrist.** When the phone answers a relayed
prompt with a cloud engine, the watch reply carries the watched-object glyph exactly like the
phone UI. On-device replies carry none. The wrist never hides who answered.

## 2. Architecture (three pieces, one repo)

All in `mbaliga/Android-IDE-core` — no new repo needed (repo split is an owner decision for a
later stage, same as Sprint R).

```
:watch-bridge     pure-Kotlin Gradle module — the wire contract. Versioned envelope +
                  org.json codecs in the domain/contracts EnvelopeCodec idiom. No Android
                  deps, exhaustively JVM-tested. Both sides consume it; nothing else does.
:core-engine      gains data/watch/WearRelayService — phone side. Listens on the Wearable
                  Data Layer (MessageClient for requests, ChannelClient for streamed
                  replies), bridges to the existing engine path (real token usage, honest
                  estimated flag), TaskStore, and GraphRunLedger. Lazy DI seam in
                  AppContainer; library-manifest service entry.
:watchbrew        the Wear OS app (com.android.application, Compose for Wear OS).
                  Depends ONLY on :watch-bridge + androidx.wear.compose + play-services-
                  wearable. It must NOT depend on :core-engine — that would drag ~130 MB
                  of native engine libs and the whole substrate into a watch APK.
```

**DECIDED — transport is the Wearable Data Layer** (`com.google.android.gms:
play-services-wearable`). It is device-local (Bluetooth/Wi-Fi between paired devices), not
telemetry — rule 1 is about phoning home, and nothing here leaves the pair. The license gate
already admits GMS artifacts ("Android Software Development Kit License" scoped to
`com.google.android.gms:.*` in `config/allowed-licenses.json`), so `checkLicense` stays green
with no allowlist edit. Honest caveat, recorded not hidden: the Data Layer requires Google
Play services on both devices and requires **matching applicationIds** phone↔watch — which
every Wear OS device has, and which constrains nothing the full-sideload stance cares about. A
GMS-free transport (raw BLE GATT) is a real alternative and a real project; parked as a named
follow-up, not silently dismissed.

**Consequence of matching appIds:** `:watchbrew` declares the same `dist` flavor pair as the
phone app — `full` → `dev.aarso.full`, `play` → `dev.aarso` — and must be signed with the same
key as the phone build it pairs with. Watch version spine starts fresh at **0.1.0 (1)**
(different device, independent monotonic line).

## 3. v1 surfaces (all shape/label-coded — the colorblind rule applies on a 1-inch screen more,
not less)

1. **Ask** — the chat relay. Input via RemoteInput (voice or keyboard). The reply streams
   token-by-token over a ChannelClient channel; engine identity + watched-object glyph shown
   with the reply; a relayed turn lands in the phone's message tree like any other turn
   (append-only — the watch mints nothing retroactively).
2. **Tasks** — the free floor's TaskStore at a glance: list, check-off (single writer stays
   the phone; the watch sends a toggle request, the phone's store applies it). Convert→Task
   output from the phone shows up here — the wrist is where that row earns its keep.
3. **Loops** — read-only run glances: state glyph, step name, and the run's real spend from
   GraphRunLedger (real counts only; `estimated` shown as such — the engine never invents a
   number, so neither does the watch).
4. **Tile + complication** — one Tile (Ask + last reply snippet) and one complication
   (pending-task count). Monochrome Fonebrew glyph; the B/W brand mark finally has a home
   that is monochrome by nature.

**Connection honesty:** phone unreachable is a first-class rendered state (material/motion
primary per Hyle's law, text on demand) — never a spinner that implies progress.

**Hyle on the wrist:** Wear Compose has its own component system; v1 maps Hyle **color tokens
only** (ink/paper/brew accent ramp) through a small theme bridge in `:watchbrew`. Porting Hyle
*components* to Wear is explicitly deferred — a named follow-up for the Hyle repo, not a
silent fork (single-source rule).

## 4. What v1 deliberately leaves out

Council editing, tree surgery, branch/restore, the IDE, image/3D — all phone work. The watch
reads and asks; it does not administer. Per-member council files, loop editing, settings
mirroring: out. Standalone mode (watch-only, no phone): out — it would force keys or models
onto the watch, and both are ruled out above.

## 5. Apple Watch (gated follow-up — owner's gate, by their own words)

Gate: **owner verifies WatchBrew on a real Wear OS device first.** Then watchOS needs: a macOS
host + Apple developer account (the porting program has already chosen the paid Apple Developer
Program and an Apple-silicon Mac mini — chosen, not yet bought), SwiftUI UI (no Compose on
watchOS), and a phone-side host — Apple Watch cannot pair with an Android phone at the OS
level, so an Apple Watch WatchBrew realistically rides on the **iOS Fonebrew port the porting
program already schedules (~20 weeks, after Ubuntu Touch and Linux)**. `:watch-bridge` stays
pure Kotlin precisely so a Kotlin Multiplatform conversion (`watchosArm64` target, program's
KMP pins: Kotlin 2.1.20/2.2.21) can carry the contract to Swift later without a rewrite.

## 6. iOS / iPadOS / Ubuntu Touch (status answer, recorded here so the question has one home)

- **A real porting program exists; nothing is built.** Branch
  `claude/multi-platform-porting-plan-nnvjug` (2026-10-06/07, draft PRs in all nine repos —
  Personal-Tracker #10 carries the 1,033-line `PORTING_PROGRAM.md` + per-platform files; this
  repo's copy is `docs/PORTING_PLAN.md` on that branch). Its own status line: "PLAN… Nothing…
  built, run on a device, signed or submitted."
- **Owner rulings already recorded there:** "native apps on all mobile devices, without
  exception"; order **Ubuntu Touch → Linux → iOS/iPadOS → macOS → Windows**; Fonebrew core
  estimated at 24 weeks (UT, native-only — Waydroid rejected) and 20 weeks (iOS); Studio 7/6
  weeks; macOS/Windows no-port for core. Hardware: a OnePlus 6 for Ubuntu Touch (20.04 only),
  an iPad Pro M4 in hand, Mac mini chosen-not-bought, paid Apple Developer Program chosen.
  Still open there: OQ-36 (LGPL Qt vs Fonebrew's linking stance; Studio's paid unlock under
  store IAP rules), and directive amendments PROPOSED-1..4 (drafted, not in force).
- WatchBrew is **new scope on top of that program**, consistent with its "all mobile devices"
  ruling; it does not reorder the program's UT-first sequence — Wear OS is Android, so it
  rides the existing toolchain, not a port lane.
- **Ubuntu Touch remains a rewrite** in any case (no ART/JVM; Qt/Lomiri) — the program prices
  it accordingly rather than pretending it's a port.
- The only other cross-platform intent on record is **Orrery's** (registry IDEAS.md,
  2026-10-12): a thin six-platform *workspace* syncing through a git repo — different app,
  still OPEN awaiting the owner.

## 7. Verification honesty

The build container has **no watch, no emulator, no paired phone**. Machine-verified: the
`:watch-bridge` codecs (round-trip + malformed-input), relay request/response builders,
presenters, and both flavors compiling through the gate. Owner-verified only: pairing, voice
input, streaming feel, Tile/complication rendering, battery behavior. A `docs/watch/
OWNER_VERIFY.md` checklist ships with the module.

## OWNER decision points (one sentence each; defaults in force on the dev branch meanwhile)

1. Data Layer (GMS, decided default) vs sovereign BLE transport (big follow-up) — ratify or redirect.
2. Watch surfaces of v1 (Ask/Tasks/Loops/Tile) — trim or extend.
3. Apple Watch sequencing — accept that it realistically waits on an iOS host decision.
4. Whether WatchBrew ever gets its own repo (today: module in core).
