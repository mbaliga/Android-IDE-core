# Semantic search — a real on-device embedder (lane A3)

Status: design note, written before the code. Everything under "Owner-verified only" is a
prediction until the owner runs it on the phone; this container has no device, no emulator and
no embedding model it has run.

## 1. What this adds, and what it does not

Today `PlaceholderEmbedder` is a hashed bag-of-characters (it says so in its own file),
`SemanticSearchProvider` has only `DisabledSemanticSearchProvider`, and the native bridge has no
embedding entry point. This lane adds:

- a native embedding entry point (`llama_embed_jni.cpp`) on its **own** llama context;
- `LlamaEmbedder`, a real `Embedder`, loaded only while semantic search is enabled and a model
  is chosen, unloaded the moment it is disabled;
- pure vector code in `domain/search/` (top-k, chunking, index state machine, rank fusion with
  per-hit provenance, resumable incremental indexer);
- a separate vector store file in app storage (never uploaded, with a Clear action);
- a real `SemanticSearchProvider`;
- a "Semantic search" section in Settings -> Models and a "matched by meaning" chip on hits.

It does **not**: touch `Search.sq` (FTS5 tables, triggers, the two documented SQLDelight
workarounds), edit `llama_jni.cpp` or `LlamaCppEngine`, change `PlaceholderEmbedder` (the
cold-start logging pipeline keeps using it), download anything without an explicit tap, send any
text or vector off the device, or rank anything with a fabricated number.

## 2. The seams

```
Settings (toggle, model, Pause/Rebuild/Clear)        Search overlay
        |                                                   |
SemanticSettingsStore (prefs, default OFF)           SearchViewModel -- lexical path UNCHANGED
        |                                                   |  (semantic stage runs only when active)
SemanticSearchService  --- owns ---> EmbedderHost -> LlamaEmbedder -> LlamaEmbedNative (JNI)
   |        |                                   (loaded iff enabled AND model chosen)
   |        +--> SemanticIndexer (domain, pure) --> VectorStore (interface)
   |                      ^ corpus: SearchDatabase projections (read-only, existing queries)
   +--> EmbeddingSemanticSearchProvider (implements SemanticSearchProvider)
VectorStore impl: SqliteVectorStore (own file semantic-vectors.db)
```

- Corpus is read **read-only** from the existing `conv_projection` / `loop_projection` /
  `task_projection` rows through queries that already exist (`selectRecentWithFacets`,
  `selectRecentLoops`, `selectRecentTasks` with a large limit). No new SQL in `Search.sq`.
- Embedding models live in their **own directory** (`files/embedding-models/`), not in
  `LocalModelStore`, because that store feeds the chat-model picker and an embedding GGUF there
  would show up as a chat model that cannot chat.
- Lexical path: `SearchViewModel` keeps computing `repository.search(...)` exactly as before.
  A second stage runs only when `SemanticSearchService.isActive` (enabled, model loaded, index
  not empty). With semantic off the list handed to the presenter is the same list object, and
  `ResultRow.matchedBy` stays `null`, so no chip is drawn. A test pins this.
- Semantic fusion is applied only to **plain-word queries** (no facet chips, no regex). A
  semantic hit cannot honestly satisfy `is:starred` or `after:-7d`, so those queries stay
  lexical-only; the overlay says nothing extra, same as today.

## 3. Native bridge

`llama_embed_jni.cpp`, compiled into the existing `aarso_llama` target (one added
`target_sources` line; `-march` flags and the android-31 platform pin untouched). JNI class
`dev.fonebrew.embedding.LlamaEmbedNative` (a plain class with `external fun`s; nothing R8 would
rename, and the release build stays unminified).

- `nativeLoad(path, ctxSize, threads) -> long`: positive = opaque handle id, <= 0 = typed
  failure code (bad args, file missing, model load failed, context failed, pooling NONE/RANK
  unsupported, internal). Handles are ids in a mutex-guarded registry, never raw pointers, so a
  stale handle returns `INVALID_HANDLE` rather than crashing.
