# WatchBrew interaction prototype

Open `index.html` directly in a browser. No server, installation, external fonts or network
services are needed. This is a browser concept, not a Wear OS application or Kindle emulator.

Try: Capture → sample dictation → Send; Simulate result → Message → rate/reply; switch next-turn
mode; preview regeneration; enable e-ink → Show on e-ink → Refresh → page through the document.
Disconnect the host, queue a draft, reconnect, then explicitly sync it. Request stop and use
Confirm stop to distinguish the request from its acknowledgment. Disconnect the e-ink display
and verify the existing page remains readable. Change host only after pending work is finished.

Sample dictation inserts text; it does not activate a microphone. All tasks/results are simulated.
State is in memory and resets on reload. Queues, mode switches and ratings are demonstration
state transitions, not connected to core's persistent message tree.

Run `node prototypes/watchbrew/model.test.cjs` from the repo root. The tests check offline
queuing, cancellation races, display independence, stable reading revisions and conversation
history. Physical rendering, accessibility and radio/power behavior need device verification.

See [the design](../../docs/design/watchbrew.md) for architecture, scope and next milestones.
