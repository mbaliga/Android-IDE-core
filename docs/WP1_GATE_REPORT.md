# WP-1 Gate Report — non-loop contract corpus

**Scope:** the six domain agents' output (`foundations`, `workspace`, `execution`, `authority`,
`device-and-distribution`, `integration`) plus the `non-ratified-registers` agent's three
registers. Explicitly **excludes** `schemas/loops/` and `docs/ratified/loops/` (separate WP-1L
work package, verified separately — confirmed present on disk, not touched or re-validated here).

**Run date:** 2026-08-07. **Method:** independent re-validation via Bash + Python `jsonschema`
4.26.0 + `referencing` (cross-file `$ref` resolution) — the agent reports' own transcripts were
treated as claims to verify, not as ground truth.

## Gate verdict: **YELLOW — green on every testable item, with two named, non-blocking gaps**

No fixture, schema, or traceability defect was found. One real (now-fixed) Kotlin lexical bug was
found and corrected. Two items are honestly UNVERIFIED because this sandbox has no JVM/kotlinc
toolchain, exactly as flagged in every domain agent's own report. Nothing here is a silent green.

---

## 1. Schemas validate — PASS (30/30)

Every schema under `schemas/{common,workspace,execution,authority,devices,integrations}/*.schema.json`
(30 files, `schemas/loops/` excluded) loaded with `json.load` and passed
`jsonschema.Draft202012Validator.check_schema()` with zero exceptions. No missing `$schema`
fields, no fixes needed.

Additionally (beyond the letter of the gate, because cross-file `$ref` correctness is part of
"validates" in a multi-file schema set): built a `referencing.Registry` over all 30 schemas by
`$id` and resolved every `$ref` in every schema against it — **88/88 `$ref` occurrences resolved,
0 unresolvable.** (Execution → common `ArtifactRef`/`CapabilityManifest`, devices → common
`ArtifactRef`/`CapabilityManifest`/`ErrorEnvelope`, etc.)

## 2. Fixtures behave as required — PASS (130/130 JSON files; 25/25 sibling `.expected.txt` present)

Re-validated every fixture against its schema (cross-file `$ref`-aware), independently mapping
each fixture file to its schema by content inspection rather than trusting filename prefixes
alone (three fixtures needed a manual cross-domain mapping — `disconnect-mid-flash` and
`wrong-board-attempt` validate against `schemas/devices/operation.schema.json` despite the
`device-identity`/`firmware-artifact` neighbors in that folder; `play-manifest-declares-
download-and-exec` validates against `schemas/common/capability-manifest.schema.json`, not a
devices schema — exactly as each report described; two workspace adversarial fixtures are JSON
*arrays* validated per-element: `buffer-journal-forced-kill-mid-append` and
`resource-uri-cross-provider-ambiguity`).

| Domain | valid (PASS) | invalid (rejected) | adversarial (structural PASS + `.expected.txt`) | Total |
|---|---|---|---|---|
| authority | 8/8 | 7/7 | 6/6 | 21 |
| common | 6/6 | 6/6 | 4/4 | 16 |
| devices | 9/9 | 10/10 | 5/5 | 24 |
| execution | 14/14 | 8/8 | 3/3 | 25 |
| integrations | 13/13 | 5/5 | 3/3 | 21 |
| workspace | 8/8 | 11/11 | 4/4 | 23 |
| **Total** | **58/58** | **47/47** | **25/25** | **130** |

All 25 adversarial `.expected.txt` siblings confirmed present, non-empty, and (spot-checked
across all six domains) substantive — each states a concrete rejection code, the reason
structural JSON Schema validation cannot catch the defect, and the required real-implementation
behavior. Not placeholder text.

**Zero fixtures behaved unexpectedly.** No invalid fixture validated when it shouldn't have; no
valid fixture failed; no adversarial fixture was schema-rejected or missing its sibling.

## 3. LICENSE-PENDING markers — PASS (48/48), no fixes needed

- `docs/ratified/*.md` (12 files, `loops/` excluded): all carry `> **License:** \`LICENSE-PENDING\`
  — see \`docs/non_ratified/LICENSE_PENDING.md\`.` directly under the H1.
- `schemas/**/*.schema.json` (30 files, `schemas/loops/` excluded): all carry a top-level
  `$comment` starting `LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md.`