- `nativeEmbed(handle, utf8, out) -> int`: 0 ok, 1 ok-but-truncated, negative = typed failure
  (null/empty/oversize input, tokenize failed, decode failed, non-finite output, ...). Output is
  L2-normalised. Pooling is whatever the GGUF metadata says (`pooling_type` left UNSPECIFIED so
  llama.cpp reads it); a model whose pooling is NONE or RANK is refused at load, not guessed at.
  Truncation is to the context; if the model appends a SEP/EOS token it is restored after cutting.
- `nativeDim`, `nativeUnload`. Each context has its own `std::mutex`; unload removes the id from
  the registry then waits on that mutex, so an in-flight embed finishes first.
- All entry points are `try/catch(...)`-wrapped; no C++ exception crosses JNI.

llama.cpp API used was read from the pinned submodule's `include/llama.h` (`llama_init_from_model`,
`llama_get_memory`/`llama_memory_clear`, `llama_encode`/`llama_decode`,
`llama_get_embeddings_seq`, `llama_model_n_embd`, `llama_pooling_type`).

## 4. Provenance

Every stored vector carries `(embedder_id, dim)`. `embedder_id` =
`llama-embed:<file name>:<size>:<fingerprint of head+tail bytes>:<prefix profile>:dim<N>` - so a
renamed-but-identical file keeps matching the *content* fingerprint only if the name also
matches, and a different model, a re-quantised file or a different prompt-prefix profile gets a
different id. Rules, all tested:

1. The store returns vectors for exactly one embedder id; `topK` never sees a mixed set.
2. A document whose manifest row has a different embedder id (or content hash) is **stale**
   and is re-embedded; its old vectors are deleted in the same transaction.
3. State `STALE n` is shown while any doc is stale after a model change.
4. `PlaceholderEmbedder` ids (`placeholder-hash-v1:*`) can never be read by the semantic store.

## 5. Store choice

A separate SQLite file via the bundled driver already in the build (`androidx.sqlite`
`BundledSQLiteDriver`, raw statements), **not** a second SQLDelight database and **not** an
FTS5 table:

- a second SQLDelight database needs gradle config and generated code, and anything near
  `Search.sq` risks the documented analyzer workarounds - the brief forbids it;
- a flat file would need hand-written atomic per-doc replace for crash safety; SQLite gives
  transactions for free, which is exactly what "resumable after process death" needs (a doc is
  *done* only when its manifest row commits in the same transaction as its chunk blobs);
- JVM tests run real SQLite (bundled natives ship for the host), same as the search tests.

Vectors are little-endian float32 BLOBs. The file is `files/semantic-vectors.db` (app-private),
excluded from nothing special - and **never uploaded**; the app has no code path that reads it
into a network call. Clear deletes every row (and `VACUUM`s).

## 6. Search algorithm and scale

Brute force: for each query, scan all chunk vectors of the current embedder id and keep the best
k in a bounded min-heap (O(N log k)); per-doc score = max chunk similarity. Vectors are
normalised at write time so cosine = dot.

Where it stops being fine (an estimate from arithmetic, **not measured**): N chunks x D dims x 4
bytes. At D=768, 100k chunks = ~307 MB of float data and ~77M multiply-adds per query - the
memory is the first wall, around 50-100k chunks on a phone sharing RAM with a model. The store
is read streaming per query rather than cached, so the cost is I/O + scan; if a query scan
becomes visibly slow the next step is an in-memory cache or an ANN structure, and that is a
named follow-up, not built here. Chunk cap per doc (24) bounds one huge conversation.

## 7. Chunking, indexing, states

- `DocChunker` reuses `Segmenter` word boundaries: windows of up to 160 words with 24 words of
  overlap, cut on original-text offsets (so the chunk is a real substring), title prefixed to
  every chunk. Hard cap 24 chunks/doc; the doc is marked `truncated`.
