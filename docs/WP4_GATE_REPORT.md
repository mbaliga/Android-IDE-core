# WP-4 Gate Report — Execution Contract + Authority engine (phase C)

**Scope:** `06_WORK_PACKAGES.md`'s WP-4 entry: execution object model + lifecycle (already declared
in WP-1's `contracts/kotlin/ExecutionContracts.kt`, `docs/ratified/EXECUTION_CONTRACT.md`); a local
Android execution provider (process-supervisor semantics, JVM-mocked); the **real authority policy
engine** `docs/ratified/CAPABILITY_AUTHORITY_MODEL.md` itself names as "not built by this WP-1
pass... a future implementation" (grants, the eight-rung ladder, default-deny, escalation prompts
as a callback-shaped result, no transitive delegation); a secret-handle broker interface; receipts
wired through WP-2's `ReceiptStore`. SSH/CI providers are explicitly **not** in scope (WP-5's job,
per the brief — this pass's `ExecutionProvider` is `LOCAL_ANDROID` only).

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 has used — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — compiled and passed on the first real build attempt; every named gate item verified

---

## 0. What was built

All under `core-engine/src/main/java/dev/aarso/`:

- `domain/authority/CapabilityRegistry.kt` — a compile-time mirror of
  `schemas/loops/registries/capability-ids.v1.json` (the frozen WP-1L-G0 registry), giving
  `AuthorityEngine` the `fb.* -> authorityRung` / `requiresPurposeBinding` lookup
  CAPABILITY_AUTHORITY_MODEL.md §2 says lives in that external registry, not the general model
  itself. All 20 entries manually cross-checked against the real registry file (not automated —
  see §6).
- `domain/authority/AuthorityEngine.kt` — the real decision engine. `evaluate()` resolves one
  capability request to exactly one of the five `AuthorityDecision` variants. The single
  correctness-critical property: it **only ever reads `GrantStore.grantsForPrincipal(requesting
  principal's own id)`** — it never consults an ancestor's grants to satisfy a request. A private
  `ancestorHoldsCoveringGrant` lineage walk exists purely to pick a more specific *deny reason*
  (`AUTHORITY_TRANSITIVE_DELEGATION_REJECTED` vs. plain `AUTHORITY_DEFAULT_DENY`) — it runs only
  after the real decision is already `Deny` and can never turn one into an `Allow`.
- `domain/authority/SecretHandleBroker.kt` — the interface the brief asks for, plus
  `InMemorySecretHandleBroker` (a JVM-testable reference implementation; real Keystore-backed
  storage already exists at `security/KeystoreSecret.kt` and needs a real Android Keystore to
  exercise, per this repo's environment-honesty rule — see §6).
- `domain/authority/SecretRedactionScanner.kt` — the "secret-redaction sentinel test" mechanism:
  scans arbitrary text for verbatim appearances of a run's actual live secret values (not a regex
  heuristic).
- `domain/authority/AuditedAuthorityEngine.kt` — wires `AuthorityEngine` through WP-2's
  `ReceiptStore`: every `evaluate()` call is also an append-only `authority-decision` receipt,
  reusing WP-2's general mechanism rather than inventing a second audit log for this domain alone.
- `domain/execution/LocalProcessExecutionProvider.kt` — the `LOCAL_ANDROID` `ExecutionProvider`.
  Real process lifecycle (prepare → start → observe → terminal receipt), cancellation
  (COOPERATIVE/FORCEFUL, both via real `Process.destroy()`/`destroyForcibly()`), and reconnect
  (token → live handle, or `null` for an unrecognized token, per FB-RAT-EXE-004's two distinct
  failure modes). "JVM-mocked where Android-bound," per the brief: real `LOCAL_ANDROID` execution
  is bounded by the W^X SELinux restriction (no download-and-exec path exists on-device at all —
  `EXECUTION_CONTRACT.md` §3.1), so this class's supervisor *logic* is exercised in this JVM gate
  against `/bin/sh -c` — a real process, safe to spawn here even though it isn't what a real
  on-device `LOCAL_ANDROID` command would look like — the same "test the real mechanism against a
  real JVM-native equivalent" pattern WP-3's `LocalWorkspaceProvider` already established.
