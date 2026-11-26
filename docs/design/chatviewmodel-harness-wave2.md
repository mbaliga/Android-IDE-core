# ChatViewModel JVM harness, wave 2 (lane P2b)

Status: design note, written before the code. Wave 1 (`ui/chatharness/`, 45 tests) drives the real
`ChatViewModel` on the JVM for the plain single-model send, streaming, stop, tree and
model-switch paths. It could not reach the paths below because they call
`GenerationService.start/stop(appContext)` (builds an `Intent`, which the unit-test android.jar
refuses) or reach straight into `LocalExec` / `BitmapFactory`.

## What this lane builds

Tests, in the Wave 1 fake-and-fixture idiom, for:

| Path | Behaviours pinned |
|---|---|
| Council fan-out (`sendCouncil`) | one user node, then one assistant node per voice, in roster order; each voice runs on its own model and with its own instructions + memory in a leading system message; a failing voice does not poison the others (see "Defect watch"); cloud voices carry `modelId` of a watched spec; `@Name` narrows to one voice; model-diversity roster skips Echo; pending photo handling |
| Interaction model | already covered for the happy path in Wave 1; adds: confirm never mutates history, council turn after a branch lands under the summary node |
| Compaction | `runCompaction` with a scripted agent: success persists a receipt and a COMPACTION_RUN marker, the next turn's prompt is routed through it; a violating agent persists nothing and raises the loud failure state; no active model / no anchor does nothing |
| `!` shell escape | `!cmd` makes a USER node then an ASSISTANT node with `metadata["shell"]="true"`; the command text handed to the runner has the sigil stripped; empty output becomes `(no output)`; a failing runner is an error not an answer; non-escape text never reaches the runner; no engine is touched; the pty session is not involved (the only collaborator is the injected one-shot runner) |
| LOCAL_GGUF turns | foreground service started before load and stopped after (success, engine failure, load failure, Stop); not started for Echo or cloud; KV load path used only when the snapshot file exists, save path is keyed to the new assistant node's id |
| Vision refusal | a pending photo on a vision-blind model is refused with a stated reason and nothing is inserted or sent; same for council with no sighted voice; image mode refuses a photo |
| Image / 3D routing | decision level only: image mode with no model -> error and no nodes; 3D on-device success and failure through a scripted engine and the real `ProceduralObjectEngine`; 3D cloud with no provider -> error; the service is stopped on every exit |

## Seams (production behaviour unchanged)

All are trailing constructor parameters of `ChatViewModel` with defaults that are the exact
expressions the code used before.

1. `startGenerationService: () -> Unit = { GenerationService.start(appContext) }`
2. `stopGenerationService: () -> Unit = { GenerationService.stop(appContext) }`
   (every one of the 13 former `GenerationService.*(appContext)` call sites, including `onCleared`).
3. `shellExec: suspend (String, File) -> String = { c, d -> LocalExec.run(c, d) }`.
4. `photoStore: ((ByteArray) -> String?)? = null`: when null the existing `downscaleAndStore`
   (BitmapFactory) runs; when set it replaces only the decode/scale/save step.
   `addPendingAttachmentFromBytes` becomes `internal` so a test can feed it without a `Uri`.

No clock or id seam: tests assert ordering and structure (insertion order, parent links), never
wall-clock values; the KV save path is asserted against the persisted assistant node's own id.

## What this lane will not do

- No change to what any path does, including the defects noted below.
- No edit outside `ui/ChatViewModel.kt`, the `chatharness` package, new `ChatViewModel*Test`
  classes and this doc.
- `LocalExec`'s 20 000-char cap and 30 s timeout live inside `LocalExec` (hard-coded
  `/system/bin/sh`), not in the view-model. They cannot run on a Linux JVM and `data/remote/` is
  not this lane's file. Follow-up: a shell-path parameter on `LocalExec` would let a JVM test run
  it with `/bin/sh`. Until then the view-model tests assert that the view-model hands the
  runner's output through unmodified, and say so.

## Failure modes watched

- Council member whose engine throws: the current loop is one `try` around the whole `for`, so a
  throw ends the fan-out and drops the later voices. The brief says a failing member must not
  poison the others; if that is what the code does, the test is `@Ignore`d with the reason and
  reported as a finding rather than the code being changed here.
- Service leak: start without stop on cancel or error.
- Stale KV snapshot used when the file is absent.

## Owner-verified only (cannot be shown here)

Real foreground-service behaviour and the "FGS did not start in time" class of crash; real
llama.cpp KV session load/save; `LocalExec` against `/system/bin/sh` (timeout kill, cap);
`BitmapFactory` downscale and camera/gallery intake; SD and cloud image engines; cloud 3D; native
OCR; anything about how any of this feels on the phone.
