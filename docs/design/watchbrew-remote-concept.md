> **Provenance (salvage, 2026-11-04):** recovered verbatim from `feat/watchbrew-concept-prototype`
> @ `73fbbc8` (authored 2026-10-22, owner direction of 2026-10-15) before that branch was closed.
> That line was cut before WatchBrew v1 merged, so its `watchbrew/` module collided with the
> shipped one and was NOT imported (reference snapshot: `prototypes/watchbrew/native-m1-snapshot/`).
> This document is the **v2 concept**, not current law — `docs/design/watchbrew.md` (v1) governs
> what ships today. Where the two disagree, the disagreements are owner-direction extensions v2
> must reconcile, most sharply: next-turn mode switching vs core's immutable-interaction-model
> rule (this doc itself says that needs a migration/adapter and an owner ruling), and the watch
> gaining reply/rate/regenerate powers v1 deliberately withheld.

# WatchBrew companion — concept and prototype

Status: browser concept plus native Wear OS fake-host demo, 2026-10-15. Owned entirely by Android-IDE-core.
The `watchbrew/` module builds a debug APK with persisted draft/outbox state and reviewed system
dictation. Real host transport, native e-ink pairing and physical hardware validation remain pending.
See [native module notes](../../watchbrew/README.md) for build and device acceptance instructions.

## Product contract

WatchBrew lets a person capture intent, follow work, answer bounded questions and resume a
conversation from a watch. A phone, computer or explicitly selected cloud host owns execution.
An e-ink display is optional. Every essential watch flow works with that display disabled.
The initial owner devices are Galaxy Watch8 LTE, Bluetooth earphones and a jailbroken Oasis 2.

The watch is a companion, not a full editor: graph authoring, large diffs, credentials and project
configuration belong on a larger surface. Saved Loops can be invoked after reviewing their
scope and budget. Hardware work requires a reachable bridge beside the board; cloud compilation
alone cannot flash an unplugged Arduino. Store releases still need platform-specific build,
signing and review workflows.

This owner direction extends the phone-first model to remote hosts. Cloud is explicit opt-in,
provider-generic, visibly marked as watched. No silent cloud fallback or telemetry. A watch must
never download a large model merely because a host is unavailable.

## First experience

1. Pair a trusted host on the phone or computer, select a project and configure permitted actions.
2. Open WatchBrew: current project, selected host, connection and latest task are visible.
3. Capture by short push-to-talk, keyboard or saved prompt. Review transcription before sending.
4. Receive a short result, a question, or a task-status notification. The watch can then sleep.
5. Read detail on the watch in bounded sections, or explicitly send it to an optional display.
6. Resume on phone/computer using session, branch, message and document-revision identifiers.

The browser prototype uses typed sample dictation and explicit simulated host events. It sends
no network requests, records no audio and stores no credentials. Reload resets its in-memory
session. The interface labels these limits; it does not pretend a task ran.

## Watch information architecture

Home: project, host and connection; Capture, Task, Message. Use large, labeled controls and a
scrollable round viewport with generous edge insets. Future Wear tiles/complications expose
only a short status and entry action. No scrolling transcript or live animation in ambient mode.

Capture: a short draft and review/send step; offline submission becomes a visibly queued intent.
Task: latest confirmed state, last update, scope and a stop request. Sending stop is distinct
from host-confirmed cancellation. A job may finish before the cancellation arrives.
Message: summary, author/model and routing; value -2/-1/0/+1/+2; Reply; Regenerate from here.
Regeneration creates a branch and preserves later history. Require a review step before it runs.
Mode: Single, Council · models, Council · experts. A mode change is an explicit event applying
to the next turn; retain previous turns and participant/configuration provenance. The older
core spec locks a chat's mode. This new owner requirement therefore needs a migration/adapter;
the prototype demonstrates the target, not a claim that the engine already supports it.

Use buttons and accessibility actions for every gesture. Later gesture exploration: vertical
message-value adjustment, left reply, right regeneration preview. Avoid conflicts with Wear OS
back/navigation and scrolling; do not ship gesture-only controls. Screen-reader labels, visible
focus, font scaling and non-color state cues are required. Validate physical target sizes and
round-screen clipping on the watch; browser dimensions are illustrative.

## Optional e-ink workspace

This is a separately rendered document surface, not a permanent screen mirror. It is useful for
conversations, specifications, code, unified diffs, repository summaries and terminal snapshots.

- Explicit opt-in and independent connection state. Disabling it must not affect a watch task.
- Black text on a light background, substantial contrast, no gray-on-gray controls or color-only
  syntax/diff meaning. Use +/−, line numbers and textual labels. Avoid animation and gradients.
- Stable pages, selectable font size, a document title, source revision and page position.
- Freeze the page being read. New results produce a pending-update notice; apply at a page
  boundary or by explicit Refresh. Coalesce intermediate updates rather than streaming tokens.
- Partial refresh for small changes where supported; full refresh on demand and when the device
  requires it. Exact waveform/ghosting policy must be measured per firmware and FBInk support.
- Physical page buttons navigate pages; touch controls provide parity. Preserve reading position
  across sleep and disconnect. Never replace unread text under the user's finger.
- Offline content is clearly labeled cached. Display outage must not block watch interaction.
- Pairing authorizes a display to receive a selected session/document, not unrestricted access to
  all repositories or credentials. The watch remains capable of reviewing a short result itself.