- `contracts/kotlin/*.kt` (6 files): all carry the marker as the first line-comment.

Every marker was present and correctly formatted on first check — nothing needed adding.

## 4. Traceability matrix — regenerated, PASS (101/101 IDs, 0 dangling, 0 double-owned) with 1 named format gap

`docs/TRACEABILITY_MATRIX.md` regenerated from `inputs/RATIFICATION_REGISTER.md` (the handoff
pack's authoritative 109-ID register — the original `inputs/TRACEABILITY_MATRIX.md` only ever
covered the 14 `ACCEPTED`-only `docs/ratified/*.md` artifacts and was never a complete picture of
DEFERRED/EXPERIMENTAL/REJECTED IDs; it stays untouched as historical record). All 101 non-loop IDs
(8 `FB-RAT-LOOP-*` IDs excluded, separate WP) were confirmed present in their register-assigned
artifact, with no artifact's own `Status:` line claiming ownership of another domain's ID. Full
detail and the reasoning behind classifying most raw-grep "duplicates" as benign, expected
cross-references (not ownership conflicts) is in `docs/TRACEABILITY_MATRIX.md`'s Findings section.

**Named gap (not a dangling reference, a format inconsistency):** all 5 integration-domain
ratified docs cite their 22 `ACCEPTED` decision IDs as short-form `INT-NNN` rather than the
corpus-standard full `FB-RAT-INT-NNN` form every other domain uses. Content coverage is complete
(manually verified — every ID is addressed in its correct home document) but `grep -r
"FB-RAT-INT-0" docs/ratified/` returns zero hits, which would break any tooling that greps for the
canonical ID string. Left unfixed here: correcting ~90 citation instances across 5 already-
reviewed documents is a real edit, not a "trivial, safe, single-token" fix like a missing
`$schema` field or a missing LICENSE-PENDING marker, and mis-editing prose under time pressure
risks introducing new errors into content that otherwise checks out. **Flagged as an explicit
follow-up** for the next session or the owner.

## 5. Kotlin — one genuine defect found and fixed; compilation remains UNVERIFIED

