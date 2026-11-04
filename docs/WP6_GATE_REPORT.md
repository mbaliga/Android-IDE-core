# WP-6 Gate Report — search contracts + wiring existing LexicalSearch into the Workspace Kernel

**Scope:** `06_WORK_PACKAGES.md`'s WP-6 entry: contracts for index source, freshness,
cancellation, result provenance, query grammar, and an embedder-provider interface (sovereignty
constraint); wiring the already-real `LexicalSearch.kt`/`SearchIndexables.kt` into the Workspace
Kernel (WP-3) plus a minimal functional search surface; a semantic-search-stage provider interface
+ flag, behind a disabled default. **Unlike every other domain this build-out has contracted, no
ratified spec or schema corpus for search existed before this pass** — per `docs/WP0_SURVEY.md`
§1(g), the real lexical engine is "already exists, already wired" into conversation search, but
nothing had ever formally named its contract vocabulary. This gate writes that contract against
the real, already-shipped engine.

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — compiled and passed on the first real build attempt; zero regression on the 30 pre-existing lexical search tests

---

## 0. What was built

- **`contracts/kotlin/SearchContracts.kt`** — `IndexSourceKind` (`CONVERSATION` /
  `WORKSPACE_BUFFER`), `IndexFreshness`, `SearchCancellation` (a `fun interface`, mirroring the
  execution domain's cooperative-cancellation posture without importing that domain's
  request/handle machinery), `ResultProvenance`, `QueryGrammarRef` (a citation to the real,
  already-tested grammar at `domain/search/query/*` — not redefined), and
  `SemanticUnavailableReason`. Does **not** redefine an embedder interface — `dev.fonebrew.embedding.
  Embedder` already exists and already satisfies the brief's "embedder provider interface"
  requirement (checked before writing anything, to avoid a duplicate competing interface).
- **`domain/search/WorkspaceSearchProjector.kt`** — maps a WP-3 `BufferSnapshotEntry` (+ its
  caller-supplied materialized content) into a `SearchDoc`, the exact wire shape `LexicalSearch`'s
  real, already-shipped ranking engine consumes for conversation search. This is the literal "wire
  existing LexicalSearch.kt... into the workspace kernel" instruction: **no new ranking/scoring
  code was written** — the existing engine now indexes a second kind of document.
- **`domain/search/WorkspaceSearchIndex.kt`** — the "minimal functional search surface" the brief
  asks for ("final visuals deferred to owner design"). Deliberately a **plain in-memory index, not
  a new SQLDelight table** — see §2 for why. Wraps `LexicalSearch.search()` directly; attaches
  `ResultProvenance` per hit.
- **`domain/search/SemanticSearchProvider.kt`** — the interface plus exactly one real
  implementation, `DisabledSemanticSearchProvider` (the honest disabled default the brief asks
  for). No speculative embedding pipeline invented — see §4.
- **`AppContainer.kt`** — `workspaceSearchIndex` + `semanticSearchProvider` wired (no consumer
  yet, same "domain/data layer ready, live-surface wiring is a later pass" pattern every prior WP
  has left for pieces with no natural default consumer).

Three new test files, 9 new tests.

## 1. `core-engine` JVM gate — PASS, first real attempt

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 1m 14s
```

**1359 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since WP-1L
has reported), up from WP-5's 1350-test baseline. **The pre-existing `LexicalSearchTest` — 30
tests, not the WP-6 brief's stale "24" figure (corrected here the same way this build-out has
already corrected "868 tests" and other stale pack figures) — reran with zero regressions,**
confirming the wiring genuinely reuses the existing engine rather than forking or destabilizing it.

## 2. Why an in-memory index, not a new SQLDelight table

`CLAUDE.md`'s own build rules single out the existing conversation FTS5 schema (`data/search/
Search.sq`, `SearchDriverFactory`) as a **"do not unify"** hazard zone — two SQLDelight-analyzer
workarounds are documented inline there specifically because that schema is fragile to touch.
Extending it to also cover workspace buffers would have meant editing exactly that file. Separately,
WP-3's own gate report (§5) already flagged that **no live human-editing UI exists yet** in this
codebase to populate a persistent workspace-buffer index from — there is nothing today that would
actually write to a persistent index across restarts. Given both facts, a small, honest, in-memory
index that reuses the real ranking engine is the right-sized "minimal functional search surface"
this pass can build and prove — not a speculative persistence layer for a UI that doesn't exist.

## 3. Provenance + freshness — real, tested, not just declared

`WorkspaceSearchIndexTest` proves the full round trip end to end: indexing a `BufferSnapshotEntry`
produces a result whose `ResultProvenance.sourceKind == WORKSPACE_BUFFER` and whose
`IndexFreshness.sourceRevisionOrSequence` carries the buffer's real `journalSequence` (not a
placeholder), `isStale == false` at index time. Re-indexing the same `bufferId` replaces rather
than duplicates; `remove()` genuinely drops a buffer from search results
(FB-RAT-WS-006 — "indexes are disposable projections," this domain's concrete instance of that
invariant); a cancelled `SearchCancellation` genuinely suppresses results even after the rank pass
already ran, proving the cancellation check isn't dead code.

## 4. Semantic stage — contract + honest disabled default, nothing speculative

`SemanticSearchProvider` + `DisabledSemanticSearchProvider` matches the brief's own instruction
("implemented as far as room allows, contracts first... unimplemented semantic feature exists as
contract + disabled flag"). `SemanticSearchProviderTest` proves the disabled provider reports
`enabled = false` and returns `SemanticSearchOutcome.Unavailable(FEATURE_DISABLED)` rather than a
fabricated empty-but-claimed-successful result set — the difference matters: a caller checking only
`hits.isEmpty()` couldn't distinguish "searched, found nothing" from "didn't actually search,"
`Unavailable` cannot be confused for either. A real ASOM-routed implementation
(`CAPABILITY_AUTHORITY_MODEL.md` §10.2's `127.0.0.1:11435` local routing) is a follow-up, not
invented speculatively here — there is no real embedding index anywhere in this codebase yet
(`dev.fonebrew.embedding.PlaceholderEmbedder` is itself still a placeholder, per its own file).

## 5. What's still genuinely unverified / honestly out of scope this pass

- **No live consumer** — `AppContainer.workspaceSearchIndex` has nothing calling `.index()`/
  `.search()` yet; a real Develop-tab search surface (or a `WorkspaceJournal` observer that
  auto-indexes on save) is a follow-up.
- **`SearchKind.TEXT` reuse for workspace buffers** — flagged, not hidden: `SearchKind`'s own doc
  comment still frames the enum as conversation-specific ("mirrors the Conversations filter
  tabs"); using `TEXT` for a workspace file is a pragmatic, documented stretch, not a clean
  first-class fit.
- **Semantic search** — contract + honest-disabled only, as designed (§4); no real embedding index.
- **No automated grammar-conformance check** — `QueryGrammarRef` cites `domain/search/query/*` by
  name but does not itself re-verify that grammar's own (already-passing) tests; this file is a
  citation, not a duplicate test suite.
