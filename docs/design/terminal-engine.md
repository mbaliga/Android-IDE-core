# Terminal engine (wave 2, lane T1)

Status: design note, written before code. Scope is the **engine only** - no UI. The terminal
screen (`ui/develop/TerminalView.kt`, `TerminalFacet.kt`, `ui/remote/*`) is designed separately
and is not touched here. Every behaviour below is JVM-tested; nothing is claimed to work on a
device (this container has none).

## What exists today (audit)
- `domain/remote/term/`: `VtParser` (swallows queries), `ScreenBuffer` (1000-line `ArrayDeque`
  scrollback, one `Char` per cell, no wrap flags, no wide chars, lossy resize), `PtyChannel`.
- `data/remote/PtyShellSession` + `NativePty` (JNI `pty_exec.cpp`): `/system/bin/sh -i`,
  inherited environment, blocking 8 KB reads decoded with `String(bytes)` per chunk (a multibyte
  character split across reads is corrupted).
- `data/remote/TerminalSessionHolder`: one session per process, line-based `sendLine`, no
  keystrokes, no paste, no persistence.

## What this lane builds
1. **Emulator completeness** (`domain/remote/term`): `TerminalModes` (DECCKM, DECKPAM,
   bracketed paste 2004, focus 1004, mouse 1000/1002/1003 + SGR 1006, autowrap, alt screen
   with 1049 saved cursor); a *reply channel* (`TerminalReplies`) for DA1, DA2, DSR 5n/6n,
   `CSI ? 6 n`, `CSI 18 t`; OSC 0/2 title and OSC 7 cwd as metadata; OSC 52 write parsed into a
   `ClipboardWriteRequest` event that is **never applied** by the engine (the host must obtain
   consent); wide and combining characters (own width table generated from the public Unicode
   Character Database by `scripts/gen-wcwidth.py`); scrollback as a bounded ring of lines with
   soft-wrap flags; resize that reflows soft-wrapped lines.
2. **Keystroke path** (`domain/terminal`): `KeyEncoder`, `PasteEncoder`, `MouseEncoder`,
   focus-report encoder - all pure, mode-aware, table-tested against the xterm control sequence
   documentation (public).
3. **Streaming decode + environment** (`domain/terminal`): `Utf8StreamDecoder` (stateful,
   split-safe, invalid -> U+FFFD without desync) and `TerminalEnvironment` (explicit allow-list
   child environment + generated `ENV` rc file with a marked extension point).
4. **Sessions** (`domain/terminal` pure `SessionRegistry`; `data/terminal`
   `TerminalSessionManager` over a `SessionBackend` seam so it is JVM-testable with fakes):
   multiple sessions, stable ids, titles, cwd, Running / Exited(status) / restartable,
   activity + bell flags, foreground-process indicator (`tcgetpgrp` on the pty master, else
   Unknown), close-with-confirm-if-busy, restart, SIGINT/SIGTSTP/SIGQUIT/SIGHUP to the pty's
   foreground process group, window-size propagation. `TerminalSessionHolder` becomes a facade
   over the manager; its public API is unchanged.
5. **Staying alive** (`TerminalSessionService`, `domain/terminal/ServicePlan`): a foreground
   service of type `specialUse` holding the sessions while backgrounded, with a Close-all action,
   self-stopping when the last live session closes. Scrollback snapshots (text, <= 2 MB per
   session, app-private, deletable) offered on the next launch as *previous session history -
   not live*.
6. **Native** (`pty_exec.cpp`): existing three JNI functions keep their signatures. Added:
   `spawnEx` (explicit environment, new process group hygiene: inherited fds closed, signal
   dispositions/mask reset), `foregroundPgid`, `signalForeground`, `tryWait`.

## What this lane does not build
- Any Compose/UI, soft-keyboard or hardware-key capture, selection UI, clipboard consent UI
  (the engine surfaces the request; a later package renders the consent).
- Command bridges (the rc file only reserves the extension point).
- Real reattach after process death (needs a userland multiplexer - later package).
- Sixel/images, OSC 8 hyperlinks, UTF-8 mouse encoding (1005), 1015, alt-scroll (1007),
  grapheme clustering beyond combining marks, VS16 width promotion.
