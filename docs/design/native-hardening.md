# Native-engine hardening (lane H1) — design note

Status: wave-2 lane H1. Plan first; committed before code, updated at the end with what was built.

## What this builds
1. **`countTokens` under slot discipline.** `LlamaCppEngine.countTokens` read `slot.handle`
   without any guard, so a concurrent `unload()`/swapping `loadModel()` could free the handle
   between the read and the JNI call (use-after-free). The generate lock cannot simply be taken:
   `generate` holds the slot mutex for the whole stream and callers count tokens while the reply is
   still streaming (including from the collector of generate's own flow), so waiting on that mutex
   would stall, or deadlock if the collector were what let the stream finish.
   Chosen design: a second, tiny lock inside `LoadedModelSlot` — a `ReentrantReadWriteLock`.
   `countTokens` takes the READ side, re-reads the handle inside it, calls native, releases.
   `freeResident()` takes the WRITE side around "clear handle, free". So a free waits for any
   in-flight count (a short tokenize call), and a count that starts after the free sees handle 0 and
   returns 0 (the pre-existing "nothing loaded" answer) — it never touches a freed handle. It never
   takes the generate mutex, so it cannot deadlock against generate, from inside or outside the flow.
   `ModelNative` gains `countTokens(handle, utf8)` so the seam is JNI-free and testable.
2. **CPU guard for libaarso_sd.** The sd module is built with the same forced
   `-march=armv8.2-a+dotprod+i8mm+fp16` as llama. `SdImageEngine`'s static initialiser now runs
   `CpuFeatureCheck` BEFORE `System.loadLibrary("aarso_sd")` via a pure, injectable
   `SdNativeLoader`. A missing feature yields a typed `UnsupportedCpuException` with an
   image-specific, legible message. The initialiser records the failure instead of throwing (a
   throwing static init becomes `ExceptionInInitializerError`, and every later use of the class a
   `NoClassDefFoundError`); `generate()` rethrows it, which both call sites already surface as the
   tile/chat error text. `-march` flags and CMake are untouched.
3. **Ctrl+K in Regular.** `RegularShell`'s root gets the same Ctrl+K toggle on the already-mounted
   `SearchOverlay`. The decision is a pure function (`RegularSearchShortcut.decide`): key-down only,
   Ctrl+K only, ignore key repeat, and not while a descendant (a text field) holds focus. Focus
   detection: the root is a focus target; the shortcut fires only when the root itself is the
   focused node (or nothing is focused), not when focus sits on a descendant. SpatialRoot is not touched.

## What this does not build
No native/cpp changes, no CMake or `-march` change, no baseline-variant libs (a benchmark
decision, see on-device-benchmark.md). No change to SpatialRoot. No cloud fallback when the CPU is
unsupported: the message points the user at a cloud provider, which stays an explicit opt-in.

## Failure modes
- count during unload: waits for the (short) tokenize, then unload proceeds; count after: returns 0.
- count while generating: runs concurrently with the decode (as before, tokenizing is read-only on
  the vocab); it is not blocked by the stream.
- cpuinfo unreadable: `Unknown`, does not block (no invented verdict).
- Ctrl+K with a text field focused: ignored, the keystroke reaches the field.

## Owner-verified only (no device here)
- That the Regular root actually receives the hardware Ctrl+K and that a focused text field
  suppresses it on a real keyboard; focus return after the overlay closes.
- That an older arm64 chip shows the UnsupportedCpu message instead of SIGILL (the parse logic is
  JVM-tested with fake cpuinfo; the real chip is not).
- That no JNI-level race remains (the JVM test proves the Kotlin-side discipline only).
