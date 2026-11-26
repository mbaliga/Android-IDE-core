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

---

# Proposed decision table

**Everything in this section is a PROPOSAL for the owner to ratify.** None of it is a fact, a
measurement or a requirement. No number below has been produced by this harness yet (it has never
run on a device). Thresholds are placeholders chosen to make the *question* concrete; the owner
should replace them with what feels acceptable on the target phone after seeing real results.
Repeated runs on the same model should agree roughly before any single run is trusted (look at
the spread and the drift column, not just the median).

## How to read the numbers

| Reading | Means | Does not mean |
|---|---|---|
| Decode tok/s (median, per prompt size) | Steady generation speed on CPU through the current llama.cpp build | Quality of output, or speed with a different quant/context |
| Prefill tok/s | Speed of reading the prompt (slightly under-read, see above) | Pure prefill time; TTFT also includes template + first sample |
| TTFT at ~2048 tokens | How long a long document/agent context makes you wait | Interactive latency for short chats (see the short prompt) |
| Decode drift (last vs first repeat) | Sustained-load behaviour; negative = slowing down (heat is the usual suspect) | A diagnosis; correlate with the thermal before/after fields |
| PSS after load / after generation | Memory this process holds | Total phone memory headroom (other apps, GPU memory not in PSS) |
| Load time | Cold-ish load of this file now (OS page cache may make repeats faster) | First-boot cold-start |

## Question 1: is a GPU/NPU path worth building?

Proposed rule: *acceleration is only worth building if CPU is demonstrably the bottleneck for a
model the owner actually wants to use, and the CPU number is below what is tolerable.*

| Observation on the owner's phone (PROPOSED thresholds) | Proposed next step |
|---|---|
| Decode >= ~15 tok/s median on a model the owner would use daily, drift better than about -15% | **Do not build acceleration.** CPU is adequate; spend the effort elsewhere. |
| Decode ~5 to ~15 tok/s, prefill >= ~100 tok/s | **Hold.** Usable; re-run after a model/quant change before committing to a backend. Consider a smaller or lower-bit quant first (cheaper than a new backend). |
| Decode < ~5 tok/s on a model size the owner wants, or prefill < ~50 tok/s (2048-token TTFT of tens of seconds) | **Spike an acceleration backend** behind a flag, benchmarked with this same harness, before any product work. |
| Drift worse than about -30% across repeats, or thermal reaches SEVERE | **Sustained load is the problem, not peak speed.** An accelerator that draws more power may make it worse; measure energy (battery delta) alongside before choosing. |
| Battery drop per run large relative to the run's length | Treat power as a first-class cost; a faster path that burns more battery is not an unambiguous win. |

Reasoning: decode on phones is usually memory-bandwidth-bound, so a GPU does not automatically
beat a well-tuned CPU path for small models; prefill (compute-bound) is where an accelerator is
most likely to help. That is why prefill and decode are reported separately.

## Question 2: local vision

Local vision needs a second file (a projector/"mmproj") plus an image encoder pass that adds
prefill-like work and memory. Proposed gate, using the numbers this harness already records:

| Observation (PROPOSED) | Proposed next step |
|---|---|
| PSS after load for the intended text model leaves roughly >= 2 GB headroom on the phone (owner reads free RAM separately) AND prefill at ~512 tokens >= ~100 tok/s | A vision spike is plausible; image encode cost then needs its own measurement (this harness does not measure it). |
| Little headroom, or prefill < ~50 tok/s | **Defer local vision**; keep vision on an opt-in cloud provider (a watched object) as today. |

This harness cannot measure image-encoder time. A vision benchmark would be a separate, later
extension of the same runner; it is named here as a follow-up, not built.

## Question 3: tool-capable models

Tool use means longer prompts (tool schemas + results) and multi-turn agent loops, so what matters
is prefill at 1024 to 2048 tokens and sustained drift. Proposed gate:

| Observation (PROPOSED) | Proposed next step |
|---|---|
| TTFT at ~2048 tokens under ~20 s and drift better than about -20% | Local tool-using agent loops are plausibly tolerable; evaluate tool-call *reliability* separately (not a speed question, and not measured here). |
| TTFT at ~2048 tokens above ~45 s | Local agent loops will feel stalled; keep agents on a smaller context budget or cloud (opt-in, watched). |

Speed is necessary, not sufficient: whether a given small model emits valid tool calls is a
quality question this harness does not and cannot answer.

## What this table deliberately does not do

- It names no model, quantisation or download link as recommended. The catalog is not touched.
- It does not rank phones, generalise from one device, or treat one run as a verdict.
- It is not a requirement on anyone: if the owner ratifies different thresholds, edit this table.

## Secondary, unverified background (for context only)

A web search was run once per topic during this lane. The pages were **not fetched or verified**;
only search-result summaries were read. Treat as leads, not facts:

- Qualcomm announced an OpenCL backend for Adreno GPUs in llama.cpp (summary says Q4_0 is the
  optimised quantisation; no tok/s figures seen).
  <https://www.qualcomm.com/developer/blog/2024/11/introducing-new-opn-cl-gpu-backend-llama-cpp-for-qualcomm-adreno-gpu>
- A mobile study (an iPhone, not Android) reported multi-threaded CPU beating GPU for ~0.5-1B models
  while GPUs held an edge above ~1.5B. <https://arxiv.org/html/2505.06461v1>
- Community reports of poor Vulkan performance on some Android GPUs (older, possibly outdated).
  <https://github.com/ggml-org/llama.cpp/discussions/9464>
- No upstream Hexagon NPU path was seen in results; only an unofficial fork was mentioned. Not
  verified.
- llama.cpp multimodal splits a model into a language GGUF plus a projector GGUF (mmproj):
  <https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md>. A VLM case study on a
  OnePlus 13R exists: <https://arxiv.org/html/2507.08505v2>. Memory rule of thumb seen in a
  third-party app README: model file + KV cache + a few hundred MB for the encoder.

## Follow-ups named, not built

- Image-encoder timing for a vision benchmark.
- Energy measurement beyond battery percentage deltas (coarse; owner to judge usefulness).
- Optional per-run thermal timeline (current record keeps before/after and per-repeat only).
- A second runtime (non-llama.cpp) behind the same runner for comparison, once an accelerated
  engine exists.
