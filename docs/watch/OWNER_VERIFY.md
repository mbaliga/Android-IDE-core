# WatchBrew v1 — owner verification checklist (end to end)

> **Honest zero.** As of 2026-10-14 **no box below has been ticked**, by anyone. The build container
> has no watch, no emulator and no paired phone, so nothing in this file is machine-verified and none
> of it is claimed. What *is* machine-verified (the wire codecs, the relay presenter, the watch
> presenters, both flavors compiling and passing) is listed in `docs/STATE.md` section 12; it proves
> the logic, not the radio, the screen or the battery.
>
> Every box starts unchecked. There is deliberately no running "N of M" tally in this file (it would
> go stale the moment a box is ticked): to see what has been verified, `grep -n '\- \[x\]'`.

This is the **end-to-end pass, in the order you would actually do it**. It owns the phone side and the
pairing. The per-screen, watch-side detail (every rendered state and its wording) lives in
[`watchbrew/OWNER_VERIFY.md`](../../watchbrew/OWNER_VERIFY.md); use it alongside this one rather than
duplicating it. Design law for all of it: [`docs/design/watchbrew.md`](../design/watchbrew.md).

**You need:** your arm64 phone with Google Play services; a Wear OS 3+ watch (the app's `minSdk` is 30)
already paired to that phone through the Wear OS companion; `adb` reach to both (the watch via
Developer options, Debug over Wi-Fi); and the two APKs, built together (section 0).

---

## 0. Pair and install — the signing trap

The Wearable Data Layer only delivers between a phone app and a watch app with the **same
applicationId and the same signing key**. A mismatched pair installs cleanly, runs, and then exchanges
nothing: expect the watch to say "Phone not reachable" / "No answer yet" forever. That is a *predicted*
symptom from the Data Layer's rules, not one observed here.

- [ ] The two APKs you are installing were **built together, from one container, in the same session**:
      `fonebrew-sd.apk` (phone) and `fonebrew-watch.apk` (watch), both the **`full`** flavor
      (`dev.aarso.full`). Neither was refreshed alone. (The WatchBrew closeout lane did **not** build
      or publish either APK to `apk-dist`: the phone's native cross-compile was not attempted there
      and the container has no CMake. The first pair on `apk-dist` must be built together; see
      `CLAUDE.md` "APK delivery". The watch APK builds with `./gradlew :watchbrew:assembleFullDebug`,
      output `watchbrew/build/outputs/apk/full/debug/watchbrew-full-debug.apk`, published as
      `fonebrew-watch.apk`.)
- [ ] Their signing certificates match. On a machine with the Android build-tools:
      `apksigner verify --print-certs fonebrew-sd.apk` and the same for `fonebrew-watch.apk`; the
      `certificate SHA-256 digest` lines are **identical**. If they differ, stop: rebuild both from one
      machine and reinstall both.
- [ ] Phone: install `fonebrew-sd.apk`. Watch: `adb -s <watch-address> install -r fonebrew-watch.apk`.
      Both report the package `dev.aarso.full` (`adb shell pm list packages dev.aarso`).
- [ ] You did not mix flavors (a `full` phone with a `play` watch is a different applicationId: the
      Data Layer delivers nothing).
- [ ] Open Fonebrew on the phone once and make sure a model is chosen (section 1 needs one).
- [ ] The watch launcher entry is labelled **WatchBrew** with the monochrome Fonebrew mark (the
      drink-on-a-lanyard), not a placeholder, and its version line is `0.1.0 (1)`.
- [ ] Open WatchBrew. Home shows **"Phone linked"**; after a moment the detail reads
      **"Fonebrew <version>"** (the phone's hello arrived — proof both directions work).

## 1. First Ask — on-device engine

Precondition: the phone has a downloaded on-device model (or on-device Gemini Nano) active.

- [ ] Tap Ask, speak a prompt (voice input) — it is sent. (Also try the keyboard path once.)
- [ ] The watch shows "Asking your phone. Waiting N s." counting up, then the reply **streams in** and
      the text grows. **Feel check, yours alone to judge:** is it smooth enough on a wrist, or does it
      stutter / arrive in lumps? Note chunk cadence if it bothers you.
- [ ] The reply carries the **on-device** house marker (a shape **and** the words "on-device") plus the
      engine id, and **no** watched-object marker.
- [ ] The finished reply's usage line is honest: engine-reported counts plain; an estimate reads
      `est. in ~N · out ~M`; unknown reads "unknown" — **never a bare `0` for something unknown**.
- [ ] The **last words** of the reply arrive (the channel tail is not cut off). Try one long reply:
      it scrolls in screen-sized pieces and nothing is missing.
- [ ] A long reply is **not killed mid-stream** (the stream is written inside the phone's listener
      service callback; this is the part most likely to need adjusting on a real device).
- [ ] **On the phone:** Conversations now has **"Wrist · WatchBrew"**, opening with a visible system
      line saying where it comes from, then your prompt and the reply as ordinary turns. You can open
      it, branch it and continue it from the phone like any chat; nothing about earlier turns changed.
- [ ] Ask a second question: it **continues the same Wrist conversation** (appended under the tip), it
      does not start a new one and it does not rewrite the first turn.
- [ ] With the phone's screen **off and Fonebrew backgrounded** (the normal case: phone in a pocket),
      an Ask is still answered. If it stalls or the process dies with a large local model resident,
      that is the open question "foreground-service start from a Data Layer callback" (Android may
      refuse it; the code then runs without the boost) — record what you see.
- [ ] While **the phone's own chat is generating**, send a wrist Ask. Neither may corrupt the other or
      crash; the wrist Ask either answers or gets a plain-sentence refusal (this is the open question
      "concurrent chat and relay on the one local engine").
- [ ] A **second Ask while one is still being answered** is refused with a plain sentence, not silently
      queued.
- [ ] (Optional, destructive) With **no model set up** on the phone, an Ask gets the fixed sentence
      "No model is set up on the phone yet. Open Fonebrew there to choose one." — not a spinner, not an
      exception text.

## 2. First Ask — cloud engine, and the watched-object glyph

**Read this first, it changes what can be checked.** The v1 phone relay answers wrist questions with an
**on-device model only**. It resolves the model through the phone's own `DefaultModelPolicy`, which by
binding rule 2 *never* resolves a cloud model ("cloud is opt-in per use, never an ambient default"): a
question typed on a watch arrives while the phone is in a pocket, with no screen to confirm "send this to
a cloud provider". The cloud path (the `watchedObject` flag on every chunk, provider usage, the
glyph on the wrist) is built and exercised in tests **with a cloud-shaped fake only**. Turning it on is a
deliberate follow-up with its own owner decision ("wrist cloud opt-in"), **not done**. So there is no way
to see the watched glyph on a real reply today, and this checklist does not pretend otherwise.

- [ ] **Must pass in v1 — no silent cloud.** Configure a cloud provider on the phone and select a cloud
      model as the active one. Ask from the wrist. The reply comes from an **on-device** model (or the
      fixed no-model sentence) — **never from the cloud**: the reply shows the on-device marker, the
      Wrist conversation's turn names an on-device model, and the phone shows **no new cloud usage** for
      that turn. (This is the sovereignty check that actually applies to v1.)
- [ ] **BLOCKED until "wrist cloud opt-in" ships — leave unticked.** A cloud-answered wrist reply shows
      the cloud shape and the words **"cloud · watched"**, and a reply mixing on-device and cloud chunks
      shows as watched. Cannot be exercised today; recorded so the glyph is not forgotten.
- [ ] No API key, token or provider secret is ever visible on the watch, anywhere (it has no
      settings for one, and the wire contract has no field that could carry one). Confirm the watch app
      requests **no network permission**: `adb -s <watch-address> shell dumpsys package dev.aarso.full`
      lists no `android.permission.INTERNET`. (The only permission expected is the app-private
      `...DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that AndroidX injects.)

## 3. Task toggle round-trip

- [ ] The Tasks list matches the phone's; each row has a **shape (to do / doing / blocked / done) and the
      word**; the header count is the phone's true pending count, and the footer says "+ N more pending
      on the phone" if the list was truncated.
- [ ] Tap a to-do row: it shows **"Asking phone to mark done…"** and **keeps its old shape**. Within a
      second or two the phone's task changes to done (check the phone's Tasks surface) and **then** the
      watch row flips. If the row ever flips *before* the phone does, that is a bug (the watch must not
      be optimistic).
- [ ] Tap a done row: the phone reopens it (to do) and the watch follows only after the phone confirms.
- [ ] Turn the phone off (or leave range) mid-toggle: after ~15 s the row says **"No confirmation from
      phone"** and still shows the last *confirmed* state.
- [ ] Complete a task **on the phone**. As built, the phone **never pushes on its own** (no timer, no
      observer; it only answers the watch), so the watch does **not** change yet. Resume the watch app
      (or tap the Home link chip / "Check again"): the list now matches. Record whether that staleness
      is acceptable for a glance, or whether you want push-on-change added (it is not built).
- [ ] A task created by the phone's Convert→Task shows up on the wrist after the same refresh.

## 4. Loop glance during a live run

- [ ] Start a multi-step Loop on the phone (one that lasts a minute or more). Open or resume WatchBrew
      (or tap the Home link chip): Loops shows the run with a **state shape and word** ("running"),
      the current **step**, the **spend line**, and **"as of <age>"**.
- [ ] Tap the link chip again while it runs: the step/spend advance. It is a point-in-time **snapshot**,
      never a live stream (the phone pushes nothing unprompted).
- [ ] After the run ends, refresh: the glance shows complete / failed with distinct shapes and words.
- [ ] Spend is honest: an on-device run reads a real `0.00`; an estimated figure reads `~… (estimated)`;
      a run with no ledger figure reads "not recorded" — **never a bare `0`** for unknown.
- [ ] A state key the watch does not know (only possible with a newer phone) renders as the generic
      diamond with the key as its label, not a crash or a blank. (Skip if you have no newer phone build.)

## 5. Tile

- [ ] Add the **Ask Fonebrew** tile: it shows the last reply snippet, its on-device marker and its age;
      tapping opens Ask.
- [ ] The provenance glyphs **actually render** in the tile (they are vectors loaded through
      ProtoLayout). If they show blank, that is the known risk; the fix is a PNG fallback.
- [ ] Ask something, return to the tile: it updates to the new reply.

## 6. Complication

- [ ] Add the **Pending tasks** complication to a watch face: it shows the count; `--` before any data
      has arrived; a trailing `?` when the data is older than an hour; `99+` above 99. Tapping opens
      Tasks.
- [ ] It updates when a snapshot arrives (after a toggle or a refresh). It has no update period and
      polls nothing, so it will **not** change on its own when the phone's tasks change.

## 7. Phone unreachable (a first-class rendered state, never a spinner)

- [ ] Bluetooth off / phone out of range: Home shows **"Phone not reachable"** with its own
      broken-link shape; Ask shows the same panel with **"Check again"**; Tasks shows the last list
      **with its age** and offers no toggles. Bring the phone back, tap Check again: it recovers.
- [ ] Phone nearby but **Fonebrew force-stopped**: Ask, send, and after ~45 s the screen says
      **"No answer yet"** (not a spinner forever). Open Fonebrew on the phone, ask again: it works.
- [ ] Walk the phone out of range **mid-reply**: **"Phone link lost"**, the partial text labelled
      "Partial — not a finished answer" — never a finished-looking answer.
- [ ] A watch/phone pair on mismatched contract versions (only if you have one) says **"Versions don't
      match"** and names which app to update.

## 8. The colour-blind rule (you are the instrument)

- [ ] On Home, Ask, Tasks, Loops, the Tile and the complication, **every state is readable from its shape
      and words alone** — link ok / checking / not reachable / mismatch; on-device vs cloud-watched; to
      do / doing / blocked / done; running / complete / failed. Nothing is distinguishable *only* by hue.
      Note any screen where you reached for colour to tell two states apart.

## 9. Battery over a day

- [ ] Wear the watch normally for a full day with WatchBrew installed, the Tile and the complication
      added. Check the watch's own battery stats afterwards: **no unexplained drain attributed to
      WatchBrew.** (By construction the app polls nothing and the complication has no update period;
      the only wake-ups are Data Layer deliveries and the app being open.) Record: start %, end %, hours,
      WatchBrew's share.
- [ ] Phone side: a phone that never pairs a watch allocates nothing for the relay (it is constructed
      lazily on the first watch message). After a day of paired use, Fonebrew shows no unexplained
      battery or memory pressure attributable to the relay.

---

## Result log (fill in; leave blank until you actually run it)

| Field | Value |
|---|---|
| Date run | |
| Phone model / Android version | |
| Watch model / Wear OS version | |
| Phone build (`fonebrew-sd.apk` commit / time) | |
| Watch build (`fonebrew-watch.apk` commit / time) | |
| `apksigner` certificate digests identical? | |
| Boxes that failed (section + what the screen said) | |

## Known not-done (so it is not mistaken for a bug)

- **Wrist cloud opt-in** — the v1 relay is on-device only (section 2); the watched glyph is built but
  unreachable until an explicit per-use opt-in on the phone exists.
- **Push on change** — the phone answers the watch, it never initiates (sections 3, 4, 6).
- A **Hyle wear target** / Wear-shaped Hyle components, **R8 shrinking**, **ambient / always-on**
  rendering and **tile-carousel preview art** — see `watchbrew/README.md` "Deliberately deferred".
- A **GMS-free transport** (raw BLE GATT) and **Apple Watch** — design doc sections 2 and 5; Apple Watch
  is gated on this checklist passing on a real Wear OS device first.
