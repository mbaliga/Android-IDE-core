# 3D objects — generate, import, preview (design + build spec)

Owner ruling (2026-08-20): build **both** generation paths, **user-selectable** — on-device and
watched-cloud — and a viewer that can preview **any** model output regardless of which path (or
which outside tool) produced it. This doc is the Fable-authored scope; execution is by Sonnet
agents, one phase per landing, gate-green each.

## 0. The ChatGPT precedent (research finding, and what we take from it)

ChatGPT has **no 3D engine in the model or the chat runtime**. Its "3D software in the
environment" is two separate mundane pieces:

1. **Geometry as text.** The model writes *code or mesh text* — Python in its sandbox
   (trimesh / numpy-stl / CadQuery), OpenSCAD scripts, or raw OBJ/STL — and the sandbox executes
   it into a file. The LLM never "imagines" a mesh; it programs one. Parametric outputs
   (brackets, enclosures, threads) are dimensionally exact for exactly this reason.
2. **A browser viewer widget.** The chat client renders the resulting file in an interactive
   WebGL viewer (orbit/zoom/pan, three.js-class tech). External generators (image→3D services)
   attach through tool calls; output lands as GLB/FBX/STL/OBJ.

Both pieces map cleanly onto what Fonebrew already has: a local LLM that streams text, and a
locked-down-WebView asset pattern (WP11 graph room). **On-device 3D generation = the model
writes geometry-as-text; the viewer interprets it.** No 3D diffusion model needed, no dishonesty
about what runs where.

## 1. User-facing shape

- **Settings → 3D tab** (replaces `PlannedProvider`): the existing On-device ⇄ Cloud·watched
  scope toggle becomes real. On-device explains the procedural path (uses the active chat
  model — nothing extra to download). Cloud configures a provider: kind (**Meshy** / **Tripo**;
  custom base URL escape hatch), API key via `KeystoreSecret` (rule 5), marked **watched**
  (`Provenance.Cloud`, `Pulse.WATCHED`) like every cloud surface (rule 2).
- **Composer `+` → "Generate 3D…"**: mini-chooser On-device · Cloud (defaults to the Settings
  scope; cloud is opt-in **per use** — rule 2). Prompt → generation → result minted as a node.
- **Composer `+` → "3D file…"**: SAF picker import. Any supported file is copied into app
  storage and minted as a node — this is the "preview anything's output" path (files from
  ChatGPT, Meshy, a slicer, a scanner…).
- **Tap a 3D node → viewer**: full-screen interactive orbit/zoom/pan. 3D turns are **nodes on
  the same append-only tree** (IA §6, exactly like image turns) — not a separate library.

## 2. Format matrix (viewer must load ALL of these)

| Format | Loader | Notes |
|---|---|---|
| GLB / glTF 2.0 | GLTFLoader | primary; what Meshy/Tripo emit |
| OBJ (+MTL) | OBJLoader/MTLLoader | also the on-device raw-mesh output |
| STL ascii+binary | STLLoader | print ecosystem |
| PLY | PLYLoader | scans |
| FBX | FBXLoader | DCC interchange |
| DAE (Collada) | ColladaLoader | legacy interchange |
| 3MF | ThreeMFLoader | modern print |
| USDZ | USDZLoader | **best-effort** (three.js support is partial; degrade with an honest "partial support" note, never a fake render) |

Format sniffed from magic bytes + extension (`domain/object3d/Object3dFormat.kt`, pure, tested).

## 3. Viewer architecture

