# Graphify — dev-tool notes (evaluated 2026-09-06)

[Graphify](https://github.com/Graphify-Labs/graphify) turns a codebase into a queryable
knowledge graph (local tree-sitter AST parsing, no embeddings/vector store, every edge
tagged EXTRACTED or INFERRED). Evaluated against this repo and adopted as **optional
dev tooling** for agent sessions — it is NOT a runtime dependency of any app and must
never become one.

## Install (per session; containers are ephemeral)

```
python3 -m venv ~/graphify-venv && source ~/graphify-venv/bin/activate
pip install graphifyy        # note: PyPI package is graphifyy (double y); CLI is `graphify`
graphify extract /home/user/Android-IDE-core --code-only --out ~/graphify-out
```

Verified at graphifyy 0.9.55 / tree-sitter-kotlin 1.1.0: indexing this repo took ~38 s
(1291 files → ~21k nodes / 41k edges, 93% EXTRACTED / 7% INFERRED).

## Non-negotiable usage rules (from the 2026-09-06 source-level evaluation)

- **Always `--code-only`.** In that mode the LLM/network code path never loads — verified
  in source, not assumed (cli.py's `needs_llm` gate). Never configure a semantic backend;
  this project's no-telemetry rule extends to dev tooling run against its source.
- **Treat `query`/`path` output as leads, not citations.** Two verified failure modes:
  - The Kotlin *call graph undercounts*: same-package calls with no import, and
    `import Object; Object.method()` calls, produce NO `calls` edge (verified with
    minimal repros — ~15 real callers of `ThreadGraphProjector.project` from its own
    test file are absent from the graph).
  - Name-collision false positives on INFERRED paths: a path query linked GraphRoom →
    PricingBook through Kotlin's stdlib `with()` colliding with an unrelated `.with()`.
- **Grep cross-check is mandatory before acting on "who calls this"** — especially
  pre-refactor. Class-level `explain` output (imports, methods, communities) is the
  reliable part; method-level callers attach to the *method* node, so query the method
  name, not the class.
- Known parse failures (5/1291 files): tree-sitter-kotlin 1.1.0 chokes on `open` used
  as a plain identifier in expression position (`open = true`, `open(x)`), and on a NUL
  byte inside a string-literal test fixture. Those files are simply absent from the graph.

## Multi-repo (the constellation)

Purpose-built and real: extract each repo separately (`--code-only`), then

```
graphify global add <repo>/graphify-out/graph.json --as core   # etc. for studio/hyle
```

Node IDs are prefixed per repo (no collisions) and cross-repo `calls` stitching exists
(`cross_repo_calls.py`). Agent access: plain CLI, the `/graphify` skill, or the MCP
server (`python -m graphify.serve <graph.json>`), all local.

License: Apache-2.0 (+MIT for pre-relicensing contributions). Local-only query log is
opt-in via `GRAPHIFY_QUERY_LOG_ENABLE=1` and never leaves the machine.
