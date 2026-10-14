# desktop/PORTABILITY.md

> **GROUNDWORK for IOS-1 — NOT a migration.** This file is a *static inventory* of JVM-only API usage
> in the sources the desktop host lane (LX-1) maps. Nothing here migrates, replaces or rewrites any
> code; no `commonMain` source set, no `kotlinx-datetime`, no `kotlinx-serialization` exists in this
> repo because of it. It is the work list that `docs/PORTING_PLAN.md` step **IOS-1** (domain and
> contracts to `commonMain`: `org.json` → kotlinx-serialization, `java.time` → kotlinx-datetime, the
> `java.text` users → platform actuals, `WatchService` / `ProcessBuilder` out of the iOS source set)
> will consume. IOS-1 itself is gated (`OQ-2`, `OQ-38`, `P-LX seam green`) and is **not started**.

**Generated file — do not edit by hand.** Regenerate with `python3 -I desktop/scripts/portability_inventory.py --write`;
`--check` fails if this file is stale. The output is deterministic (sorted, no clock, no absolute paths), so
the same sources always yield the same bytes.

## Scope

The same file set Gradle compiles in `desktop/core-host` (single source: `desktop/core-host/mapping.txt`):

| | files |
|---|--:|
| mapped **main** (`domain/` + `contracts/kotlin`) | 271 |
| mapped **test** (`domain/` tests) | 214 |

Roots: `core-engine/src/main/java/dev/fonebrew/domain`, `contracts/kotlin`, `core-engine/src/test/java/dev/fonebrew/domain`.

Not inventoried because the lane does not compile them (each carries a back-edge into `data/` or the
`shared-libraries` submodule; see `mapping.txt` for the witness line and reason):

- `core-engine/src/main/java/dev/fonebrew/domain/authority/AuditedAuthorityEngine.kt`
- `core-engine/src/main/java/dev/fonebrew/domain/execution/CiActionsExecutionProvider.kt`
- `core-engine/src/main/java/dev/fonebrew/domain/mode/GestureModeDefaults.kt`
- `core-engine/src/test/java/dev/fonebrew/domain/authority/AuditedAuthorityEngineTest.kt`
- `core-engine/src/test/java/dev/fonebrew/domain/execution/CiActionsExecutionProviderTest.kt`
- `core-engine/src/test/java/dev/fonebrew/domain/mode/GestureModeDefaultsTest.kt`
- `core-engine/src/test/java/dev/fonebrew/domain/loop/LoopTemplateCatalogTest.kt`

## How to read the numbers

Each cell is **`files / refs`**. *files* = mapped files that name the API at all. *refs* = CODE lines that
name it by package (an `import` line or a fully-qualified use) — or, for `WatchService` and
`ProcessBuilder`, a line naming the type. Comments, KDoc, string literals and backticked test names are
masked out first, so documentation that merely *mentions* `org.json` is not counted. **refs is not a
call-site count** (`JSONObject(...)` on a line with no `org.json` is invisible to a package scan); files are
the stable unit. `-` means zero.

## 1. Main sources (`domain/` + `contracts/kotlin`) — per package