- `domain/contracts/AuthorityCodec.kt` / `ExecutionCodec.kt` — JSON encode/decode for every
  top-level `authority`/`execution` shape, same unknown-field-preserving pattern as WP-2/WP-3's
  codecs.
- `di/AppContainer.kt` — wired: `grantStore`/`principalStore` (in-memory, no consumer yet),
  `authorityEngine` (the audited wrapper), `secretHandleBroker`, `localExecutionProvider`.

Six new test files, 42 new tests.

## 1. `core-engine` JVM gate — PASS, first real attempt

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 1m 5s
```

**1339 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since WP-1L
has reported — no regression), up from WP-3's 1297-test baseline. Unlike WP-2 and WP-3, this pass
compiled and passed cleanly on the **first** real Gradle invocation — the accumulated discipline
from those two passes (proactively grepping for the Kotlin nested-block-comment hazard before
ever running Gradle, double-checking every contract field name against the real `*Contracts.kt`
file rather than from memory) paid down here rather than surfacing as a build failure to fix.

## 2. Authority engine — the full privilege-escalation fixture pack, as real engine tests

`AuthorityEngineTest` (16 tests) exercises every named scenario from
`docs/ratified/CAPABILITY_AUTHORITY_MODEL.md` §5/§6/§9 as a real `evaluate()` call, not a static
fixture:

- **Default deny** (§5) — a novel `(principal, capability, scope)` triple with zero grants →
  `Deny(AUTHORITY_DEFAULT_DENY, matchedGrantId=null)`.
- **Child exceeds parent** (§6.3) — a `LOOP_RUN` principal requesting `fb.device.flash` when
  neither it nor its `USER` parent ever held a covering grant → denies (proves the engine doesn't
  accidentally check ancestors for an ordinary, non-transitive-delegation-shaped request).
- **Transitive delegation rejected** (§9) — a three-hop-deep `STUDIO_WORKFLOW` principal (spawned
  by a `LOOP_RUN`, spawned by a `LOCAL_AGENT_PERSONA`, spawned by the `USER`) attempts
  `fb.repo.push_remote` while only its `USER` ancestor holds a covering grant → denies with the
  specific `AUTHORITY_TRANSITIVE_DELEGATION_REJECTED` reason (distinguishing "an ancestor could
  have covered this" from plain "nobody ever did"), `matchedGrantId` still `null`.
- **Purpose-crossing secret use** (§7) — a `fb.secret.use` grant purpose-bound to
  `"asom-model-provider-authentication"` correctly denies a request declaring a different purpose
  (`AUTHORITY_PURPOSE_MISMATCH`) and correctly allows the matching-purpose request — both
  directions tested, not just the rejection.
- **Expired grant** (§7) — a grant whose `expiresAtUtc` is in the past denies
  (`AUTHORITY_GRANT_EXPIRED`), never silently renews.
- **`DelegationRule` widening** (§6, item 2) — constructing a `Grant` whose
  `delegationRule.maxDelegatedRung` exceeds its own `authorityRung` throws at construction time
  (the contract's own `init{}` check, exercised here as a real thrown exception, not just read).
- **Grant/capability rung mismatch** — a genuine bug class this gate report's own engine design
  had to solve carefully (see §4): a grant that names `fb.device.flash` but wrongly declares
  `EXECUTE_REVERSIBLE` (the registry says `EXECUTE_DESTRUCTIVE`) is treated as **not covering
  anything** (fail-safe), denied with a distinct `AUTHORITY_GRANT_RUNG_CAPABILITY_MISMATCH`
  reason rather than being silently trusted.
- Plus: resource-scope mismatch, unknown capability, principal not found, `REVOKED` principal,
  `ALWAYS_REQUIRED` confirmation → `RequireConfirmation`, a grant-level mitigation constraint →
  `AllowWithRedactionOrSandbox`, and an explicit rung-escalation request → `RequireStrongerAuthority`
  (see §3 for why this last variant needed its own, deliberately-scoped mechanism).

`AuditedAuthorityEngineTest` proves every `evaluate()` call also lands as an append-only
`authority-decision` receipt via WP-2's `ReceiptStore` — the "receipts wired through WP-2 store"
requirement, demonstrated end to end rather than left as two unconnected pieces.

## 3. A real bug this pass found and fixed *during design*, not during the Gradle run

While writing `AuthorityEngine.evaluate()`'s scope-mismatch-vs-rung-mismatch branch, an early draft
conflated two different failure reasons: a grant naming the right capability but the *wrong
resourceScope* was reported identically to a grant naming the right capability at the *wrong rung*
(the `grant-authorityrung-capability-mismatch` adversarial case
`CAPABILITY_AUTHORITY_MODEL.md` §2 names as "a case only a real engine consulting the registry can
catch"). Caught by re-reading my own draft against the three distinct cases the decision table
actually requires, before ever running the code — fixed by explicitly splitting `sameCapabilityGrants`
into `rungConsistentGrants` (further split by scope match) vs. the rung-mismatched remainder, each
with its own reason code. `` `grant-rung-capability mismatch -- ...` `` in `AuthorityEngineTest` is
the regression test for exactly this distinction.

## 4. `RequireStrongerAuthority` — a scope decision, stated plainly

The decision table names `RequireStrongerAuthority` for "the request needs a rung no held grant
reaches." Read literally against a single fixed `fb.*` capability (which always sits at exactly
one registry-declared rung), that condition is unreachable as a *distinct* outcome from plain
`Deny` — a principal either holds a covering grant for that exact capability (at its one fixed
rung) or does not. Rather than leave this variant completely untriggerable, `evaluate()` gained an
explicit, documented `escalateToRung` parameter: a caller may ask the engine to evaluate as if a
specific invocation needed a rung *above* the capability's nominal one ("this specific operation's
authority needs widened mid-run," per the decision table's own example). This always resolves to
`RequireStrongerAuthority`, citing the base grant (if any) as a courtesy only — never as sufficient
authority. Flagged here as a genuine scope interpretation, not hidden in the code.

## 5. `LocalProcessExecutionProvider` — lifecycle, cancellation, reconnect, all real

`LocalProcessExecutionProviderTest` (7 tests): a successful `echo` command reaches
`SUCCEEDED_UNVERIFIED` (never `SUCCEEDED` — this provider performs no semantic result
verification, so `ReceiptVerification.state` is honestly `NOT_PERFORMED`, which `ExecutionReceipt`'s
own `init{}` check requires to pair with `SUCCEEDED_UNVERIFIED`, never `SUCCEEDED`); a failing
command (`exit 7`) reaches `FAILED_SAFE`; both `COOPERATIVE` (`SIGTERM`) and `FORCEFUL`
(`SIGKILL`) cancellation genuinely stop a real `sleep 30` process within the 2-second confirmation
window; `reconnect()` with a live token returns the current handle
(`ReconnectOutcome.ESTABLISHED`), with an unrecognized token returns `null` (FB-RAT-EXE-004's two
distinct failure modes, both exercised). `SecretRedactionScannerTest`'s last case runs a full
prepare→start→observe cycle and scans the resulting receipt's log excerpt against a set of live
secret values — the "secret-redaction sentinel test," zero hits.

## 6. What's still genuinely unverified

- **`CapabilityRegistry` vs. the real `capability-ids.v1.json` file** — manually cross-checked
  (all 20 entries match), not automated. An automated consistency test was considered and skipped
  to avoid Gradle-working-directory-relative-path fragility for a file that lives outside any
  module's resource directory; flagged for a future pass rather than silently assumed correct.
- **Real Android Keystore-backed secrets** — `InMemorySecretHandleBroker` is JVM-testable
  reference plumbing only; `security/KeystoreSecret.kt`'s real AES-GCM/Keystore path needs a real
  Android Keystore, unavailable in this sandbox — owner-verified per this repo's standing rule.
- **Real on-device `LOCAL_ANDROID` execution** — this pass's provider is exercised against
  `/bin/sh -c` in the JVM sandbox; the actual W^X-constrained, package-signed-executable-only
  behavior a real Android `LOCAL_ANDROID` target requires is unverifiable without a device, per
  `EXECUTION_CONTRACT.md` §3.1 and this repo's environment-honesty rule.
- **Grant/Principal persistence** — in-memory only this pass (`InMemoryGrantStore`/
  `InMemoryPrincipalStore`), same "no consumer or persistence need yet" note WP-2/WP-3 each left
  for comparable pieces; a Room-backed store is a reasonable follow-up once something actually
  issues/reads grants across a restart.