`ui/object3d/ObjectViewerRoom.kt` + committed single-file asset
`core-engine/src/main/assets/object3d/object-viewer.html`, built by
`scripts/build-object-viewer.js` from vendored `third_party/threejs/` (MIT; pinned version;
license text hand-appended to `NOTICE` — checkLicense can't see assets). Follows the WP11
`GraphWebRoom` lockdown verbatim: `blockNetworkLoads=true`, no file/content access, CSP meta,
always-block navigation, narrow `@JavascriptInterface` bridge, `__objectViewer.load(...)` entry.
Model bytes enter via the bridge as base64 (no `file://` widening); >32 MB refused with an
honest size message (base64 inflation is real; `WebViewAssetLoader` is the documented
optimization if the cap bites).

**The one deliberate divergence from binding constraint 7: this WebView needs WebGL.** The
G6 graph room stays Canvas2D; 3D cannot. The prior garbling (Hyle `1198515`) was
WebGL-in-WebView — so: hardware acceleration explicitly on, a WebGL-context-creation self-test
in the HTML that reports failure through the bridge (Kotlin shows an honest "WebGL unavailable
on this device" card, never a garbled canvas), and **owner-verify is the gate**. Documented
fallback if device verify fails: native Filament (glTF-only) with server-side conversion for
the rest — a bigger build, deliberately not started unless WebGL actually fails.

## 4. On-device generation (the ChatGPT-style path)

`inference/object3d/ProceduralObjectEngine.kt`: prompts the **active chat engine** (local by
default) for a fenced ```object3d``` block containing either:

- **Primitive DSL (preferred)** — compact JSON: list of `box/sphere/cylinder/cone/torus/plane`
  ops with transforms, colors, and `union` grouping. Interpreted in-viewer by three.js
  primitives. Small models generate this far more reliably than hundreds of vertex lines —
  the "write a program, not a mesh" lesson from ChatGPT.
- **Raw OBJ (accepted)** — for models that prefer emitting mesh text directly.

Pure Kotlin validators (`ProceduralScene.kt` codec+validator, `ObjParser.kt`) JVM-tested;
invalid output → one structured retry with the validator's complaint (existing
structured-output retry pattern), then honest failure. Provenance: `OnDevice`.

## 5. Cloud generation (watched)

`inference/object3d/CloudObject3dEngines.kt`: provider-generic seam (`CloudObject3dEngine`),
adapters for **Meshy** and **Tripo** (both: text→3D and image→3D; async job → poll → download
GLB). Request builders are **pure and JVM-tested** (`domain/object3d/CloudObject3dContracts.kt`
— no network in tests, per the Git-API precedent); polling/download in the engine with OkHttp.
Job lifecycle: `Object3dJob.kt` state machine (QUEUED→RUNNING→DOWNLOADING→DONE/FAILED),
tested. Keys per rule 5. Every surface marks the provider a watched object.

## 6. Contracts first (repo convention)

`schemas/object3d/`: `object3d-node.schema.json` (node metadata: `object3d.file|format|source
|provider|prompt`), `object3d-job.schema.json` (cloud job envelope) — in `ContractEnvelope`,
with valid/invalid/**adversarial** fixtures + `.expected.txt` siblings (adversarial: a DSL
smuggling a URL into a color field; an OBJ with a 10⁹-vertex header; a job response pointing
the download at a non-provider host — refused, per the loop-release precedent).

## 7. Tests (JVM gate) + owner-verify list

- Validators/codec/state-machine/request-builder tests as above.
- `ObjectViewerAssetTest` (mirror `GraphRoomAssetTest`): CSP present, zero external URLs,
  MIT header present, WebGL self-test hook present, loader inventory covers §2's matrix.
- Owner-verify (travels in the PR body): WebGL renders un-garbled on device; orbit/zoom/pan
  feel; big-GLB load time; SAF import flow; Meshy/Tripo end-to-end with a real key.

## 8. Storage

Files under app-private `objects3d/` (hash-named); node metadata carries the relative path.
Export-everything includes the directory (TreeArchive addition). No cloud copy ever kept
beyond the one download (rule 1).

## 9. Explicit non-goals (v1)

Editing/sculpting; AR view; on-device mesh *diffusion*; USDZ full fidelity; video. Each is a
separate owner decision later.
