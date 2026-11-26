# Git engine, part 3 (`domain/gitcore/transport/`, `domain/gitcore/client/`, `data/gitcore/`) — design note

Status: wave-2 lane G3. This first version is the PLAN; the closing section is rewritten at the end
of the lane with what was actually built. Part 1 (`docs/design/git-engine.md`) is the object model,
packs, refs, index and work-tree operations; this part adds the network.

## Why
Part 1 can read, write and check out a repository but cannot get one. The owner's goal is "clone,
branch, commit, push ... against any git host, no desktop step". The REST layer (`domain/git/`) stays
the fast path for browse and agent commits and is NOT touched. This lane speaks the real wire
protocol, clean-room from the public protocol documentation (pkt-line, capabilities, protocol v0/v2,
side-band, pack format, receive-pack report-status) and our own tests. The installed git 2.43 is a
black-box oracle and the test SERVER only; no JGit/libgit2/other GPL-family code is read or ported.

## What gets built
1. `domain/gitcore/transport/` (pure Kotlin, JVM-tested, no `android.*`, no OkHttp, no sshj):
   pkt-line reader/writer with flush/delim/response-end and length caps; capability parsing;
   side-band / side-band-64k demultiplexer (pack bytes out, progress to a callback, band 3 -> typed
   error); v0/v1 advertisement parser; protocol v2 `ls-refs` and `fetch` (want/have/done, shallow,
   deepen, ofs-delta, thin-pack, include-tag); receive-pack request builder and report-status parser
   (delete refs, atomic when advertised, push is never forced by default).
2. Negotiation and pack handling: a bounded single-round `have` set (ref tips first, then recent
   commits by committer time, hard cap) sent together with `done`; the pack goes through the part-1
   `PackIndexer` (spool + trailer check + thin completion); a connectivity check runs BEFORE any ref
   moves; the push pack contains only what the remote lacks (`objectsToSend` against the remote's
   advertised tips we hold).
3. Transports behind one port (`GitTransport`): smart HTTP(S) on OkHttp and SSH on sshj, in
   `data/gitcore/`. Credentials come from ports (`CredentialProvider`, `SshKeyProvider`,
   `HostKeyPolicy`); the engine never stores them.
4. `domain/gitcore/client/GitClient.kt`: clone (Flow of typed progress events), fetch, pull
   (fast-forward only), push, remote add/remove/list, ls-remote; every failure a `GitFailure`.
5. A real-git test fixture (JDK `HttpServer` bridged to `git http-backend`) and the interop suite.

## What is NOT built (named follow-ups)
Partial-clone filters, bundle URIs, LFS, the `git://` protocol, HTTP proxies, resumable clone,
multi-round negotiation (one bounded round with `done`), v2 push (does not exist upstream), push
options, signed pushes, `want-ref`, SHA-256 object format, merge-on-pull (typed `NeedsMerge` unless
the part-2 merge engine is on the head), credential helpers / OAuth device flow, the SSH-key
management UI (the key store is only a PORT here).

## Seams and the credential model
- `CredentialProvider.credentialFor(CredentialRequest(scheme, host, port, usernameHint))` is
  consulted PER HOST and only after the server answered 401 with a Basic challenge (git's own
  order: anonymous first). Credentials are therefore never sent pre-emptively and a redirect to
  another host re-asks the provider for THAT host (which has no entry), so one host's token cannot
  reach another by construction. URLs carrying a password are refused; every message prints
  scheme://host/path only.
- `Credential` and `SshKeyMaterial` override `toString` to a redaction and are never put in
  exception messages; a test greps every exception, event and `toString` from a failing run for the
  secret.
- Tokens live in the existing Keystore-backed `GitHostStore`; `GitHostCredentialProvider` adapts it
  (by lambdas, so it is JVM-testable). `GitHostFlavor` (GitHub, GitLab, Gitea/Forgejo, Bitbucket,
  other) only maps a token to the right Basic username convention; the engine stays host-agnostic.
- SSH: `HostKeyPolicy` reuses `domain/remote` `KnownHosts`/`Trust`: only `Vetted` connects silently,
  `Unknown`/`Changed` go to the user-decision callback, default is refuse. Identity comes from
  `SshKeyProvider`. No password prompt and no agent.

## Safety model
- Hostile server: pkt-line lengths, side-band frames, advertisement size, ref count and sideband
  error text are capped; a pack that fails trailer checksum, is truncated, or lacks a base is
  refused by the indexer; a pack that indexes but leaves a wanted tip unreachable (missing tree,
  blob or parent) fails the connectivity check. In every case NO ref is created or moved. Objects
  from a refused pack may remain unreferenced in the object store (harmless; no gc yet).
- Refs move only in one place (`FetchApply`), after connectivity, each with compare-and-swap.
- Push: `ForceMode.None` is the default. A non-fast-forward is detected locally (and server-side
  rejections are surfaced verbatim as `Rejected`). `Force` and `WithLease(expectedOld)` are the only
  ways to send a non-FF update; lease compares the remote's advertised value with `expectedOld`
  before sending, and the old value rides in the command so the server also compare-and-swaps.
- Redirects: at most 5, handled by us (OkHttp auto-follow is off), https -> http refused, the new
  base must end in `/info/refs` (as git requires), credentials not carried.
- Cancellation: clone writes into a sibling staging directory and renames it onto the target only
  after checkout completes; cancel or failure deletes the staging directory. The target path never
  holds a repository that looks valid but is incomplete. Not resumable (named follow-up).
- Memory: the pack is streamed to disk by the part-1 indexer; the push pack streams from the object
  store into the request body; only pkt-lines and the ref advertisement are buffered (capped).

## Owner-verified-only list (nothing here ran against these)
Real GitHub / GitLab / Gitea / Bitbucket behaviour (their token username rules, rate limits,
`WWW-Authenticate` shapes, v2 support); SSH against a real sshd (no sshd in the build container, so
the SSH adapter is compile-verified and its pure helpers are unit-tested only); large repositories
on phone storage; cellular data cost (no metered-network guard exists); Android TLS trust store
behaviour; time-to-first-byte and pack indexing speed on the phone.