| package | files | org.json | java.time | java.text | WatchService | ProcessBuilder | Base64 |
|---|--:|--:|--:|--:|--:|--:|--:|
| contracts | 16 | - | 15 / 15 | - | - | - | - |
| domain (root files) | 6 | - | - | - | - | - | - |
| domain/a11y | 1 | - | - | - | - | - | - |
| domain/audit | 1 | - | - | - | - | - | - |
| domain/authority | 4 | - | 1 / 1 | - | - | - | - |
| domain/bpmn | 2 | - | - | - | - | - | - |
| domain/bridge | 3 | 1 / 2 | - | - | - | - | - |
| domain/builds | 3 | 2 / 3 | - | - | - | - | - |
| domain/catalog | 5 | 1 / 2 | - | - | - | - | - |
| domain/cloud | 2 | - | - | - | - | - | - |
| domain/codelens | 1 | - | - | - | - | - | - |
| domain/contracts | 9 | 6 / 12 | 5 / 5 | - | - | - | - |
| domain/cost | 8 | 3 / 4 | - | - | - | - | - |
| domain/council | 7 | - | - | - | - | - | - |
| domain/curation | 12 | 1 / 2 | 1 / 1 | - | - | - | - |
| domain/device | 16 | - | 2 / 2 | - | - | - | - |
| domain/diff | 3 | - | - | - | - | - | - |
| domain/disclosure | 1 | - | - | - | - | - | - |
| domain/execution | 8 | - | 5 / 6 | - | - | 1 / 1 | - |
| domain/format | 2 | - | 1 / 4 | 1 / 4 | - | - | - |
| domain/gesture | 2 | - | - | - | - | - | - |
| domain/git | 4 | 3 / 5 | - | - | - | - | 1 / 2 |
| domain/guide | 1 | - | - | - | - | - | - |
| domain/ide | 4 | 1 / 1 | - | - | - | - | - |
| domain/image | 2 | - | - | - | - | - | - |
| domain/inspect | 1 | - | - | - | - | - | - |
| domain/instrument | 3 | - | - | - | - | - | - |
| domain/integrations | 4 | 2 / 3 | 3 / 3 | - | - | - | - |
| domain/language | 5 | - | - | - | - | - | - |
| domain/ledger | 5 | - | 1 / 3 | - | - | - | - |
| domain/library | 5 | - | - | 1 / 1 | - | - | - |
| domain/loop | 28 | 1 / 3 | 4 / 4 | 1 / 1 | - | - | - |
| domain/markdown | 2 | - | - | - | - | - | - |
| domain/material | 1 | - | - | - | - | - | - |
| domain/mirror | 4 | 1 / 1 | - | - | - | - | - |
| domain/mode | 1 | - | - | - | - | - | - |
| domain/model | 3 | - | - | - | - | - | - |
| domain/net | 2 | - | - | - | - | - | - |
| domain/object3d | 5 | 3 / 4 | 1 / 1 | - | - | - | - |
| domain/pm | 2 | 1 / 2 | - | - | - | - | - |
| domain/prompt | 1 | - | - | - | - | - | - |
| domain/provenance | 2 | - | - | - | - | - | - |
| domain/remote | 9 | - | - | - | - | - | - |
| domain/scope | 5 | - | - | - | - | - | - |
| domain/search | 18 | - | 5 / 9 | 2 / 5 | - | - | - |
| domain/state | 1 | - | - | - | - | - | - |
| domain/sync | 3 | 2 / 2 | - | - | - | - | 1 / 2 |
| domain/tasks | 5 | - | - | - | - | - | - |
| domain/template | 1 | - | - | - | - | - | - |
| domain/thread | 17 | 3 / 5 | 5 / 5 | - | - | - | - |
| domain/tree | 10 | 2 / 4 | - | - | - | - | - |
| domain/workspace | 5 | - | 2 / 2 | - | 1 / 11 | - | - |
| **total** | **271** | **33 / 55** | **51 / 61** | **5 / 11** | **1 / 11** | **1 / 1** | **2 / 4** |

## 2. Test sources (`domain/` tests) — per package

IOS-1 moves tests to `commonTest` "where they can"; this is what stands in the way, per package.

