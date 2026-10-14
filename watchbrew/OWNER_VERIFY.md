# WatchBrew (watch app) — owner verification checklist

Everything below needs **a real Wear OS watch paired to the phone running the matching Fonebrew build**.
The build container has neither, so none of it is machine-verified, and none of it is claimed. Each line
says what to look for and what "wrong" looks like. This file is the **watch app's half**; the
phone-side relay's half belongs with the relay (the design doc plans a `docs/watch/OWNER_VERIFY.md` for it —
check whether it exists yet).

## 0. Before anything: same applicationId, same key

- [ ] Install **the same flavor of both**: `full` phone (`dev.aarso.full`) with `full` watch, or `play`
      with `play`. A cross-flavor pair is a different applicationId and the Data Layer will deliver nothing.
- [ ] Both APKs were signed by **the same keystore** (debug builds from one machine share its default
      debug keystore; a CI phone APK + a laptop watch APK do **not**). Symptom of a mismatch: both apps
      install and run, and the watch says "Phone not reachable" / "No answer yet" forever.
- [ ] The watch app's version line is `0.1.0 (1)`.

## 1. Link states (the first-class rendered states)

- [ ] Phone nearby, Fonebrew running: Home shows **"Phone linked"**; after a moment the detail reads
      **"Fonebrew <version>"** (the hello arrived).
- [ ] Phone linked but Fonebrew force-stopped: Ask → send → after ~45 s **"No answer yet"** (not a spinner
      forever). Open Fonebrew on the phone and ask again: it works.
- [ ] Phone out of range / Bluetooth off: Home shows **"Phone not reachable"** with its own broken-link
      shape; Ask shows the same panel with **"Check again"**; Tasks shows the last list **with its age**
      and offers no toggles. Bring the phone back, tap Check again: states recover.
- [ ] Mismatched versions (e.g. a phone build with a different contract version, if you have one): **"Versions
      don't match"** naming which app to update.
- [ ] None of these states is conveyed by colour alone: each has a distinct **shape** and **words**.

## 2. Ask

- [ ] Tap Ask → the system voice/keyboard input opens; speak a prompt; it is sent.
- [ ] "Asking your phone. Waiting N s." counts up. The reply streams in; the text grows. **Feel:** is the
      streaming smooth enough on a wrist? (latency per chunk is the thing we cannot measure here.)
- [ ] **On-device model:** reply shows the house shape + **"on-device"** and the engine id.
- [ ] **Cloud model (watched):** reply shows the cloud shape + **"cloud · watched"**. If you can arrange a
      reply that mixes on-device and cloud chunks, it must show as watched.
- [ ] The finished reply shows the usage line: engine-reported counts plain; an estimate as `est. in ~N · out ~M`;
      unknown as "unknown" (never `0`).
- [ ] The relayed turn appears in the phone's conversation like any other turn.
- [ ] Walk the phone out of range mid-reply: the screen says **"Phone link lost"** with the partial text
      labelled "Partial — not a finished answer" — never a finished-looking answer.
- [ ] A long reply scrolls (it is split into screen-sized pieces); nothing is cut off.
- [ ] Reply streaming through the listener service: a long reply on a real watch is not killed mid-stream
      (the read blocks inside the `WearableListenerService` callback — this is the part most likely to need
      adjusting).

## 3. Tasks

- [ ] The list matches the phone's tasks; each row has a shape (to do / doing / blocked / done) **and** the word.
- [ ] Tap a to-do row: it shows **"Asking phone to mark done…"** and **keeps its old shape**; within a second or
      two the phone's store changes and the row flips to done. (If the row ever flips *before* the phone does,
      that is a bug — the watch must not be optimistic.)
- [ ] Switch the phone off mid-toggle: after ~15 s the row says **"No confirmation from phone"** and still shows
      the last confirmed state.
- [ ] A task completed on the phone appears on the watch (a snapshot push) without opening the watch app, if the
      relay pushes on change.
- [ ] A phone with many tasks: the header count is the phone's true pending count and the footer says
      "+ N more pending on the phone" if the list was truncated.

## 4. Loops

- [ ] Start a loop on the phone: a glance appears with a state shape + word, the step, the spend line and its age.
- [ ] On-device run: spend reads a real `0.00`. Estimated spend reads `~… (estimated)`. A run with no ledger
      figure reads "not recorded".
- [ ] A state key this build does not know (a newer phone) shows the generic diamond with the key as its label.

## 5. Tile and complication

- [ ] Add the **Ask Fonebrew** tile: shows the last reply snippet, its on-device / cloud-watched marker and its age;
      tapping opens Ask. Check the vector provenance glyphs actually render in the tile (they are vectors loaded
      through ProtoLayout; if they show blank, that is the known risk and the fix is a PNG fallback).
- [ ] Add the **Pending tasks** complication to a watch face: shows the count; `--` before any data; a trailing
      `?` when older than an hour; `99+` above 99. Tapping opens Tasks. Check it updates when a snapshot arrives.
- [ ] The launcher icon is the monochrome Fonebrew mark (the drink-on-a-lanyard), not a placeholder.

## 6. Battery / background

- [ ] Leave it installed for a day: no unexplained battery drain. (The complication has no update period and the
      app polls nothing; the only wake-ups are Data Layer deliveries and the app being open.)

## 7. Known not-done (so it is not mistaken for a bug)

Ambient / always-on rendering, tile-carousel preview art, R8 shrinking, a Hyle wear target — see the module
README's "Deliberately deferred".