Every domain agent reported only a brace/paren-balance self-check, honestly labeled
"UNVERIFIED — no kotlinc in this sandbox." That self-check method (naive character counting) is
weaker than it sounds: it can't tell a real `{`/`}` from one embedded inside a string or a
comment, and it can't tell a real block-comment terminator from a coincidental `*/` inside a
comment's own prose. This gate replaced it with a proper lexer-aware scanner (tracks `//` line
comments, `/* */` block comments including premature-closure detection, `"..."` string literals
with `\`-escapes, `'x'` char literals, and Kotlin's `${...}` string-interpolation braces) run
against all 6 files.

**Found:** `contracts/kotlin/IntegrationContracts.kt` line 73 (inside the KDoc comment for
`ImportErrorCode`, opened at line 72) originally read:

> `* The closed IMPORT_*/PROOF_MISSING vocabulary (docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md`

The substring `IMPORT_*` immediately followed by `/PROOF_MISSING` contains the literal two-
character sequence `*/`, which is a block-comment terminator in Kotlin (and Java/C/C++/JS). This
prematurely closes the `/**` comment mid-sentence, at line 73, before its intended closing `*/` on
line 78. Everything between the accidental early close and the real one (roughly "PROOF_MISSING
vocabulary ... A plain `enum class` cannot represent ... mirroring ...") would be parsed as live
top-level Kotlin source — prose text with backticks, angle brackets, section marks, and em-dashes
— which is not valid Kotlin syntax. **This is a genuine compile-blocking defect**, independent of
and stronger than "unverified" — it was locatable and provable through comment-lexing rules alone,
with no compiler needed. It is exactly the kind of thing a naive brace-counter cannot see (which
is why the domain agent's own "147/147, 249/249" self-check didn't catch it — that check counts
the raw `{`/`}` characters in the file, which happened to stay balanced even with the comment
misparsed, since no stray `{`/`}` occurred in the accidentally-uncommented span).

**Fix applied** (trivial, single line, no semantic change — the doc comment now reads `` `IMPORT_*`
/ `PROOF_MISSING` `` with the slash and asterisk separated so no `*/` sequence occurs):

```diff
- * The closed IMPORT_*/PROOF_MISSING vocabulary (docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md
+ * The closed `IMPORT_*` / `PROOF_MISSING` vocabulary (docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md
```

After the fix, all 6 files scan as lexically balanced (braces/parens/brackets all end at depth 0,
never dip negative, no unterminated string/char/comment state at EOF):

| File | Lexer-aware balance |
|---|---|
| `AuthorityContracts.kt` | OK |
| `CommonContracts.kt` | OK |
| `DeviceContracts.kt` | OK |
| `ExecutionContracts.kt` | OK |
| `IntegrationContracts.kt` | OK (after fix) |
| `WorkspaceContracts.kt` | OK |

A whole-corpus scan for the same class of bug (a doc-comment containing a bare `*/` in its own
prose) found no other occurrences.

**Kotlin compiles — UNVERIFIED, honestly, as an explicit follow-up.** Lexical balance is a
necessary but not sufficient condition for compiling — type errors, unresolved references, and
other semantic issues cannot be ruled out without `kotlinc`/Gradle, which this sandbox does not
have (matches `CLAUDE.md`'s environment-honesty rule and every domain agent's own header). **This
is the sub-item this gate cannot close; it needs the next session (or CI) with the Gradle
toolchain available to run `./gradlew :app:compileFullDebugKotlin` (or equivalent) against these
6 files plus their `dev.aarso.contracts.*` package structure.**

## 6. Unknown-field round-trip — UNVERIFIED, honestly, as an explicit follow-up

FB-RAT-COM-003 requires "preserve unknown minor fields when round-tripping." This cannot be
proven without a real deserializer/serializer pair actually running (decode a JSON payload with
an extra unrecognized field → re-encode it → assert the extra field survived) — a genuinely
runtime property, not something `jsonschema.validate()` on a static fixture can establish, and
this sandbox has no Kotlin runtime to execute one.

**What was confirmed structurally** (necessary-but-not-sufficient design evidence, not proof of
round-trip behavior): every `schemas/**/*.schema.json` checked sets `additionalProperties: true`
at the relevant object level and defines an explicit `unknownFields` wire property; every
corresponding Kotlin data class in `contracts/kotlin/*.kt` declares a matching
`val unknownFields: Map<String, Any?> = emptyMap()` field. This shows the contract was *designed*
for round-trip preservation (a decoder has somewhere to park fields it doesn't recognize, and an
encoder has something to re-emit them from) — it does not show the design actually works end to
end. **Flagged as an explicit follow-up**, same blocker as item 5: needs a real Kotlin runtime.

---

## Summary against the WP-1 gate text

| Gate item | Verdict |
|---|---|
| All schemas validate | **PASS** — 30/30, plus 88/88 `$ref`s resolved |
| Valid fixtures pass, invalid fixtures fail | **PASS** — 130/130 JSON fixtures behaved exactly as required; 25/25 adversarial `.expected.txt` present and substantive |
| Kotlin compiles | **UNVERIFIED** (no kotlinc in this sandbox) — 1 real lexical defect found and fixed during this gate (`IntegrationContracts.kt`, see §5); all 6 files now lexically balanced; actual compilation is an explicit follow-up for the next session/CI |
| Unknown-field round-trip proven | **UNVERIFIED** (no JVM runtime in this sandbox) — structural design evidence present (see §6); genuinely requires a real deserializer to prove; explicit follow-up |
| Traceability matrix regenerated and complete | **PASS** — `docs/TRACEABILITY_MATRIX.md` regenerated; 101/101 in-scope IDs assigned to exactly one artifact, 0 dangling, 0 double-owned; 1 named format-consistency gap (§4), not a content gap |

**No item is reported green that is not actually green.** The two UNVERIFIED items are UNVERIFIED
for the same structural reason stated in every domain agent's own report and in `CLAUDE.md`'s
environment-honesty rule (no device/emulator/JVM toolchain in this build sandbox) — they are not
silently passed, and they are not new problems introduced by this gate. The one genuine defect
this gate found (the `*/`-in-KDoc-prose Kotlin lexical bug) was fixed in place, with the exact
diff shown above.

## Appendix — every WP-1 file on disk (`schemas/loops/`, `docs/ratified/loops/` excluded)

207 files: 12 `docs/ratified/*.md` + 4 `docs/non_ratified/*.md`, 30 schema files across 6 domains,
6 Kotlin contract files, 155 fixture files (130 JSON + 25 sibling `.expected.txt`). Plus this
report and the regenerated traceability matrix (2 more files, not part of the domain agents'
corpus — gate output).

```
docs/non_ratified/DEFERRED_DECISIONS.md
docs/non_ratified/EXPERIMENTAL_DECISIONS.md
docs/non_ratified/LICENSE_PENDING.md
docs/non_ratified/REJECTED_ALTERNATIVES.md
docs/ratified/ASSAY_REPO_CONTRACT_V1.md
docs/ratified/CAPABILITY_AUTHORITY_MODEL.md
docs/ratified/COMMON_CONVENTIONS.md
docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md
docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md
docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md
docs/ratified/EXECUTION_CONTRACT.md
docs/ratified/IMPORT_RECEIPT_V1.md
docs/ratified/INCIDENT_SOURCE_AND_PROOF_CONTRACT.md
docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md
docs/ratified/PRODUCT_DIRECTION_AND_BENCHMARK_BASELINE.md
docs/ratified/WORKSPACE_KERNEL_SPEC.md
schemas/authority/decision.schema.json
schemas/authority/grant.schema.json
schemas/authority/principal.schema.json
schemas/common/artifact-ref.schema.json
schemas/common/capability-manifest.schema.json
schemas/common/conformance-suite.schema.json
schemas/common/envelope.schema.json
schemas/common/error.schema.json
schemas/common/migration-plan.schema.json
schemas/devices/connection.schema.json
schemas/devices/device-identity.schema.json
schemas/devices/firmware-artifact.schema.json
schemas/devices/operation.schema.json
schemas/devices/snapshot.schema.json
schemas/execution/handle.schema.json
schemas/execution/receipt.schema.json
schemas/execution/request.schema.json
schemas/execution/target.schema.json
schemas/integrations/assay-index.v1.schema.json
schemas/integrations/import-preview.v1.schema.json
schemas/integrations/import-receipt.v1.schema.json
schemas/integrations/issues-manifest.v1.schema.json
schemas/integrations/proving-tests.v1.schema.json
schemas/workspace/buffer-journal-entry.schema.json
schemas/workspace/document-buffer.schema.json
schemas/workspace/project.schema.json
schemas/workspace/reconnect-token.schema.json
schemas/workspace/recovery-snapshot.schema.json
schemas/workspace/resource-uri.schema.json
schemas/workspace/workspace.schema.json
contracts/kotlin/AuthorityContracts.kt
contracts/kotlin/CommonContracts.kt
contracts/kotlin/DeviceContracts.kt
contracts/kotlin/ExecutionContracts.kt
contracts/kotlin/IntegrationContracts.kt
contracts/kotlin/WorkspaceContracts.kt
fixtures/authority/adversarial/decision-child-exceeds-parent.adversarial.json
fixtures/authority/adversarial/decision-child-exceeds-parent.expected.txt
fixtures/authority/adversarial/decision-default-deny-unmatched.adversarial.json
fixtures/authority/adversarial/decision-default-deny-unmatched.expected.txt
fixtures/authority/adversarial/decision-purpose-crossing-secret-use.adversarial.json
fixtures/authority/adversarial/decision-purpose-crossing-secret-use.expected.txt
fixtures/authority/adversarial/decision-request-against-expired-grant.adversarial.json
fixtures/authority/adversarial/decision-request-against-expired-grant.expected.txt
fixtures/authority/adversarial/decision-transitive-delegation-attempt.adversarial.json
fixtures/authority/adversarial/decision-transitive-delegation-attempt.expected.txt
fixtures/authority/adversarial/grant-authorityrung-capability-mismatch.adversarial.json
fixtures/authority/adversarial/grant-authorityrung-capability-mismatch.expected.txt
fixtures/authority/invalid/decision-allow-missing-matchedgrant.invalid.json
fixtures/authority/invalid/decision-require-stronger-authority-missing-rung.invalid.json
fixtures/authority/invalid/grant-delegation-rung-widening.invalid.json
fixtures/authority/invalid/grant-transitive-delegation-flag-rejected.invalid.json
fixtures/authority/invalid/grant-widening-approval-flag-rejected.invalid.json
fixtures/authority/invalid/principal-nonuser-missing-parent.invalid.json
fixtures/authority/invalid/principal-user-with-parent.invalid.json
fixtures/authority/valid/decision-allow-valid.json
fixtures/authority/valid/decision-require-confirmation-valid.json
fixtures/authority/valid/grant-cloud-inference-delegable-valid.json
fixtures/authority/valid/grant-read-repo-valid.json
fixtures/authority/valid/grant-secret-use-purpose-bound-valid.json
fixtures/authority/valid/grant-ssh-remote-exec-now-expired-valid.json
fixtures/authority/valid/principal-loop-run-valid.json
fixtures/authority/valid/principal-user-valid.json
fixtures/common/adversarial/artifact-ref-path-traversal.adversarial.json
fixtures/common/adversarial/artifact-ref-path-traversal.expected.txt
fixtures/common/adversarial/capability-manifest-privilege-escalation.adversarial.json
fixtures/common/adversarial/capability-manifest-privilege-escalation.expected.txt
fixtures/common/adversarial/envelope-integrity-digest-mismatch.adversarial.json
fixtures/common/adversarial/envelope-integrity-digest-mismatch.expected.txt
fixtures/common/adversarial/error-secret-leak-in-detail.adversarial.json
fixtures/common/adversarial/error-secret-leak-in-detail.expected.txt
fixtures/common/invalid/artifact-ref-missing-verification-state.invalid.json
fixtures/common/invalid/capability-manifest-missing-versions.invalid.json
fixtures/common/invalid/conformance-suite-bad-testclass-enum.invalid.json
fixtures/common/invalid/envelope-unsupported-major.invalid.json
fixtures/common/invalid/error-missing-recovery-action.invalid.json
fixtures/common/invalid/migration-plan-rollback-contradiction.invalid.json
fixtures/common/valid/artifact-ref-valid.json
fixtures/common/valid/capability-manifest-valid.json
fixtures/common/valid/conformance-suite-valid.json
fixtures/common/valid/envelope-valid.json
fixtures/common/valid/error-valid.json
fixtures/common/valid/migration-plan-valid.json
fixtures/devices/adversarial/connection-lock-holder-secret-leak.adversarial.json
fixtures/devices/adversarial/connection-lock-holder-secret-leak.expected.txt
fixtures/devices/adversarial/disconnect-mid-flash.adversarial.json
fixtures/devices/adversarial/disconnect-mid-flash.expected.txt
fixtures/devices/adversarial/operation-authority-without-current-lock.adversarial.json
fixtures/devices/adversarial/operation-authority-without-current-lock.expected.txt
fixtures/devices/adversarial/play-manifest-declares-download-and-exec.adversarial.json
fixtures/devices/adversarial/play-manifest-declares-download-and-exec.expected.txt
fixtures/devices/adversarial/wrong-board-attempt.adversarial.json
fixtures/devices/adversarial/wrong-board-attempt.expected.txt
fixtures/devices/invalid/connection-busy-without-lock.invalid.json
fixtures/devices/invalid/connection-missing-state-reason.invalid.json
fixtures/devices/invalid/device-identity-missing-confidence.invalid.json
fixtures/devices/invalid/firmware-artifact-empty-board-compatibility.invalid.json
fixtures/devices/invalid/operation-destructive-without-authorization.invalid.json
fixtures/devices/invalid/operation-failed-side-effects-empty.invalid.json
fixtures/devices/invalid/operation-flash-missing-artifact.invalid.json
fixtures/devices/invalid/operation-state-generic-failure-rejected.invalid.json
fixtures/devices/invalid/operation-succeeded-without-verification.invalid.json
fixtures/devices/invalid/snapshot-last-known-good-without-verification.invalid.json
fixtures/devices/valid/connection-busy-with-lock-valid.json
fixtures/devices/valid/connection-ready-valid.json
fixtures/devices/valid/device-identity-arduino-uno-valid.json
fixtures/devices/valid/device-identity-uncataloged-ch340-valid.json
fixtures/devices/valid/firmware-artifact-arduino-uno-blink-valid.json
fixtures/devices/valid/operation-flash-succeeded-verified-valid.json
fixtures/devices/valid/operation-self-test-in-progress-valid.json
fixtures/devices/valid/snapshot-unverified-not-last-known-good-valid.json
fixtures/devices/valid/snapshot-verified-last-known-good-valid.json
fixtures/execution/adversarial/receipt-secret-leak-in-log-excerpt.adversarial.json
fixtures/execution/adversarial/receipt-secret-leak-in-log-excerpt.expected.txt
fixtures/execution/adversarial/request-secret-value-in-envvars.adversarial.json
fixtures/execution/adversarial/request-secret-value-in-envvars.expected.txt
fixtures/execution/adversarial/target-visibility-contract-incomplete.adversarial.json
fixtures/execution/adversarial/target-visibility-contract-incomplete.expected.txt
fixtures/execution/invalid/handle-missing-heartbeat.invalid.json
fixtures/execution/invalid/handle-stale-token-false-success-rejected.invalid.json
fixtures/execution/invalid/receipt-failed-side-effects-empty.invalid.json
fixtures/execution/invalid/receipt-succeeded-without-verification.invalid.json
fixtures/execution/invalid/request-external-missing-idempotency.invalid.json
fixtures/execution/invalid/request-open-ended-datasync-rejected.invalid.json
fixtures/execution/invalid/target-missing-fgstype.invalid.json
fixtures/execution/invalid/target-visibility-contract-missing.invalid.json
fixtures/execution/valid/handle-reconnect-stale-token-unknown.json
fixtures/execution/valid/handle-running-valid.json
fixtures/execution/valid/handle-succeeded-valid.json
fixtures/execution/valid/receipt-failed-side-effects-possible-valid.json
fixtures/execution/valid/receipt-log-excerpt-redacted-clean.json
fixtures/execution/valid/receipt-succeeded-unverified-valid.json
fixtures/execution/valid/receipt-succeeded-verified-valid.json
fixtures/execution/valid/receipt-target-state-unknown-valid.json
fixtures/execution/valid/request-bounded-build-valid.json
fixtures/execution/valid/request-external-ci-dispatch-valid.json
fixtures/execution/valid/request-open-ended-agent-loop-valid.json
fixtures/execution/valid/target-github-actions-valid.json
fixtures/execution/valid/target-local-android-valid.json
fixtures/execution/valid/target-ssh-host-valid.json
fixtures/integrations/adversarial/import-preview-digest-mismatch.adversarial.json
fixtures/integrations/adversarial/import-preview-digest-mismatch.expected.txt
fixtures/integrations/adversarial/import-receipt-path-traversal-sourcelocation.adversarial.json
fixtures/integrations/adversarial/import-receipt-path-traversal-sourcelocation.expected.txt
fixtures/integrations/adversarial/issues-manifest-producer-neutrality-task-injection.adversarial.json
fixtures/integrations/adversarial/issues-manifest-producer-neutrality-task-injection.expected.txt
fixtures/integrations/invalid/assay-index-partial-missing-explanation.invalid.json
fixtures/integrations/invalid/import-preview-missing-sourcedigest.invalid.json
fixtures/integrations/invalid/import-receipt-bad-decision-and-errorcode.invalid.json
fixtures/integrations/invalid/issues-manifest-missing-severity.invalid.json
fixtures/integrations/invalid/proving-tests-passing-without-lastrun.invalid.json
fixtures/integrations/valid/assay-index-valid.json
fixtures/integrations/valid/import-preview-valid.json
fixtures/integrations/valid/import-receipt-baseline-valid.json
fixtures/integrations/valid/import-receipt-conflict-rejected-valid.json
fixtures/integrations/valid/import-receipt-duplicate-snapshot-valid.json
fixtures/integrations/valid/import-receipt-oversized-batch-valid.json
fixtures/integrations/valid/import-receipt-partial-confirmed-valid.json
fixtures/integrations/valid/issues-manifest-baseline-valid.json
fixtures/integrations/valid/issues-manifest-conflict-identity-mismatch-valid.json
fixtures/integrations/valid/issues-manifest-duplicate-resubmit-valid.json
fixtures/integrations/valid/issues-manifest-oversized-valid.json
fixtures/integrations/valid/issues-manifest-partial-batch-valid.json
fixtures/integrations/valid/proving-tests-valid.json
fixtures/workspace/adversarial/buffer-journal-forced-kill-mid-append.adversarial.json
fixtures/workspace/adversarial/buffer-journal-forced-kill-mid-append.expected.txt
fixtures/workspace/adversarial/document-buffer-unresolved-conflict.adversarial.json
fixtures/workspace/adversarial/document-buffer-unresolved-conflict.expected.txt
fixtures/workspace/adversarial/reconnect-token-plaintext-secret-leak.adversarial.json
fixtures/workspace/adversarial/reconnect-token-plaintext-secret-leak.expected.txt
fixtures/workspace/adversarial/resource-uri-cross-provider-ambiguity.adversarial.json
fixtures/workspace/adversarial/resource-uri-cross-provider-ambiguity.expected.txt
fixtures/workspace/invalid/buffer-journal-entry-delete-with-content.invalid.json
fixtures/workspace/invalid/buffer-journal-entry-insert-missing-content.invalid.json
fixtures/workspace/invalid/document-buffer-conflict-state-mismatch.invalid.json
fixtures/workspace/invalid/document-buffer-dirty-state-contradiction.invalid.json
fixtures/workspace/invalid/document-buffer-save-failed-missing-error.invalid.json
fixtures/workspace/invalid/project-missing-rooturi.invalid.json
fixtures/workspace/invalid/reconnect-token-missing-expiry.invalid.json
fixtures/workspace/invalid/recovery-snapshot-missing-content-digest.invalid.json
fixtures/workspace/invalid/resource-uri-bad-provider-enum.invalid.json
fixtures/workspace/invalid/resource-uri-scheme-provider-mismatch.invalid.json
fixtures/workspace/invalid/workspace-missing-displayname.invalid.json
fixtures/workspace/valid/buffer-journal-entry-valid.json
fixtures/workspace/valid/document-buffer-save-failed-valid.json
fixtures/workspace/valid/document-buffer-valid.json
fixtures/workspace/valid/project-valid.json
fixtures/workspace/valid/reconnect-token-valid.json
fixtures/workspace/valid/recovery-snapshot-valid.json
fixtures/workspace/valid/resource-uri-valid.json
fixtures/workspace/valid/workspace-valid.json
```

Gate output (not part of the domain agents' corpus):
```
docs/TRACEABILITY_MATRIX.md
docs/WP1_GATE_REPORT.md
```

## Addendum (WP-2, 2026-08-07) — "Kotlin compiles" upgraded from UNVERIFIED to REAL-VERIFIED

This gate originally left "Kotlin compiles" UNVERIFIED because this sandbox had no `kotlinc`/
Gradle toolchain (§5). During WP-2, a real Android SDK + JDK toolchain was successfully
provisioned (`scripts/setup-android-sdk.sh`, plus a `LANG=C.utf8` fix for a JVM file-encoding bug
that otherwise breaks Kotlin's synthetic lambda-class naming on any backtick-quoted test name
containing non-ASCII characters — an em dash, in the case that surfaced it). `contracts/kotlin/`
is now wired as an additional source directory into `core-engine`'s main source set
(`core-engine/build.gradle.kts`), so every file this gate covers is part of a real
`:core-engine:compileFullDebugKotlin` run.

**First real compile attempt found one genuine defect this gate's brace-counting scan could not
see**: `contracts/kotlin/IntegrationContracts.kt` compiled cleanly (the WP-1 gate's own lexical
fix already caught that file's `*/`-in-KDoc bug), but two files from **WP-1L** —
`LoopAuthoringContracts.kt` and `LoopPackageContracts.kt` — declared the same
`data class ValidationFinding` in the same package (`dev.aarso.contracts.loops`), a real Kotlin
redeclaration error no structural or lexical check in either the WP-1 or WP-1L gate could detect.
Fixed (see `docs/WP1L_GATE_REPORT.md`'s own addendum for the full account, since the defect and
its fix both live in WP-1L's files, not WP-1's). After the fix: **`:core-engine:compileFullDebugKotlin`
BUILD SUCCESSFUL**, all 14 `contracts/kotlin/*.kt` files (this gate's 6 plus WP-1L's 8) compile
together, and **`:core-engine:testFullDebugUnitTest` BUILD SUCCESSFUL, 1214 tests, 0 failures, 1
ignored** — identical to the pre-existing baseline, confirming the new source directory
introduced zero regressions to the shipping app.

**Still not closed by this addendum:** unknown-field round-trip preservation (§6) remains
UNVERIFIED — compiling the data classes proves they are syntactically and structurally sound, not
that a real encode-decode-re-encode cycle actually preserves an `unknownFields` map end to end.
That needs a real test exercising the round-trip, not just a successful compile — left for WP-2's
own test-writing work or a later session.