Existing seams: domain/workdeck/WorkdeckNativeDocument.kt and WorkdeckNativePublisher; section
kinds already cover prompt responses, repository state, terminal output, diffs, file context,
agent status and long-form reading. Add session/message linkage and capability negotiation in a
versioned envelope rather than silently changing Workdeck v1 ordinal encoding.

Existing Kindle client targets Oasis 3 on a specific firmware. Oasis 2 is a separate device gate:
verify architecture/binary compatibility, firmware, framebuffer, FBInk, touch orientation, page
button events, sleep/reconnect and refresh behavior before adding a supported device profile.
The browser preview is not an Oasis emulator and cannot establish compatibility.

## Connection architecture

Watch + earphones → paired session host ← optional e-ink client. The host connects to selected
model providers/build workers and optionally the user's Git source. Clients should connect
independently; the watch should not relay large screen/audio streams to the display.

Pocket mode: Android phone is the host; use a Wear companion transport where appropriate.
Remote mode: watch reaches a user-owned computer or opted-in cloud host over LTE/Wi-Fi.
The host owns durable work; losing the watch connection does not kill the task. A phone left at
home is another possible host, but requires separately validated background execution.

Oasis needs its own usable network. Watch8 LTE is not proof of supported hotspot functionality.
With no Kindle Wi-Fi, the watch can still work over LTE and the Kindle can retain cached pages.
Do not promise arbitrary internet through Amazon's Kindle cellular service or a Bluetooth bridge.
The existing Workdeck TCP/HMAC transport is a LAN prototype, not encrypted internet transport.
Remote mode requires authenticated encrypted transport, explicit device pairing/revocation and
bounded data access; do not expose the existing raw server to the public internet.

## Session contract to implement next

A command carries protocolVersion, commandId, deviceId, sessionId, branchId, expectedRevision,
kind and payload. Use persistent unique IDs, replay deduplication and host acknowledgments.
The host serializes accepted commands and rejects stale/conflicting state-changing requests.
Reconnect resumes an event cursor; if unavailable, fetch a snapshot. Wall-clock ordering alone
is insufficient. Device timestamps are informational.

Events include intent queued/accepted, task progress, decision required, result available,
stop requested/confirmed, rating changed, reply linked, branch created, mode changed and document
published. Stop is idempotent and best effort. Do not auto-replay stale approvals, model/council
configuration changes, destructive actions or publishing commands after reconnect. Offline
capture is safe to queue; review the destination before delivery. Persist the outbox in native
implementation; the browser fixture is deliberately ephemeral.

The host retains original turns, revisions, ratings, provenance, branch ancestry and compaction
records in the portable history. Compaction is a derived view, never deletion of the originals.
Git sync remains optional and user-owned. No model-provider keys on the Kindle; paired clients
receive scoped credentials. Remote execution keys are configured on the selected host, encrypted
at rest there, and sent only to their intended provider. Phone-held keys are not silently copied.

## Power and interaction budget

No on-watch model inference or compilation in the initial scope. No always-listening microphone,
continuous display mirroring, unbounded log streaming or polling loop. Push meaningful events,
batch updates and close voice/network sessions promptly. Adapt to OS battery/thermal signals,
show stale/offline state and let the screen sleep while the remote job runs. LTE, speech and
Bluetooth still cost energy; no battery-life or temperature guarantee before measurement.

## Milestones and acceptance

M0 (this change): product contract + dependency-free interactive prototype + state-transition
checks. Demonstrates optional display, disconnected capture, explicit sync, stop acknowledgment,
ratings, reply/regeneration and next-turn modes. No inference or real builds.

M1 (native fake-host source and APK implemented; device acceptance pending): a small Wear OS application module in this repo, backed by a fake session adapter. Keep it
independent of phone-only native inference dependencies. Verify round layout, rotary input,
TalkBack, dictation review and lifecycle on Watch8. Add durable draft/outbox storage.

M2: one real host adapter, pairing, encrypted transport, deduplication, event resume and bounded
job lifecycle. Start with one selected host and one safe read-only project task. Verify device
loss, duplicate commands, late cancellation and reconnect after process death.

M3: Oasis 2 native document adapter and capability profile after physical verification. Measure
reading comfort, ghosting, refreshes, reconnection and power; preserve the watch-only path.

M4: conversation-tree integration, editable council configuration, saved Loops, Git history,
capability-gated hardware bridge actions and carefully scoped approvals. Keep advanced review on
a larger surface. No store publication/firmware flashing from a vague watch confirmation.

Device acceptance: complete one task with the display off; lose LTE mid-task and reconcile;
queue a draft and restart the watch; stop a job with a delayed acknowledgment; read a long diff
without involuntary page replacement; switch modes with complete provenance; resume exactly the
same message on the phone. Record watch battery, thermal state, radio use and interaction latency
under real LTE conditions. These remain owner/device verification, not browser-test claims.

## Prototype

Open ../../prototypes/watchbrew/index.html in a browser. Run state tests with:
`node prototypes/watchbrew/model.test.cjs`.

Primary platform references:
- https://developer.android.com/training/wearables/data/network-communication
- https://developer.android.com/training/wearables/apps/power
- https://developer.android.com/design/ui/wear