- Incremental: plan = docs with no manifest, a different content hash, or a different embedder
  id. Unchanged docs cost nothing. Deleted docs' vectors are removed.
- Pausable: a flag checked between chunks; paused state is reported, not lost.
- Resumable: progress lives only in committed manifest rows; a kill mid-doc leaves that doc
  without a manifest and it is redone on the next run.
- States: `NOT_ENABLED`, `NO_MODEL`, `BUILDING n/total` (+ paused flag), `READY`, `STALE n`.
- Fusion: reciprocal rank fusion, k=60, over the lexical ranking and the semantic ranking. Each
  fused hit keeps `MatchSource` = WORDS / MEANING / BOTH. Semantic-only contributions are capped
  at 10 docs. **No similarity floor is applied** because a threshold would be an uncalibrated,
  model-specific invented number; calibration is owner-verified follow-up (section 10).

## 8. Provider states

`SemanticSearchProvider.search` returns `Results` only when enabled AND model installed AND
loaded AND index has vectors for the loaded embedder. Otherwise a typed `Unavailable`:
disabled -> `FEATURE_DISABLED`; no model chosen / not installed -> `NO_EMBEDDER_CONFIGURED`;
load or embed failure -> `PROVIDER_ERROR`. It never returns a fabricated result set.

## 9. Model supply (opt-in, never automatic)

- **Local file**: the Settings section has a "Choose a .gguf file" action (system file picker,
  `OpenDocument`). The repo had no existing local-file import (only URL download), so this lane
  adds a small one for embedding models: copy into `files/embedding-models/`, validate the `GGUF`
  magic, refuse otherwise.
- **Catalog**: one entry, verified from this container on 2026-11-26 through the proxy:
  `nomic-ai/nomic-embed-text-v1.5-GGUF`, file `nomic-embed-text-v1.5.Q4_K_M.gguf`,
  URL `https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf`
  - HTTP 302 then a ranged GET answered **206**, `Content-Range: bytes 0-15/84106624`, first
  bytes `GGUF`; size 84,106,624 bytes (80.2 MiB) and LFS sha256
  `d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac` read from the Hugging Face
  model API; **license Apache-2.0** (repo card `license: apache-2.0`). Needs task prefixes
  `search_document: ` / `search_query: ` per the model card; llama.cpp defaults it to 2048
  tokens context per the same card. The download only starts on an explicit tap labelled with
  the host and size; the sha256 is checked after download and a mismatch deletes the file.
- The UI shows each model's license when known ("Apache-2.0" for the catalog entry; "license
  unknown - check the model's page" for a picked file; we do not guess).

## 10. Failure modes

Model fails to load (bad file, OOM) -> `PROVIDER_ERROR`, section shows the typed reason, search
stays lexical. Pooling NONE/RANK -> refused with a clear reason. Process death mid-index ->
resume at doc granularity. Dim change -> new embedder id -> STALE, rebuild. Model deleted while
enabled -> `NO_MODEL`. Corrupt store file -> opened fresh (Clear semantics), index rebuilds.
Huge doc -> chunk cap + truncation flag. Empty / null / oversize text at the JNI -> typed codes.

## 11. Owner-verified only

Nothing below has been run: that `llama_embed_jni.cpp` links and runs on the phone and produces
sensible vectors; embedding quality (does "automobile" find "car"); latency per chunk and
battery/thermal cost of a full build; peak memory with chat model + embedder both resident
(they hold separate contexts; the embedder is small but not free); the file picker flow;
the download flow and sha check; the chip rendering and colour-independence on the real screen;
whether fusion order feels right and where a similarity floor should sit.
What IS machine-checked: vector math, top-k, chunking, fusion, state machine, resume, staleness,
store round trip against real SQLite, provider states, presenters, and that the C++ compiles
for arm64-v8a (task named in the lane report).