- Encrypted snapshots (not required; plain text in app-private storage, deletable).
- Reading the clipboard on behalf of the remote (`OSC 52 ; ? ` is ignored, never answered).

## Seams
`PtyChannel` stays the parser+screen pair the UI already reads (`screen`, `onOutput(String)`,
`resize`, `reset`, `attach`). New optional collaborators are constructor defaults, so existing
callers and tests compile unchanged. `Cell.char` is kept (first UTF-16 unit); new `Cell.glyph`
and `Cell.width` carry the full cluster and 1/2/0 width (0 = right half of a wide cell).
**A later UI package must render `glyph`, and skip width-0 cells**, or non-BMP characters
and wide characters will render wrongly (the unchanged UI draws a space for the right half).

## Memory and bounds (stated)
- Scrollback: default 5,000 lines, hard cap 100,000 lines, **and** a cell budget of 4,000,000
  cells per session (about 110 MB worst case at roughly 28 bytes per live cell on ART, which is
  an estimate, not a measurement). Rows are stored with trailing blanks trimmed when not
  soft-wrapped, so ordinary output costs far less. Whichever bound is hit first evicts the oldest.
- Snapshot: 2 MB of UTF-8 text per session, newest lines kept (oldest dropped on a line
  boundary). OSC payloads: title 1 KB, cwd 4 KB, clipboard 64 KB decoded. Replies: at most 16 per
  `feed` call (a hostile stream cannot make us flood the pty).

## Security posture
- Replies are produced only for the specific queries listed and only into the session's own pty.
  A hostile byte stream can already make any terminal answer queries; the 16-per-chunk cap and
  the fixed reply shapes (no echo of attacker text) bound it.
- OSC 52 write: event only. OSC 52 read: ignored.
- Paste: `PasteEncoder` removes every ESC, C1 control and C0 control except TAB/CR/LF, so a paste
  cannot contain the bracketed-paste end marker, an escape sequence or a Ctrl-C.
- Child environment is an allow-list built from nothing (`TerminalEnvironment`): HOME, PATH
  (`/system/bin:/system/xbin:<app bin>`), TMPDIR, LANG=C.UTF-8, TERM, COLORTERM, ENV, SHELL, plus
  - only if present in the app process - `ANDROID_ROOT`, `ANDROID_DATA`, `ANDROID_STORAGE`,
  `ANDROID_RUNTIME_ROOT`, `ANDROID_ART_ROOT`, `ANDROID_I18N_ROOT`, `ANDROID_TZDATA_ROOT`,
  `ANDROID_ASSETS`, `EXTERNAL_STORAGE`, `BOOTCLASSPATH`. Nothing else is forwarded (loader
  variables, proxies, anything a library exported); no API key is ever placed in it. The native
  child also resets signal dispositions/mask and closes every inherited descriptor above stderr.
  Whether the pass-through set is enough for every Android tool is owner-verified.
- Signals go only to the pty's foreground process group as reported by the kernel for **our**
  pty (`tcgetpgrp(master)`), refused if that group is <= 1 or our own group, whitelisted to
  INT/TSTP/QUIT/HUP. Ctrl-C through the line discipline (writing 0x03) stays the primary path;
  the explicit signal path is for programs that cleared ISIG.

## Failure modes
- Shell dies (exit, kill, phantom-process killer): reader sees EOF, session becomes
  `Exited(status or unknown)`, restartable; the UI must show it as not live.
- App process death ends every shell. Android guarantees nothing about children of a cached
  process; the foreground service lowers the chance of the *app* being killed for memory but
  cannot stop the Android 12+ phantom-process killer (it can SIGKILL a child at any time,
  see EXECUTION_CONTRACT 3.3) nor a user force-stop. Low-memory kills happen to the whole
  process. After any of these the next launch offers the saved snapshot as history only.
- Reattach to a still-running shell after process death is impossible without a userland
  multiplexer outliving the app; not attempted here.
- Foreground-service start refused (flavor without the declaration, background-start limits,
  notification permission denied hides the notification but not the service): the manager runs
  without it, no crash.
- Snapshot write fails (disk full): ignored with a flagged status; never blocks the session.

