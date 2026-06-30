# Agentic Android IDE — IA / UX spec (what to wire)

> Status anchor for turning the **headless domain that already exists** (Sprints 1–6) into a
> usable, legible agentic IDE. This is the design contract the wiring PRs follow. Scope: the
> four surfaces the owner asked for — (1) agentic repo loop, (2) editable code + diff review,
> (3) device targets (RPi / Arduino-via-Pi / ESP-OTA), (4) direct-USB Arduino.
>
> Binding rules still apply: on-device default, cloud/remote = **watched object** (raw output,
> never paraphrased), no telemetry, keys/SSH-secrets in Keystore only, never claim on-device/
> hardware behaviour works (the build env has no device, no SSH server, no board).

---

## 0. Where it lives (spatial IA)

The spatial shell today: **Chat** = home; **Chats** left; **Settings** right; **Models** bottom-
overscroll; **Tree** z-axis (pinch); **Project** top; **Develop / Builds / Loops** = the POWER-
tier dev overlay (bottom room). The agentic IDE is a **POWER-tier concern** and folds into the
**Develop** room and the **Project** room — it is not new top-level chrome.

| Surface | Home | Entry point |
|---|---|---|
| Agentic repo loop (#1) | **Project** board card → **Develop** | "Work this with the agent" on a card; or a free-objective runner in Develop → **Agent** tab |
| Code + diff review (#2) | **CodeLens** (file viewer) + the review sheet | "Edit" in the lens; the **Review** sheet is shared with #1 |
| Devices (#3) | **Develop → Devices** tab (new) | picks a connected SSH host as the "via"; Remote/SSH stays in Settings for raw shell |
| Direct-USB Arduino (#4) | **Develop → Devices**, "This phone (USB)" target | full-flavor only; device-gated |

Develop becomes **Launch / Builds / Cost / Agent / Devices** (5 tabs). Remote SSH connect stays
where it is (Settings → Global → "your machines") — that's the *account/trust* surface; Devices
is the *do work on a target* surface that consumes those hosts.

---

## 1. Agentic repo loop (#1) — the keystone

**Exists (headless, tested):** `domain/ide/RepoWorkLoop` (`RepoReader`, `ChangeProposer`,
`ChangeCommitter` seams; `run(card, paths, approve)`), `domain/diff/ChangeSet` + `LineDiff` +
`ReviewSession`/`ChangeSetReview`, `domain/loop/GraphRunner` (arbitrary BPMN). **Git I/O exists:**
`GitBrowse.read`, `GitEdit.open/commit` → `GitContentsApi.putFile`.

**To wire (adapters):**
- `GitRepoReader(host, token)` implements `RepoReader.read(path)` via `GitBrowse.read`.
- `LlmChangeProposer(engine, spec)` implements `ChangeProposer.propose(objective, context)`:
  prompt = objective + each context file (path + body); the model returns **whole new file
  bodies** in a fenced, path-tagged block; parse → `FileChange(path, oldText, newText)` →
  `ChangeSet`. (Whole-file is more robust than unified-diff round-tripping from a small model.)
- `GitCommitter(host, token)` implements `ChangeCommitter.commit(changeSet, message)`: loop
  `GitEdit.commit` per `FileChange` (Contents API is one-file-per-commit). **Honest limit:** N
  files = N commits on the branch; a true single squashed commit needs the Git Data/tree API
  (not built — note it, don't fake it).

**IA / UX flow:**
1. **Entry A (issue-driven):** Project → Tasks (Board) → a card → **"Work this with the agent."**
   The card title/body becomes the objective.
2. **Entry B (free objective):** Develop → **Agent** tab → objective field + a **file picker**
   (candidate paths to read as context, browsed via `GitBrowse.list`) + model selector → **Run.**
3. **Run** streams a status line (reading files → proposing → proposed N changes). Legibility:
   show *which files were read* (watched-object honesty — the model saw these and only these).
4. **Review (shared sheet, see #2):** the proposed `ChangeSet` opens in `ChangeSetReview` —
   per-file, per-hunk **approve / reject**, unified-diff rendered, approve-all / reject-all.
5. **Commit:** on confirm, the **approved** subset commits (message = `Address #<n>: <title>` for
   cards, or the objective). Result card shows commit id(s) + a link; offer **"Open PR"**
   (existing PR creation path) and **"Run CI"** (Builds tab already polls checks).
6. The run persists as a **tree sub-graph** (like loop runs) so it's on the Tree, exportable,
   and Git-backed.

**Options exposed:** model (on-device ⌂ / cloud ☁), context files, auto-approve toggle (default
**off** — review-first is the sovereignty stance), commit-vs-PR, branch name.

**Sovereignty hooks:** review-first by default; the model's read-set is shown; cloud model =
watched badge; nothing commits without explicit approval.

---

## 2. Editable code + diff review (#2)

**Exists:** `ReviewSession` (per-hunk decide/approve/reject, `applied()`, `approvedChange()`),
`ChangeSetReview`. `CodeLensScreen` (view-only, syntax-highlighted, the "lens reads code aloud"
overlay). `GitEdit.open/commit`.

**To wire:**
- **Edit mode in CodeLens:** a pencil toggle flips the read-only view into a `BasicTextField`
  over the same text (mono, the lens overlay hides while editing). Keep it simple — full editor
  affordances are out of scope; this is "fix a line and ship it."
- **Review & commit:** "Review changes" builds a `ReviewSession(path, oldText, editedText)` →
  the **Review sheet** (shared with #1) → on approve, `GitEdit.commit(approvedChange, message)`.
- **Review sheet component (build once, reused by #1 and #2):** lists files; per file the hunks
  with +/- gutter colours; tap a hunk to toggle approve/reject; header approve-all/reject-all +
  a settled/▶ commit button. Renders `LineDiff.Hunk`s; no third-party diff lib.

**IA / UX:** CodeLens gains a top-bar **Edit** ✎ and, in edit mode, **Review ▸**. The Review
sheet is a full-screen `Dialog` (like Participants). Commit message prefilled from the path.

**Options:** branch (defaults to host branch), commit message, discard.

---

## 3. Device targets — RPi / Arduino-via-Pi / ESP-OTA (#3)

**Exists (headless):** `domain/device/` — `DeployTarget` (`Remote(host)`, `Arduino(via, fqbn,
port, label)`), `DeviceRecipes` (`run`, `shell`, `compile`, `upload`, `compileAndUpload`,
`listBoards`, `espOta`), `ArduinoCli.parseCompile/parseUpload`, `Fqbn`, `SerialPort`. **Exec
exists:** `RemoteSessionDriver.exec(ExecRequest, onChunk)` + `shell(...)` over `SshjTransport`,
with TOFU trust. **`DeviceRecipes` already emit `ExecRequest`s** — they are *made to be run by
the driver*. Nothing wires recipe → driver → parser yet.

**To wire:** a `DeviceRepo` that, given a `DeployTarget`, opens a `RemoteSessionDriver` to the
host, runs the recipe `ExecRequest`s, streams raw output (watched object), and folds Arduino
output through `ArduinoCli.parse*` for a legible summary (errors with file:line, sketch size,
upload ok).

**IA / UX — Develop → Devices tab:**
- **Hosts:** the connected SSH hosts (from `RemoteHostStore`) as cards; "Open shell" = the
  existing terminal.
- **RPi actions** on a `Remote` target: **Run script** (interpreter + path + args →
  `DeviceRecipes.run`), **Shell command**, plus a **"Install…"** affordance that's just a shell
  command with a confirm (apt/pip) — legible, not magic.
- **Arduino (via Pi):** pick a host as **"via"**, **List boards** (`listBoards` → parsed port +
  FQBN suggestions), define a target (FQBN + serial port + sketch path on the host), then
  **Compile**, **Upload**, or **Compile & upload**. Output panel shows raw stream + the parsed
  result (✓/✗, errors as tappable file:line, sketch/flash size bars).
- **ESP-OTA:** device IP + bin path + espota path → `espOta` recipe; network flash, no cable.
- **Agentic tie-in:** a device run can be a step the agent drives — "compile, read errors, fix
  the sketch (loop #1 over the sketch file), re-upload" — by feeding `ArduinoCli` errors back as
  the objective. Phase-2 nicety; the manual flow ships first.

**Options:** host (via), FQBN (free text + suggestions from board list), serial port, sketch path
(on the host's filesystem — sketches live on the Pi, browsed by SFTP), extra `arduino-cli` flags.

**Sovereignty / honesty:** every byte the board/host emits is shown verbatim. **All of §3 is
owner-verified** — no SSH server and no board in CI; mark 📱 and never report success unseen.

---

## 4. Direct-USB Arduino (#4) — device-gated, build the testable core only

**Reality:** phone-cable-to-board needs (a) USB-host access + `usb-serial-for-android` (CDC/FTDI/
CP210x/CH34x), (b) an avrdude-equivalent: **STK500v1** (Uno/Nano old-bootloader) / **AVR109** /
optiboot over serial, (c) an **Intel HEX** parser, (d) reset-via-DTR timing. Compilation is NOT
on-device — the `.hex` comes from `arduino-cli` on a host/CI; the phone only **flashes** it.

**Build now (pure, JVM-testable — no device needed):**
- `domain/device/usb/IntelHex` — parse `.hex` → byte pages (+ checksum validation). Tested.
- `domain/device/usb/Stk500` — the STK500v1 state machine as pure frame encode/decode over a
  `SerialLink` seam (`fun interface SerialLink { suspend fun write(bytes); suspend fun read(n): bytes; suspend fun setDtrRts(...) }`).
  Page-program protocol, sync, enter/leave progmode — all unit-tested against a fake link.

**Spec only (device-gated, do NOT wire untested into the shipped app):**
- `data/device/UsbSerialLink` over `usb-serial-for-android` (full-flavor dependency).
- `<uses-feature android:name="android.hardware.usb.host">` + a `USB_DEVICE_ATTACHED`
  intent-filter + device-filter XML (full-flavor manifest only — Play/`play` flavor excludes it,
  consistent with the overlay/screen-capture split).
- Devices tab target **"This phone (USB)"**: detect attached board → pick `.hex` (from a host
  build artifact or Files) → flash with a live progress/verify bar.

**Why split:** the protocol + HEX logic is the hard, bug-prone part and it's fully testable
headless; the USB transport + permission dance is pure device work that the build env cannot
verify (rule 6). Ship the tested core; the owner wires + verifies the transport on the phone.

---

## 5. Cross-cutting

- **Shared Review sheet** (§2) is the single diff-review component for #1 and #2.
- **Runs persist into the Tree** (reuse `RunLog`-style mapping) → exportable, Git-backed,
  legible history of what the agent did.
- **Watched-object law everywhere:** remote/board/model raw output shown verbatim; cloud models
  badged; the agent's file read-set surfaced.
- **Disclosure:** all of this is POWER tier; CORE/STUDIO users never see it unless they opt up.
- **Auto-approve is opt-in, off by default** — the sovereignty stance is review-first.

## 6. Status (2026-06-26) — what shipped vs open

**Shipped (PRs #58–#66):** Agent tab (#1), editable CodeLens (#2), Devices RPi/Arduino-via-Pi/
ESP-OTA (#3), tested STK500/HEX core + the CDC USB transport/flasher/panel (#4),
**per-hunk** review sheet, and **squashed commits** via the Git tree API (per-file fallback).

**Resolved decisions:**
- *Commit granularity* → **squashed commit per ChangeSet** (Git Data API), falls back to per-file.
- *Per-hunk review* → **done** (ReviewSheet drives `ReviewSession`).
- *USB transport* → **CDC built** (dependency-free), device-gated; clones (CH340/CP210x) still
  need a vendor driver (`usb-serial-for-android`) — the one remaining USB follow-up.

**Still open for the owner:**
1. **Develop tab count:** Launch/Builds/Cost/Agent/Devices = 5. Keep, or fold Agent into the
   Project-card flow and drop the tab?
2. **Sketch storage:** sketches-on-the-Pi (SFTP-browsed) vs a phone-local store synced to the
   host. Current build assumes on-the-Pi.
3. **USB on-device verification:** the CDC flasher is unexercised in CI — needs a real board.
4. **Clone-chip USB driver** (CH340/CP210x/FTDI) — add `usb-serial-for-android` when wanted.
5. **AI-assisted config** — still parked at the owner's request.
