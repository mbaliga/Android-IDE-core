# Design: Tree sovereignty — your history, in your repo

> Status: **design + first brick built.** The serialization format and a
> round-trippable `TreeArchive` exist (`domain/sync/TreeArchive.kt`, JVM-tested);
> the Git-host transport and UI are next. This is the purest expression of the
> "Sovereignty of Attention" thesis: the user's whole history is portable plain
> text in a repo they own — Aarso becomes the *glass*, not the *vault*.

## The idea

Aarso's message tree (`domain/MessageNode.kt`) is append-only, multi-root, with
branch/restore as git-like operations — it is *already a git DAG*. Today it lives
in private SQLite. This feature **mirrors it into a Git repository the user owns**
(GitHub, Gitea, self-hosted) as diff-friendly files, so the user can **move freely
across platforms, devices, models and tools — and discard the shell of Aarso
entirely without losing anything.**

## How it works

### On-disk format (open, documented — this IS the promise)

One file per node — nodes are immutable, so files are *created, never edited*:

```
aarso/manifest.json            { "format": "aarso-tree", "version": 1 }
aarso/nodes/<nodeId>.json      one node each
```

A node file (see `TreeArchive`):

```json
{
  "id": "…",
  "parentId": "…",        // null for a conversation root
  "role": "assistant",     // wire string (stable across enum renames)
  "content": "…",
  "modelId": "…",          // null for user/system turns
  "createdAt": 1718000000000,
  "tokenCounts": { "qwen2": 412 },
  "metadata": { "agent": "Skeptic", "council": "…" }
}
```

Conversations are *derivable* (`parentId` links + multi-root), so no folder
hierarchy is needed — flat `nodes/` keeps merges trivial.

### Conflict-free merging (the elegant part)

Append-only + unique IDs ⇒ **pull is just union**: import any node files you don't
already have. Two devices generate different IDs, so concurrent work never
collides. No three-way merge, no conflict UI — CRDT-like behaviour falls straight
out of the architecture.

### Sync flow

Manual "Sync now" first (write new nodes → commit → push; pull → import missing).
Opt-in on-close later. **Never** automatic background push — anything leaving the
device is explicit and user-initiated (no-telemetry ethos). A configured remote is
a **watched object**.

## "Discard the shell" — why this matters

If the format is open and stable, your entire intellectual history — every prompt,
every model's reply, every fork, with model attribution and timestamps — is
readable by *any* tool: an editor, a git client, a script, a different client.
You can leave Aarso and lose nothing. Move the repo between devices/installs →
identical history. Resume a thread on a different model on a different device. It
makes the user's data agency total and *verifiable*.

## The sharp tension: plaintext interop vs. E2E privacy

- **Plaintext** ⇒ "any tool can read it / discard the shell" works literally — but
  the remote host can read your conversations.
- **End-to-end encryption** (encrypt node files before commit) ⇒ even GitHub can't
  read them; sovereignty holds on a third-party host — but you can't grep it, and
  "any tool" narrows to "any tool implementing our scheme."

**Decision (owner): default plaintext with a plain-language warning; E2E strongly
recommended for advanced users.** This is deliberately anti-obfuscation — the
default must be something the user can literally open and read (no black box, the
opposite of the "AI as black-magic art" posture), while E2E is one toggle away for
those who want the host blind too. Both the file format **and** the encryption
scheme are documented as open specs, so "discard the shell" still holds in the
encrypted case (anyone can implement a reader; you just need your key). The
remote's privacy properties are surfaced unmissably in the UI.

## Concerns / cons (honest)

- **Secrets never sync.** API keys live in the Keystore; a guard must keep them out
  of the archive. Non-negotiable.
- **Image/binary blobs.** Image turns reference large files; git is poor at big
  binaries. v1: keep images device-local (sync the text tree only), noted in UI.
  Later: Git LFS or a content-addressed side store.
- **Format-as-contract.** The schema becomes a contract; evolve via `version` +
  migrations.
- **Deletion/redaction.** Append-only has no delete; "forget" needs tombstones, and
  git history still remembers. A real tension with a right-to-forget.
- **Selectivity.** Per-conversation opt-in for private threads.
- Auth (PAT in Keystore), large-history performance (batch commits).

## Relationship to the coding assistant

Orthogonal but both touch Git, and they **share the Git-host connection layer**
(host abstraction, PAT-in-Keystore, identity — build once, serve both):

- **Tree-sovereignty**: Aarso's *own* data → your repo (portability of your
  conversations).
- **Coding-assistant** (`coding-assistant.md`): Aarso operates on your *other*
  repos (code) as a tool.

Both reinforce sovereignty — data ownership and tool freedom.

## Build order

1. ✅ **Format + `TreeArchive`** (write/read, manifest, version) — pure, JVM-tested.
2. **Git-host connection layer** (shared with coding-assistant): host kind + base
   URL + identity + PAT in `KeystoreSecret`; a `GitTransport` abstraction.
3. Manual export → commit → push of the archive; pull → union-import.
4. Per-conversation opt-in; image policy (device-local v1).
5. Optional E2E (documented scheme); on-close opt-in sync.

## Open questions for the owner

- Default privacy for third-party hosts: plaintext-with-warning, or force E2E?
- Images in v1: device-local only (recommended) vs. LFS from the start?
- Selectivity: sync-all by default, or per-conversation opt-in from day one?
- Transport: JGit (real git) vs. host REST contents API.