## Shape of the code
- `domain/remote/term`: `ScreenBuffer` (grid of `Row`s, `LineRing` scrollback, reflow),
  `VtParser`, `PtyChannel`, `TerminalModes`/`TerminalMetadata`/events/replies, `WcWidth`.
- `domain/terminal` (pure): `KeyEncoder`, `PasteEncoder`, `MouseEncoder`, `Utf8StreamDecoder`,
  `TerminalEnvironment`, `SessionRegistry` (state machine), `ServiceKeeper`, `TerminalHistory`.
- `data/terminal`: `TerminalSessionManager`/`TerminalSession` over the `SessionBackend` seam
  (fakes in tests), `NativePtyBackend`, `ProcInspector` (/proc), `TerminalHistoryStore`,
  `TerminalFiles`, `TerminalPlatform` (Android seams). `service/TerminalSessionService`.
- `TerminalSessionHolder` keeps one stable `PtyChannel` and drives one manager session at a time
  for the existing screens; a shell that exits on its own reads as "closed" (so the existing
  Reconnect screen shows). A local shell now starts in `<filesDir>/terminal/home`.
- Exit and restart write a visible marker line into the transcript (`[session exited with
  status N]`, `[session restarted]`) so a saved history can never look like a live shell.
- The foreground indicator: `tcgetpgrp(master)`; if the shell's own group is in front it also
  looks for children in /proc (a background job counts as busy), and reports Unknown when /proc
  will not say. If the shell runs without job control, a foreground command shares the shell's
  group and only the child scan can see it - owner-verified.
- Persistence: `persistSnapshots` is called on app-background (activity lifecycle hook), on task
  removal (service) and on session end/close; slots are per run and session, so repeated saves
  overwrite; at most 20 files are kept.

## Flavors and the service type
EXECUTION_CONTRACT 3.2: terminals have no standard FGS type and need `specialUse` with a
justification string and Play review. The service is therefore declared **only in the `full`
manifest overlay**, next to the other `specialUse` service, and not in `play`. In `play` the
code path detects the missing declaration (`PackageManager.resolveService`) and runs without a
foreground service. Revisit if Play review of `specialUse` is obtained.

## Owner-verified only (no device here)
How the shell behaves on a real device and Android version (mksh options, ENV rc loading,
prompt); the foreground service and its notification, Close-all action and self-stop;
phantom-process killing; `tcgetpgrp` on a pty master and `/proc/<pgid>/comm` readability on
real Android; hardware- and soft-keyboard delivery of Ctrl/Alt/Esc/arrows (UI); vim, tmux, htop,
less actually rendering correctly; mouse reporting feel; arm64 behaviour of the new JNI code
(it is only compiled here, once, near the end).

## Verification status (what was and was not machine-checked)
- JVM gate green (full and play flavors, watch-bridge, watchbrew, checkLicense): core-engine 3772
  tests per flavor (9 skipped), up from 3080. Includes parser golden transcripts (vim-like,
  less-like, htop-like), key/paste/mouse tables, UTF-8 split fuzz at every split point, ring bounds,
  reflow, registry, manager with a fake backend, history cap, service keeper, and one test that
  pipes real shell output from a host pty (`script`) through the parser (skipped where `script` is
  missing). A host pty is not Android's pty: that stays owner-verified.
- Native: `pty_exec.cpp` was compiled for arm64-v8a / android-31 with the NDK 28.2 toolchain and the
  same two CMake statements the module uses (`add_library(aarso_pty SHARED pty_exec.cpp)` +
  `log`), clean with -Wall -Wextra; all eight `Java_dev_fonebrew_data_remote_NativePty_*` symbols are
  exported. AGP exposes only whole-ABI CMake tasks (`buildCMakeDebug[arm64-v8a]`, which also builds
  llama.cpp, and the submodule is not checked out in lane worktrees), so the Gradle-driven native
  build was not run; nothing was executed on a device.
- Not covered by a JVM test: `TerminalSessionHolder` (needs Main dispatcher and host store; it was
  compiled and its old surface kept), `TerminalSessionService`, `AndroidTerminalPlatform`,
  `NativePtyBackend` (JNI).
