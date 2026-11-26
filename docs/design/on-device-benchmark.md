# On-device benchmark harness (wave-2 lane A2)

Status: design note, written before code. Standing owner rule: **benchmark before building any
GPU/NPU path, and never assume.** This lane builds the instrument and the decision record. It
builds **no acceleration, no vision, no engine change.**

## What gets built

| Piece | Where | Pure? |
|---|---|---|
| `BenchmarkPlan` - fixed deterministic prompt set (short + prefill-heavy ~512/1024/2048 tokens from repeated neutral text), decode length, repeats, context size | `domain/benchmark/` | yes |
| `BenchmarkRunner` - Clock-injected, drives an `InferenceEngine`; load time, TTFT, prefill tok/s, decode tok/s per repeat | `domain/benchmark/` | yes (JVM-tested against a scripted fake engine + fake clock) |
| `BenchmarkStats` - median, p10/p90, min/max, first-vs-last drift | `domain/benchmark/` | yes |
| `BenchmarkResult` + text/JSON codecs | `domain/benchmark/` | yes |
| Probe interfaces (`ThermalProbe`, `MemoryProbe`, `BatteryProbe`) returning `Reading.Unknown` rather than a guess | `domain/benchmark/` (interfaces), `data/benchmark/` (Android impls) | interfaces yes |
| `BenchmarkHistoryStore` - append-only JSON file in app storage, bounded to last 50 | `data/benchmark/` | file I/O behind a path, JVM-tested on a temp dir |
| Models-screen entry (`Benchmark` action) + results screen + progress/stop | `ui/benchmark/` (new files), one-line hook in `ui/rooms/ModelsRoom.kt` | no (owner-verified) |

## What will NOT be built

- No GPU/NPU/Vulkan/OpenCL/Hexagon path, no engine option, no CMake/JNI change. `inference/` and
  `src/main/cpp` are untouched; the harness is a *client* of the existing `InferenceEngine` interface.
- No local vision, no tool-calling work. The decision table only says what numbers would *mean*.
- No catalog entries or download links. The harness benchmarks models already on the device.
- No network, no cloud engine, no upload, share intent, analytics or telemetry. The only export is
  an explicit "Copy results as text" to the clipboard.

## Seams

- Runner depends only on `InferenceEngine` (`loadModel`, `countTokens`, `generate`, `unload`) plus
  injected `Clock`, probes and a `shouldStop` / thermal gate. Android types never enter `domain/`.
- The engine is obtained via the existing `EngineProvider.engineFor(spec)` for a **`LOCAL_GGUF`
  spec with a model file present**; any other runtime is refused with a stated reason
  (`BenchmarkPreflight`). Cloud specs are never offered.
- Process survival with the screen off reuses the existing `GenerationService` foreground path
  (`GenerationService.start/stop`), exactly like generation does.
- CPU feature string reuses `CpuFeatureCheck` (`REQUIRED` names + `evaluate`); this lane does not
  edit it.

## What is measured, and how (and what is *not* claimed)

- **Load time**: `loadModel` wall time on the injected clock.
- **TTFT**: clock delta from `generate()` collection start to the first emitted token. This includes
  chat-template render + prefill + first sample. It is *not* pure prefill time.
- **Prefill tok/s** = engine-reported prompt token count / TTFT. Prompt count comes from the
  engine's own `countTokens(promptText)`; that excludes chat-template overhead tokens, so the figure
  is a slight **under**-estimate of true prefill throughput and is labelled so. If `countTokens`
  throws, the count is **Unknown** and so is prefill tok/s - never estimated.
- **Decode tok/s** = (emitted tokens - 1) / (last token time - first token time). The emitted-token
  count is the number of `GeneratedToken`s the engine delivered (its own unit). Fewer than 2 emitted
  tokens -> decode tok/s Unknown (not 0).
- **Stats**: median, p10/p90 (nearest-rank), min/max over repeats that have a value; Unknown
  repeats are excluded and counted, never zero-filled. **Drift** = (last - first)/first over decode
  tok/s, a sustained-load indicator (thermal throttling shows up as negative drift). One repeat -> drift Unknown.
- **Probes**: thermal status (`PowerManager.getCurrentThermalStatus`), PSS (`Debug.MemoryInfo`)
  before load / after load / after generation, battery % (sticky `ACTION_BATTERY_CHANGED`). Each
  returns `Unknown` when the platform does not give it.
- The result is **verdict-free** and carries the sentence: *measured on this device at this time;
  unverified by anyone else.*

## Safety / legibility

- Starts **only** from an explicit Start tap on a screen that says what it will do (loads the model,
  ~N generations, roughly M minutes, will warm the phone). No auto-run, no deep link, no schedule.
- Local model must already be on the device; the benchmark never downloads and never touches the network.
- Stop at any time (cooperative cancel; model is unloaded and the foreground service stopped).
- Thermal status SEVERE or worse (checked before the run and between every generation) **aborts**
  the run and says so; the partial result is kept and marked aborted, with the reason.
- Progress and results carry state as glyph + label (check / cross / warning / unknown "?"), never
  red/green hue alone (owner is red-green colorblind).

## Failure modes considered

- Engine throws mid-run -> run recorded as failed with the exception class + sanitized message;
  partial repeats kept. No key/path secrets in messages (paths are reduced to the file name).
- Process killed (OOM / user swipe) -> nothing half-written: history is written to a temp file and
  renamed; a result is only appended when a run ends (complete, aborted, stopped or failed).
- Corrupt history file -> treated as empty, **not** overwritten silently: the bad file is renamed
  `*.corrupt` first.
- A model larger than RAM -> existing `ModelFit` gate is surfaced as a refusal reason; the harness
  adds no fit logic of its own.
- Another generation is running -> refused ("busy"), since there is one resident model.

## Owner-verified only

Every number this harness will ever produce; probe accuracy (PSS vs real RSS, thermal API coverage
per OEM, battery granularity); thermal behaviour and whether the abort fires in time; foreground-
service survival with the screen off; the screens (layout, glyph legibility, clipboard). This
container has no device or emulator; JVM tests prove the **arithmetic and the rules**, not the phone.

## Decision record

See "Proposed decision table" below (added in a later commit of this lane). All thresholds are
**proposals for the owner to ratify**, not facts.
