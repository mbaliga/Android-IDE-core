# Cloud provider contract tests (fixture + opt-in live)

Scope: `inference/cloud/` (Anthropic, OpenAI-compatible, Gemini; SSE streaming).

## Status, plainly

- **Fixture tests (always on):** pass in the normal gate. They are **documentation-derived**:
  every transcript under `core-engine/src/test/resources/cloud-contract/` was hand-authored
  from each provider's public streaming docs. **None was recorded from a live call.**
- **Live suite (opt-in):** `LiveContractTest`. **It has never been run** (the author had no key).
  It is known only to compile and to skip cleanly. **Live behaviour is UNVERIFIED** until a
  developer runs it with a real key and reports the result here.

## What the fixture tests prove

Each engine is driven through the real OkHttp SSE stack against a loopback-only fake server
(`FakeProviderServer`, binds 127.0.0.1, never dials out). Per provider:

- normal streamed text; real usage counts reach `lastUsage`; absent usage leaves `lastUsage`
  at `ZERO` (never invented; consumers then label their own estimate)
- documented stop reasons complete cleanly (Anthropic `pause_turn` latches `wasPaused`)
- mid-stream error event becomes an error (text already sent is kept); a key echoed in it is redacted
- HTTP 401 (key echoed by the provider is redacted), 429 with `Retry-After` (one request, no hidden
  retry), 5xx; a connection cut mid-chunk fails loudly
- one malformed JSON event is skipped; unknown event types ignored; multibyte characters split
  across TCP writes decode intact
- request builder: endpoint, required headers, key only in that provider's own auth header (not
  the URL, body, or another provider's header), key absent from engine/provider `toString`
- `NoNetworkInAlwaysOnTestsTest` fails the gate if an always-on test names a non-loopback host

They do **not** prove the providers still send these shapes, that the model ids work, or anything
about latency, quota or billing.

## Running the live suite

Nothing runs unless **both** are set: `FONEBREW_LIVE_CONTRACT=1` and that provider's key.

```
export FONEBREW_LIVE_CONTRACT=1
export FONEBREW_LIVE_ANTHROPIC_KEY=...   # and/or FONEBREW_LIVE_OPENAI_KEY / FONEBREW_LIVE_GEMINI_KEY
# optional: FONEBREW_LIVE_<ANTHROPIC|OPENAI|GEMINI>_MODEL, FONEBREW_LIVE_OPENAI_BASE_URL
./gradlew --no-daemon :core-engine:testFullDebugUnitTest --tests 'dev.fonebrew.inference.cloud.LiveContractTest'
```

Default model ids in the test are examples and may be stale; override them. This is
developer-initiated cloud use on your own machine and key. Never run in CI; no secret or
workflow step exists for it. The key is read from the environment, passed only to that
provider's engine, and never printed.

Each provider gets two tests: one minimal request (prompt "Reply with the single word: ok",
`max_tokens` 16: roughly 10-30 input and 1-16 output tokens, an estimate, so a fraction of a cent)
that checks streamed text and real usage counts; and one request with a deliberately invalid
key (no tokens billed) that checks a 4xx is surfaced and the bogus key is not echoed.

Without the opt-in every live test reports **skipped** in the JUnit XML (`<skipped/>`), never passed.

## Skipped-count change

Adding this lane raised the skipped count of `:core-engine` per flavor from 1 to 9:
6 live tests (skipped by assumption) plus 2 `@Ignore`d findings below. A green gate with these
skipped says nothing about live providers.

## Findings (not fixed here)

1. A 200 stream that closes cleanly **without its terminal event** (Anthropic `message_stop`,
   OpenAI `[DONE]`) is treated as a normal end, so a cut reply looks complete. Two `@Ignore`d tests
   state the desired behaviour. Not fixed: some OpenAI-compatible servers omit `[DONE]`, so a
   strict rule needs an owner decision.
2. Stop reasons (`max_tokens`, `length`, `MAX_TOKENS`) are not surfaced; a reply cut by the token
   cap is indistinguishable from a finished one. Only `pause_turn` is exposed.
3. The 429 `Retry-After` value is not surfaced to the user (the error says 429 and the provider body).
4. OkHttp's `Request.toString()` redacts only `Authorization`, so `x-api-key` / `x-goog-api-key`
   would print in clear if a `Request` were ever logged. Nothing logs one today.

## Defects found and fixed with this lane

- A provider error event inside a 200 stream was swallowed; the stream just ended. Now an error.
- A key echoed in an error body (401) or error event could reach the exception message. Redacted.
- OpenAI-compatible requests omitted `stream_options.include_usage`, so real usage never arrived.
  Added (behaviour change: a strict server that rejects unknown fields would now 400; owner to
  confirm against the servers in use).
- The debug-only SSE parse-failure log could itself throw and kill the stream; now guarded.