| package | files | org.json | java.time | java.text | WatchService | ProcessBuilder | Base64 |
|---|--:|--:|--:|--:|--:|--:|--:|
| domain (root files) | 1 | - | - | - | - | - | - |
| domain/a11y | 1 | - | - | - | - | - | - |
| domain/audit | 1 | - | - | - | - | - | - |
| domain/authority | 2 | - | 1 / 1 | - | - | - | - |
| domain/bpmn | 1 | - | - | - | - | - | - |
| domain/bridge | 3 | 1 / 1 | - | - | - | - | - |
| domain/builds | 2 | - | - | - | - | - | - |
| domain/catalog | 3 | - | - | - | - | - | - |
| domain/codelens | 1 | - | - | - | - | - | - |
| domain/contracts | 8 | 2 / 2 | 4 / 4 | - | - | - | - |
| domain/cost | 8 | - | - | - | - | - | - |
| domain/council | 7 | - | - | - | - | - | - |
| domain/curation | 10 | 1 / 2 | - | - | - | - | - |
| domain/device | 16 | - | 2 / 2 | - | - | - | - |
| domain/diff | 4 | - | - | - | - | - | - |
| domain/disclosure | 1 | - | - | - | - | - | - |
| domain/execution | 8 | - | 4 / 4 | - | - | - | - |
| domain/format | 2 | - | 1 / 2 | - | - | - | - |
| domain/gesture | 2 | - | - | - | - | - | - |
| domain/git | 2 | 2 / 2 | - | - | - | - | 1 / 2 |
| domain/guide | 1 | - | - | - | - | - | - |
| domain/ide | 4 | 1 / 1 | - | - | - | - | 1 / 2 |
| domain/inspect | 1 | - | - | - | - | - | - |
| domain/instrument | 2 | - | - | - | - | - | - |
| domain/integrations | 4 | 2 / 2 | 3 / 3 | - | - | - | - |
| domain/language | 6 | - | - | - | - | - | - |
| domain/ledger | 3 | - | - | - | - | - | - |
| domain/library | 4 | - | - | - | - | - | - |
| domain/loop | 27 | 2 / 18 | 1 / 1 | - | - | - | - |
| domain/markdown | 2 | - | - | - | - | - | - |
| domain/material | 1 | - | - | - | - | - | - |
| domain/mirror | 2 | 1 / 1 | - | - | - | - | - |
| domain/mode | 1 | - | - | - | - | - | - |
| domain/model | 2 | - | - | - | - | - | - |
| domain/net | 2 | - | - | - | - | - | - |
| domain/object3d | 5 | 3 / 3 | 1 / 1 | - | - | - | - |
| domain/pm | 1 | 1 / 2 | - | - | - | - | - |
| domain/prompt | 1 | - | - | - | - | - | - |
| domain/provenance | 2 | - | - | - | - | - | - |
| domain/remote | 7 | - | - | - | - | - | - |
| domain/scope | 4 | - | 1 / 1 | - | - | - | - |
| domain/search | 15 | - | 5 / 6 | - | - | - | - |
| domain/state | 1 | - | - | - | - | - | - |
| domain/sync | 2 | - | - | - | - | - | - |
| domain/tasks | 3 | - | - | - | - | - | - |
| domain/template | 1 | - | - | - | - | - | - |
| domain/thread | 13 | 4 / 4 | 8 / 8 | - | - | - | - |
| domain/tree | 9 | - | - | - | - | - | - |
| domain/workspace | 5 | - | 2 / 2 | - | - | - | - |
| **total** | **214** | **20 / 38** | **33 / 35** | **-** | **-** | **-** | **2 / 4** |

## 3. Work list: main files per API

These are the files IOS-1 has to touch for each API. (Expand a row.)

<details><summary><b>org.json</b> -- 33 files</summary>

| file | refs |
|---|--:|
| `core-engine/src/main/java/dev/fonebrew/domain/bridge/BridgeCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/builds/BuildsApi.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/builds/CiTrigger.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/catalog/RemoteCatalog.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/AuthorityCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/EnvelopeCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/ExecutionCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/IntegrationsCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/JsonInterop.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/WorkspaceCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/cost/FreeTier.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/cost/PricingBook.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/cost/ProviderUsage.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/curation/CompactionReceiptCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/git/GitContentsApi.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/git/GitLookupApi.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/git/GitTreeApi.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/ide/ScaffoldPublishApi.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/integrations/CsAppImportLane.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/integrations/SarifResults.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/loop/LoopPackageCodec.kt` | 3 |
| `core-engine/src/main/java/dev/fonebrew/domain/mirror/AarsoEventLog.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/object3d/CloudObject3dContracts.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/object3d/Object3dJob.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/object3d/ProceduralScene.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/pm/IssueBoardApi.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/sync/LoopSync.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/sync/TreeArchive.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadChains.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadCodec.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadGraphJson.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/tree/Attachments.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/tree/Sources.kt` | 2 |

