# License — pending owner decision

**Status:** `LICENSE-PENDING`. `android-ide-core` currently ships no `LICENSE` file. This is a
known, owner-reserved decision (`08_OPEN_QUESTIONS.md` §B.1 of the Fonebrew handoff pack, and
`Android-IDE-Studio/RATIFICATION.md` S11–S24 track the same open item from an earlier,
independent session — see `Personal-Tracker/DECISIONS.md` D-U for that history; license PRs were
opened and held unmerged against this repo and `Hyle-Design-System` on 2026-07-31).

**Recommendation on record (handoff pack, not self-ratified):** Apache-2.0 — matches ASOM
(`asystemofcells/asystemofmodels`), includes a patent grant, is maximally adoptable for a
contracts-and-parsers layer, and keeps proprietary Studio linkage clean (unlike GPL/AGPL, which
would contaminate Studio; unlike MIT, which drops the patent grant). This is a recommendation for
the owner to accept or override — not a decision this session is authorized to make.

## What every new public file in this repo does until the owner decides

Every file emitted under `docs/ratified/`, `docs/non_ratified/`, `schemas/`, `contracts/kotlin/`,
and `fixtures/` during the Fonebrew handoff-pack build-out carries a `LICENSE-PENDING` marker
pointing at this document:

- Markdown: a blockquote line under the H1 title.
- JSON: a top-level `"licenseStatus": "LICENSE-PENDING"` field (data registries) or a
  `"$comment"` referencing this file (JSON Schema documents, where `$comment` is a normal
  Schema-2020-12 keyword).
- Kotlin: a `// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md` line under the file
  header comment.

This is bookkeeping, not a license grant of any kind — it exists so a future `git grep
LICENSE-PENDING` finds every file that needs its header updated (or removed) the moment the owner
rules on this.
