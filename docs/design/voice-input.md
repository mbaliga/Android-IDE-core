# Design: Voice input — on-device, push-to-talk

> Status: **built** (2026-07-27), first surface only — the Distill intake (Loop editor →
> "Distill a loop"). `service/OnDeviceDictation.kt` wraps
> `SpeechRecognizer.createOnDeviceSpeechRecognizer` — **never** the networked recognizer, no
> fallback — behind a push-to-talk mic button next to "Choose a file…" in `DistillDialog.kt`.
> Holding it transcribes speech into the same `source` field paste/URL/file-pick already feed, so
> distillation itself needed no new code path — voice is purely a new *intake modality* for the
> existing text pipeline, exactly as sketched below. The mic control only renders where
> `OnDeviceDictation.isAvailable(context)` is true; where it isn't, voice is simply absent, no
> dead button, no cloud fallback. RECORD_AUDIO is requested in context, on first press, via a
> normal Android permission dialog — not up front at install/launch.
>
> Original design (below) still holds for anywhere voice input expands next — e.g. dictating a
> node's system prompt in the Loop editor's node-config panel, or a chat composer's `+` menu.

## Why deferred, and why on-device

Fonebrew has **no speech recognition today, by design**:
`service/FonebrewRecognitionService.kt` is a deliberate stub that returns
`ERROR_CLIENT` — the app is summoned by *gesture*, not a wake-word, and does no
listening. Adding voice is therefore net-new and runs straight into the binding
rule:

- **No telemetry / phoning home (CLAUDE.md §1).** Android's default
  `SpeechRecognizer` and most cloud STT send audio off-device. That is
  incompatible with the thesis. So if/when voice lands, it must be **on-device**.

## The on-device path (when we build it)

- **On-device recognizer**: Android 13+ exposes on-device
  `SpeechRecognizer.createOnDeviceSpeechRecognizer(...)`; the target device
  (Android 13+) supports it. Gate strictly to on-device; never fall back to the
  networked recognizer. Where unavailable, voice is simply absent (no silent cloud
  path).
- **Alternative**: a bundled small offline model (e.g. a Whisper-class GGUF via the
  existing native pipeline) — heavier, but fully under our control and consistent
  with "everything on-device". Evaluate size/latency on the target device before
  committing.
- **Surface**: voice is an *input method* for the same NL authoring the text path
  uses (see `council-workflows.md`) — transcribe → the existing parser. No new
  semantics, just a different way to dictate the objective / edit a node.
- **Push-to-talk, not always-listening.** Consistent with "summoned, not
  wake-worded": the mic opens on an explicit press and closes when done. No
  ambient listening, ever.

## Constraints (must hold)

- On-device recognition only; no cloud STT, no audio leaving the device.
- Explicit, momentary capture (push-to-talk). No background listening.
- Microphone permission requested in context, with a plain explanation.

## Open questions for the owner

- On-device `SpeechRecognizer` (light, OS-provided) vs a bundled Whisper-class
  model (heavier, fully ours) — preference? **Decided for now**: shipped with the OS-provided
  on-device recognizer (no new native engine, no model download) — revisit only if a phone in
  practice reports `isOnDeviceRecognitionAvailable() == false` often enough to matter, or if
  language coverage turns out too thin.
- Languages beyond English (the owner's locale register matters) — the recognizer follows
  whatever the phone's own configured input language is; no explicit language picker built yet.
