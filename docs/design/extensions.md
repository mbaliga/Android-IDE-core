# Extensions and themes

Status: **declarative layer + themes built and JVM-tested; script runtime designed, not built.**
Owner rulings (2026-11-28): extensions are *declarative packs plus sandboxed scripts*; themes are one
extension kind; Android first, iOS next, so nothing here may depend on Android-only machinery.

## 1. Principles (these decide every fork below)

1. **An extension is data until the user says otherwise.** A manifest states what the extension
   is and what it asks for. Nothing in a manifest executes.
2. **Requests are not grants.** An extension *requests* capabilities; the user *grants* them through
   the existing capability-authority model (`domain/authority`, `docs/ratified/CAPABILITY_AUTHORITY_MODEL.md`).
   Grants are scoped, visible, revocable.
3. **Unsupported means rejected, loudly.** A kind or capability this build does not implement is a
   validation error, never a silent no-op, so nothing looks installed while doing nothing.
4. **No telemetry, no backend, no hidden cloud** (binding rules 1–2 in `CLAUDE.md`). There is no
   extension store we operate; packs move as files or git objects the user chose.
5. **One format on every platform.** A pack must mean the same thing on Android, iOS and desktop.

## 2. Pack shape

A pack is a directory or archive with `extension.json` at its root (plus assets it references).
Content-addressing, signing and the transfer boundary reuse the `.floop` LoopPackage rules
(`contracts/kotlin/LoopPackageContracts.kt`): immutable, digest-addressed, local-only activation.

```json
{
  "format": "fonebrew-extension",
  "formatVersion": 1,
  "id": "dev.example.theme.abyss",
  "name": "Abyss",
  "version": "1.0.0",
  "kind": "theme",
  "author": "…",
  "license": "Apache-2.0",
  "capabilities": ["appearance.write"],
  "theme": { "mode": "DARK", "accent": "#2DD4BF", "texture": 0.30, "gradient": "#4DA3FF" }
}
```

`domain/extension/ExtensionManifest.kt` parses and validates this. Each kind may request only a fixed
capability set (a theme can never ask for anything but `appearance.write`).

## 3. Kinds

| Kind | Payload | Needs a runtime? | Status |
|---|---|---|---|
| `theme` | mode, accent, texture, ambient gradient | no | **built** (`ThemePack`, picker row, 5 bundled packs) |
| `loop` | a `.floop` LoopPackage | no (GraphRunner) | next: wrap the existing package as an extension kind |
| `prompt-pack` / `keymap` / `tool-def` | declarative lists | no | designed, not built |
| `script` | sandboxed program (§4) | yes | **rejected by the validator until §4 lands** |

Themes drive the same four knobs the in-app picker exposes, via `SessionStore`. They do not carry
palette overrides: Hyle's ramp derivation and contrast clamp keep any accent AA-legible, which is
exactly why a pack cannot produce an unreadable UI. Per-token overrides (Hyle `tokens/*.json`) are a
later, separately-reviewed kind because they bypass that clamp.

## 4. Sandboxed scripts (design — needs owner confirmation before code)

**Recommendation: QuickJS-NG (JavaScript), one engine on Android, iOS and desktop.**

- *Why JS:* extensions are authored on a phone, in the IDE. JavaScript needs no toolchain; WebAssembly
  does. One small embeddable interpreter (MIT, a few hundred KB, no JIT) builds for arm64 Android and
  iOS from the same source, keeping the one-format-everywhere rule.
- *Why not WebAssembly first:* better isolation story, but authoring needs a compiler on the phone and
  every language runtime adds size. It can be added later as a second engine behind the same host API.
- *Why not Android dex/APK plugins:* arbitrary native code, Play policy exposure, impossible on iOS.

Sandbox contract (the host API is the *only* way out; there is no ambient authority):

1. No filesystem, network, clock-of-record, process or reflection access by default. Every host call is
   `host.<area>.<verb>(…)` and is checked against the extension's **granted** capabilities at call time.
2. Hard limits per run: wall time, instruction budget, heap size, output size. Exceeding one kills
   the run and records why (legibility). A runaway script cannot starve the UI thread — it runs on its
   own thread/isolate.
3. Capabilities are the existing authority vocabulary, scoped to resources (a workspace, a file glob,
   one host). Anything touching the network is a **watched object**, shown as such (binding rule 2).
4. Keys are never reachable: the runtime holds no secret handles; a script asking an LLM does so
   through a host call that routes via the app's provider layer, which attaches the key itself.
5. Deterministic receipts: each run produces a receipt (inputs digest, granted capabilities used,
   outputs) in the same shape the loop runtime already emits.

Store policy check (to confirm before building): Google Play allows an embedded interpreter running
user-installed scripts that do not change the app's declared purpose; iOS (guideline 2.5.2) permits
interpreters only when scripts do not change the app's primary purpose and are not downloaded to
add features that bypass review. Fonebrew's scripts act inside user-owned workspaces and loops, which
is the intended reading, but this should be reviewed again at the iOS port.

## 5. Install and trust flow

1. User picks a pack (file, git URL they configured, or bundled). The manifest is parsed; failures show
   the exact validation errors.
2. Review sheet states, in plain words: what it is, who signed it (or *unsigned*), and the capabilities
   it requests with their scopes.
3. User grants, partially grants or declines. Declining a requested capability disables only the
   features that need it.
4. Activation is local-only and revocable at any time from one place; uninstall removes the pack and
   its grants.

Unsigned packs are allowed (sovereignty) but are labelled as such and can never receive network or
workspace-write grants without an extra confirmation.

## 6. What exists in code today

- `domain/extension/ExtensionManifest.kt` — kinds, capabilities, strict validation.
- `domain/extension/ThemePack.kt` — theme payload, `Apply` plan, catalog that *reports* rejected packs.
- `data/ThemePackAssets.kt` — loads bundled packs from `assets/extensions/themes/`.
- `ui/theme/ThemePicker.kt` — "Theme packs" row (rendering is owner-verified only).
- Tests: `domain/extension/*` including a test that fails the build if any bundled pack is invalid.

## 7. Open decisions

1. Confirm QuickJS-NG as the script engine (adds a native dependency; MIT).
2. Where installed (non-bundled) packs live on disk, and whether they sync through the tree-sync repo.
3. Signing scheme: reuse the `.floop` signature fields, or a separate extension signing key.
4. Whether `loop` extensions replace the current import dialog or sit beside it.