</details>

<details><summary><b>java.time</b> -- 51 files</summary>

| file | refs |
|---|--:|
| `contracts/kotlin/AuthorityContracts.kt` | 1 |
| `contracts/kotlin/CommonContracts.kt` | 1 |
| `contracts/kotlin/DeviceContracts.kt` | 1 |
| `contracts/kotlin/ExecutionContracts.kt` | 1 |
| `contracts/kotlin/IntegrationContracts.kt` | 1 |
| `contracts/kotlin/LoopActivationContracts.kt` | 1 |
| `contracts/kotlin/LoopAuthoringContracts.kt` | 1 |
| `contracts/kotlin/LoopCompatibilityContracts.kt` | 1 |
| `contracts/kotlin/LoopDefinitionContracts.kt` | 1 |
| `contracts/kotlin/LoopMarketplaceContracts.kt` | 1 |
| `contracts/kotlin/LoopPackageContracts.kt` | 1 |
| `contracts/kotlin/LoopResultAndLineageContracts.kt` | 1 |
| `contracts/kotlin/LoopRuntimeContracts.kt` | 1 |
| `contracts/kotlin/SearchContracts.kt` | 1 |
| `contracts/kotlin/WorkspaceContracts.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/authority/AuthorityEngine.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/AuthorityCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/EnvelopeCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/ExecutionCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/IntegrationsCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/contracts/WorkspaceCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/curation/CompactionReceiptCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/device/broker/DeviceBroker.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/device/broker/FlashOperationDriver.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/execution/LocalProcessExecutionProvider.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/execution/RunGrants.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/execution/RunOutcome.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/execution/RunRequestFactory.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/execution/SshExecutionProvider.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/format/LocaleFormat.kt` | 4 |
| `core-engine/src/main/java/dev/fonebrew/domain/integrations/AssayImportLane.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/integrations/CsAppImportLane.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/integrations/ImportReceiptBuilder.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/ledger/LedgerAggregations.kt` | 3 |
| `core-engine/src/main/java/dev/fonebrew/domain/loop/LoopImportPresenter.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/loop/LoopInstallationDriver.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/loop/LoopPackageCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/loop/LoopRunDriver.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/object3d/Object3dJob.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/search/WorkspaceSearchIndex.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/search/WorkspaceSearchProjector.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/search/query/FacetEvaluator.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/search/query/QueryParser.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/search/query/RelativeDate.kt` | 5 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadEvent.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadGraph.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadGraphJson.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/thread/ThreadGraphProjector.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/workspace/AgentEditJournalAdapter.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/workspace/LocalWorkspaceProvider.kt` | 1 |

</details>

<details><summary><b>java.text</b> -- 5 files</summary>

| file | refs |
|---|--:|
| `core-engine/src/main/java/dev/fonebrew/domain/format/LocaleFormat.kt` | 4 |
| `core-engine/src/main/java/dev/fonebrew/domain/library/ConversationFilters.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/loop/LoopPackageCodec.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/search/LexicalSearch.kt` | 1 |
| `core-engine/src/main/java/dev/fonebrew/domain/search/Segmenter.kt` | 4 |

</details>

<details><summary><b>WatchService</b> -- 1 files</summary>

| file | refs |
|---|--:|
| `core-engine/src/main/java/dev/fonebrew/domain/workspace/LocalWorkspaceProvider.kt` | 11 |

</details>

<details><summary><b>ProcessBuilder</b> -- 1 files</summary>

| file | refs |
|---|--:|
| `core-engine/src/main/java/dev/fonebrew/domain/execution/LocalProcessExecutionProvider.kt` | 1 |

</details>

<details><summary><b>Base64 (java.util / android.util)</b> -- 2 files</summary>

| file | refs |
|---|--:|
| `core-engine/src/main/java/dev/fonebrew/domain/git/GitContentsApi.kt` | 2 |
| `core-engine/src/main/java/dev/fonebrew/domain/sync/TreeArchive.kt` | 2 |

</details>

## 4. Android-runtime references in mapped main code (any form, not just `import`)

The porting plan's section 2 says the domain tree has "no `android.*`/`androidx.*` import (grep)". That is
true of `import` lines and false of the code: a fully-qualified reference has no import line. This table
is what an `import`-grep cannot see. Every row is a place the file cannot compile on a plain JVM without a
stand-in, and a place iOS needs an `expect`/`actual`.

| file | references |
|---|---|
| `core-engine/src/main/java/dev/fonebrew/domain/search/Segmenter.kt` | 5 × `android.icu.text.BreakIterator`, `android.icu.text.BreakIterator.DONE`, `android.icu.text.BreakIterator.getWordInstance` |

The host lane compiles these files unchanged against `desktop/core-host/src/hostShim/kotlin/android/icu/text/BreakIterator.kt`,
a compile-time stand-in whose factory throws (the same outcome `android.jar`'s stub produces in the Android
unit-test lane), so `Segmenter.defaultSource` takes its `java.text` fallback branch, exactly as in the Android gate.

(The same scan over the mapped *test* sources finds: none.)

## 5. Every other platform-specific import (mechanical, main sources)

Imports whose package starts with `java.`, `javax.`, `android.`, `androidx.`, `org.w3c.`, `org.xml.`, `sun.` or
`com.sun.`, grouped by imported package, most-used first. Several of these are fine on iOS (Kotlin maps
some `java.util` types) and several are not (`java.util.concurrent`, `java.io`, `java.security`,
`javax.crypto`); deciding which is IOS-1's analysis, not this file's.

| imported package | files | import lines |
|---|--:|--:|
| `java.time` | 50 | 56 |
| `java.util` | 9 | 10 |
| `java.net` | 6 | 7 |
| `java.text` | 4 | 5 |
| `java.nio.charset` | 3 | 3 |
| `java.util.concurrent` | 3 | 4 |
| `java.io` | 2 | 2 |
| `java.security` | 2 | 2 |
| `java.time.format` | 2 | 3 |
| `java.math` | 1 | 2 |
| `java.nio` | 1 | 2 |
| `java.nio.file` | 1 | 5 |
| `java.time.temporal` | 1 | 1 |
| `javax.xml.parsers` | 1 | 1 |
| `org.w3c.dom` | 1 | 1 |

## 6. Same table for mapped test sources

| imported package | files | import lines |
|---|--:|--:|
| `java.time` | 33 | 35 |
| `java.util` | 5 | 5 |
| `java.nio.charset` | 2 | 2 |
| `java.io` | 1 | 1 |
| `java.nio.file` | 1 | 1 |
| `java.security` | 1 | 1 |

## What this inventory cannot tell you

- **JVM-isms with no import**: `String.format`, `System.currentTimeMillis`, `Thread`, `@Synchronized`,
  `@Volatile`, `kotlin.jvm.*` annotations and `java.lang.*` types are implicit, so a package scan misses them.
  The only reliable finder is compiling the same sources as `commonMain` (IOS-1's first step).
- **Behavioural equivalence** of any replacement (`kotlinx-datetime` vs `java.time` zone rules,
  `kotlinx-serialization` vs `org.json` number/ordering/escaping): the golden canonicalization vectors
  (`schemas/loops/fixtures/canonicalization/`, `scripts/canon_reference.py`) must stay byte-identical, and
  nothing here has been tested against them on a non-JVM target.
- **iOS runtime behaviour**: nothing in this repo builds, links or runs on iOS. This file is not evidence of
  iOS feasibility.

